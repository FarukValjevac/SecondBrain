package dev.secondbrain.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class DurableEventWorker implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(DurableEventWorker.class);
    private final JdbcEventStore store;
    private final EventDispatcher dispatcher;
    private final EventDeliveryProperties settings;
    private final Clock clock;
    private final UUID owner = UUID.randomUUID();
    private final Semaphore capacity;
    private final Map<UUID, RunningTask> active = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollingTask;
    private ThreadPoolExecutor executor;
    private Instant nextCleanup = Instant.MIN;

    DurableEventWorker(
            JdbcEventStore store,
            EventDispatcher dispatcher,
            EventDeliveryProperties settings,
            Clock clock) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.settings = settings;
        this.clock = clock;
        this.capacity = new Semaphore(settings.concurrency());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Previous event workers have not stopped");
        }
        executor =
                new ThreadPoolExecutor(
                        settings.concurrency(),
                        settings.concurrency(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(settings.concurrency()),
                        Thread.ofPlatform().daemon(true).name("event-handler-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("event-poller").factory());
        running = true;
        pollingTask =
                scheduler.scheduleWithFixedDelay(
                        this::tickSafely,
                        0,
                        settings.pollInterval().toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        if (!running) {
            return;
        }
        try {
            tick();
        } catch (Exception infrastructureFailure) {
            LOG.atWarn()
                    .addKeyValue("operation", "event_worker")
                    .addKeyValue("outcome", "poll_failed")
                    .addKeyValue("code", "DATABASE_OR_EXECUTOR_UNAVAILABLE")
                    .log("Event polling failed");
        }
    }

    private void tick() {
        for (var task : active.values()) {
            if (task.isCancelled()) {
                continue;
            }
            if (!clock.instant().isBefore(task.lease.deadline())) {
                task.cancel();
                var context = new DeliveryLogContext(task.lease);
                try (context) {
                    boolean recorded = store.fail(task.lease, DeliveryFailure.Code.TIMED_OUT, true);
                    LOG.atWarn()
                            .addKeyValue("operation", "event_delivery")
                            .addKeyValue("outcome", recorded ? "failure_recorded" : "lease_lost")
                            .addKeyValue("code", "TIMED_OUT")
                            .log("Event delivery timed out");
                }
            } else if (!store.renew(task.lease)) {
                task.cancel();
            }
        }
        if (!clock.instant().isBefore(nextCleanup)) {
            // Retention is maintenance: a failed cleanup must not starve delivery claims.
            nextCleanup = clock.instant().plus(settings.cleanupInterval());
            try {
                store.cleanup();
            } catch (Exception cleanupFailure) {
                LOG.atWarn()
                        .addKeyValue("operation", "event_worker")
                        .addKeyValue("outcome", "cleanup_failed")
                        .addKeyValue("code", "DATABASE_OR_RETENTION_UNAVAILABLE")
                        .log("Event retention cleanup failed");
            }
        }
        for (int claimed = 0; claimed < settings.claimBatchSize() && running; claimed++) {
            if (!capacity.tryAcquire()) {
                break;
            }
            boolean submitted = false;
            RunningTask task = null;
            try {
                var lease = store.claim(owner);
                if (lease.isEmpty()) {
                    break;
                }
                task = new RunningTask(lease.get());
                active.put(task.lease.token(), task);
                var submittedTask = task;
                executor.execute(() -> run(submittedTask));
                submitted = true;
            } catch (RejectedExecutionException shuttingDown) {
                // Its persisted lease expires normally; never acknowledge unstarted work.
                if (task != null) {
                    active.remove(task.lease.token(), task);
                }
                if (running) {
                    throw shuttingDown;
                }
            } finally {
                if (!submitted) {
                    capacity.release();
                }
            }
        }
    }

    private void run(RunningTask task) {
        try {
            var context = new DeliveryLogContext(task.lease);
            try (context) {
                try {
                    if (running && task.attach()) {
                        dispatcher.dispatch(task.lease);
                    }
                } catch (Exception infrastructureFailure) {
                    LOG.atWarn()
                            .addKeyValue("operation", "event_worker")
                            .addKeyValue("outcome", "delivery_interrupted")
                            .addKeyValue("code", "DATABASE_UNAVAILABLE")
                            .log("Event delivery interrupted");
                }
            }
        } finally {
            task.detach();
            active.remove(task.lease.token(), task);
            // Cancellation is cooperative: do not free a slot until its actual thread exits.
            capacity.release();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        // Let already queued wrappers release their permits, without invoking their handlers.
        executor.shutdown();
        active.values().forEach(RunningTask::cancel);
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    synchronized boolean isOperational() {
        return running
                && pollingTask != null
                && !pollingTask.isDone()
                && scheduler != null
                && !scheduler.isShutdown()
                && executor != null
                && !executor.isShutdown();
    }

    int activeCount() {
        return active.size();
    }

    private static final class RunningTask {
        private final DeliveryLease lease;
        private Thread thread;
        private boolean cancelled;

        private RunningTask(DeliveryLease lease) {
            this.lease = lease;
        }

        synchronized boolean attach() {
            if (cancelled) {
                return false;
            }
            thread = Thread.currentThread();
            return true;
        }

        synchronized void detach() {
            thread = null;
            cancelled = true;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized void cancel() {
            cancelled = true;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
