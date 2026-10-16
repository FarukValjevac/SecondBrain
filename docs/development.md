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

## Database inspection

Flyway applies pending migrations on startup. V1 enables pgvector and V2 creates the durable event tables. Applied versions are recorded in `flyway_schema_history`; subsequent startups validate history and apply only pending migrations.

Open a database shell using the configured user and database:

```sh
docker compose exec postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Then run:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

Use `\q` to exit. Stop the application with `Ctrl+C`, start it again, and repeat the queries to inspect the unchanged migration history.

## Health checks

With the application running, inspect its local health endpoints:

```sh
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/actuator/health/liveness
curl -i http://127.0.0.1:8080/actuator/health/readiness
```

Liveness reports Spring's application viability state and does not check PostgreSQL. Readiness combines Spring's readiness state with a database connectivity check. After successful startup with a healthy database, both return HTTP 200 and `{"status":"UP"}`. If the running application loses database connectivity, readiness returns HTTP 503 and `{"status":"DOWN"}` while liveness can remain UP. A failed startup may leave no HTTP server to answer these requests.

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

The timer reports a request count plus total and maximum request duration in seconds. It appears after a request completes; filtering on a route with no recorded requests returns HTTP 404. HTTP metric tags include method, route pattern, status, outcome, and error/exception categories; query values are omitted. Database connection-pool metrics are also available when the datasource is active.

Metrics use a local in-memory registry with no external exporter or dashboard. Counts reset on application restart, and measurements aggregate across matching tags unless filtered. These are runtime and HTTP measurements; there are no custom delivery/backlog meters.

Reading the filtered request timer does not increase the count for `GET /`. Request the library page again, then inspect the timer to observe the increase.

## Console logs

Application logs are written as one JSON object per entry using Spring Boot's built-in Logstash format. Restart the application with `./mvnw spring-boot:run` and inspect the startup messages: each has `@timestamp`, `level`, `logger_name`, `thread_name`, and `message` fields. Structured key/value fields supplied by a logger are included as additional JSON fields.

Logs stay on the console; no Logstash server, log file, or external collector is configured. Maven's `[INFO]` build output and JVM warnings keep their own format. HTTP and event-worker summaries use separate JSON fields. Tracing is not instrumented. Formatting preserves message contents; application logs must use safe messages and metadata.

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

The README lists the standard test commands. Fast tests check application wiring, library rendering, event policies/worker behavior, health/metrics endpoints, and JSON console formatting. Database-dependent features use isolated PostgreSQL/pgvector Testcontainers; those tests do not use the Compose database or `.env`. Required integration tests fail if Docker is unavailable. Routine tests need no AI credentials or external telemetry service; tests tagged `live-ai` are excluded.

Apply Java formatting before committing:

```sh
./mvnw spotless:apply
```

The [GitHub Actions workflow](../.github/workflows/verify.yml) runs the shared Maven verification command on pushes and manual dispatch. It can be started manually from the Actions tab.

## Troubleshooting

- **Java runtime not found:** run `java -version` in the same terminal. Ensure a Java 25 JDK is available through `JAVA_HOME` or `PATH` in every new shell.
- **Integration tests cannot find Docker:** start the Docker engine and check `docker info` from the terminal running Maven. A healthy Compose database alone does not establish that Testcontainers can access Docker.
- **Database authentication fails after editing `.env`:** the stored database retains its existing credentials. Use those credentials or deliberately change them in PostgreSQL; editing `.env` configures the clients and does not update an initialized database user.
