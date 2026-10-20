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

Tomcat may need to read and discard the rest of a rejected upload before the browser can receive its error response. `server.tomcat.max-swallow-size` is set to `16MB`, allowing an 11 MiB oversized-file check to return the form's error feedback with the default 10 MB upload limit. This does not increase the accepted file/request limits. Larger rejected bodies can still exhaust this finite discard bound and cause a connection reset; review this setting when changing the upload limit. See [Tomcat's maxSwallowSize](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html).

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

After restarting with `./mvnw spring-boot:run`, look for JSON console entries with `operation=original_cleanup`. Successful batches report `inspected`, `deleted`, and `busy` counts; failures report the fixed `STORAGE_OR_DATABASE_UNAVAILABLE` code. No filenames, paths, document text, or exception details are logged. `busy` means deletion was deferred because an upload was protected. These are original-file cleanup diagnostics; document extraction uses the durable event worker.

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

## Development reload

Start PostgreSQL as described in the README, then run:

```sh
./mvnw -Pdev spring-boot:run
```

The Maven `dev` profile adds Boot-managed DevTools 4.1.1 and activates the Spring `dev` settings. Those settings read templates and static files directly from `src/main/resources` using filesystem locations, with their caches disabled. Application configuration and migrations remain on the normal compiled classpath. Normal startup uses `./mvnw spring-boot:run`; DevTools is absent from the default dependency set and excluded from executable JARs by Spring Boot's packaging defaults.

- **HTML/CSS:** save the template or stylesheet and refresh the browser. No compilation or application restart is required. Browser refresh is manual; the LiveReload server is disabled.
- **Java:** save the code, then run the following in another terminal from the project root:

  ```sh
  ./mvnw compile
  ```

  DevTools detects changed compiled classes and automatically restarts the application context. Wait for the startup log before making another request. This resets in-memory metrics; committed documents and events remain in their configured database/storage.
- **IDE builds:** an IDE that compiles changed Java classes into `target/classes` can replace the manual compile command. Saving source alone does not compile Java, and IDE output in another directory will not update the Maven-launched application.
- **Dependency/build changes:** stop and relaunch the Maven command after changing `pom.xml`. Changes to classpath application properties can trigger a restart; changes to the external `.env` require a manual restart.

To check the setup, make a temporary text change in `src/main/resources/templates/library.html`, refresh and confirm it appears, then revert it. For Java, temporarily append `" (reload check)"` to the `maximumUploadSize` model value in `LibraryController.java`, compile, and confirm a new startup log and the marker next to the form's size limit; revert and compile again. Stop the existing normal-mode process before starting development mode on the same port. Do not edit applied Flyway migrations to test reloads.

If tests report missing application settings, missing templates, or missing tables after finding zero Flyway migrations, stop any development process started with the older build configuration. Run `./mvnw clean verify` to restore and check the compiled resources, then relaunch development mode. The previous Maven `addResources` setting removed resource copies from `target/classes`; the current configuration preserves them for tests. Relaunching the old process once is necessary because it keeps its original Maven configuration.

