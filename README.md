Work in progress...! 

## Note 
This side project was inspired by my desire to support my girlfriend through her studies. Moral support now comes with version control. 😜

# SecondBrain

SecondBrain is a local, single-user knowledge library built with Java and Spring Boot. Currently, it accepts TXT, Markdown, and selectable-text PDF files through a browser form or HTTP endpoint, stores originals privately, and extracts text through durable background processing. The library lists uploads with processing status and links to document pages, which show numbered source lines, PDF page references, safe failure messages, retry controls, and deletion controls. Health checks, local metrics, and JSON console logs support diagnostics.

## Project goal

The goal is to turn a personal collection of notes and study materials into a library you can search and ask questions about. When complete, SecondBrain will let you:

- Upload TXT, Markdown, and PDFs containing selectable text, then inspect the extracted content.
- Find relevant passages by meaning, even when you do not remember the exact words.
- Ask questions about your documents and receive answers with citations linking to supporting passages.
- Track document processing, retry failures, and delete documents and their derived data.

Answers should acknowledge when the documents do not contain enough information. For example, you could ask “How do these notes explain photosynthesis?” and check the passages used to answer.

Semantic search and AI answers are still under development. The application and library run locally, with hosted AI services planned for semantic search and answering.

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

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/), choose a TXT, Markdown, or PDF file, and select **Upload document**. A successful upload shows **Saved and queued**; invalid files receive a useful message. Find uploads under **Your documents**, newest first, with up to 50 per page. Select a name or **Open document** to follow processing and inspect the extracted text; pending detail pages refresh automatically. The application listens on loopback and applies pending database migrations automatically.

To remove a saved document, select **Delete** beside its status in **Your documents**, or **Delete document** on its detail page, and confirm. It disappears from the library immediately; saved content is cleaned up in the background. Your source file is unchanged.

Supported extensions are `.txt`, `.md`, `.markdown`, and `.pdf`, with a default limit of 10 MB per file. TXT/Markdown extraction requires valid UTF-8. PDFs require selectable text and support up to 200 physical pages; encrypted files and scanned documents without text are unsupported. See [PDF support and limits](docs/development.md#pdf-support-and-limits) for extraction budgets and failure behavior. Extraction supports up to 100,000 lines. Originals are stored in `./data/documents`. Keep this directory and the PostgreSQL data together to preserve uploads across restarts.

On Windows, use `Copy-Item .env.example .env` and `mvnw.cmd` in place of `./mvnw`.

## Development checks

For automatic development restarts and HTML/CSS refreshes, start with `./mvnw -Pdev spring-boot:run`. See [development reload](docs/development.md#development-reload) for the edit/compile workflow.

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
