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

Open `/documents/{documentId}` or follow **Open document** after uploading. Queued/processing pages refresh every two seconds. A failed document offers **Retry extraction** after automatic attempts finish. A manual retry keeps the document/original identity and increments its processing version; it does not repair invalid source content. Extraction alone does not make a searchable index; optional embeddings must finish and publish a complete index.

Existing accepted uploads are reconciled in batches of 100 every second. `secondbrain.documents.extraction.enabled=false` disables the handler and reconciliation while acceptance remains possible; `secondbrain.documents.extraction.reconcile-enabled=false` disables only legacy scheduling. Worker polling/retry/concurrency settings remain under `secondbrain.events`. Restarting with normal settings resumes committed work; reconciliation never resets failed deliveries.

Use existing `secondbrain.event.delivery.attempts`, `secondbrain.event.delivery.duration`, and backlog metrics for processing diagnostics. `event.delivery` observations and safe structured logs carry document/version/event/delivery/correlation IDs across restart; fixed outcomes/codes distinguish completion, skipped stale work, retries, and failure. No document text, names, or exception messages enter this telemetry. Failure state and attempt/retry bookkeeping commit together under the same lease fence as successful completion.

## Indexing requests

Once text extraction completes, a durable `TextExtracted` delivery selects an independent index generation and publishes `ReindexRequested` in the same transaction as its acknowledgment. A bounded reconciliation pass also enrolls existing extracted documents after startup, without needing their historical extraction events. Repeated enrollment reuses the selected generation; extraction retries and document deletion prevent old work from applying.

The passage consumer reads the stored extraction, splits it locally, and commits the passages, their configuration/count metadata, and a durable `PassagesPrepared` fact with its delivery acknowledgment. Existing retained requests receive missing passage deliveries during reconciliation. `PassagesPrepared` remains stored while embeddings are disabled; the opt-in embedding consumer processes it when enabled. The library still shows extraction status; a prepared passage set is not yet a searchable index. No model call or credential is needed for passage preparation.

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

Embedding settings are opt-in. Enabling them constructs the Cloudflare HTTP adapter after validation; client construction and health probes make no inference request. The document embedding consumer is enabled by the same opt-in and can process new and retained passages when background polling runs. This sends bounded passage text to Cloudflare; normal disabled startup needs no AI credentials and makes no model calls. Run `./mvnw -Dtest=CloudflareEmbeddingProviderTest,EmbeddingConfigurationTest,EmbeddingVectorTest test` for configuration/protocol checks without an account.

The supported contract is explicitly selected `@cf/baai/bge-m3` with 1024 dimensions; it is not yet a live-verified default. Unknown models and incompatible dimensions fail validation. Model changes require deliberate reindexing. A vector must have exactly the configured number of finite components, with at least one nonzero component for cosine retrieval; callers cannot mutate its stored components.

| Setting | Default | Accepted value or purpose |
|---|---|---|
| `SECONDBRAIN_EMBEDDINGS_ENABLED` | `false` | Enable the provider and background passage embedding consumer |
| `CLOUDFLARE_ACCOUNT_ID` | Empty | Required 32-character hexadecimal account ID when enabled |
| `CLOUDFLARE_API_TOKEN` | Empty | Required API token when enabled; keep private in `.env` or the environment |
| `CLOUDFLARE_EMBEDDING_MODEL` | Empty | Explicitly select `@cf/baai/bge-m3` |
| `CLOUDFLARE_EMBEDDING_DIMENSIONS` | `0` | Explicitly select `1024` |
| `secondbrain.embeddings.batch-size` | `8` | Between 1 and 32 inputs per provider request |
| `secondbrain.embeddings.connect-timeout` | `2s` | Between 100 ms and 10 seconds |
| `secondbrain.embeddings.request-timeout` | `30s` | At least the connect timeout and at most 60 seconds |

The uppercase settings work in the imported `.env` file; corresponding application properties use the `secondbrain.embeddings` prefix. The endpoint is fixed to the account-scoped HTTPS API on `api.cloudflare.com`. Credentials are excluded from settings diagnostics and validation messages. Each explicit Java `embed` call enforces those settings. An explicitly supplied `EmbeddingProvider` bean replaces the default adapter without requiring Cloudflare credentials, supporting controlled tests.

Live inference requires a Cloudflare account on **Workers Free** and an API token with Workers AI access, and sends passage/query text to Cloudflare. Originals and persisted content remain local. Stay on Workers Free: the application cannot inspect or enforce an account's billing plan. The free allowance is shared and finite; calls after exhaustion fail. See [Cloudflare pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/) and [data usage](https://developers.cloudflare.com/workers-ai/platform/data-usage/). No live model quality, quota consumption, or account permissions have been verified.

## Embedding HTTP adapter

`CloudflareEmbeddingProvider` sends one bounded application request to the account-scoped compatible embeddings endpoint and validates one vector per input. It restores input ordering using response indexes. Calls are rejected inside database transactions. Inputs are limited to the configured batch size (default 8, maximum 32), 1200 Unicode code points per nonblank input, valid Unicode without NUL, and a 256 KiB encoded body. Response reading is capped at 1 MiB; JSON depth, strings, numbers, and token counts are bounded. Incorrect model metadata, indexes/counts, dimensions, nonnumeric/nonfinite/zero components, malformed JSON, and unexpected success content types fail safely.

Redirects are disabled, and production has no endpoint override. The configured request deadline includes receiving the complete body; timeout/interruption cancels the pending operation. Owned HTTP resources and their bounded networking executor close with the application. The adapter performs no automatic application retry, fallback, paid upgrade, database write, or event acknowledgment.

Failures expose fixed reasons: access denial, unavailable model, invalid input/response, rate limiting, daily quota exhaustion, provider/transport unavailability, and timeout. Valid `Retry-After` hints are returned as timestamps for retryable rate/quota/service failures. Documented Cloudflare quota code `3036` also suggests the next UTC reset; private message text is not used for classification. Oversized/incomplete response streams are safe transport failures. The adapter never sleeps until a retry time. The document handler passes these hints to durable delivery scheduling, using the later of normal backoff and the hint. The configured attempt limit still applies; no worker waits until the reset. Raw parser/transport causes remain private and outside the default logged/serialized cause chain.