The [DevTools reference](https://docs.spring.io/spring-boot/reference/using/devtools.html) explains restart behavior; the [Maven plugin reference](https://docs.spring.io/spring-boot/maven-plugin/run.html) documents how `addResources` removes duplicate output resources.

## Uploading documents in the browser

Open the library at [http://127.0.0.1:8080/](http://127.0.0.1:8080/), choose one TXT, Markdown, or PDF file, and select **Upload document**. The page displays the supported extensions and configured size limit. A successful submission returns to the library with **Saved and queued** and status **QUEUED**. Validation or storage failures return to the form with a safe error message; select a file again to retry.

The browser uses a normal multipart form and a `303 See Other` redirect after submission. Feedback appears once; refreshing the resulting page does not repeat the upload. **Your documents** lists stored uploads with their processing states and detail links, newest first, with 50 per page. Use **Previous** and **Next** for other pages; refresh to update processing states. When durable events are disabled, the form is hidden and the library reports that it is unavailable.

Allow cookies for the local application to receive this feedback. Sessions use cookies only (`server.servlet.session.tracking-modes=cookie`), so redirects keep the library URL `/` without adding a session ID. After changing application configuration, restart the server and check the first upload in a fresh browser session as well as subsequent uploads.

### Browser and restart walkthrough

Use small, non-sensitive UTF-8 fixtures. Return to document details through **Your documents** on the library page.

1. Start the database and application using the README. Open the library in a fresh browser session with cookies enabled. Check the form at desktop and narrow window widths.
2. Upload one non-empty TXT file and one Markdown file. Each should return to `/` with **Saved and queued** and **QUEUED**, including the first upload in the fresh session. Follow **Open document** and wait for **EXTRACTED**; verify Unicode, blank lines, and numbered source anchors.
3. Refresh after success. The message should disappear and the browser should not resubmit the file. The database should still contain one accepted record per submission.
4. Submit an empty file and a TXT file larger than the displayed limit. Each should return to the form with a useful error and no success message or new accepted record. The native file picker filters unsupported extensions; use the [HTTP check](#uploading-documents-over-http) below to verify server-side rejection too. Select the file again when retrying.
5. Use [database inspection](#database-inspection) to record the two document IDs, storage keys, checksums, and correlated events. Check that each `<storage_key>.original` exists in the configured storage directory and matches its source file.
6. Stop the application with `Ctrl+C`, then start it again from the same working directory with the same configuration. Repeat the database and original-file checks: IDs, bytes, checksums, and events should remain unchanged, and extracted text should remain available at the saved document URLs. If checking Compose persistence too, run `docker compose down`, bring PostgreSQL back using the README command, and restart the application.

Rollback, interrupted writes, abandoned-file recovery, and concurrent acceptance use isolated automated fixtures; see [verification](#verification-and-formatting). Passing this browser walkthrough does not replace `./mvnw verify`.

## PDF support and limits

PDFs need selectable text. Encrypted PDFs (including those that open with an empty password), corrupt/unsupported files, and PDFs without selectable text fail with a fixed message; OCR is not available. **Saved and queued** or HTTP `202` confirms that the original was stored and processing was queued. **EXTRACTED** confirms successful extraction.

Source links show physical PDF pages, counted from the first page including covers and blank pages; printed page numbers may differ. Each **Page N** link targets a stable numbered source line. A blank page retains a labeled empty line. Filenames and extracted text remain literal, escaped content.

| Limit | Current policy |
|---|---|
| Input bytes | Configured upload limit (10MB by default), capped at 32 MiB for PDF extraction |
| Physical pages | 200 |
| Glyph text and output text | 1,000,000 UTF-16 code units each |
| Content operators | 1,000,000 |
| Parsing time | Cooperative 15-second budget |
| Stored lines | 100,000 across all pages, including empty and trailing lines |

The upload limit is configured through `secondbrain.documents.max-upload-size`; the other PDF budgets are fixed application policy. The time/work limits are cooperative application checks, not hard process or heap isolation. Invalid input and exceeded PDF budgets stop automatic delivery after one attempt. Source I/O failures use bounded retries; manual retry starts a new processing version without repairing the original file. The existing retry and deletion controls apply.

### PDF browser check

1. Choose a small selectable-text PDF using **Choose a file**, then select **Upload document** and **Open document**. Wait for **EXTRACTED** and check that the text and **Page N** labels match its physical pages, including any blank pages. Select a page label and confirm the URL targets that numbered source line.
2. Return to **Your documents** and reopen it. Restart the application using the README commands, then confirm its content and page references remain available.
3. Select **Delete**, then **Cancel**, and confirm the PDF remains available. Repeat deletion and confirm it; the entry should disappear and its old detail URL should remain unavailable after restart. Your source file stays unchanged.

Run `./mvnw verify` with Docker available as the full automated gate. Generated fixtures in `DocumentExtractionIT` cover permanent PDF failures, retry/restart recovery, stored page identity, and cleanup without using the real library. Parser budget/failure cases are in `PdfTextExtractorTest`. The browser check supplements these tests.

## Text extraction and retry

The worker decodes TXT/Markdown strictly as UTF-8. It removes an optional leading byte-order mark, recognizes CR/LF/CRLF, and preserves Unicode, indentation, blank lines, and literal Markdown syntax. The upload byte budget also bounds extraction; documents may contain at most 100,000 numbered lines. Empty/whitespace-only text, NUL characters, and invalid encoding produce safe failures. The detail page escapes all names and content; it does not render document HTML.

Open `/documents/{documentId}` or follow **Open document** after uploading. Queued/processing pages refresh every two seconds. A failed document offers **Retry extraction** after automatic attempts finish. A manual retry keeps the document/original identity and increments its processing version; it does not repair invalid source content. Extracted text is not yet a searchable index.

Existing accepted uploads are reconciled in batches of 100 every second. `secondbrain.documents.extraction.enabled=false` disables the handler and reconciliation while acceptance remains possible; `secondbrain.documents.extraction.reconcile-enabled=false` disables only legacy scheduling. Worker polling/retry/concurrency settings remain under `secondbrain.events`. Restarting with normal settings resumes committed work; reconciliation never resets failed deliveries.

Use existing `secondbrain.event.delivery.attempts`, `secondbrain.event.delivery.duration`, and backlog metrics for processing diagnostics. `event.delivery` observations and safe structured logs carry document/version/event/delivery/correlation IDs across restart; fixed outcomes/codes distinguish completion, skipped stale work, retries, and failure. No document text, names, or exception messages enter this telemetry. Failure state and attempt/retry bookkeeping commit together under the same lease fence as successful completion.

## Indexing requests

Once text extraction completes, a durable `TextExtracted` delivery selects an independent index generation and publishes `ReindexRequested` in the same transaction as its acknowledgment. A bounded reconciliation pass also enrolls existing extracted documents after startup, without needing their historical extraction events. Repeated enrollment reuses the selected generation; extraction retries and document deletion prevent old work from applying.

The passage consumer reads the stored extraction, splits it locally, and commits the passages, their configuration/count metadata, and a durable `PassagesPrepared` fact with its delivery acknowledgment. Existing retained requests receive missing passage deliveries during reconciliation. `PassagesPrepared` remains stored for the later embedding consumer. The library still shows extraction status; a prepared passage set is not yet a searchable index. No model call or credential is needed for passage preparation.

The Java application operation is `DocumentIndexingService.requestNext(documentId, correlationId)` for a deliberate new generation. `request(documentId, indexingVersion, correlationId)` repeats one specified generation idempotently; obsolete versions and missing, hidden, or unextracted documents return an empty result. These operations do not yet have a browser or HTTP reindex control.

To inspect enrollment, start the app normally, wait a few seconds, then run this read-only query in the database shell described below:

```sql
SELECT d.document_id, d.original_name, d.processing_version AS extraction_version,
       d.desired_indexing_version, i.passage_count,
       i.passage_configuration_version, k.event_id, delivery.state AS passage_delivery_state
FROM documents d
LEFT JOIN document_index_versions i ON i.document_id = d.document_id
    AND i.indexing_version = d.desired_indexing_version
LEFT JOIN event_keys k ON k.document_id = d.document_id
    AND k.processing_version = d.desired_indexing_version
    AND k.event_type = 'ReindexRequested'
LEFT JOIN event_deliveries delivery ON delivery.event_id = k.event_id
    AND delivery.handler_id = 'documents.prepare-passages.v1'
WHERE d.deleted_at IS NULL AND d.extraction_state = 'EXTRACTED'
ORDER BY d.document_id;
```

After processing, expect a positive desired version/event ID, `passage_count` greater than zero, configuration version 1, and a COMPLETED passage delivery. Pending or failed deliveries retain their corresponding state; no partial snapshot is marked complete. Source and index version numbers may differ. `secondbrain.documents.indexing.enabled=false` pauses automatic extraction handoffs/enrollment and passage consumption; `secondbrain.documents.indexing.reconcile-enabled=false` disables only reconciliation. `secondbrain.documents.indexing.passages.enabled=false` pauses just the passage consumer, preserving requests for reconciliation when re-enabled. Explicit Java indexing requests remain available.

Safe request measurements are `secondbrain.document.indexing.requests` and `secondbrain.document.indexing.request.duration`, with fixed requested/unchanged/unavailable outcomes. Success is recorded only after commit. Request counts include repeated calls; they are not unique index counts. Existing event metrics and correlated logs describe durable handoffs. No filenames, text, or credentials become metric labels.

## Embedding configuration

Embedding settings are opt-in and currently validate configuration only. No provider client or embedding consumer is installed, and no text is sent to Cloudflare. Normal startup needs no AI credentials. Run `./mvnw -Dtest=EmbeddingConfigurationTest,EmbeddingVectorTest test` to verify this contract without Docker or an account.

The supported contract is explicitly selected `@cf/baai/bge-m3` with 1024 dimensions; it is not yet a live-verified default. Unknown models and incompatible dimensions fail validation. Model changes require deliberate reindexing. A vector must have exactly the configured number of finite components, with at least one nonzero component for cosine retrieval; callers cannot mutate its stored components.

| Setting | Default | Accepted value or purpose |
|---|---|---|
| `SECONDBRAIN_EMBEDDINGS_ENABLED` | `false` | Opt into configuration validation |
| `CLOUDFLARE_ACCOUNT_ID` | Empty | Required 32-character hexadecimal account ID when enabled |
| `CLOUDFLARE_API_TOKEN` | Empty | Required API token when enabled; keep private in `.env` or the environment |
| `CLOUDFLARE_EMBEDDING_MODEL` | Empty | Explicitly select `@cf/baai/bge-m3` |
| `CLOUDFLARE_EMBEDDING_DIMENSIONS` | `0` | Explicitly select `1024` |
| `secondbrain.embeddings.batch-size` | `8` | Between 1 and 32 inputs per provider request |
| `secondbrain.embeddings.connect-timeout` | `2s` | Between 100 ms and 10 seconds |
| `secondbrain.embeddings.request-timeout` | `30s` | At least the connect timeout and at most 60 seconds |

The uppercase settings work in the imported `.env` file; corresponding application properties use the `secondbrain.embeddings` prefix. The endpoint is fixed to the account-scoped HTTPS API on `api.cloudflare.com`. Credentials are excluded from settings diagnostics and validation messages. Limits currently constrain settings; transport enforcement belongs to the provider implementation.

Live inference requires a Cloudflare account on **Workers Free** and an API token with Workers AI access, and sends passage/query text to Cloudflare. Originals and persisted content remain local. Stay on Workers Free: the application cannot inspect or enforce an account's billing plan. The free allowance is shared and finite; calls after exhaustion fail. See [Cloudflare pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/) and [data usage](https://developers.cloudflare.com/workers-ai/platform/data-usage/). No live model quality, quota consumption, or account permissions have been verified.

## Staged vector storage

`document_embeddings` stores one vector per stable passage ID, tied to its document, index generation, and model/dimensions. The first batch binds the model on `document_index_versions`. The storage adapter accepts up to 32 vectors in a caller-owned transaction, rechecks current document/source/desired-version eligibility, preserves identical retries, and rejects conflicting values. Foreign keys cascade physical cleanup; partial or old generations remain staged.

No embedding consumer currently invokes this adapter, so normal uploads still produce no vector rows. Run `./mvnw -Dit.test=EmbeddingRepositoryIT,DatabaseMigrationIT verify` with Docker to check isolated vector round-trip, migration, rollback, duplicate/concurrent staging, and ownership constraints. The full gate remains `./mvnw verify`; neither requires live AI credentials.

## Deleted-document cleanup

Select **Delete** beside a document’s status in **Your documents**, or open its detail page and select **Delete document**, then review the confirmation page. **Cancel** returns without changes. Confirming submits a POST request, hides the document immediately, commits a durable cleanup request, and redirects to the library with one-time feedback. Refreshing the library does not resubmit deletion; repeated submissions safely report that the document is already absent. The `documents.delete.v1` worker removes its generated original file outside a database transaction, then removes document metadata, all extracted-text versions, and obsolete processing payloads/deliveries in the lease-checked acknowledgment transaction. Small event keys remain to prevent duplicate publication. A missing original is success; a storage or database failure retains recoverable work and uses the configured event retry budget. Exhausted attempts remain failed and hidden.

| Setting | Default | Purpose |
|---|---|---|
| `secondbrain.documents.deletion.enabled` | `true` | Register the durable cleanup handler |
| `secondbrain.documents.deletion.reconcile-enabled` | `true` | Schedule retained requests that predate the handler |

Reconciliation runs every second in batches of up to 100. It does not duplicate deliveries or reset failed attempts. Disabling the handler still permits deletion requests and preserves their events. `secondbrain.events.polling-enabled=false` pauses execution; `secondbrain.documents.cleanup.enabled` controls the separate abandoned-file scanner. GET `/documents/{id}/delete` displays confirmation without changing state; POST to that URL requests deletion and returns `303 See Other` to `/`. Malformed IDs return 400, unavailable confirmation targets return 404, and database/transaction failures return fixed 503 text without a success message.

Restarting the application with normal settings processes existing deletion requests automatically. Use the document UUID with the [database inspection](#database-inspection) connection: successful cleanup leaves no matching row in `documents` or `document_extractions`, and its `documents.delete.v1` delivery is `COMPLETED` (or `SKIPPED` for already absent work). The generated `.original` file is removed; source files selected for upload remain untouched. Generic event-delivery logs, attempt metrics, and backlog metrics cover cleanup using persisted correlation IDs. The `secondbrain.document.cleanup.deleted` counter belongs to the abandoned-file scanner and does not count this durable handler.

Run `./mvnw -Dit.test=DocumentDeletionIT,DocumentDeletionDeliveryIT verify` for isolated PostgreSQL cleanup, missing-file recovery, retry, and deletion-request checks, including competing delivery claims, lease expiry during cleanup, and old extraction work after deletion. The full gate remains `./mvnw verify`.

## Uploading documents over HTTP

With the application running, submit one existing TXT, Markdown, or PDF file using the `file` field:

```sh
curl -i -F 'file=@notes.txt' http://127.0.0.1:8080/documents
```

Accepted HTTP extensions are `.txt`, `.md`, `.markdown`, and `.pdf`, case-insensitively. Filename metadata is limited to 1024 UTF-8 bytes and cannot contain null characters. The file's bytes are stored unchanged; acceptance does not decode text. Each accepted upload gets a generated document ID and private storage key, even when its contents match an earlier upload.

For a PDF, use an existing file, for example `curl -i -F 'file=@notes.pdf' http://127.0.0.1:8080/documents` from the directory containing `notes.pdf`. Follow the returned document ID to `/documents/{documentId}` for extracted text and page references or a safe processing-failure message. See [PDF support and limits](#pdf-support-and-limits) for accepted content, budgets, and recovery behavior.

A successful response is HTTP `202 Accepted`, with an `X-Correlation-ID` header and JSON shaped as:

```json
{"documentId":"00000000-0000-4000-8000-000000000001","state":"QUEUED"}
```

The ID above is illustrative; each upload returns its own ID. Metadata and its durable `DocumentAccepted` event commit together before the response. Only the ID and queued state are returned; the original path and checksum are internal. Open `/documents/{documentId}` to follow extraction and read the result. The browser form uses `/documents/upload` and redirects to the page with feedback; `/documents` continues to return JSON with the status codes below.

Missing, repeated, empty, unsupported, or invalid file input returns HTTP `400` with a fixed `code` and `message`. A file or total request exceeding the configured bounds returns HTTP `413`; malformed multipart requests return `400`, and a non-multipart content type returns `415`. Storage/database failures return `503` with a fixed message. Error responses omit filenames, contents, and internal exception details.

To check unsupported-extension rejection despite the browser's file-picker filter, submit the existing TXT bytes with an unsupported extension:

```sh
curl -i -F 'file=@notes.txt;filename=notes.exe' http://127.0.0.1:8080/documents
```

Expected: HTTP `400` with code `UNSUPPORTED_TYPE` and message `Only TXT, Markdown, and PDF files are supported.`

To associate acceptance with a known request, supply a canonical UUID in `X-Correlation-ID`; otherwise the application generates one. The accepted ID is returned in the response header and persisted in the durable event. Disabling durable events with `secondbrain.events.enabled=false` also disables the upload endpoint; disabling only worker polling still permits uploads to be durably queued.

## Database inspection

Flyway applies pending migrations on startup. V1 enables pgvector, V2 creates the durable event tables, V3 creates document metadata, V4 creates versioned extraction/numbered-text-line tables, and V5 records fixed extraction failure reasons. V6 adds deletion markers, V7 adds optional physical page numbers to stored lines, and V8 permits PDF metadata and failure codes. V9 adds index-generation metadata tied to an extracted-text version, plus an optional desired-version pointer; existing documents start with no selected index. V10 adds private staged passages with stable IDs/source ranges and binds their count/splitter configuration to each index generation. V11 adds private `vector(1024)` embedding rows, composite passage/model ownership, and per-index model binding; it does not generate vectors or activate indexes. Applied versions are recorded in `flyway_schema_history`; subsequent startups validate history and apply only pending migrations.

`document_extractions` identifies text by document UUID and processing version; `document_text_lines` stores its text once, with one-based line numbers and preserved empty lines. Its `page_number` is a positive physical page for PDFs and SQL NULL for TXT/Markdown. The durable extraction handler writes these tables and publishes `TextExtracted` together with delivery acknowledgment. Upload HTTP responses still return immediately with `QUEUED`; extraction completes asynchronously.

Open a database shell using the configured user and database:

```sh
docker compose exec postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Then run:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
```

After an upload, inspect its current stored extraction state and event correlation without selecting private names or text:

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

### Upload, deletion request, and cleanup measurements

| Meter | Unit | Labels / meaning |
|---|---|---|
| `secondbrain.document.uploads` | count | `outcome=accepted,rejected,unavailable` |
| `secondbrain.document.upload.duration` | seconds | Acceptance-operation duration; same outcomes, plus fixed `error=none` |
| `secondbrain.document.cleanup.batches` | count | `outcome=completed,failed` |
| `secondbrain.document.cleanup.duration` | seconds | Cleanup-batch duration; same outcomes, plus fixed `error=none` |
| `secondbrain.document.cleanup.deleted` | count | Originals actually deleted; no labels |
| `secondbrain.document.deletions` | count | Deletion requests; `outcome=requested,unchanged,unavailable` |
| `secondbrain.document.deletion.duration` | seconds | Deletion-request duration through transaction completion; same outcomes, plus fixed `error=none` |
| `secondbrain.document.deletion.backlog` | documents | `state=pending,failed`; outstanding cleanup requests, split into disjoint states |
| `secondbrain.document.deletion.backlog.oldest` | seconds | Age since the oldest outstanding deletion request, including failures |
| `secondbrain.document.deletion.backlog.available` | 0 or 1 | Whether the most recent cleanup backlog query succeeded |

Deletion-request measurements cover calls to the internal deletion service. `requested` means a new deletion marker and event committed; `unchanged` means the document was absent or already marked deleted. Rollback or transaction failure records `unavailable`. These outcomes do not mean original files or text have been physically removed. Logs use `operation=document_deletion` and a UUID correlation ID. The isolated PostgreSQL checks run with `./mvnw -Dit.test=DocumentDeletionIT verify`; the browser confirmation action uses the same measured service.

Cleanup backlog gauges share one five-second-cached query with a five-second SQL timeout. Pending includes hidden documents waiting for a delivery, execution, or retry, including requests made while the handler was disabled; failed includes current terminal cleanup failures. Active documents and obsolete delivery versions are excluded. Physical cleanup removes the document row and clears its contribution. Counts are zero and age is zero when nothing remains. A failed database read yields `NaN` counts/age and availability 0; it never invents an empty backlog. No document IDs, names, or paths are metric labels.

```sh
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.deletion.backlog?tag=state:pending'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.document.deletion.backlog?tag=state:failed'
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.document.deletion.backlog.oldest
```

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

The PDF extraction component can be checked independently with `./mvnw -Dtest=PdfTextExtractorTest test`. Its generated fixtures cover page references, Unicode, encrypted/corrupt/image-only input, resource budgets, and interruption. Optional page-number storage and the upgrade of existing text can be checked with `./mvnw -Dit.test=DocumentRepositoryIT,DatabaseMigrationIT verify` (requires Docker). PDF uploads now use the same durable worker; check them with `./mvnw -Dit.test=DocumentExtractionIT,DocumentRepositoryIT,DatabaseMigrationIT verify`. The browser picker also accepts PDFs; document pages display escaped text with physical page labels linked to the numbered source lines.

Index-version metadata, source prerequisites, desired-version ownership, rollback, and cascade cleanup can be checked with `./mvnw -Dit.test=IndexingVersionIT,DatabaseMigrationIT verify` (requires Docker). Atomic requests, concurrent allocation, enrollment/restart, controlled passage routing, stale-work guards, and acknowledgment rollback are covered by `./mvnw -Dit.test=DocumentIndexingIT,DurableEventsIT verify`. The stage in this test is a controlled probe; it does not establish real passage splitting, embeddings, or search readiness.

Check the standalone splitter with `./mvnw -Dtest=PassageSplitterTest test` without Docker. Its fixtures cover bounded overlap, Unicode, line references, and physical PDF page boundaries.

With Docker running, use `./mvnw -Dit.test=DocumentPassageDeliveryIT,PassageRepositoryIT,DatabaseMigrationIT verify` for passage processing. These tests cover stable IDs/source locations, immutable configuration, atomic passage/event/acknowledgment rollback, stale/deleted work, restart recovery, repeated effects, event ordering, and migration compatibility. They use isolated containers and temporary originals.

Full `./mvnw verify` remains the completion gate. For manual inspection of prepared passages, use the query under [indexing requests](#indexing-requests).

Apply Java formatting before committing:

```sh
./mvnw spotless:apply
```

The [GitHub Actions workflow](../.github/workflows/verify.yml) runs the shared Maven verification command on pushes and manual dispatch. It can be started manually from the Actions tab.

## Troubleshooting

- **Java runtime not found:** run `java -version` in the same terminal. Ensure a Java 25 JDK is available through `JAVA_HOME` or `PATH` in every new shell.
- **Integration tests cannot find Docker:** start the Docker engine and check `docker info` from the terminal running Maven. A healthy Compose database alone does not establish that Testcontainers can access Docker.
- **Database authentication fails after editing `.env`:** the stored database retains its existing credentials. Use those credentials or deliberately change them in PostgreSQL; editing `.env` configures the clients and does not update an initialized database user.
