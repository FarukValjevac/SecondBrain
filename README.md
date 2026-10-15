# SecondBrain

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

Flyway applies `src/main/resources/db/migration/V1__enable_pgvector.sql` on first startup, enabling the `vector` extension and recording the migration in `flyway_schema_history`. Subsequent startups validate migration history and apply only pending migrations. The application requires database credentials but no AI credentials.

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

## Development checks

```sh
./mvnw test
./mvnw verify
```

`test` runs fast tests without Docker or database credentials. Its startup smoke test explicitly excludes database and Flyway auto-configuration; it checks only the remaining application wiring. A Spring MVC web test renders the real Thymeleaf library template and checks the HTML response and empty state without starting an HTTP server.

`verify` also runs the required Testcontainers integration test, compiler/static checks, packaging, and Java formatting checks. The integration test starts its own isolated PostgreSQL/pgvector container, applies the real Flyway migration through application startup, checks vector operations, and restarts the application to check data and migration history. It does not use the Compose database or `.env`. Docker must be running and accessible; unavailable Docker fails the test rather than skipping it. No AI credentials are needed, and tests tagged `live-ai` remain excluded.

The [GitHub Actions workflow](.github/workflows/verify.yml) runs `./mvnw --batch-mode --no-transfer-progress verify` on pushes, using Java 25. It can also be started manually from the Actions tab. The extra flags keep CI logs concise; they do not change the checks.

Apply Java formatting with:

```sh
./mvnw spotless:apply
```