Run `./mvnw -Dit.test=CloudflareEmbeddingHttpIT verify` for a real loopback stub with synthetic credentials/text. It needs local socket permission, but no Docker or Cloudflare account. It covers request/response ordering, bounds, status/quota errors, redirect refusal, slow headers/body, interruption, and connection failure. Full `./mvnw verify` remains the completion gate. No live BGE-M3 response or retrieval quality is established by the stub.

## Staged vector storage

`document_embeddings` stores one vector per stable passage ID, tied to its document, index generation, and model/dimensions. The first batch binds the model on `document_index_versions`. The storage adapter accepts up to 32 vectors in a caller-owned transaction, rechecks current document/source/desired-version eligibility, preserves identical retries, and rejects conflicting values. Foreign keys cascade physical cleanup; partial or old generations remain staged.

The opt-in document handler saves each successful batch through a lease-checked checkpoint. Default disabled uploads produce no vector rows; enabled uploads resume missing batches across retries/restarts. Run `./mvnw -Dit.test=EmbeddingRepositoryIT,DatabaseMigrationIT verify` with Docker to check isolated vector round-trip, migration, rollback, duplicate/concurrent staging, and ownership constraints. The full gate remains `./mvnw verify`; neither requires live AI credentials.

## Durable document embeddings

With `secondbrain.embeddings.enabled=true`, the `documents.embed-passages.v1` handler consumes `PassagesPrepared`. It reads at most the configured batch size of missing passages, closes its read transaction, calls the provider, then saves vectors in a short transaction guarded by the delivery lease and current document/source/index generation. Every successful checkpoint preserves the attempt/deadline and prior progress. Final acknowledgment requires all expected vectors and publishes the complete active index with an `IndexPublished` fact in the same fenced transaction. Partial or obsolete generations remain staging data. The search service and browser search form use only complete active indexes.

Existing indexing reconciliation schedules up to 100 missing deliveries for retained passage facts per pass when the handler is present. It never resets existing pending/failed/completed deliveries. Stop event polling before disabling embeddings if outstanding embedding or publication deliveries need to remain paused; removing a handler while polling continues uses the existing missing-handler failure behavior. Terminal embedding failures currently appear in durable-delivery diagnostics; document pages still show extraction status.

Retries request only missing vectors. A crash after an HTTP response and before saving its batch can repeat a provider call and consume allowance again. Quota/reset hints persist as delivery eligibility timestamps; they release worker capacity and cannot bypass the attempt limit. No paid/provider fallback occurs. Model/account compatibility and eligibility still need a separately authorized bounded live check before real use; routine verification uses deterministic inference.

```sh
./mvnw -Dit.test=DocumentEmbeddingDeliveryIT,DurableEventsIT,EmbeddingRepositoryIT verify
```

This requires Docker but no Cloudflare account. It checks multi-batch/restart recovery, stale versions/deletion/leases, final-acknowledgment rollback, retained facts, and retry eligibility. Existing event traces/attempt metrics cover the delivery. Provider request telemetry is described below.

## Index publication prerequisites

V12 adds `documents.active_indexing_version`, initially NULL. It is separate from `desired_indexing_version`: desired identifies the generation being built, while active identifies a generation selected after completeness validation. Existing data is not activated by the migration. The enabled embedding worker calls the activation operation after staging all vectors. Activation, `IndexPublished`, and acknowledgment share the dispatcher's lease-fenced transaction. There is no semantic-search endpoint yet.

`JdbcIndexingRepository.activate` is an internal caller-transaction operation. It locks/rechecks the visible, extracted, desired generation and its source, then requires the supported passage configuration, exact contiguous passage count, compatible model/dimensions, and one valid owned vector per passage. It returns `ACTIVATED`, `ALREADY_ACTIVE`, `INCOMPLETE`, or `INELIGIBLE`. Only successful new activation changes the pointer/timestamp. A repeat leaves the timestamp unchanged; a failed or incomplete replacement leaves an existing active pointer intact. The composite deferred foreign key prevents selecting another document's generation or removing a selected target independently.

The repository operation does not publish an event or validate delivery leases by itself. `DocumentIndexPublication` supplies the shared effect; the normal embedding handler calls it before returning to the dispatcher for acknowledgment. If activation, event insertion, transaction completion, or the final lease check fails, the active pointer, new fact/key, and acknowledgment roll back together. Earlier batch checkpoints remain staged. Future retrieval must require a visible document and an active generation compatible with its current extraction/model, rather than treating staging rows as searchable.

```sh
./mvnw -Dit.test=IndexPublicationIT,DatabaseMigrationIT verify
```

These PostgreSQL/pgvector checks require Docker and synthetic vectors only. They exercise completeness/version/deletion guards, caller rollback, concurrent activation, immutable repeats, replacement preservation, ownership constraints, and cascade deletion. For a read-only schema check after applying V12:

```sql
SELECT document_id, desired_indexing_version, active_indexing_version
FROM documents
WHERE deleted_at IS NULL
ORDER BY created_at DESC;
```

An empty active pointer is expected while processing is disabled, incomplete, or failed. Avoid setting it manually; direct SQL writes bypass the application completeness guard.

## Atomic publication and recovery

The normal `documents.embed-passages.v1` delivery acknowledges `PassagesPrepared` only after the shared publication effect succeeds. It selects the complete desired/current generation, emits one ID-only schema-1 `IndexPublished` fact, and acknowledges in the same transaction. Correlation is retained, and causation points to that `PassagesPrepared` event. Repeating an effect uses the original logical event identity; an already active generation still repairs a missing fact. `IndexPublished` is terminal and creates no additional business delivery. Library pages still display extraction status. Index readiness metrics are available below; semantic-search endpoints remain under development.

