# Development guide

See the [README](../README.md) for prerequisites, setup, startup, tests, and shutdown. This guide covers configuration and diagnostics for the implemented application.

## Configuration

### Database

| Setting | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | Required | Password for the local database user |
| `POSTGRES_DB` | `secondbrain` | Database name |
| `POSTGRES_USER` | `secondbrain` | Database user |
| `POSTGRES_PORT` | `5432` | Local database port |

These settings can be supplied through `.env` or environment variables. For an existing database, the application also accepts standard Spring settings: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. That database must have pgvector installed and permit the configured user to enable the extension.

Compose uses `pgvector/pgvector:0.8.7-pg18-bookworm` and binds PostgreSQL to `127.0.0.1:5432` by default. Its named volume preserves data across `docker compose down`. PostgreSQL's initial user, password, and database settings apply when the volume is first initialized; editing `.env` later does not change existing database credentials.

### Original document storage

The upload size limit defaults to `10MB` (`secondbrain.documents.max-upload-size`). The private original-file directory defaults to `./data/documents` (`secondbrain.documents.storage-directory`), relative to the application's working directory. The default directory is excluded from Git and is not served as a web resource. On POSIX filesystems, new storage directories and files receive owner-only permissions; an existing directory with group or other access is rejected. Other filesystems use their normal access controls. Both settings can be overridden with Spring environment variables such as `SECONDBRAIN_DOCUMENTS_MAX_UPLOAD_SIZE` and `SECONDBRAIN_DOCUMENTS_STORAGE_DIRECTORY`.

The same upload limit applies while parsing multipart file parts and while streaming the original into private storage. The total multipart request is bounded at that limit plus 1 MiB for headers and form fields; both bounds follow the configured upload limit.

### Abandoned-file cleanup

The application periodically removes old originals that have no document metadata. This covers files left by a crash before acceptance committed, including partial writes. It retains files owned by any document state, recent files, symbolic links, directories, and unrelated filenames. Cleanup skips deletion while an upload is writing or committing; a database lookup failure stops that batch and retains the file.

| Setting | Default | Allowed values |
|---|---|---|
| `secondbrain.documents.cleanup.enabled` | `true` | `true` / `false` |
| `secondbrain.documents.cleanup.grace-period` | `1h` | 1 minute through 7 days |
| `secondbrain.documents.cleanup.interval` | `1m` | 1 second through 1 hour |
| `secondbrain.documents.cleanup.batch-size` | `100` | 1 through 1000 entries |

The first batch starts after one interval; each later batch starts one interval after the previous batch finishes. The batch size bounds all inspected directory entries, including retained and unrelated entries. A cursor continues across batches and starts a new scan after reaching the end; it closes at shutdown and restarts from the beginning after an application restart. Ownership lookups have a five-second JDBC query timeout. Disabling durable events also disables cleanup.

Run one application process for each storage directory and keep that directory paired with its database. Upload/cleanup protection is shared within the process; sharing a folder across multiple processes or changing its database is unsupported.

After restarting with `./mvnw spring-boot:run`, look for JSON console entries with `operation=original_cleanup`. Successful batches report `inspected`, `deleted`, and `busy` counts; failures report the fixed `STORAGE_OR_DATABASE_UNAVAILABLE` code. No filenames, paths, document text, or exception details are logged. `busy` means deletion was deferred because an upload was protected. These are cleanup diagnostics; accepted documents still remain queued.

To exercise cleanup with isolated temporary files and a controlled clock:

```sh
./mvnw -Dtest=OriginalFileReconcilerTest,DocumentAcceptanceServiceTest test
```

Run `./mvnw verify` for the PostgreSQL restart and concurrent-upload checks as well.

### HTTP port

To use another application port on macOS/Linux:

```sh
SERVER_PORT=8081 ./mvnw spring-boot:run
```

### Executable JAR

Build and run the packaged application:

```sh
./mvnw verify
java -jar target/secondbrain-0.1.0-SNAPSHOT.jar
```

It serves the same local URL and reads configuration from the working directory, just like the Maven startup command.

## Uploading documents in the browser

