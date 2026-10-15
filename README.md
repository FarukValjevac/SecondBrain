# SecondBrain

A minimal Java 25 Spring Boot application. It initializes its application context and exits; it does not start an HTTP server.

## Requirements

- A Java 25 JDK, available through `JAVA_HOME` or `PATH`.
- Internet access to Maven Central for the first build.
- On macOS/Linux, `curl` or `wget` and `unzip` for Maven Wrapper downloads. On Windows, use PowerShell and `mvnw.cmd`.

Maven Wrapper downloads Maven 3.9.16 automatically. A separate Maven installation is not required.

## Run

```sh
./mvnw spring-boot:run
```

Or build and run the executable JAR:

```sh
./mvnw verify
java -jar target/secondbrain-0.1.0-SNAPSHOT.jar
```

The application currently requires no database or AI credentials.

## Development checks

```sh
./mvnw test
./mvnw verify
```

`test` runs unit tests. `verify` also runs configured integration tests, compiler/static checks, and Java formatting checks. Live AI tests tagged `live-ai` are excluded from both commands. The startup smoke test checks that the application context can initialize without external services.

The [GitHub Actions workflow](.github/workflows/verify.yml) runs `./mvnw --batch-mode --no-transfer-progress verify` on pushes and pull requests, using Java 25. It can also be started manually from the Actions tab. The extra flags keep CI logs concise; they do not change the checks.

Apply Java formatting with:

```sh
./mvnw spotless:apply
```
