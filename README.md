# SecondBrain

SecondBrain is a local, single-user knowledge library built with Java and Spring Boot. It accepts TXT and Markdown files over HTTP, stores originals privately, and records queued processing in PostgreSQL. The browser displays an empty library. Health checks, local metrics, and JSON console logs support diagnostics.

## Requirements

- Java 25 JDK, available through `JAVA_HOME` or `PATH`.
- Docker with Docker Compose and a running Docker-compatible engine, such as Docker Desktop.
- Internet access for the initial Maven and Docker image downloads.
- On macOS/Linux, `curl` or `wget` and `unzip` for Maven Wrapper.

The included Maven Wrapper downloads Maven automatically; a separate Maven installation is not required.

## Setup and startup

Run these commands from the repository root. Create the local configuration:

```sh
cp .env.example .env
```

Edit `.env` and replace the password placeholder. Use plain `KEY=value` entries without quotes or shell exports. Keep this file private; it is ignored by Git. No AI credentials are required.

Start PostgreSQL, then the application:

```sh
docker compose up -d --wait --wait-timeout 60 postgres
./mvnw spring-boot:run
```

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/). The application listens on loopback and applies pending database migrations automatically. The library currently has no document-management controls.

On Windows, use `Copy-Item .env.example .env` and `mvnw.cmd` in place of `./mvnw`.

## Development checks

```sh
./mvnw test
./mvnw verify
```

`test` runs the fast suite without Docker. `verify` also runs PostgreSQL integration tests, packages the application, and checks formatting; it requires a running Docker engine.

## Shutdown

Stop the application with `Ctrl+C`, then stop the database:

```sh
docker compose down
```

Database data remains in the Docker-managed volume and is reused on the next startup.

See the [development guide](docs/development.md) for HTTP upload usage, configuration options, diagnostics, formatting, and troubleshooting.