Indexing reconciliation also handles fully staged generations whose embedding delivery is already completed. It creates an idempotent `IndexPublicationRequested` recovery intent and a `documents.publish-index.v1` delivery. This pure database operation uses the same publication effect and its own fenced acknowledgment. It never reopens the completed embedding delivery, copies a failed job into a new budget, or repeats inference. Pending/running/failed/skipped embedding deliveries and existing recovery/publication keys exclude this enrollment; ordinary existing work keeps its original retry policy. Counts/model/configuration must indicate complete staging, and the consumer rechecks completed embedding provenance and the full activation guard again.

Up to 100 recovery candidates are read per reconciliation pass, with a separate bounded transaction and a document/version/state recheck for each intent. Existing `secondbrain.documents.indexing.reconcile-enabled` and embedding/indexing enable switches apply. Retained recovery intents can gain missing deliveries when the consumer is enabled. A failed recovery delivery remains failed rather than receiving a new intent on every scan.

If the old passage event payload still exists, recovery retains its correlation and trace context. If retention has removed the payload/delivery while keeping the logical key, recovery starts a fresh correlation root and retains the passage event ID as causation. The published fact points to the recovery intent, preserving that chain. No document text or extra queue/progress table is added. Recovery enrollment requires no model call.

```sh
./mvnw -Dtest=DocumentIndexPublicationTest,DocumentEmbeddingHandlerTest test
./mvnw -Dit.test=DocumentEmbeddingDeliveryIT,IndexPublicationIT,DurableEventsIT verify
```

The PostgreSQL checks exercise insertion/deferred-transaction failure, acknowledgment failure, lease expiry after publication effects, competing valid deliveries, restart with retained/cleaned historical payloads, and exclusion of existing embedding budgets. They use isolated databases, temporary originals, and deterministic inference. Full `./mvnw verify` remains the gate; no live credentials or original library data are required.

## Index readiness telemetry

When embeddings, indexing, and events are enabled, Actuator exposes these unlabelled metrics:

| Metric | Meaning |
|---|---|
| `secondbrain.index.active` | Number of visible, current-source active indexes compatible with the configured model and splitter configuration |
| `secondbrain.index.active.available` | 1 when the cached database read succeeded, 0 when unavailable |
| `secondbrain.index.ready.duration` | Timer from original upload acceptance to each newly committed index activation, in seconds |

The count comes from the committed active-version relationship, not a publication counter. Retries cannot inflate it, and a restart rebuilds it from PostgreSQL. A compatible old active generation stays counted while its replacement is staged. Hidden documents, outdated extraction sources, and staged-only generations are excluded. Publication's completeness guard validates passages/vectors before selecting that pointer; the metric does not scan all vector contents on every scrape.

Both gauges share one read cached for five seconds, including failures. Queries use a separate read-only transaction with a five-second transaction/query timeout. A failed read reports `NaN` for the count and availability 0 rather than claiming an empty library. Values may lag publication/deletion by up to the cache interval. These metrics describe index data; they do not change `/actuator/health/readiness`.

The timer records only after a new activation commits, including activation through publication recovery. It does not count provider calls, vector checkpoints, rollback, lease loss, or repeated publication of an already active generation. Reindexing can add a new sample measured from the document's original acceptance. Samples include queued/retry time. Timer data resets on restart and is best effort: a crash after commit, telemetry failure, or deletion/replacement before its post-commit lookup can lose a sample. It is not a durable publication ledger. Future-dated acceptance timestamps produce no sample. Application correctness never depends on these measurements.

```sh
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.index.active
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.index.active.available
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.index.ready.duration
```

With indexing enabled and successfully processed documents, availability should be 1. The active count reflects the eligible library; the timer's `COUNT` reflects new activations observed by this process. No filenames, document IDs, content, model credentials, or queries are metric labels. These queries make no provider request. Do not enable hosted inference solely to inspect metrics unless its setup and data sharing are intended.

For isolated verification without credentials or model requests:

```sh
./mvnw -Dtest=IndexReadinessMetricsTest,DocumentIndexPublicationTest,DocumentTelemetryEndpointTest test
./mvnw -Dit.test=IndexPublicationIT,DocumentEmbeddingDeliveryIT verify
```

The PostgreSQL checks cover committed versus uncommitted state, rollback, replacement visibility, source/deletion changes, fresh collectors, retries, failed acknowledgment, and lost leases. The full gate remains `./mvnw verify`.

## Verifying the complete indexing workflow

The indexing pipeline is:

```text
Upload -> DocumentAccepted -> extracted text -> TextExtracted
       -> ReindexRequested -> passages -> PassagesPrepared
       -> embedding batches -> active index + IndexPublished
```

The upload returns `202 Accepted` with `QUEUED` after storing its original and durable request. The automatic worker handles later stages. A successful provider response alone does not make an index ready: all batches, guarded activation, publication, and acknowledgment must complete. Library pages currently show extraction status; use the active pointer or index metrics to inspect indexing. Semantic search is not yet implemented.

Run the complete offline workflow checks with Docker and local HTTP listeners available:

```sh
./mvnw -Dit.test=DocumentIndexingWorkflowIT verify
./mvnw verify
```

`DocumentIndexingWorkflowIT` uses real HTTP multipart uploads, isolated PostgreSQL/pgvector, temporary original files, automatic polling, real stage handlers, and deterministic embeddings. It covers TXT/Markdown/selectable PDF input; upload completion while inference is deliberately blocked; located passages and vector identity; one logical publication despite repeated requests; correlation/causation and linked stage spans; quota-delayed partial progress across restart; and deletion during inference followed by restart without resurrection. It makes no Cloudflare or external telemetry request and ignores local configuration imports. Required tests fail if Docker or local listeners are unavailable rather than silently skipping.

Existing delivery tests additionally cover failed publication insertion/commit/acknowledgment, stale leases, competing completions, old completed staging, and retry-budget exhaustion. The workflow checks complement those focused fault tests.

For a read-only check of an already configured local library:

