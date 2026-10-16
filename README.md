# SecondBrain (WIP)

SecondBrain is a local, single-user knowledge library being built to help you find information in your own documents. Its goal is to let you upload documents, search by meaning, and ask questions with answers linked to supporting passages in your sources.

A local Java 25 Spring Boot application that displays an empty document library in the browser. It uses PostgreSQL with pgvector and applies Flyway database migrations on startup.

## Requirements

- A Java 25 JDK, available through `JAVA_HOME` or `PATH`.
- Docker with Docker Compose and a running Docker-compatible engine, such as Docker Desktop.
- Internet access to Maven Central and Docker Hub for the first build and image download.
- On macOS/Linux, `curl` or `wget` and `unzip` for Maven Wrapper downloads. On Windows, use PowerShell and `mvnw.cmd`.

Maven Wrapper downloads Maven 3.9.16 automatically. A separate Maven installation is not required.

## Run

Run commands from the repository root. Create the local configuration once:

```sh
cp .env.example .env
```

On Windows, use `Copy-Item .env.example .env`. Edit `.env` and replace the password placeholder before starting the database. Keep plain `KEY=value` entries without quotes or shell exports; a generated password containing letters and digits works with both configuration readers. `.env` is ignored by Git. Both Compose and the application load it from the repository root.

Start PostgreSQL and wait for readiness:

```sh
docker compose up -d --wait --wait-timeout 60 postgres
```

The database uses `pgvector/pgvector:0.8.7-pg18-bookworm` and listens on `127.0.0.1:5432` by default. Start the application:

```sh
./mvnw spring-boot:run
```

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/) to see the document library and its empty state. The server listens on loopback and stays running until you stop it with `Ctrl+C`. The library currently contains no documents or document-management controls.

Or build and run the executable JAR:

```sh
./mvnw verify
java -jar target/secondbrain-0.1.0-SNAPSHOT.jar
```

The JAR serves the same local URL. To use another HTTP port, set `SERVER_PORT`; for example, `SERVER_PORT=8081 ./mvnw spring-boot:run` on macOS/Linux.

Flyway applies pending migrations on startup: V1 enables pgvector and V2 creates the durable event tables. It records applied versions in `flyway_schema_history`; subsequent startups validate history and apply only pending migrations. The application requires database credentials but no AI credentials.

### Database configuration

| Setting | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | Required | Password for the local database user |
| `POSTGRES_DB` | `secondbrain` | Database name |
| `POSTGRES_USER` | `secondbrain` | Database user |
| `POSTGRES_PORT` | `5432` | Local database port |

These settings can be supplied through `.env` or environment variables. For an existing database, the application also accepts standard Spring settings: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. That database must have pgvector installed and permit the configured user to enable the extension.

PostgreSQL stores data in a Docker-managed named volume. After stopping the application with `Ctrl+C`, stop the database with:

```sh
docker compose down
```

The volume survives this command and is reused on the next startup. PostgreSQL's initial user, password, and database settings apply when the volume is first initialized; changing `.env` later does not change existing database credentials.

### Inspect migrations

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

The overall health response also lists the `liveness` and `readiness` groups. Responses hide component details and error messages. Health uses the application's loopback listener; only the health endpoint is exposed, with Actuator discovery and JMX exposure disabled. Metrics, environment, configuration, and heap-dump endpoints are not exposed. No model APIs or external telemetry services are called by these checks.

## Development checks

```sh
./mvnw test
./mvnw verify
```

`test` runs fast tests without Docker or database credentials. Its startup smoke test explicitly excludes database, Flyway, and durable event processing; it checks only the remaining application wiring. A Spring MVC web test renders the real Thymeleaf library template. Event tests check input/configuration validation, retry timing, Spring wiring, and bounded worker cancellation. Maven loads the managed Mockito version as a startup test agent for Java 25.

Health endpoint tests use the real HTTP mappings with a simulated database health indicator. They check readiness failure/recovery, independent liveness, hidden private details, and unavailable management endpoints; they do not establish real PostgreSQL connectivity.

`verify` also runs required Testcontainers integration tests, compiler/static checks, packaging, and Java formatting checks. These use isolated PostgreSQL/pgvector containers for migrations, vector operations, restart recovery, transactional event publication, duplicate delivery, lease fencing, retry limits, timeouts, and retention. They do not use the Compose database or `.env`. Docker must be running and accessible; unavailable Docker fails the tests rather than skipping them. No AI credentials are needed, and tests tagged `live-ai` remain excluded.

The [GitHub Actions workflow](.github/workflows/verify.yml) runs `./mvnw --batch-mode --no-transfer-progress verify` on pushes, using Java 25. It can also be started manually from the Actions tab. The extra flags keep CI logs concise; they do not change the checks.

Apply Java formatting with:

```sh
./mvnw spotless:apply
```

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