Open the library at [http://127.0.0.1:8080/](http://127.0.0.1:8080/), choose one TXT or Markdown file, and select **Upload document**. The page displays the supported extensions and configured size limit. A successful submission returns to the library with **Saved and queued** and status **QUEUED**. Validation or storage failures return to the form with a safe error message; select a file again to retry.

The browser uses a normal multipart form and a `303 See Other` redirect after submission. Feedback appears once; refreshing the resulting page does not repeat the upload. The page provides upload controls and feedback, while stored-document listing is not available. When durable events are disabled, the form is hidden and the page reports that uploads are unavailable.

Allow cookies for the local application to receive this feedback. Sessions use cookies only (`server.servlet.session.tracking-modes=cookie`), so redirects keep the library URL `/` without adding a session ID. After changing application configuration, restart the server and check the first upload in a fresh browser session as well as subsequent uploads.

### Browser and restart walkthrough

Use small, non-sensitive fixtures. This walkthrough saves queued documents; the application does not yet list or extract them.

1. Start the database and application using the README. Open the library in a fresh browser session with cookies enabled. Check the form at desktop and narrow window widths.
2. Upload one non-empty TXT file and one Markdown file. Each should return to `/` with **Saved and queued** and **QUEUED**, including the first upload in the fresh session.
3. Refresh after success. The message should disappear and the browser should not resubmit the file. The database should still contain one accepted record per submission.
4. Submit an empty file, an unsupported extension such as `.pdf`, and a TXT file larger than the displayed limit. Each should show a useful error without a success message or a new accepted record. Select the file again when retrying.
5. Use [database inspection](#database-inspection) to record the two document IDs, storage keys, checksums, and correlated events. Check that each `<storage_key>.original` exists in the configured storage directory and matches its source file.
6. Stop the application with `Ctrl+C`, then start it again from the same working directory with the same configuration. Repeat the database and original-file checks: IDs, bytes, checksums, and events should remain unchanged, and both documents should remain queued. If checking Compose persistence too, run `docker compose down`, bring PostgreSQL back using the README command, and restart the application.

Rollback, interrupted writes, abandoned-file recovery, and concurrent acceptance use isolated automated fixtures; see [verification](#verification-and-formatting). Passing this browser walkthrough does not replace `./mvnw verify`.

## Uploading documents over HTTP

With the application running, submit one existing TXT or Markdown file using the `file` field:

```sh
curl -i -F 'file=@notes.txt' http://127.0.0.1:8080/documents
```

Accepted extensions are `.txt`, `.md`, and `.markdown`, case-insensitively. Filename metadata is limited to 1024 UTF-8 bytes and cannot contain null characters. The file's bytes are stored unchanged; acceptance does not decode text. Each accepted upload gets a generated document ID and private storage key, even when its contents match an earlier upload.

A successful response is HTTP `202 Accepted`, with an `X-Correlation-ID` header and JSON shaped as:

```json
{"documentId":"00000000-0000-4000-8000-000000000001","state":"QUEUED"}
```

The ID above is illustrative; each upload returns its own ID. Metadata and its durable `DocumentAccepted` event commit together before the response. Only the ID and queued state are returned; the original path and checksum are internal. Accepted documents currently remain queued. The browser form uses `/documents/upload` and redirects to the page with feedback; `/documents` continues to return JSON with the status codes below.

Missing, repeated, empty, unsupported, or invalid file input returns HTTP `400` with a fixed `code` and `message`. A file or total request exceeding the configured bounds returns HTTP `413`; malformed multipart requests return `400`, and a non-multipart content type returns `415`. Storage/database failures return `503` with a fixed message. Error responses omit filenames, contents, and internal exception details.

To associate acceptance with a known request, supply a canonical UUID in `X-Correlation-ID`; otherwise the application generates one. The accepted ID is returned in the response header and persisted in the durable event. Disabling durable events with `secondbrain.events.enabled=false` also disables the upload endpoint; disabling only worker polling still permits uploads to be durably queued.

## Database inspection

Flyway applies pending migrations on startup. V1 enables pgvector, V2 creates the durable event tables, and V3 creates document metadata. Applied versions are recorded in `flyway_schema_history`; subsequent startups validate history and apply only pending migrations.

Open a database shell using the configured user and database:

```sh
docker compose exec postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Then run:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

After an upload, inspect its queued state and event correlation without selecting private names or text:

```sql
SELECT d.document_id, d.storage_key, d.original_size_bytes, d.content_sha256,
       d.extraction_state, k.event_type, e.correlation_id
FROM documents d
JOIN event_keys k ON k.document_id = d.document_id
JOIN domain_events e ON e.event_id = k.event_id
WHERE k.event_type = 'DocumentAccepted';
```

Each original is stored as `<storage_key>.original` in the configured private directory. Use `\q` to exit the database shell. To compare one with its source on macOS/Linux, replace the illustrative UUID and source path below (also adjust the storage directory if configured):

```sh
cmp notes.txt ./data/documents/00000000-0000-4000-8000-000000000001.original
```

`cmp` exits successfully with no output when the files match. After restart, the migration history and accepted document/event records should be unchanged.

## Health checks

With the application running, inspect its local health endpoints:

```sh
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/actuator/health/liveness
curl -i http://127.0.0.1:8080/actuator/health/readiness
```

Liveness reports Spring's application viability state independently of PostgreSQL, original-file storage, and background work. Readiness combines Spring's readiness state, database connectivity, an operational event-worker scheduler/executor, and usable original-file storage. Deliberately disabling events or polling removes the worker requirement. Disabling events also disables uploads and removes the original-storage requirement; disabling only polling or abandoned-file cleanup does not remove it. A failed delivery or unavailable telemetry backend does not make the application unready. After successful startup with healthy dependencies, both probes return HTTP 200 and `{"status":"UP"}`. Losing database connectivity, a required worker, or usable original storage makes readiness return HTTP 503 and `{"status":"DOWN"}` while liveness can remain UP. A failed startup may leave no HTTP server to answer these requests.

The `originalStorage` contributor uses the configured private document directory. It creates that directory with the usual permission checks when needed, checks directory access, then writes, forces, and reads one temporary byte. The probe file is removed on close; uploaded originals are not opened or changed. Readiness recovers on the next successful check once storage is restored. Probe errors have no path or exception details, even if health details are explicitly enabled.

After restarting the application, check the readiness URL above. To exercise storage failure and recovery without changing the real library or stopping PostgreSQL:

```sh
./mvnw -Dtest=OriginalStorageReadinessTest,OriginalStorageHealthIndicatorTest test
```

These tests use a temporary directory and verify HTTP 503/DOWN on storage failure, HTTP 200/UP after recovery, unchanged liveness, and private error handling.

The overall health response also lists the `liveness` and `readiness` groups. Responses hide component details and error messages. Management endpoints use the application's loopback listener; only health and metrics are exposed, with Actuator discovery and JMX exposure disabled. Environment, configuration, and heap-dump endpoints are not exposed. No model APIs or external telemetry services are called by these checks.

## Local metrics

Actuator exposes Micrometer's built-in measurements through a read-only endpoint. With the application running:

```sh
curl http://127.0.0.1:8080/actuator/metrics
curl http://127.0.0.1:8080/actuator/metrics/process.uptime
curl http://127.0.0.1:8080/actuator/metrics/jvm.memory.used
```

The first request lists available metric names. Uptime is measured in seconds and JVM memory in bytes. To observe library requests, visit the page and inspect its request timer:

```sh
curl -o /dev/null -s http://127.0.0.1:8080/
curl 'http://127.0.0.1:8080/actuator/metrics/http.server.requests?tag=uri:/&tag=method:GET'
```

The timer reports a request count plus total and maximum request duration in seconds. It appears after a request completes; filtering on a route with no recorded requests returns HTTP 404. HTTP metric tags include bounded method/route/status/outcome categories and `none`/`failure` error categories. Unrecognized methods/routes become `OTHER`; query values and IDs are omitted. Database connection-pool metrics are also available when the datasource is active.

Metrics use a local in-memory registry with no external exporter or dashboard. Counts and timers reset on application restart; measurements aggregate across matching tags unless filtered. The event infrastructure also exposes:

| Meter | Unit | Meaning / labels |
|---|---|---|
| `secondbrain.event.delivery.attempts` | count | Committed attempt results; `outcome=completed,skipped,retry,failed` |
| `secondbrain.event.delivery.duration` | seconds | Time from the attempt's persisted start to its committed result; same outcome labels |
| `secondbrain.event.backlog` | count | Database projection; `state=ready,delayed,retrying,running,failed` |
| `secondbrain.event.backlog.oldest` | seconds | Age of the oldest claimable delivery |
| `secondbrain.event.backlog.available` | 0 or 1 | Whether the most recent backlog read succeeded |
| `secondbrain.event.workers.active` | count | In-flight worker tasks, including canceled work that has not exited |

`ready` includes due pending deliveries and expired leases awaiting recovery. `delayed` is pending work scheduled for the future; `running` counts unexpired leases; `failed` counts terminal deliveries. `retrying` counts pending work with earlier attempts and overlaps ready/delayed. Oldest age starts at eligibility, or at lease expiry for recoverable work. Backlog gauges share one bounded database read per five seconds, reload after restart, and return `NaN` with availability 0 if that read fails.

```sh
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.event.backlog
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.event.backlog.oldest
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.event.delivery.attempts
```

Attempt meters update after the database commits and do not count a rejected stale acknowledgment twice. They are best-effort runtime telemetry: a process crash immediately after commit can lose an increment. Database delivery/attempt records remain authoritative; counters are not a durable accounting ledger.

Reading the filtered request timer does not increase the count for `GET /`. Request the library page again, then inspect the timer to observe the increase.

### Upload and cleanup measurements

| Meter | Unit | Labels / meaning |
|---|---|---|
| `secondbrain.document.uploads` | count | `outcome=accepted,rejected,unavailable` |
| `secondbrain.document.upload.duration` | seconds | Acceptance-operation duration; same outcomes, plus fixed `error=none` |
| `secondbrain.document.cleanup.batches` | count | `outcome=completed,failed` |
| `secondbrain.document.cleanup.duration` | seconds | Cleanup-batch duration; same outcomes, plus fixed `error=none` |
| `secondbrain.document.cleanup.deleted` | count | Originals actually deleted; no labels |

Upload measurements cover attempts that reach DocumentAcceptanceService, including domain validation, original storage, transaction completion, and failure compensation. `accepted` increments after the metadata/event transaction returns successfully. `rejected` means application upload validation rejected the input; `unavailable` covers storage, database/transaction, and unexpected acceptance failures. Multipart parsing, missing/repeated file parts, and failures opening the HTTP input stream can occur before the service and are excluded from these application counters; the existing HTTP metrics still measure their requests. Both browser and JSON uploads use the same acceptance measurements.

Cleanup batch measurements cover scheduled executions. The deletion counter updates after each successful deletion, including deletions made before a later entry fails. A repeated scan of already-missing files does not increase it. Batch completion/failure and inspected/deleted/busy counts remain available in the safe `original_cleanup` logs. Upload completion logs use `operation=document_upload`, a fixed outcome, and the UUID correlation ID. Filenames, file contents, paths, and raw exceptions are never passed to document telemetry. Correlation IDs appear only in logs/traces, never as metric labels.

Micrometer observations supply the duration timers and correlated spans; they also expose `.duration.active` long-task timers while operations are active. Timers appear after the first matching operation completes, so an unrecorded timer/outcome can return 404. Counters start at zero and reset on restart. Metrics and observations are best effort, not a durable accounting ledger, and instrumentation failures cannot change application results. Raw application exceptions are intentionally not attached to observations, so inspect `outcome`, rather than the fixed `error=none` timer tag, to distinguish success from failure. Trace export remains optional and uses the existing sanitizer.

To check this manually, restart the application, then use a second terminal. The successful request below creates one queued test document:

```sh
printf 'SecondBrain metrics check.\n' > /tmp/secondbrain-metrics.txt
curl -i -F 'file=@/tmp/secondbrain-metrics.txt' http://127.0.0.1:8080/documents
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.uploads?tag=outcome:accepted'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.upload.duration?tag=outcome:accepted'
```

Expect HTTP 202/QUEUED for the upload. On a fresh process with no other uploads, the accepted counter and timer `COUNT` are 1; the timer also reports total/max time in seconds. Now send the same bytes with an unsupported extension:

```sh
curl -i -F 'file=@/tmp/secondbrain-metrics.txt;filename=notes.pdf' http://127.0.0.1:8080/documents
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.uploads?tag=outcome:rejected'
```

Expect HTTP 400/UNSUPPORTED_TYPE and rejected count 1. The accepted count stays unchanged. After the first `original_cleanup` log (one minute after startup by default), inspect the completed-batch counter, its timing, and deleted-file count:

```sh
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.cleanup.batches?tag=outcome:completed'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.cleanup.duration?tag=outcome:completed'
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.document.cleanup.deleted
```

Completed batches should increase as cleanup runs; deleted count can correctly remain zero when there are no old unowned originals. The focused tests exercise deletion, later-batch failure, unavailable uploads, and telemetry failures using isolated fixtures:

```sh
./mvnw -Dtest=DocumentTelemetryTest,DocumentTelemetryEndpointTest test
```

Run `./mvnw verify` for the full database-backed verification gate.

## Console logs

Application logs are written as one JSON object per entry using Spring Boot's built-in Logstash format. Restart the application with `./mvnw spring-boot:run` and inspect the startup messages: each has `@timestamp`, `level`, `logger_name`, `thread_name`, and `message` fields. Structured key/value fields supplied by a logger are included as additional JSON fields.

Logs stay on the console; no Logstash server or log file is configured. Maven's `[INFO]` build output and JVM warnings keep their own format. HTTP and event-worker summaries use separate JSON fields; tracing adds `traceId`/`spanId` while a span is in scope. Formatting preserves message contents; application logs must use safe messages and metadata.

### Request correlation

Each synchronous HTTP request returns an `X-Correlation-ID` response header. Supply one canonical lowercase UUID to reuse it; missing, malformed, or repeated header values receive a generated UUID. For example:

```sh
curl -i -H 'X-Correlation-ID: 11111111-1111-4111-8111-111111111111' http://127.0.0.1:8080/
```

The response header and the JSON completion log share `correlation_id`. Logs emitted during synchronous handling also receive this context. The summary records `operation=http_request`, `duration_ms`, `outcome`, and the response `status` when handling returns normally. `outcome=completed` means the handler chain returned; inspect `status` for HTTP success or failure. An escaping exception produces `outcome=failed` and `code=REQUEST_FAILED`, with no guessed status or exception text.

The summary omits URLs, queries, bodies, cookies, and arbitrary headers. The ID is removed from the thread's logging context after handling, or the previous value is restored. Request context covers synchronous handling; it is not automatically copied into async tasks. Correlation IDs are log fields, not metric tags.

### Event delivery correlation

Workers rebuild log context from each persisted delivery: `correlation_id`, `event_id`, `delivery_id`, `document_id`, `processing_version`, `attempt`, and optional `causation_id`. Handler logs on the delivery thread inherit these fields. Context is restored or cleared after the attempt, including failures. A publishing caller supplies the event's correlation/causation IDs; workers read them from the durable envelope rather than the original HTTP thread.

Delivery summaries use `operation=event_delivery` and `outcome=acknowledged`, `failure_recorded`, or `lease_lost`. `acknowledged` means the database accepted completion; it also covers a handler that skipped obsolete work. `failure_recorded` means a failure was saved; the database retry policy determines whether it will retry or remain terminal. Safe `code` fields describe failures without raw exception messages. Worker polling/cleanup failures use `operation=event_worker`. No event payload, unknown handler/type text, or provider error body is added to these summaries.

There are no production event handlers yet. Exercise the logging/context behavior with the focused tests:

```sh
./mvnw -Dtest=DeliveryLogContextTest,EventDispatcherTest,DurableEventWorkerTest test
```

These checks use controlled handlers and database doubles; full PostgreSQL restart/delivery verification still requires `./mvnw verify`.

## Tracing and optional export

Micrometer Observation instruments HTTP handling, and Micrometer Tracing bridges to OpenTelemetry. Publication captures the active span's W3C version-00 `traceparent` in the durable envelope, falling back to the caller's explicit trace context when there is no active span. Each consumer attempt starts a new root span named `event.delivery` linked to that producer; retries get independent spans. Correlation/causation IDs remain caller-supplied event fields. Scope cleanup restores the worker thread after success or failure.

Trace export is disabled by default. To send traces to an existing OTLP/HTTP collector, opt in explicitly:

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments="--secondbrain.telemetry.otlp-endpoint=http://127.0.0.1:4318/v1/traces --management.tracing.sampling.probability=1"
```

The endpoint must use HTTP(S) without credentials, query parameters, or fragments. Leave the native `management.tracing.export.otlp.enabled` setting disabled; the application's exporter applies the privacy boundary. No collector is required for application processing or routine tests. Metrics and logs remain local.

Default tracing samples 10% of traces. Export uses a 256-span queue, batches of at most 64, a one-second schedule, a two-second export deadline, a one-second connection deadline, and one transport attempt. Queue overflow or backend failure may discard telemetry while delivery continues. Spans allow at most 24 attributes, 128 characters per attribute, one producer link, and zero events. Baggage propagation is disabled.

HTTP observations omit full URLs and query strings. Export allows only valid UUID/version/attempt fields and fixed delivery outcomes; it strips exception events, status descriptions, link baggage/attributes, and arbitrary resource metadata. Other span names are normalized to `application.operation`. The exported resource identifies only `service.name=secondbrain`. IDs stay in logs/traces and never become metric tags.

## Durable event processing

The application includes a PostgreSQL-backed event publisher and background delivery workers. Events and their registered deliveries commit with the caller's database changes. Delivery is at least once: interrupted work can be retried, while a current lease is required to commit handler effects and acknowledgment. The application currently has no production event handlers; test handlers run only in isolated integration tests.

Workers start with the application. Configure them using Spring properties or environment variables. For example, limit processing and each claim batch to one delivery:

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments="--secondbrain.events.concurrency=1 --secondbrain.events.claim-batch-size=1"
```

| Property (`secondbrain.events.` prefix) | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Enable event infrastructure |
| `polling-enabled` | `true` | Start background polling and cleanup; publishing remains available when false |
| `concurrency` | `2` | Maximum in-flight handlers, configurable from 1 to 4 |
| `claim-batch-size` | `2` | Maximum claims per poll, at most concurrency |
| `poll-interval` | `1s` | Poll and heartbeat interval |
| `lease-duration` | `30s` | Renewable ownership interval |
| `execution-timeout` | `2m` | Maximum duration of one delivery attempt |
| `transaction-timeout` | `5s` | Database statement/transaction limit, including the publishing caller's transaction |
| `max-attempts` | `5` | Total automatic attempts, including expired leases; configurable from 1 to 10 |
| `initial-backoff` | `1s` | Initial exponential retry ceiling; jitter chooses 50–100% of the ceiling |
| `max-backoff` | `1m` | Maximum retry ceiling |
| `completed-retention` | `30d` | Retain completed/skipped payloads and attempt history for this interval |
| `cleanup-batch-size` | `100` | Maximum completed events removed per cleanup |
| `cleanup-interval` | `1h` | Cleanup frequency |

Invalid resource and timing settings fail startup. A lease must span at least three poll intervals, the transaction timeout must use whole seconds and be at least one second and at most half the lease duration, and the execution timeout must cover a lease. Database lock waits are limited to one second. Shutdown interrupts workers and waits up to five seconds for each executor. A handler that ignores interruption retains its concurrency slot until its thread exits; expired ownership prevents late database acknowledgment.

Events with pending or failed deliveries, and events with no deliveries, are retained. Cleanup removes old payloads/history only when every delivery completed or was skipped. A cleanup failure is logged and does not block delivery claims. Compact event keys remain as deduplication fences after cleanup; cleanup does not permit an old event to create new work.

Inspect delivery state in the database shell:

```sql
SELECT state, count(*) FROM event_deliveries GROUP BY state ORDER BY state;
SELECT handler_id, attempts, failure_code FROM event_deliveries WHERE state = 'FAILED';
```

Failure codes are safe categories; raw handler exception messages and document contents are not stored in delivery history.

## Verification and formatting

The README lists the standard test commands. Fast tests check application wiring, library rendering, event policies/worker behavior, health/metrics, log context, real in-memory traces, privacy, and export failure isolation. PostgreSQL/pgvector Testcontainers verify committed metrics, restart backlog/trace context, and delivery with failed telemetry export; those tests do not use the Compose database or `.env`. Required integration tests fail if Docker is unavailable. Routine tests need no AI credentials or external telemetry service; tests tagged `live-ai` are excluded.

For upload failure recovery checks:

```sh
./mvnw -Dtest=OriginalFileStoreTest test
./mvnw -Dit.test=DocumentAcceptanceIT verify
```

The first command checks private original storage, including cancellation after a partial write, preserved interruption, and subsequent upload/cleanup. The second also runs the fast suite and requires Docker: an isolated PostgreSQL fixture rejects event insertion or final commit, then checks rollback, file compensation, failure counters, and a successful retry. It uses temporary storage and a container database. Run the full `./mvnw verify` before considering the issue complete.

Apply Java formatting before committing:

```sh
./mvnw spotless:apply
```

The [GitHub Actions workflow](../.github/workflows/verify.yml) runs the shared Maven verification command on pushes and manual dispatch. It can be started manually from the Actions tab.

## Troubleshooting

- **Java runtime not found:** run `java -version` in the same terminal. Ensure a Java 25 JDK is available through `JAVA_HOME` or `PATH` in every new shell.
- **Integration tests cannot find Docker:** start the Docker engine and check `docker info` from the terminal running Maven. A healthy Compose database alone does not establish that Testcontainers can access Docker.
- **Database authentication fails after editing `.env`:** the stored database retains its existing credentials. Use those credentials or deliberately change them in PostgreSQL; editing `.env` configures the clients and does not update an initialized database user.
