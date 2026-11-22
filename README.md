# SecondBrain

SecondBrain is a local, single-user knowledge library built with Java and Spring Boot. Currently, it accepts selectable-text PDF, Word (.docx), HTML, Markdown and UTF-8 text files such as TXT, CSV, JSON, YAML and source code through a browser form or HTTP endpoint, stores originals privately, and extracts text and prepares source-linked passages through durable background processing. The library lists uploads with processing status and links to document pages, which show numbered source lines, PDF page references, safe failure messages, retry controls, and deletion controls. Health checks, local metrics, and JSON console logs support diagnostics.

## Project goal

The goal is to turn a personal collection of notes and study materials into a library you can search and ask questions about. SecondBrain lets you:

- Upload PDFs containing selectable text, Word documents, web pages, Markdown and text files, then inspect the extracted content.
- Find relevant passages by meaning, even when you do not remember the exact words.
- Ask questions about your documents and receive answers with citations linking to supporting passages.
- Track document processing, retry failures, and delete documents and their derived data.
- Use search and answering without paid AI APIs or subscriptions, within a hosted provider's free allowance.

Answers should acknowledge when the documents do not contain enough information. For example, you could ask “How do these notes explain photosynthesis?” and check the passages used to answer.

Optional background embeddings send passage batches to Cloudflare, store vectors locally, and activate complete indexes. Saved batches survive restarts, replacements become active only when complete, and document deletion also removes derived indexes. Embeddings are disabled by default; see [embedding configuration](docs/development.md#embedding-configuration). Browser search shows matching passages across the library or selected documents. Optional questions display answers with checked source references and excerpts, or insufficient-information feedback. Source links recheck current visibility when opened. Inspect the excerpts to assess whether they support an answer.

## Requirements

- Java 25 JDK, available through `JAVA_HOME` or `PATH`.
- Docker with Docker Compose and a running Docker-compatible engine, such as Docker Desktop.
- Internet access for the initial Maven and Docker image downloads.
- For semantic search and questions, your own Cloudflare Workers AI account on the **Workers Free** plan, an API token with Workers AI access, and configured models. Uploads, text inspection, retry and deletion work without them. Enabling them sends passage text, queries and bounded evidence to Cloudflare over the internet; originals and stored content stay local. The free allowance is finite and cannot be checked by the application, and there is no paid or fallback provider.
- On macOS/Linux, `curl` or `wget` and `unzip` for Maven Wrapper.

The included Maven Wrapper downloads Maven automatically; a separate Maven installation is not required.

## Setup and startup

Run these commands from the repository root. Create the local configuration:

```sh
cp .env.example .env
```

Edit `.env` and replace the password placeholder. Use plain `KEY=value` entries without quotes or shell exports. Keep this file private; it is ignored by Git. Uploads and text inspection need no AI credentials. For semantic search, configure and enable [embeddings](docs/development.md#embedding-configuration).

Start PostgreSQL, then the application:

```sh
docker compose up -d --wait --wait-timeout 60 postgres
./mvnw spring-boot:run
```

Open [http://127.0.0.1:8080/](http://127.0.0.1:8080/). The home page is a conversation with your documents when questions are enabled: type a question, press Enter (Shift+Enter adds a line) and the cited answer appears in the thread below it. The thread lives only in that page and is not stored. Press Ctrl+K (⌘K on a Mac) on any page to jump to a page or action, and `?` to list keyboard shortcuts. Without JavaScript the same box opens the answer page. Open the library at [http://127.0.0.1:8080/library](http://127.0.0.1:8080/library), choose one or more PDF, Word, HTML, Markdown or text files (or drop them onto the upload card), and select **Upload document**. A successful upload shows **Saved and queued**; invalid files receive a useful message. Use **Manage folders** to create folders such as Sport or Psychology (nested up to three levels), choose **Save to folder** when uploading a file or importing a web page, and move a document from its page. Find uploads under **Your documents** as cards, newest first, with up to 50 per page; type in **Filter this page** to narrow the cards shown. Select a name or **Open document** to follow processing and inspect the extracted text; use **Refresh status** to see saved processing progress. Library and document pages distinguish text extraction from search readiness and offer **Retry processing** for eligible failed work. The application listens on loopback and applies pending database migrations automatically.

To remove a saved document, select **Delete** beside its status in **Your documents**, or **Delete document** on its detail page, and confirm. It disappears from the library immediately; saved content is cleaned up in the background. Your source file is unchanged.

If removal needs attention, follow **Inspect cleanup and retry failures** in the library to check outstanding cleanup and retry it after resolving the storage or database problem.

To ask questions, enable embeddings and configure [questions](docs/development.md#question-provider-boundary), restart, then select **Ask your documents**. Choose the whole library or selected documents and submit a question. To ask about one document, select **Ask about this document** on its page. With embeddings enabled, document pages also list related documents found from stored vectors, at no extra allowance. You can rate each answer as helpful or not; ratings stay on your computer. Optional [document summaries](docs/development.md#document-summaries) are off by default because each uses one chat request. **Make flashcards** on a document page creates cited study cards (one chat request), and **Review flashcards** schedules them with spaced repetition. **Practice quiz** asks questions about a document and grades your answers against its passages. Optional [folder sync](docs/development.md#folder-sync) imports and updates files from one local folder, and **Import a web page** saves a public page from its URL. Questions send query text and bounded relevant passages to the configured Cloudflare account; search and answering share its allowance.

The [example workflow](docs/development.md#example-workflow-fresh-checkout-to-deletion) walks through a fresh database, upload, processing, search, questions, source inspection and deletion with non-sensitive files.

Supported formats, with a default limit of 10 MB per file: `.pdf`; Word `.docx`; `.html`/`.htm`; `.md`/`.markdown`; and UTF-8 text such as `.txt`, `.csv`, `.tsv`, `.json`, `.xml`, `.yaml`, `.log` and common source code. Text, Markdown and HTML must be valid UTF-8. Word reads body text only (not headers, footers, comments or images); legacy `.doc`, spreadsheets and presentations are not supported. See [supported formats](docs/development.md#supported-formats). PDFs require selectable text and support up to 200 physical pages; encrypted files and scanned documents without text are unsupported. See [PDF support and limits](docs/development.md#pdf-support-and-limits) for extraction budgets and failure behavior. Extraction supports up to 100,000 lines. Originals are stored in `./data/documents`. Keep this directory and the PostgreSQL data together to preserve uploads across restarts.

On Windows, use `Copy-Item .env.example .env` and `mvnw.cmd` in place of `./mvnw`.

## Diagnostics and recovery

Check health at `http://127.0.0.1:8080/actuator/health/readiness` (database, original storage and background workers) and `/actuator/health/liveness`; metrics are at `/actuator/metrics`. Logs are JSON on the console and omit filenames, document text, queries and provider responses. Interrupted background work is retried automatically with backoff after a restart; a failed step can be retried once per click with **Retry processing**. See [health](docs/development.md#health-checks), [metrics](docs/development.md#local-metrics), [event recovery](docs/development.md#durable-event-processing) and [troubleshooting](docs/development.md#troubleshooting).

## Limitations

SecondBrain is a local single-user tool with no authentication, listening only on loopback. Requests from other websites or host names are rejected, but any program on your computer can use it. Search and answers rely on a hosted model whose quality was checked only on a small synthetic collection. A valid citation shows which passage was supplied, not that the answer is correct, so read the excerpt. See [release capabilities and known limitations](docs/development.md#release-capabilities-and-known-limitations).

## Development checks

For the optional local Grafana dashboard, start PostgreSQL as above, then run:

```sh
docker compose --profile observability up -d --wait --wait-timeout 180 observability
./mvnw spring-boot:run -Dspring-boot.run.profiles=observability
```

Open [the processing dashboard](http://127.0.0.1:3000/d/secondbrain-processing). See [local observability](docs/development.md#local-observability-dashboard) for recovery checks, trace lookup, sampling, retention, and shutdown. Normal startup requires no telemetry backend.

For automatic development restarts and HTML/CSS refreshes, start with `./mvnw -Pdev spring-boot:run`. See [development reload](docs/development.md#development-reload) for the edit/compile workflow.

```sh
./mvnw test
./mvnw verify
```

`test` runs the fast suite without Docker. `verify` also runs PostgreSQL integration tests, including the example workflow from an empty database with deterministic providers, packages the application, and checks formatting; it requires a running Docker engine but no Cloudflare credentials.

## Shutdown

Stop the application with `Ctrl+C`, then stop the database:

```sh
docker compose down
```

Database data remains in the Docker-managed volume and is reused on the next startup.

See the [development guide](docs/development.md) for HTTP upload usage, configuration options, diagnostics, formatting, and troubleshooting.