```sql
SELECT d.document_id, d.original_name, d.extraction_state,
       d.desired_indexing_version, d.active_indexing_version,
       i.embedding_model_id, i.passage_count
FROM documents d
LEFT JOIN document_index_versions i
  ON i.document_id = d.document_id
 AND i.indexing_version = d.active_indexing_version
WHERE d.deleted_at IS NULL
ORDER BY d.created_at DESC;
```

A null active version is expected with embeddings disabled or incomplete processing. An extracted document can still be waiting for embeddings. Do not set the active pointer manually. The offline tests prove deterministic processing boundaries; they do not establish live account permissions, remaining allowance, model quality, or semantic search quality. The separate [live embedding smoke check](#embedding-request-telemetry) remains explicitly invoked within its documented budget.

## Search query contracts

The `search` feature defines validated Java input/result types, query embedding, and a synchronous `SearchService` Java API for local pgvector retrieval. The POST search form connects the browser to that service and displays located passages with document names.

`SearchQuery` accepts at most 1,200 Unicode code points, including outer whitespace before trimming; blank text, NUL, and malformed Unicode are rejected. It preserves internal text/case and strips outer whitespace. Result limits range from 1 through 20, defaulting to 5. These are application limits, not a measured quality threshold.

`SearchScope.all()` explicitly selects the library. `SearchScope.selected(Set<UUID>)` accepts up to 100 IDs and keeps an empty set as an empty selection. It never converts that selection into all documents. The input set is copied and cannot change through later caller mutation.

`SearchResult` holds an immutable list of up to 20 distinct passage matches. Each match has stable passage/document IDs, index version, sequence, text, source coordinates, and a finite cosine score in [-1,1]. A score is similarity, not answer confidence. Lines/pages are one-based, columns count Unicode code points from zero, and the range end is exclusive. Optional PDF page numbers preserve physical page identity. Query/result/match diagnostics omit private text and IDs.

Run the focused checks without Docker or credentials:

```sh
./mvnw -Dtest=SearchContractsTest test
```

When `secondbrain.embeddings.enabled=true`, `SearchConfiguration` supplies `QueryEmbedding` with the same `EmbeddingProvider` used by document indexing. Construction performs no inference. Query preparation is independent of event polling and background-indexing enablement. It skips inference for an explicit empty document selection, checks the supplied index model against the provider model, and otherwise requests exactly one vector for the normalized query. It never sends document passages or calls chat.

Provider I/O is refused inside a database transaction. The existing client supplies bounded connection/full-response timeouts and input/response budgets. Query preparation adds no retries, fallback, sleeps, durable jobs, or query storage. An interruption preserves the thread flag and discards a late response. Responses must contain exactly one non-null model-compatible vector; the shared vector type validates dimensions, finite components, and nonzero magnitude.

`SearchFailure` exposes fixed reasons for incompatible models, invalid vectors, provider unavailability, rate limits, daily quota, timeout, and interruption. Optional retry eligibility is a hint for a later user request. Failure messages/printable cause chains omit query/provider details. An empty preparation result means only an explicit empty selection; failures do not masquerade as an empty search result. The retrieval service checks whether a compatible eligible stored index exists before making an inference request. Existing adapter telemetry measures actual provider attempts without private query text; retrieval-level observations come with the later retrieval service.

Check query preparation with fake vectors and errors, without credentials or network calls:

```sh
./mvnw -Dtest=QueryEmbeddingTest,SearchConfigurationTest test
```

These tests use the shared provider boundary; the existing `CloudflareEmbeddingHttpIT` suite remains the transport/timeout contract gate. Passing fake-provider tests does not establish live account/model availability or ranking quality.

For a small Java console check after compilation:

```sh
jshell --execution local --class-path target/classes
```

```java
import dev.secondbrain.search.*;
import java.util.Set;
var selected = SearchScope.selected(Set.of());
new SearchQuery("Study notes", selected);
new SearchQuery(" ", SearchScope.all());
```

The first query prints a safe summary with `SelectedDocuments[count=0]` and limit 5. The blank query throws an `IllegalArgumentException` without echoing its text. Use `/exit` to leave JShell. No provider call or database access is involved. Default `./mvnw verify` remains the completion gate.

## Local passage retrieval

When embeddings are enabled, `SearchService.search(SearchQuery)` checks index availability, prepares one query vector, and ranks eligible stored passages in PostgreSQL. It reuses saved vectors. An explicit empty selection returns an empty result without database/provider work. A nonempty scope with no compatible complete active index raises `SearchFailure` with `NO_COMPATIBLE_INDEX` before inference. A valid search with no sufficiently similar passage returns an empty result; database failures use `DATABASE_UNAVAILABLE` and keep private SQL/vector details out of printable errors.

The read-only projection joins the selected active index to its current extracted source, passages, and vectors. It excludes deleted/queued/processing/failed documents, stale extraction sources, unsupported/unbound model/configuration metadata, and generations with missing vectors/passages or sequence gaps. A compatible old active generation remains eligible while a replacement is staged; desired does not have to equal active. Selected-document filtering happens before ranking/limits.

Ranking uses exact cosine similarity (`1 - cosine distance`). Scores outside [-1,1], including undefined/nonfinite values, are excluded before thresholding and ordering; they are never clamped into a perfect match. Results sort by descending score, then document UUID, passage sequence, and passage UUID for stable ties. The inclusive minimum score defaults to 0.3 and can be configured with `secondbrain.search.minimum-score` in [-1,1], for example `--secondbrain.search.minimum-score=0.65`. This is provisional policy for later evaluation, not a measured quality guarantee. The query limits results to at most 20 (default 5). No approximate-search index or migration is added. See [pgvector's distance documentation](https://github.com/pgvector/pgvector#distances) for the operator convention.

Availability, ranking, and final candidate validation use separate read-only transactions with five-second transaction/query timeouts; no transaction spans provider I/O. The final read uses a fresh snapshot for at most the 20 candidate documents/passages, intersects the original scope, and repeats active/source/model/completeness checks. Deletion or a source/active-index change committed before that final check prevents the affected passage from being returned. Removed candidates are dropped without reranking, refilling, or another provider request, so the result can contain fewer matches or become empty. No final database read is needed for an already empty candidate list.

This is a check-time guarantee: a deletion committed after the final snapshot cannot recall previously selected or delivered text. Source links and later answer generation must perform their own visibility checks. A failed validation read returns a safe database failure instead of serving the earlier candidates. Interruption is checked around the final read. No search query history, durable job, document mutation, or chat request is created.

Run the focused checks:

```sh
./mvnw -Dtest=SearchServiceTest,SearchTelemetryTest,SearchTelemetryEndpointTest,SearchConfigurationTest,TelemetryPrivacyTest test
./mvnw -Dit.test=JdbcPassageSearchIT,SearchWorkflowIT verify
```

The integration cases require Docker and use known vectors in an isolated database. They test ranking, inclusive threshold, stable ties, undefined numerical scores, result bounds, source locations, selected scopes, active replacements, incomplete/hidden/source-changed data, preflight provider avoidance, deletion during inference, and coordinated deletion/source/replacement/completeness changes after ranking but before final validation. No credentials or live API calls are needed. Full `./mvnw verify` remains the gate. The browser search form uses this API and displays excerpts alongside a paginated document picker.

`SearchWorkflowIT` starts the application without an HTTP listener, accepts synthetic TXT/Markdown/PDF documents through the public acceptance service, and lets the real durable workers extract, split, embed, and publish them. Queries then use the actual `SearchService` and PostgreSQL reader. It checks filters, empty/no-index/no-match outcomes, source locations, deletion, restart, and timeout/rate/quota failures. Searching must reuse stored vectors without creating delivery jobs or retrying automatically; telemetry must preserve caller context without private text.

These fixtures use symbolic 1024-dimensional vectors with the BGE-M3 contract, passage configuration 1, indexing generation 1, minimum score 0.3, and default limit 5. They prove application wiring and retrieval mechanics; they do not measure semantic relevance, live provider behavior, or response latency. Existing upload HTTP tests cover multipart/browser behavior separately. Run the command with Docker available; the workflow uses a temporary library and its own container, with no `.env`, model credentials, or external telemetry service.

## Retrieval reference collection

The non-sensitive [reference collection](../src/test/resources/retrieval/reference-v1/collection.json) is `java-study-v1` (manifest schema 1). It contains example notes about [ArrayList](../src/test/resources/retrieval/reference-v1/documents/arraylist-notes.md), [HashSet](../src/test/resources/retrieval/reference-v1/documents/hashset-notes.md), and a [personal Java review plan](../src/test/resources/retrieval/reference-v1/documents/study-plan.txt). The collection facts follow the Java 25 [ArrayList](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ArrayList.html), [List](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html) and [HashSet](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/HashSet.html) contracts; the study plan is an invented personal example.

Four labeled evidence spans and eleven query cases cover direct questions, paraphrases, similar collection types, cross-document evidence, absent evidence, selected-document filters, empty selection and deletion. For example, asking whether duplicate values are allowed should distinguish the ArrayList note from the HashSet note. Another question combines collection behavior with the personal review schedule. These are permanent test inputs, separate from personal uploads and product users.

Each document has a stable logical ID, classpath-relative filename and SHA-256 digest. Each source label points to an exact quote and a one-based inclusive line range. Each case records an explicit all/selected scope, documents to remove for that independent scenario, expected source labels, verbatim expected facts and an expected/absent evidence marker. Cases describe independent scenarios; a deletion in one case is not state carried into the next.

Run the offline integrity checks:

```sh
./mvnw -Dtest=RetrievalReferenceCollectionTest test
```

The test-only loader checks bounded resources, strict UTF-8/LF files, hashes, unique labels, source quotes/ranges, query limits, case coverage, evidence/absence consistency and allowed scope/deletion references. Expected facts must appear in their declared evidence quotes. The fixture format uses immutable values and diagnostics omit queries and quotes. The checks need no Spring context, database, credentials, provider request or chat model.

Logical source labels are gold evidence spans, not preassigned database passage UUIDs. Multiple labels may lie inside one indexed passage. Runtime imports/indexes must record their own ID mapping; scoring passage recall must deduplicate actual passage identities rather than counting aliases twice. This validation does not import/index documents, execute retrieval, compute recall, measure latency/usage or establish that a model understands a paraphrase. Labels still require human review for semantic relevance; exact quote consistency is not a semantic-quality score. No live evaluation command is installed by these fixtures. Full `./mvnw verify` remains the build gate.

## Browser search input

Open `/search` or select **Search documents** in the library. Opening the page reads document choices but performs no inference. With embeddings configured and complete active indexes available, submit a whole-library query to see ranked excerpts, document names and source line ranges. PDF matches also show their physical page number. **Open source passage** resolves the exact passage from its current eligible active index; the source page also links to the full document. Similarity determines ordering; it is not shown as an answer-confidence percentage. Query text goes to the configured embedding provider and consumes the usual allowance. Without enabled embeddings, the page shows **Search unavailable** and requires no credentials.

Choose **Selected documents only** and check documents to restrict retrieval. The picker uses the library's 50-document pages. **Next documents** and **Previous documents** preserve the query, scope, and checked IDs without executing a search, even with a blank query. Selections from other pages stay visible and removable. Choose up to 100 documents; queued/failed/unindexed documents can be selected but cannot contribute results until a complete compatible index exists. A removed selection stays explicit as **Document unavailable** rather than silently becoming a whole-library search. Choosing **Whole library** explicitly searches all eligible documents even if boxes remain checked.

Queries use a form-urlencoded `POST /search`, with one `q` and one explicit `scope` (`all` or `selected`). Repeated/missing/unknown scope/query fields, malformed UUIDs, oversized text/selections and invalid pages/actions receive safe HTTP 400 before retrieval. Malformed form state is cleared with an explanation and defaults to empty selected scope; it never silently broadens to the library. A valid form with blank search text retains its selections and page while asking for a nonblank query. Repeated `documentId` values are deduplicated, with a raw 100-value budget. Optional `page` defaults to 0 and `action` to `search`; `previous`/`next` browse choices only. The server uses the service's default result limit of 5. Valid normalized input and selections remain visible after a search. An empty selected scope stays empty.

Both page responses use `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; the application creates no query session, URL, history or event. Default diagnostics omit queries, filenames and excerpts. These headers do not erase browser/provider history. Document labels are fetched in bounded batches without loading full extracted text. A result whose document becomes hidden before its label lookup is dropped, with the displayed count adjusted. A metadata failure returns safe 503 feedback without partial results; valid query/scope/checked IDs are retained, using generic selected-document labels if the picker could not load. Retrieval metrics still describe the service's final snapshot; a later metadata visibility check can make the displayed count smaller.

With the search service configured, check an empty selection without making an inference request:

```sh
curl -i --data-urlencode 'q=Study notes' --data 'scope=selected' http://127.0.0.1:8080/search
```

Expect `Matching passages: 0`. A blank search can be checked with `curl -i --data 'q=&scope=all' http://127.0.0.1:8080/search`; expect safe HTTP 400. Provider and index states have distinct feedback and HTTP statuses, listed below, without fabricating a zero-match result on failure. For a browser check against your configured provider, search the library, then select a document and repeat; inspect names/excerpts/line or page references and open a document. Browsing picker pages does not initiate a search or query embedding; background indexing can independently increase embedding-request counts. These are live searches only when you submit **Search** against eligible indexes.

Run `./mvnw -Dtest=SearchControllerTest,SearchDisabledControllerTest,DocumentInspectionServiceTest,LibraryControllerTest,TelemetryPrivacyTest test` for the MVC/template and bounded-label API checks. They need no Docker, model credentials, or actual network requests. Run `./mvnw -Dit.test=DocumentRepositoryIT verify` with Docker to check batch label filtering against PostgreSQL, then the full `./mvnw verify`. These tests establish mechanics and privacy, not live semantic relevance.

### Search feedback and recovery

| Situation | HTTP status | What to do |
|---|---|---|
| Empty selected scope | 200 | **No documents selected**: select documents or explicitly choose Whole library; no inference occurs. |
| Successful retrieval with no matches | 200 | **No matching passages**: change the wording or scope. |
| No compatible completed index in scope | 200 | **No searchable documents**: wait for indexing or choose other documents; inference is avoided. |
| Ranked sources disappear before display | 200 | **Search sources changed**: submit a new search for current passages. |
| Blank query or malformed form | 400 | Correct the input. Valid blank-query selections remain; malformed state is cleared explicitly. |
| Provider rate limit | 429 | **Too many requests**: wait before another explicit submission. |
| Shared free allowance exhausted | 429 | **Free allowance exhausted**: wait for the allowance to reset. |
| Provider request timeout | 504 | **Search timed out**: try a new request later. |
| Provider, model/response, storage or interruption failure | 503 | Follow the specific configuration/connection/storage guidance; no old results are served. |

Valid query text, scope and checked IDs remain visible after provider/storage failures. If document labels cannot be read, checked IDs use generic **Selected document** labels instead of losing the selection or displaying old private names. Blank-query feedback marks the input invalid for accessibility. Malformed inputs are not echoed or silently truncated, and the reset scope is selected/empty so retrying cannot accidentally search the whole library.

A provider-supplied retry hint may appear for rate limiting, quota exhaustion or provider unavailability as an ISO UTC timestamp. It is a suggestion, not a guaranteed reset time or remaining account balance. The page does not wait, poll, retry automatically, queue a search, switch providers or request an upgrade. A later explicit **Search** submission is a new request and may consume allowance. HTTP status and application outcome differ: no-index feedback is HTTP 200 while the search metric still records `no_compatible_index`.

For manual review with your configured provider and a disposable indexed note:

1. Search the whole library, then select that document and repeat. Confirm escaped excerpts, source references and retained query/selection.
2. Choose selected scope with no boxes checked and submit a nonblank query. Expect **No documents selected**, with no embedding request.
3. Submit spaces as the query to exercise server validation; expect **Check your query** and retained selections. An actually empty browser field is also guarded by native form validation.
4. Browse picker pages without submitting Search; the search-request count must not increase. Open a source and use the deletion check below to confirm an old link becomes unavailable.
5. Review no-match/no-index and controlled provider-failure responses through the MVC tests. They simulate rate/quota/timeout without live calls; do not deliberately exhaust a real allowance to create an error. A live irrelevant query is not guaranteed to return no matches because relevance depends on the model and provisional threshold.

`./mvnw -Dtest=SearchControllerTest,SearchDisabledControllerTest,DocumentSourceControllerTest,TelemetryPrivacyTest test` renders all controlled feedback through the actual templates without Docker or credentials. The tests require one service call per explicit submission, retained safe state, no result object on failure, safe retry-hint rendering and clean logs. A second explicit submission can recover without stale quota feedback. Full `./mvnw verify` remains the completion gate; these presentation checks do not measure semantic quality.

## Source passage links

A result's **Open source passage** link uses `/documents/{documentId}/passages/{passageId}`. Opening or refreshing it resolves that document/passage pair from PostgreSQL rather than trusting text from the search page, session, URL or browser. The stable passage ID identifies its indexing generation; an old link never substitutes a different passage from a newer generation. The page displays the exact bounded excerpt, escaped filename, line range and physical PDF page if present, with a separate **Open full document** link.

The source must belong to a visible extracted document, its current extraction and a complete active index with supported passage/model metadata and every expected passage/vector. A complete active index remains accessible while its desired replacement is staged. Deleted/physically removed documents, changed extraction sources, inactive/staged/replaced generations, incomplete indexes, unknown IDs and wrong document/passage pairs return HTTP 404 with the same content-free unavailable message. Malformed IDs return safe HTTP 400; read failures return safe HTTP 503 without an excerpt or private error.

Source access makes no inference request and writes no jobs. Saved eligible links also work with embeddings disabled, while the document feature remains enabled. The operation uses an independent read-only READ COMMITTED transaction and five-second transaction/query limits, so it cannot reuse an older snapshot from a caller's transaction. These checks observe changes committed before the statement snapshot; later deletion cannot recall content already delivered. Responses use `Cache-Control: no-store` and `Referrer-Policy: no-referrer`, including errors. Default logs/metrics/traces retain safe outcomes and the fixed route pattern, not filenames, passage text or raw URL IDs.

To check manually, open a search match and verify its excerpt and locations. Copy that source URL, delete a disposable test document through the library UI, and reopen the URL: expect HTTP 404 and no old filename/text. Refreshing the source page performs a new visibility read without using the provider. Automated tests additionally cover replaced generations and changes during an existing database transaction.

Run `./mvnw -Dtest=DocumentSourceControllerTest,DocumentInspectionServiceTest,SearchControllerTest,DocumentDetailControllerTest,TelemetryPrivacyTest,SecondBrainApplicationTest,HealthEndpointsTest test` for MVC/source-value/privacy checks without live credentials. With Docker available, run `./mvnw -Dit.test=DocumentRepositoryIT,SearchWorkflowIT verify` for real eligibility filtering, snapshot isolation and normal processing/restart/deletion composition, then the full `./mvnw verify`. The application/health smoke tests explicitly ignore local `.env` imports and disable embeddings so real provider setup cannot affect them. No live model is used by these checks.

## Retrieval telemetry

The enabled search service uses the shared local registries and optional trace export. Inspect these after an API search:

| Metric | Meaning | Labels |
|---|---|---|
| `secondbrain.search.requests` | Completed search operations, including failures and empty scopes | Fixed `outcome` |
| `secondbrain.search.duration` | Whole operation through final visibility validation, in seconds | Fixed `outcome`; framework `error=none` |
| `secondbrain.search.results` | Distribution of final visible match counts for completed searches | None; unit `passages` |

Outcomes are `success`, `no_results`, `empty_scope`, `no_compatible_index`, `database_unavailable`, `incompatible_model`, `invalid_embedding`, `provider_unavailable`, `rate_limited`, `quota_exhausted`, `timed_out`, `interrupted`, `rejected`, and fallback `unavailable`. `rejected` covers a caller attempting an I/O search inside its transaction. Input validation occurs when constructing `SearchQuery`; failures before calling the service are outside these metrics. Use outcome for errors: no private exception is attached to the observation, so the timer's framework error tag stays `none`.

The result summary records only final returned counts from 0 through 20. Completed empty selections/no-matches contribute zero; failed requests contribute no sample. It therefore does not interpret a provider/database outage as absent evidence. Counters/timers/summary reset per process and are best effort. Metrics, observations, and safe logging fail independently without changing a successful result or an original error.

The `search.retrieval` observation inherits an active caller/request trace without replacing its scope. Provider request observations retain that caller context too; this layer does not open an extra scope around them. A direct API call without a parent can create a standalone span. Safe logs use `operation=search_retrieval`, fixed outcome, and bounded result count on completion, plus any existing caller correlation context. No query, filename, excerpt, vector, selection IDs, raw exception, or result IDs become metric labels or telemetry payloads. The exporter retains the fixed span name/outcome and drops unknown fields/events.

```sh
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.search.requests
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.search.duration?tag=outcome:success'
curl http://127.0.0.1:8080/actuator/metrics/secondbrain.search.results
```

Request counters and the result summary are registered at startup with embeddings enabled; a duration series appears after an observed call. Reading metrics makes no provider request. Startup, opening the search form and browsing picker pages do not execute a search; submitting a valid Search request does. `SearchTelemetryEndpointTest` exercises the actual Actuator responses without a listener, database, or live model.

## Embedding request telemetry

The enabled Cloudflare adapter records each attempted request after local input validation. Invalid input or an already-interrupted caller rejected before transport is not counted as a provider request. An attempt includes receiving and validating the response; it is independent of saving vectors or acknowledging a delivery. A repeated request after a crash counts again.

| Metric | Meaning | Labels |
|---|---|---|
| `secondbrain.embedding.requests` | Completed application request attempts | Fixed `outcome` |
| `secondbrain.embedding.request.duration` | Request/response-validation duration in seconds | Fixed `outcome`; framework `error=none` |
| `secondbrain.embedding.tokens` | Sum of accepted, provider-reported input tokens on successful responses | None |
| `secondbrain.embedding.usage.reports` | Successful responses with accepted usage metadata | None |

Outcomes are `success`, `access_denied`, `model_unavailable`, `invalid_input`, `invalid_response`, `rate_limited`, `quota_exhausted`, `unavailable`, `timed_out`, and `interrupted`. A provider-side invalid-input response is counted; local validation rejection is not. Optional `usage.prompt_tokens` must be an integer between zero and 1,000,000; missing/invalid usage is ignored without rejecting otherwise valid vectors. A response can therefore succeed while leaving both usage counters unchanged. Zero reported tokens still increments the reports counter. These process-local totals are not remaining account allowance, billing totals, or a durable ledger; they reset with the application. Token metadata on failed/invalid responses is not counted.

The child observation is named `embedding.request` and inherits the current delivery/request trace without changing its scope. Logs contain fixed operation/outcome fields plus the existing safe correlation context. Inputs, credentials/account IDs, endpoint URLs, vectors, provider messages, retry timestamps, and raw exceptions do not enter these telemetry fields. Export sanitization retains the fixed operation/outcome and strips private fields/events; export remains optional. Broken counters, observation handlers, or logging backends cannot change inference results or retry classification. Standalone clients built with the original constructor use disabled telemetry; application wiring supplies the shared registries.

After an explicitly enabled request, inspect the local metrics:

```sh
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.embedding.requests'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.embedding.request.duration?tag=outcome:success'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.embedding.tokens'
curl 'http://127.0.0.1:8080/actuator/metrics/secondbrain.embedding.usage.reports'
```

Request counters exist at zero when the adapter is wired; the duration timer appears after an observed request. Deterministic checks need no provider account:

```sh
./mvnw -Dtest=EmbeddingTelemetryTest,EmbeddingTelemetryEndpointTest,CloudflareEmbeddingProviderTest,TelemetryPrivacyTest test
./mvnw -Dit.test=CloudflareEmbeddingHttpIT,DocumentEmbeddingDeliveryIT,DurableEventsIT verify
```

## Explicit live embedding smoke check

This check is separate from routine tests and is not run automatically. Use it only after confirming the account is on Workers Free and the selected BGE-M3 model is free-plan eligible. Keep application embeddings/polling disabled during the check to prevent retained documents from being sent. The account ID/token must already be provided securely in this shell; Spring's imported `.env` does not export shell variables. Do not paste credentials into issue descriptions or verification reports.

Budget: run once, one request, one fixed non-sensitive input, no retries, two-second connect timeout, thirty-second overall timeout, and a 1 MiB response limit. The fixed short text bounds embedding input; the embeddings protocol has no chat-style output-token setting. This uses the documented [Cloudflare compatible endpoint](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/). It does not test retrieval quality or produce application metrics because it bypasses the application adapter.

```sh
printf 'Authorization: Bearer %s\n' "$CLOUDFLARE_API_TOKEN" | curl \
  --silent --show-error --fail-with-body --retry 0 \
  --connect-timeout 2 --max-time 30 --max-filesize 1048576 \
  --request POST \
  --header @- --header 'Content-Type: application/json' \
  --data '{"model":"@cf/baai/bge-m3","input":["Synthetic SecondBrain embedding check."],"encoding_format":"float"}' \
  --output /tmp/secondbrain-embedding-smoke.json \
  "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1/embeddings"
```

Inspect the saved synthetic response locally: require one result with input index zero, exactly 1024 finite components with a nonzero vector norm, and matching model metadata if present. Record date, model/dimensions, success/failure, elapsed time, and reported token usage if present; unknown usage stays unknown. Record remaining allowance only if independently available from the account. Stop on authentication/quota/model failure; no retry, upgrade, model fallback, or real-document experiment is authorized by this check. Live compatibility and quality evidence remain pending until a run is explicitly performed and reviewed.

## Deleted-document cleanup

Select **Delete** beside a document’s status in **Your documents**, or open its detail page and select **Delete document**, then review the confirmation page. **Cancel** returns without changes. Confirming submits a POST request, hides the document immediately, commits a durable cleanup request, and redirects to the library with one-time feedback. Refreshing the library does not resubmit deletion; repeated submissions safely report that the document is already absent. The `documents.delete.v1` worker removes its generated original file outside a database transaction, then removes document metadata, all extracted-text versions, all index generations, passages, vectors, and obsolete processing payloads/deliveries in the lease-checked acknowledgment transaction. Both published indexes and staged replacements belong to the document and are removed through database ownership cascades. Small event keys remain to prevent duplicate publication. Index-generation numbers are independent of extraction/deletion versions; cleanup identifies obsolete processing state by document ownership. A missing original is success; a storage or database failure retains recoverable work and uses the configured event retry budget. Exhausted attempts remain failed and hidden.

| Setting | Default | Purpose |
|---|---|---|
| `secondbrain.documents.deletion.enabled` | `true` | Register the durable cleanup handler |
| `secondbrain.documents.deletion.reconcile-enabled` | `true` | Schedule retained requests that predate the handler |

Reconciliation runs every second in batches of up to 100. It does not duplicate deliveries or reset failed attempts. Disabling the handler still permits deletion requests and preserves their events. `secondbrain.events.polling-enabled=false` pauses execution; `secondbrain.documents.cleanup.enabled` controls the separate abandoned-file scanner. GET `/documents/{id}/delete` displays confirmation without changing state; POST to that URL requests deletion and returns `303 See Other` to `/`. Malformed IDs return 400, unavailable confirmation targets return 404, and database/transaction failures return fixed 503 text without a success message.

Restarting the application with normal settings processes existing deletion requests automatically. Use the document UUID with the [database inspection](#database-inspection) connection: successful cleanup leaves no matching rows in `documents`, `document_extractions`, `document_text_lines`, `document_index_versions`, `document_passages`, or `document_embeddings`, and its `documents.delete.v1` delivery is `COMPLETED` (or `SKIPPED` for already absent work). The generated `.original` file is removed; source files selected for upload remain untouched. Generic event-delivery logs, attempt metrics, and backlog metrics cover cleanup using persisted correlation IDs. The `secondbrain.document.cleanup.deleted` counter belongs to the abandoned-file scanner and does not count this durable handler.

Run `./mvnw -Dit.test=DocumentDeletionIT,DocumentDeletionDeliveryIT verify` for isolated PostgreSQL cleanup, missing-file recovery, retry, and deletion-request checks, including competing delivery claims, active and staged index cleanup, original-removal failure/retry, lease-expiry rollback/restart, retained-key replay, and late extraction/embedding/publication work after deletion. These checks use temporary originals, synthetic vectors, and a container database; they do not call the hosted provider. The full gate remains `./mvnw verify`.

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

Flyway applies pending migrations on startup. V1 enables pgvector, V2 creates the durable event tables, V3 creates document metadata, V4 creates versioned extraction/numbered-text-line tables, and V5 records fixed extraction failure reasons. V6 adds deletion markers, V7 adds optional physical page numbers to stored lines, and V8 permits PDF metadata and failure codes. V9 adds index-generation metadata tied to an extracted-text version, plus an optional desired-version pointer; existing documents start with no selected index. V10 adds private staged passages with stable IDs/source ranges and binds their count/splitter configuration to each index generation. V11 adds private `vector(1024)` embedding rows, composite passage/model ownership, and per-index model binding; it does not generate vectors or activate indexes. V12 adds the optional same-document active-index pointer; existing generations remain staged. Applied versions are recorded in `flyway_schema_history`; subsequent startups validate history and apply only pending migrations.

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
