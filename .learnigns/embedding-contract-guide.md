# Understanding embeddings: contracts, storage, and HTTP

This guide explains how the current embedding code works: its Java types, Spring configuration, PostgreSQL storage, and Cloudflare HTTP adapter. Read it alongside the linked source files.

The model rules, storage adapter, and HTTP adapter are joined by a durable document embedding handler. **Background embeddings are disabled by default. When explicitly enabled, prepared passages are sent to Cloudflare and their vectors are staged locally.** The enabled workflow publishes complete active indexes atomically with its durable acknowledgment. Semantic search and chat are absent from the application workflow.

The diagrams use Mermaid. Open this file in a Markdown viewer with Mermaid support to render them. The tables and text examples explain the same ideas if your viewer displays the diagram source.

## 1. Where embeddings fit in the product

An embedding is a list of numbers produced from text by a trained model. It lets us compare text numerically to find passages with similar meaning. This is different from asking a chat model to write an answer.

Imagine your notes contain:

> Plants convert sunlight into chemical energy.

You search for:

> How do plants get energy from light?

The wording differs. Embedding both texts with the same model can put them near each other in its numerical representation. This is useful evidence for retrieval, but it does not establish that a passage answers every part of a question or that a generated answer is true.

### Current data flow

```mermaid
flowchart TD
    Upload[Upload a document] --> Extract[Extract text locally]
    Extract --> Split[Split text into located passages]
    Split --> PassageStore[Store passages locally]
    PassageStore --> Fact[PassagesPrepared durable fact]
    Fact --> Enabled{Embedding handler enabled?}
    Enabled -->|No| Retain[Retain fact for later processing]
    Enabled -->|Yes| Worker[Claim durable delivery]
    Worker --> Read[Read missing passage batch]
    Read --> Embed[Call Cloudflare outside the read transaction]
    Embed --> Checkpoint[Check lease and document state; save batch]
    Checkpoint --> Read
    Read -->|No missing passages| Finish[Activate complete index, record publication fact, and acknowledge]
```

The handler is opt-in through `secondbrain.embeddings.enabled`. Startup constructs the client without a test request; background workers may then process new and retained document facts. Enabling with polling active permits real passage text to leave the computer. Keeping the default disabled requires no Cloudflare credentials and makes no inference calls.

For semantic search, passage embeddings represent stored text and a query embedding represents the search text. Comparing those vectors would identify related passages. Writing a cited answer would require a separate chat operation. Search and chat are absent from the application workflow.

## 2. What “1024 dimensions” means

Our supported model contract is:

```text
model ID:    @cf/baai/bge-m3
dimensions:  1024
```

“1024 dimensions” means **one text input produces a vector containing 1024 numbers**. It does not mean 1024 words, passages, tokens, or requests.

Here is an invented three-number example to make the shape readable:

| Input | Toy vector |
|---|---|
| A passage about plants using sunlight | `[0.90, 0.30, 0.10]` |
| A question about plants getting energy from light | `[0.88, 0.31, 0.12]` |
| A passage about replacing a bicycle tire | `[0.10, 0.05, 0.95]` |

These numbers are illustrative, not real model outputs or measured retrieval results. The individual positions are learned coordinates; we cannot simply label one position “plants” and another “bicycles.”

In the application, each row would contain 1024 components. Similarity search will compare the direction of vectors using cosine similarity.

**Matching the number of components is necessary but insufficient.** Two different models may both produce 1024 numbers while using different coordinate systems. Indexing and query embedding must use the same compatible model configuration. Changing that configuration requires deliberate reindexing of the stored passages.

## 3. The source files and their separate jobs

Paths below are relative to `src/main/java/dev/secondbrain/`.

| File | Question it answers | Responsibility |
|---|---|---|
| [EmbeddingModel.java](../src/main/java/dev/secondbrain/embeddings/EmbeddingModel.java) | Which numerical representation do we accept? | Validate the supported model ID/dimension pair |
| [EmbeddingVector.java](../src/main/java/dev/secondbrain/embeddings/EmbeddingVector.java) | Is this returned vector safe to use? | Validate and protect numerical components |
| [EmbeddingProvider.java](../src/main/java/dev/secondbrain/embeddings/EmbeddingProvider.java) | What must an embedding service be able to do? | Define the shared Java interface |
| [CloudflareEmbeddingSettings.java](../src/main/java/dev/secondbrain/embeddings/CloudflareEmbeddingSettings.java) | Which account, destination, and limits are configured? | Hold validated settings and protect credentials in diagnostics |
| [EmbeddingConfiguration.java](../src/main/java/dev/secondbrain/config/EmbeddingConfiguration.java) | When and how does Spring create those settings? | Read external values and construct settings and provider beans |

```mermaid
flowchart LR
    Spring[Spring startup] --> Config[EmbeddingConfiguration]
    Config --> Model[EmbeddingModel]
    Config --> Settings[CloudflareEmbeddingSettings]
    Model --> Settings
    Model --> Vector[EmbeddingVector]
    Provider[EmbeddingProvider interface] --> Model
    Provider --> Vector
    Adapter[CloudflareEmbeddingProvider] -->|implements| Provider
    Settings -->|supplies configuration| Adapter
```

The Cloudflare adapter receives those settings when explicitly enabled. Constructing it makes no request; a caller must invoke embed.

This separation lets document indexing and future search use the same embedding interface without putting HTTP code inside document repositories or controllers.

## 4. `EmbeddingModel`: a small Java record with rules

The declaration is:

```java
public record EmbeddingModel(String id, int dimensions) {
    // Constants and validation follow in the actual file.
}
```

A record is a concise way to represent a value. Java generates its accessors, equality, hash code, and a textual representation. Its fields cannot be reassigned after construction.

For example:

```java
var model = new EmbeddingModel("@cf/baai/bge-m3", 1024);
String name = model.id();
int count = model.dimensions();
```

`var` means Java infers the local variable's type from the expression on the right. `model` still has the fixed type `EmbeddingModel`; Java has not made it dynamically typed.

The compact constructor in the real file runs whenever someone calls `new EmbeddingModel(...)`. It rejects anything except the explicitly supported pair. An unsupported value throws `IllegalArgumentException`, so an invalid model object cannot escape the constructor.

The constants use `public static final`:

- `public`: other packages can access them.
- `static`: they belong to the class, so no object is needed to read them.
- `final`: the constant reference/value cannot be reassigned.

Defining this pair does not download a model or contact Cloudflare. It defines our compatibility rules. BGE-M3 is the initial supported candidate; live compatibility and retrieval quality remain unverified.

## 5. `EmbeddingVector`: protect the result at the boundary

A provider response becomes an `EmbeddingVector` only if it satisfies these rules:

| Check | Why it matters |
|---|---|
| The model and component array exist | Required data must not be missing |
| Component count matches the model | A 768-number result cannot enter a 1024-dimensional index |
| Every component is finite | `NaN` and positive/negative infinity cannot be treated as ordinary coordinates |
| At least one component is nonzero | A zero vector has no direction for cosine similarity |

The constructor stores a copy of the input array. The accessor also returns a copy.

```text
Caller's input array --clone--> private stored array --clone--> caller's returned array
```

Why are two copies important? In Java, `final float[] values` prevents replacing the array reference, but it does not prevent changing `values[0]`.

Without copying, a caller could supply a valid array, then change a component to `NaN` after validation. Or a caller could modify the array returned by `values()` and silently change the object's internal state.

The test demonstrates that both paths are protected:

```java
var components = new float[1024];
components[0] = 0.125f;
var vector = new EmbeddingVector(model, components);

components[0] = Float.NaN;
var returned = vector.values();
returned[0] = Float.POSITIVE_INFINITY;

// The vector's internal first component is still 0.125f.
```

The `f` suffix makes a numeric literal a Java `float`. Without it, a decimal literal such as `0.125` is a `double`.

`toString()` deliberately returns only something like `EmbeddingVector[dimensions=1024]`. It does not expose the coordinates. Even a protected textual representation is not a reason to log entire objects by default.

## 6. `EmbeddingProvider`: an interface is a promise

The interface declares:

```java
EmbeddingModel model();
List<EmbeddingVector> embed(List<String> inputs) throws InterruptedException;
```

Read that as:

1. A provider can tell us which model it uses.
2. It can receive a list of texts and return a list of vectors.
3. The vectors must correspond to the texts in the same order.
4. Its work can be interrupted, for example during shutdown.

`List<String>` means a list whose elements are strings. `List<EmbeddingVector>` means a list whose elements are vectors. These are Java generics: the compiler checks the element types.

The interface has no implementation body. `CloudflareEmbeddingProvider` uses `implements EmbeddingProvider` and supplies the HTTP behavior. **Declaring the interface does not execute a request or register a Spring bean.**

In NestJS terms, this resembles an application-facing TypeScript interface plus the idea of a provider implementation. One difference is that Java interfaces exist as runtime types; Spring can inject a registered implementation by its Java interface type. The opt-in configuration registers that implementation as a bean. Merely having an interface on the classpath would not register it.

The interface separates responsibilities; it does not introduce multiple external providers. The selected external service remains Cloudflare.

## 7. How configuration reaches the Java objects

Spring already imports `.env` as a properties file through:

```properties
spring.config.import=optional:file:.env[.properties]
```

The application properties include:

```properties
secondbrain.embeddings.enabled=${SECONDBRAIN_EMBEDDINGS_ENABLED:false}
secondbrain.embeddings.model=${CLOUDFLARE_EMBEDDING_MODEL:}
secondbrain.embeddings.dimensions=${CLOUDFLARE_EMBEDDING_DIMENSIONS:0}
```

For `${NAME:fallback}`, Spring looks up `NAME` in its configuration sources and uses the fallback if it is absent. An empty fallback after `:` means an empty string.

The import makes the uppercase names available even when they are entries in our `.env` properties file rather than exported shell variables. An ordinary `.env` file is not automatically imported by every Java application; our explicit Spring setting enables this behavior.

```mermaid
flowchart TD
    Sources[Environment variables, imported .env, and other Spring property sources] --> Properties[Resolve application.properties placeholders]
    Properties --> Enabled{embeddings.enabled is true?}
    Enabled -->|No: default| Disabled[No embedding settings bean; no credentials required]
    Enabled -->|Yes| Factory[Run the settings factory method]
    Factory --> Parse[Parse dimensions, durations, and batch size]
    Parse --> Validate[Construct and validate model and settings]
    Validate -->|Valid| Bean[Register CloudflareEmbeddingSettings bean]
    Bean --> Client[Construct the provider without making a request]
    Validate -->|Invalid| Failure[Fail startup with a safe configuration message]
```

Malformed numeric/duration input can fail during parsing, before an object constructor runs. This also prevents startup from accepting that configuration.

### The Spring annotations in this file

| Annotation | Meaning here | Approximate NestJS comparison |
|---|---|---|
| `@Configuration` | This class declares Spring-managed object factories | A module's provider configuration |
| `@Bean` | Register the returned object in Spring's application context | A `useFactory` provider |
| `@ConditionalOnProperty` | Load this configuration only when the property has the required value | Conditional/dynamic module configuration |
| `@ConditionalOnMissingBean` | Use the default adapter only when no other provider bean is supplied | An explicitly overridable provider registration |

The main application scans its packages during startup. That is how it discovers `config.EmbeddingConfiguration`; we do not call it from a controller.

Spring supplies `Environment` to the factory method. This is dependency injection: Spring provides an existing dependency rather than requiring the method to construct one. `Environment` gives access to resolved application configuration.

The factory explicitly calls `new EmbeddingModel(...)` and `new CloudflareEmbeddingSettings(...)`. **Only the returned settings object is registered by this `@Bean` method.** Constructing the nested model object does not automatically register a separate model bean. A second `@Bean` method receives the settings and a Clock, constructs the provider, and arranges for its close method to run during application shutdown.

`proxyBeanMethods = false` tells Spring not to create proxies for direct calls between bean factory methods. This configuration does not need that interception.

## 8. Settings, defaults, and limits

| Value | Default | Validation when enabled |
|---|---|---|
| Enabled flag | `false` | Only explicit `true` loads this configuration |
| Cloudflare account ID | Empty | Exactly 32 hexadecimal characters |
| API token | Empty | 1–4096 printable ASCII characters, without spaces/control characters |
| Model | Empty | Exactly `@cf/baai/bge-m3` |
| Dimensions | `0` | Exactly `1024` |
| Connect timeout | `2s` | At least 100 ms, at most 10 seconds |
| Request timeout | `30s` | At least the connect timeout, at most 60 seconds |
| Batch size | `8` | Between 1 and 32 inputs |

The account ID and token identify/authenticate the account; they are unrelated to document IDs and correlation IDs.

Account/token checks validate their format only. They do not verify that the account exists, the token works, permissions are sufficient, or the account uses the free plan.

The default model/dimension values are intentionally invalid for an enabled configuration. Enabling it requires an explicit model choice. Leaving the feature disabled needs no credentials or model choice.

Connect timeout bounds connection establishment, request timeout bounds the request operation, and batching limits how many texts enter one request. **The HTTP adapter enforces these settings when embed is explicitly called. Validating settings or constructing the adapter makes no inference request.** A batch of eight texts means eight vectors, not one average vector and not necessarily eight network requests.

The endpoint method constructs one account-scoped HTTPS destination on `api.cloudflare.com`. It does not send anything. Credentials are excluded from `toString()` and validation errors. The tests check both logs and exception text for synthetic secret sentinels.

## 9. How this connects to our durable events

Passage processing stores the passages and `PassagesPrepared` fact atomically with its delivery acknowledgment. [DocumentEmbeddingHandler](../src/main/java/dev/secondbrain/documents/DocumentEmbeddingHandler.java) consumes that fact when enabled. The event's processing version identifies the index generation, which separately records its source extraction version.

The handler reads one bounded batch of **missing** vectors, closes its read transaction, asks the provider for those passage texts, and associates the ordered results with stable passage IDs. It then asks the dispatcher to save a checkpoint. This is a short write transaction; the next HTTP call begins only after it ends.

```mermaid
sequenceDiagram
    participant H as DocumentEmbeddingHandler
    participant D as Dispatcher checkpoint
    participant DB as PostgreSQL
    participant C as Cloudflare
    H->>DB: Read missing batch; end read transaction
    H->>C: embed(texts)
    C-->>H: Validated ordered vectors
    H->>D: save(batch effect)
    D->>DB: Begin; lock and validate delivery lease
    D->>DB: Lock and recheck desired document/source generation
    D->>DB: Insert immutable vectors
    D->>DB: Recheck unexpired lease; finish write transaction
    D-->>H: Saved outcome, or empty if lease was lost
    Note over H,DB: Delivery stays RUNNING; attempt and deadline are unchanged
```

The checkpoint is supplied by the dispatcher as an [EventHandler.Checkpoint](../src/main/java/dev/secondbrain/events/EventHandler.java). In Java, a nested functional interface describes a value with one operation; here a lambda binds `save` to this specific delivery lease. Existing handlers use the default two-argument `prepare` method, which delegates to their ordinary `prepare(event)`. The embedding handler overrides the two-argument method because its preparation saves bounded progress.

`save` returns `Optional<Outcome>`. Empty means ownership was lost; the handler stops. `SKIPPED` means the document or generation became ineligible; it also stops. A saved `COMPLETED` outcome describes **that batch**, not the whole delivery. The delivery remains running until the final completeness check and acknowledgment succeed together.

If ownership expires during a checkpoint, its writes roll back. Earlier successful checkpoints remain stored. On a retry or restart, the missing-batch query excludes those saved passages, so only unfinished work needs provider calls. The existing attempt limit and overall execution deadline still apply; saving progress does not reset either one.

When no vectors are missing, the final effect locks/rechecks eligibility and completeness, selects the active generation, and records an `IndexPublished` fact. The dispatcher acknowledges the delivery in that same fenced transaction. Failure rolls back activation, the fact, and acknowledgment together; earlier vector checkpoints remain staged. A retry can finalize without repeating completed HTTP work. An active index is prepared for retrieval, but no search endpoint exists yet.

A crash between a provider response and its checkpoint can require repeating the request. Idempotent database writes prevent duplicate stored results; they cannot guarantee exactly-once external processing or allowance consumption.

## 10. Current capabilities and boundaries

| Capability | Current state |
|---|---|
| Explicit model/dimension compatibility rules | Implemented |
| Validated vectors with defensive copies | Implemented |
| Shared provider interface | Implemented with an opt-in Cloudflare adapter |
| Opt-in configuration and safe validation | Implemented |
| HTTP calls and provider response validation | Implemented with bounded inputs, responses, and deadlines |
| Vector storage in PostgreSQL/pgvector | Implemented with resumable bounded checkpoints |
| Durable embedding consumer and batch recovery | Implemented; disabled by default |
| Safe provider request metrics, logs, and child traces | Implemented; export optional |
| Guarded active-version persistence and automatic publication | Implemented with atomic acknowledgment |
| Semantic search and chat | Not implemented |
| Live model/account/quality/free-allowance verification | Not performed |

The model, data, and configuration rules give callers a consistent contract. That contract alone cannot establish live inference compatibility or retrieval quality.

The HTTP adapter uses Java's built-in HTTP client and the existing Jackson JSON library. We have not added Spring AI or a paid OpenAI account. Cloudflare's “OpenAI-compatible” endpoint describes a request/response protocol; Cloudflare is still the service receiving the request.

Our free-only requirement also depends on staying on the correct Cloudflare account plan. Java configuration cannot guarantee an account's billing settings. The provider allowance is finite. Retryable failures delay the existing delivery without holding its worker, and the configured attempt limit remains enforced.

## 11. Understanding and checking the model and configuration

Read the five production files in the order listed in section 3. Then open:

- [EmbeddingVectorTest.java](../src/test/java/dev/secondbrain/embeddings/EmbeddingVectorTest.java) for mutation protection, invalid dimensions, nonfinite components, and zero vectors.
- [EmbeddingConfigurationTest.java](../src/test/java/dev/secondbrain/config/EmbeddingConfigurationTest.java) for Spring startup, opt-in behavior, external-property mapping, and safe invalid-configuration failures.

From the repository root:

```sh
./mvnw -Dtest=EmbeddingConfigurationTest,EmbeddingVectorTest test
```

These configuration/vector checks use synthetic values and make no network request. An enabled configuration constructs an idle client, which closes with the test context. No real account or Docker is required. They check the model and configuration independently. The full project completion check remains:

```sh
./mvnw verify
```

That also requires access to Docker and a local HTTP listener for the existing integration tests. A successful focused run does not replace those checks. You do not need to fill in real Cloudflare credentials to study or test this code. Keep embeddings disabled during ordinary startup.

Questions to answer after reading:

1. Why does changing the model require more than changing `1024` to another number?
2. Why does a `final` array still need defensive copies?
3. Why does an interface alone never call the provider?
4. Which exact object does the `@Bean` method register?
5. Why can configuration validation pass even if an API token has expired?
6. Why could a retry consume allowance twice while storing only one logical result?

## 12. How vector storage is organized

The repository stores supplied vectors in PostgreSQL. The document handler supplies results from the HTTP provider to the storage adapter through delivery checkpoints.

### Storage classes and migration

| File | Responsibility |
|---|---|
| [V11 migration](../src/main/resources/db/migration/V11__persist_versioned_passage_embeddings.sql) | Add vector storage and enforce database relationships |
| [PassageEmbedding.java](../src/main/java/dev/secondbrain/documents/PassageEmbedding.java) | Associate one immutable vector with one stable passage UUID |
| [JdbcEmbeddingRepository.java](../src/main/java/dev/secondbrain/documents/JdbcEmbeddingRepository.java) | Store a bounded batch under document/version and transaction guards |
| [EmbeddingRepositoryIT.java](../src/test/java/dev/secondbrain/documents/EmbeddingRepositoryIT.java) | Exercise actual PostgreSQL constraints, rollback, retries, and concurrency |

The storage classes live in `documents` because passages, index generations, and deletion belong to that feature. The shared numerical model and provider interface stay in `embeddings`. Storage neither calls the provider nor knows its account/token.

`DocumentConfiguration` registers the repository bean. Its constructor receives `JdbcClient`, `JdbcIndexingRepository`, and the transaction manager. All refer to the same application datasource. Constructing this bean performs no SQL or network request. It needs no Cloudflare credentials.

### Three tables, three responsibilities

```mermaid
erDiagram
    DOCUMENT_INDEX_VERSIONS ||--o{ DOCUMENT_PASSAGES : owns
    DOCUMENT_INDEX_VERSIONS ||--o{ DOCUMENT_EMBEDDINGS : binds_model
    DOCUMENT_PASSAGES ||--o| DOCUMENT_EMBEDDINGS : has_staged_vector
    DOCUMENT_INDEX_VERSIONS {
        uuid document_id PK
        bigint indexing_version PK
        bigint extraction_version
        int passage_count
        string embedding_model_id
        int embedding_dimensions
    }
    DOCUMENT_PASSAGES {
        uuid passage_id PK
        uuid document_id FK
        bigint indexing_version FK
        int sequence_number
        string passage_text
    }
    DOCUMENT_EMBEDDINGS {
        uuid passage_id PK,FK
        uuid document_id FK
        bigint indexing_version FK
        string model_id FK
        int dimensions FK
        vector embedding
    }
```

The diagram omits some existing passage/source fields for readability. `vector` here means the actual PostgreSQL column type `vector(1024)`.

- `document_index_versions` identifies a particular rebuild of a document and the extracted-text version it uses. It also records the model/dimensions selected by its first stored batch.
- `document_passages` holds the text and source location for each stable passage ID.
- `document_embeddings` holds at most one vector for each passage ID. It repeats document/index/model identifiers so the database can check their relationship directly.

This repeated metadata is intentional. A valid passage UUID alone must not let code attach the vector to a different document or generation.

A **primary key** prevents duplicate identities. A **foreign key** checks that a referenced owner exists. A **composite foreign key** checks multiple columns together: the passage, document, and index generation must belong to the same row, and the stored model/dimension pair must match the index's binding.

The paired model fields start as `NULL`, meaning “not bound yet.” Both must be absent or both present. Binding a model also requires prepared-passage metadata. `NULL` does not mean failure, success, or a second job status; event deliveries remain our job records.

### What pgvector adds

PostgreSQL stores ordinary relational data already. The pgvector extension adds a numerical vector type and distance operations inside the same database. We do not introduce another database or vector service.

Conceptually, a row contains:

```text
passage_id:       the stable UUID of this passage
indexing_version: 7
model_id:         @cf/baai/bge-m3
dimensions:       1024
embedding:        [number, number, ... 1024 components ...]
```

The `vector(1024)` column enforces the actual number of components. The extension rejects nonfinite values, and our SQL constraint also rejects zero vectors. See the [pinned pgvector reference](https://github.com/pgvector/pgvector/blob/v0.8.7/README.md#vector-type). These database checks complement the earlier Java constructor checks; they also protect the table from invalid direct SQL.

There is a normal index on `(document_id, indexing_version)` for accessing a generation's rows. There is no approximate-nearest-neighbor search index yet, and no search endpoint. The term “indexing version” is our application generation number; it is different from a database index that accelerates queries.

### Why V11 instead of changing V10?

Flyway applies versioned migrations in order and records their checksums. Existing databases may already have V10. Editing an applied migration could make those databases fail validation and would not supply a clean upgrade path.

V11 defines the embedding fields, table, and constraints. Existing documents and passages stay in place; existing generations start with no model binding and no embeddings. This migration does not generate vectors or backfill them through a remote service.

## 13. How one batch is stored

The repository method is:

```java
int insertBatch(IndexingVersion version,
                EmbeddingModel model,
                List<PassageEmbedding> batch)
```

It returns the number of **new rows inserted**, not the total vectors for that document and not a success/failure flag. Zero can mean every supplied vector was already stored identically.

```mermaid
flowchart TD
    Input[Receive a batch] --> Validate[Check size, unique passage IDs, and model agreement]
    Validate --> Tx{Caller transaction exists?}
    Tx -->|No| Refuse[Reject the call]
    Tx -->|Yes| Lock[Lock and recheck the visible desired generation]
    Lock --> Bind[Bind or verify the model on prepared passages]
    Bind --> Insert[Try inserting each vector]
    Insert --> Exists{Passage vector already exists?}
    Exists -->|No| New[Count a new row]
    Exists -->|Yes| Equal{Same owner, model, and exact vector?}
    Equal -->|Yes| Reuse[Keep the existing row]
    Equal -->|No| Conflict[Throw; transaction rolls back]
    New --> Done[Return newly inserted count]
    Reuse --> Done
```

The diagram condenses the loop. Every row in the batch is checked before the method returns. Any invalid ownership or SQL constraint failure also aborts the batch.

### Step A: validate the batch

The method accepts 1–32 items, rejects repeated passage IDs within one call, and requires each vector's model to match the supplied model. The immutable `EmbeddingVector` has already checked its numbers.

Thirty-two is the repository's fixed upper bound. The configurable default batch size of eight controls the HTTP adapter; a document worker must respect it when integration is present. One is the storage ceiling; the other is a chosen request size.

### Step B: join the caller's transaction

`PROPAGATION_MANDATORY` means an existing transaction is required. The adapter cannot quietly finalize its own independent transaction.

For example, if the caller performs:

```text
BEGIN
    store vectors
    perform another required stage write
    something fails
ROLLBACK
```

The vector rows and any newly established model binding roll back too. The dispatcher uses this property to fence checkpoint writes and final acknowledgment by delivery ownership. The repository itself has no knowledge of lease tokens.

Our adapter uses separate parameterized statements for the rows, grouped in one transaction. Each statement has a five-second query timeout; the caller still owns the overall transaction timeout. It does not send a single bulk SQL statement and does not finalize each vector separately. This is bounded by at most 32 vectors per call.

### Step C: prevent stale writes

Before inserting, the adapter locks the document and checks that the requested index is still desired, its source extraction still matches, and the document is still visible/extracted. Starting a newer index, changing the source, marking deletion, or removing the document makes old work ineligible.

That lock coordinates current database state. The dispatcher additionally checks its delivery lease before and after the checkpoint effect. Both fences are required: a document-version check does not establish lease ownership by itself.

### Step D: bind the model

The first successful batch records the model and dimensions on its index generation. Later batches must agree. The model binding and the batch rows are saved atomically; a failed first batch leaves neither behind.

The current Java and SQL contracts support only the explicit BGE-M3/1024 pair. Supporting another pair would require deliberate code/schema changes and a new index generation, not editing a model string on an existing populated generation.

### Step E: handle retries without overwriting

The insert uses:

```sql
ON CONFLICT (passage_id) DO NOTHING
```

That alone would be insufficient: it could silently ignore a conflicting result. When an existing row is encountered, the adapter checks its owner/model and numerical equality.

| Attempt | Result |
|---|---|
| New passage ID with valid ownership | Insert it |
| Same passage ID and identical vector/model/owner | Keep the original; count zero new rows for it |
| Same passage ID with different values | Reject; roll back this call |
| Passage belongs to another document/version | Reject; roll back this call |

Example: suppose the first batch contains passages 1–8 and its transaction succeeds. A retry supplies the same eight results. It inserts zero new rows. A later batch for passages 9–16 can still make progress. The adapter preserves previously persisted batches, while an error inside the current batch rolls that whole batch back.

If a conflicting item appears after a new item in the same call, that earlier new insert also rolls back. Existing persisted rows remain unchanged. Rejecting conflicting results avoids silently mixing numerical snapshots within one generation.

### Step F: let PostgreSQL own the vector representation

The adapter serializes validated floats into a bracketed literal and binds that value as a SQL parameter. The SQL uses `CAST(:embedding AS vector)`; numerical content is not concatenated into executable SQL.

`Float.toString` gives a locale-independent representation, including scientific notation. Database tests read the stored result back as a PostgreSQL `real[]` and compare every component, so the check is independent of simply regenerating the same input literal.

The adapter does not log vector values or provider data. Its repository method does not contact Cloudflare.

### How deletion affects vectors

`ON DELETE CASCADE` follows ownership. Physically removing a passage or its index/document removes dependent vector rows as part of the database operation. An immediate deletion marker still serves a different purpose: it excludes a document before asynchronous physical cleanup finishes.

Stored vectors from old generations are staging/history data, not automatically visible search results. Later retrieval must use the active, complete generation of a visible document.

## 14. Checking storage behavior

Run the isolated database checks with Docker available:

```sh
./mvnw -Dit.test=EmbeddingRepositoryIT,DatabaseMigrationIT verify
```

The storage suite covers multi-batch continuation through fresh JDBC collaborators, exact component round-trip, identical/conflicting retries, rollback and injected SQL failure, concurrent writers, version/document separation, cascade cleanup, stale/deleted work, batch bounds, and direct database constraints. The tests use generated vectors and an isolated PostgreSQL/pgvector Testcontainer. They require no Cloudflare account or real document uploads.

These are integration tests because locking, foreign keys, the pgvector type, and transaction rollback need real PostgreSQL behavior. Fast tests alone cannot establish those properties. The existing complete gate remains `./mvnw verify`.

Verification results belong in the implementation plan, separately from this learning guide.

For a read-only look at staging in your normal database after applying V11:

```sql
SELECT i.document_id, i.indexing_version, i.passage_count,
       i.embedding_model_id, i.embedding_dimensions,
       count(e.passage_id) AS stored_vectors
FROM document_index_versions i
LEFT JOIN document_embeddings e
    ON e.document_id = i.document_id
    AND e.indexing_version = i.indexing_version
GROUP BY i.document_id, i.indexing_version, i.passage_count,
         i.embedding_model_id, i.embedding_dimensions
ORDER BY i.document_id, i.indexing_version;
```

With embeddings disabled, ordinary uploads have zero stored vectors and empty model fields. With the handler enabled, vectors appear as batches finish; incomplete generations remain staging data. Storage tests populate isolated containers, not your normal library database.

## 15. How the HTTP adapter works

The Cloudflare implementation of `EmbeddingProvider` sends a bounded batch when called. The opt-in document handler invokes it outside transactions; disabled embeddings leave uploads at prepared passages.

| File | Job |
|---|---|
| [CloudflareEmbeddingProvider.java](../src/main/java/dev/secondbrain/embeddings/CloudflareEmbeddingProvider.java) | Validate inputs, send HTTP, bound waiting/body size, cancel, and close resources |
| [CloudflareEmbeddingProtocol.java](../src/main/java/dev/secondbrain/embeddings/CloudflareEmbeddingProtocol.java) | Encode/decode bounded JSON and translate HTTP/provider errors |
| [EmbeddingFailure.java](../src/main/java/dev/secondbrain/embeddings/EmbeddingFailure.java) | Expose a fixed error reason, retryable flag, and optional retry timestamp safely |

These are separate from the SQL repository. The HTTP adapter cannot insert vectors or complete an event delivery. The repository cannot make an HTTP request. The document handler coordinates them through the dispatcher's checkpoint boundary.

### What one call does

```mermaid
sequenceDiagram
    participant Caller as Explicit caller
    participant Adapter as CloudflareEmbeddingProvider
    participant HTTP as Java HTTP client
    participant Cloud as Cloudflare
    Caller->>Adapter: embed(one batch of strings)
    Adapter->>Adapter: Reject active transaction or invalid input
    Adapter->>HTTP: POST the encoded batch
    HTTP->>Cloud: Account-scoped HTTPS request
    Note over Adapter,HTTP: Wait for the whole bounded body with a deadline
    Cloud-->>HTTP: Status, headers, bounded JSON body
    HTTP-->>Adapter: Completed response
    Adapter->>Adapter: Validate count, indexes, model and vector components
    Adapter-->>Caller: Immutable ordered vector list, or safe failure
```

The production destination is fixed to the Cloudflare HTTPS endpoint derived from the account ID. A package-private constructor allows a literal loopback address for tests. This is not an application setting for arbitrary hosts. Redirect following is disabled, so a redirect response cannot silently send the next request and its credentials somewhere else.

### One batch is one application attempt

With the default batch size of eight, calling `embed` with eight texts produces one application request that asks for eight vectors. Calling it with nine texts is rejected; this adapter does not silently split a larger collection into more requests.

The document handler owns batching across a document. Durable delivery owns retries. There is no sleep/retry loop inside this adapter and no second provider/model fallback. Ordinary TCP connection handling is not an exactly-once remote-execution guarantee: an interrupted or crashed process may still be unsure whether the remote service processed a request.

The request body has this shape:

```json
{
  "model": "@cf/baai/bge-m3",
  "input": ["A synthetic first passage", "A synthetic second passage"],
  "encoding_format": "float"
}
```

The token travels in the Authorization header, not inside this JSON body. Those passages really would leave the computer if an enabled provider is explicitly called with them. Synthetic tests do not establish live account permissions or model compatibility.

### Why a future is used even though embed waits

`sendAsync` returns a `CompletableFuture`: a handle for a result that will become available later. Our caller then waits on that future with a timeout. The HTTP request also has its configured timeout.

Crucially, the response handler collects a bounded byte array. The future completes after the body has been received, not merely when response headers arrive. A server cannot evade this waiting deadline by sending headers and then holding the body open. A timeout cancels the pending operation.

This does not turn document processing into an unbounded background job system. The caller still waits on its configured worker; Java's HTTP client uses a separate bounded executor for networking tasks (two threads and a 64-task queue). Two networking threads do not mean exactly two requests can be in flight: the durable worker's separate concurrency limits still matter.

`AutoCloseable` gives the adapter a close method. Spring registers it as the bean's shutdown method. It cancels owned HTTP activity and shuts down the networking executor. Tests close adapters with try-with-resources as well.

### Input and response limits

| Boundary | Rule | Reason |
|---|---|---|
| Inputs | 1 through the configured batch size; at most 32 | Bound each operation |
| Each text | Nonblank, valid Unicode, no NUL, at most 1200 code points | Match the bounded passage contract and reject malformed data |
| Encoded request | At most 256 KiB | Bound JSON bytes as well as character count |
| Response stream | At most 1 MiB | A remote service must not make us buffer an unlimited body |
| JSON | Bounded depth, names, strings, number length, and tokens | Bound parsing even for unexpected content |
| Result | Exactly one valid 1024-component vector per input | Prevent partial, duplicate, or mismatched output |

The parser also rejects duplicate JSON fields and extra JSON values after the first document. These limits are application policy, not a measurement of Cloudflare's maximum supported context.

Response entries carry input indexes. If a response presents index 1 before index 0, the adapter reconstructs the original input order. A repeated, missing, fractional, or out-of-range index fails validation. An optional returned model field must match our request; its absence does not prove or disprove a hosted model revision. Live compatibility/quality remains unverified.

### Java interruption is a cancellation signal

During shutdown, a caller may interrupt a thread that is waiting on HTTP. The adapter cancels its pending future, restores the thread's interruption flag, and throws `InterruptedException` to the caller. It does not quietly turn shutdown into a successful embedding.

The adapter also refuses to start inside an active database transaction. The document handler reads a batch, ends its read transaction, calls the provider, then requests a short fenced write checkpoint. Waiting for a network service while holding document locks would unnecessarily block deletion/reindexing.

### Errors become a small, safe vocabulary

| Reason | Retryable classification | Interpretation |
|---|---|---|
| `ACCESS_DENIED` | No | Authentication/account permission problem |
| `MODEL_UNAVAILABLE` | No | Missing/inaccessible model or paid-only requirement |
| `INVALID_INPUT` | No | Our request/input is unacceptable |
| `INVALID_RESPONSE` | No | The returned data does not satisfy our contract |
| `RATE_LIMITED` | Yes | Temporary rate/capacity restriction |
| `QUOTA_EXHAUSTED` | Yes | Documented daily free allowance exhaustion |
| `UNAVAILABLE` | Yes | Provider/transport failure, including an incomplete or oversized stream |
| `TIMED_OUT` | Yes | The operation did not finish within its deadline |

“Retryable” does not mean “repeat immediately or forever.” The adapter classifies the failure and returns a safe hint. The document handler translates that classification into a delivery failure. The dispatcher releases the attempt and persists retry eligibility using the later of normal backoff and the provider hint. Already saved vectors remain staged. Exhausted attempts stay failed even after the hinted time passes.

Cloudflare documents quota code `3036`. We recognize that numeric code in a 429 response and suggest the next UTC daily reset. We do not search human-readable error messages for words such as “quota.” Valid `Retry-After` seconds or HTTP dates become optional timestamps; a quota hint never precedes its suggested reset. The adapter never waits until that timestamp.

A paid-only-model error is a permanent model failure. It does not trigger a plan upgrade or fallback call. The program still cannot inspect or enforce the account's billing plan; using Workers Free remains a setup prerequisite.

### Why exception causes need care

An HTTP or JSON exception may contain a URL, response excerpt, or other private data. Simply wrapping it as the standard cause of a new exception would let a normal printed stack trace expose it.

`EmbeddingFailure` therefore exposes a fixed reason and safe message. Its original diagnostic cause is held privately in a transient field, outside the normal `getCause()` chain and ordinary Java serialization. Its retry timestamp contains no provider message. This is a deliberate privacy boundary, not a general recommendation to erase all exception causes throughout Java code.

The tests print failure stack traces and capture output with synthetic private markers to check this behavior. We do not enable HTTP body/header logging or provider prompt capture.

## 16. What the HTTP tests establish

[CloudflareEmbeddingProviderTest](../src/test/java/dev/secondbrain/embeddings/CloudflareEmbeddingProviderTest.java) exercises protocol rules and guards without a listener or live account. [EmbeddingConfigurationTest](../src/test/java/dev/secondbrain/config/EmbeddingConfigurationTest.java) also proves opt-in provider construction and replacement by a controlled fake without credentials.

[CloudflareEmbeddingHttpIT](../src/test/java/dev/secondbrain/embeddings/CloudflareEmbeddingHttpIT.java) uses an actual server on `127.0.0.1` with synthetic account/token/text. It checks real request serialization and ordering, status/quota errors without automatic retries, redirects, the Unicode batch boundary, oversized streams, connection failure, slow headers and body, and cancellation during a request.

```sh
./mvnw -Dtest=CloudflareEmbeddingProviderTest,EmbeddingConfigurationTest,EmbeddingVectorTest test
./mvnw -Dit.test=CloudflareEmbeddingHttpIT verify
```

The first command needs neither Docker nor a socket listener. The second needs local socket permission but no Docker or Cloudflare account. The full project gate remains `./mvnw verify`.

An HTTP stub proves how our client handles controlled responses. It does not prove that a real token works, that Cloudflare's hosted output matches the fixture, that account usage is free, or that retrieval quality is good. A separately invoked bounded live smoke check is still pending. The document handler and PostgreSQL delivery tests cover orchestration separately from the HTTP protocol.

## 17. Checking durable batching and recovery

[DocumentEmbeddingHandlerTest](../src/test/java/dev/secondbrain/documents/DocumentEmbeddingHandlerTest.java) uses controlled collaborators to check batch ordering, transaction boundaries, missing-result handling, interruption, stale documents, and final completeness. [DocumentEmbeddingConfigurationTest](../src/test/java/dev/secondbrain/documents/DocumentEmbeddingConfigurationTest.java) checks the opt-in boundary without real credentials.

[DocumentEmbeddingDeliveryIT](../src/test/java/dev/secondbrain/events/DocumentEmbeddingDeliveryIT.java) runs the real application/dispatcher against isolated PostgreSQL with a deterministic provider. It exercises multi-batch completion, partial progress across restart, response-after-expiry recovery, source/reindex/deletion changes during inference, acknowledgment rollback, and retained-event scheduling. [DurableEventsIT](../src/test/java/dev/secondbrain/events/DurableEventsIT.java) additionally checks checkpoint rollback, stale tokens, persisted retry hints, and attempt exhaustion.

```sh
./mvnw -Dtest=DocumentEmbeddingHandlerTest,DocumentEmbeddingConfigurationTest test
./mvnw -Dit.test=DocumentEmbeddingDeliveryIT,DurableEventsIT,EmbeddingRepositoryIT verify
```

The second command requires Docker. Both use synthetic data and no live inference. The overall gate is `./mvnw verify`. Test definitions describe what should be checked; current run results are recorded in the implementation plan.

## 18. Understanding embedding telemetry

Telemetry is information about an operation, such as its duration and outcome. It is separate from the operation's saved business data. [EmbeddingTelemetry](../src/main/java/dev/secondbrain/embeddings/EmbeddingTelemetry.java) observes the Cloudflare adapter; it never receives passage text, an HTTP request, an API token, a vector, or an exception.

```mermaid
flowchart LR
    Validate[Validate inputs locally] --> Start[Start request observation]
    Start --> HTTP[Send request and validate response]
    HTTP --> Outcome[Set a fixed outcome]
    Outcome --> Close[Close operation once]
    Close --> Counter[Increment request counter]
    Close --> Timer[Stop duration observation]
    Close --> Usage[Record accepted optional token usage]
    Close --> Log[Log fixed operation and outcome]
```

Only calls reaching the transport boundary count. If the adapter rejects an empty batch before sending, no provider attempt is recorded. If the provider itself rejects a request, its safe outcome is counted. `success` means valid vectors were returned, not that PostgreSQL has saved them or search is ready.

The implementation uses the existing Micrometer registries:

- A **counter** adds up events. `secondbrain.embedding.requests` separates fixed outcomes such as `success`, `quota_exhausted`, and `timed_out`.
- An **observation** describes one operation. The standard meter handler turns its `secondbrain.embedding.request.duration` name into a duration timer; the tracing handler creates an `embedding.request` child span.
- A **label** groups measurements. Outcome values come from a closed Java enum, keeping the number of time series bounded. Document IDs, text, URLs, and arbitrary error messages are not labels.

The observation inherits the current delivery/request trace without opening a replacement scope. The durable event context therefore remains the parent while the HTTP operation has its own span. The trace exporter allows only known operation names and outcomes, and removes private attributes and exception events.

The operation object implements `AutoCloseable`, so try-with-resources records completion even when the HTTP call throws. Its state belongs to that one call, not to the shared singleton telemetry service. Closing twice does not count twice. Telemetry failures are caught at their own boundary; a broken metrics backend cannot turn a valid embedding into a failed delivery or replace the original provider failure.

### Token reports are optional

A successful response may include `usage.prompt_tokens`. The bounded parser accepts a nonnegative integer no larger than 1,000,000. That ceiling is a sanity limit, not a claim about model capacity. Missing, fractional, negative, oversized, or textual values are ignored while valid vectors remain usable.

`secondbrain.embedding.tokens` totals accepted input-token reports; `secondbrain.embedding.usage.reports` counts how many successful responses supplied them. A legitimate report of zero adds zero tokens but one report. No report adds neither. Neither counter estimates remaining daily allowance, records billing, or survives process restart. Failed responses and crashes may consume provider allowance without appearing in these successful-response token totals.

[EmbeddingTelemetryTest](../src/test/java/dev/secondbrain/embeddings/EmbeddingTelemetryTest.java) uses a controllable meter clock and an in-memory tracer to check timing, outcomes, trace parentage, and fault isolation. [EmbeddingTelemetryEndpointTest](../src/test/java/dev/secondbrain/embeddings/EmbeddingTelemetryEndpointTest.java) verifies the actual Actuator response with MockMvc. The loopback HTTP tests cover telemetry through the actual client with synthetic text and credentials.

See [embedding request telemetry](../docs/development.md#embedding-request-telemetry) for metric queries and the separate live smoke-check instructions. Synthetic tests do not establish real account permissions, model quality, or remaining free allowance.

## 19. Desired and active index generations

The `documents` row has two optional pointers:

| Pointer | Meaning |
|---|---|
| `desired_indexing_version` | Which generation the application wants to build |
| `active_indexing_version` | Which generation has been selected after publication checks |

These can differ. Suppose generation 7 is active and generation 8 is being prepared. An incomplete replacement must not erase the old selection. The guard can select generation 8 only after its own passages and vectors are complete. Future retrieval also needs to check document visibility, current extraction, and model compatibility; a pointer alone does not establish those conditions.

The active pointer starts empty. Its composite foreign key references `(document_id, indexing_version)`, so a valid generation belonging to another document is still an invalid target. The foreign key is deferred: it is checked when the transaction finishes, allowing coordinated owner deletion without accepting a dangling target after the transaction succeeds.

[JdbcIndexingRepository.activate](../src/main/java/dev/secondbrain/documents/JdbcIndexingRepository.java) provides the guarded storage operation:

```mermaid
flowchart TD
    Call[Receive generation, model, and timestamp] --> Tx{Caller transaction exists?}
    Tx -->|No| Error[Reject the operation]
    Tx -->|Yes| Current{Visible, extracted, desired, and correct source?}
    Current -->|No| Stale[INELIGIBLE]
    Current -->|Yes| Complete{Supported configuration and complete compatible vectors?}
    Complete -->|No| Partial[INCOMPLETE]
    Complete -->|Yes| Selected{Already the active generation?}
    Selected -->|Yes| Same[ALREADY_ACTIVE; preserve timestamp]
    Selected -->|No| Save[ACTIVATED; select generation and update timestamp]
```

The method locks the document to serialize competing operations. It checks the stored passage count against actual rows, requires sequence numbers from one through that count without gaps, and verifies one correctly owned model/dimension-compatible vector for every passage. Database constraints additionally reject invalid numerical vectors and model/ownership relationships.

The nested `Activation` enum describes this operation's four possible results. It belongs beside its repository rather than in a global enum file. It keeps incomplete work distinct from obsolete/deleted work, and an idempotent repeat distinct from a new state change.

The caller owns the transaction. If another required database operation fails, selecting the active version rolls back too. Two callers targeting the same complete generation serialize through the document lock: one changes the pointer; the other observes an unchanged selection. Neither should count a second publication merely because the operation was called again.

The enabled embedding worker invokes the guarded operation through `DocumentIndexPublication`. The active pointer, `IndexPublished` fact, and delivery acknowledgment share the dispatcher's fenced transaction. Fully staged historical work can also be recovered as described below. There is no search endpoint. The [publication integration tests](../src/test/java/dev/secondbrain/documents/IndexPublicationIT.java) exercise the storage boundary against isolated PostgreSQL using deterministic vectors. See [publication prerequisites](../docs/development.md#index-publication-prerequisites) for verification and the read-only pointer query.

## 20. Atomic publication and completed-work recovery

[DocumentIndexPublication](../src/main/java/dev/secondbrain/documents/DocumentIndexPublication.java) owns the shared publication effect. It calls the guarded activation operation and emits the `IndexPublished` fact with the same operation timestamp. It joins the caller's transaction; the dispatcher owns the lease check and acknowledgment.

```mermaid
sequenceDiagram
    participant Worker as Delivery worker
    participant Store as Event store
    participant Publish as DocumentIndexPublication
    participant DB as PostgreSQL
    Worker->>Store: complete(lease, final effect)
    Store->>DB: Begin; lock and validate lease
    Store->>Publish: Apply final effect
    Publish->>DB: Validate and select the complete active generation
    Publish->>DB: Record one IndexPublished fact and logical key
    Store->>DB: Acknowledge only if lease/deadline remain valid
    Store->>DB: Finish the transaction
    Note over Store,DB: Failure rolls back all three effects together
```

`IndexPublished` is a fact: this generation became active. Its payload contains no text. The event header identifies document/version, correlation, and cause. Normal publication uses `PassagesPrepared` as its cause. Logical identity is document/version/event type, so repeated effects cannot create another publication fact even when a fresh candidate UUID is supplied.

An `ALREADY_ACTIVE` result still lets the application supply a missing fact. The event store returns an existing logical fact instead of overwriting it. That preserves the first fact's identity and context. An `INCOMPLETE` result becomes a permanent safe delivery failure; an `INELIGIBLE` result skips obsolete/deleted work. A lost lease rolls back effects instead of allowing a late worker to publish.

### Why completed staging has its own recovery request

A fully staged generation can have a completed embedding delivery but no publication fact. Restarting that completed delivery would change its history and retry accounting. The recovery path therefore uses a distinct intent, `IndexPublicationRequested`, handled by `documents.publish-index.v1`. The embedding delivery stays as it is. The recovery consumer performs database publication only and never calls the embedding provider.

```mermaid
flowchart TD
    Normal[PassagesPrepared delivery] --> Batches[Save missing embedding batches]
    Batches --> Effect[Shared guarded publication effect]
    Completed[Completed staging with no publication fact] --> Eligible{Eligible for recovery?}
    Eligible -->|Yes| Intent[IndexPublicationRequested durable intent]
    Intent --> Recovery[Publication recovery delivery]
    Recovery --> Effect
    Effect --> Atomic[Select active generation, record fact, acknowledge caller]
    Eligible -->|Pending/running/failed work or existing intent/fact| Leave[Preserve existing work and budget]
```

The candidate query requires current visible source/desired state, supported metadata, full stored counts, and either a completed embedding delivery or an aged-out passage payload. Pending, running, failed, or skipped embedding work is excluded. An existing recovery/publication key excludes another intent. These rules keep recovery from bypassing another job's finite attempt budget.

Reconciliation reads at most 100 candidates, then locks/rechecks each document and recovery eligibility in a bounded transaction before recording its intent. A recovery request itself has the normal finite delivery budget. Its consumer rechecks the original passage event identity and completed embedding state before activation, so a retained intent cannot bypass work that is now unfinished or failed. If the recovery fails, the next scan does not create a replacement request with a fresh budget.

With a retained passage payload, the intent keeps its correlation/trace and uses the passage event ID as cause. If payload retention has removed that context, the logical passage key still supplies provenance; the application creates a new correlation root rather than pretending it knows the lost trace. The publication fact then names the recovery request as its cause.

The [publication unit checks](../src/test/java/dev/secondbrain/documents/DocumentIndexPublicationTest.java) cover outcome/identity/context and caller transaction boundaries. The real PostgreSQL delivery tests additionally cover rollback after event insertion or transaction failure, acknowledgment failure, lease expiry, competing deliveries, and recovery across restart without another model call. Test execution results belong in the implementation plan, separately from this current-code guide.

## 21. Deleting a document and its indexes

The document is the owner of its saved content. When you confirm deletion, the application hides it immediately and records a durable cleanup request. The worker then removes the stored copy of the original and deletes the database owner. Foreign keys with `ON DELETE CASCADE` remove the dependent rows automatically. Your source file is untouched.

```mermaid
flowchart TD
    Confirm[Confirm deletion] --> Hidden[Hide document and save durable request]
    Hidden --> File[Worker removes generated original]
    File --> Tx[Lease-fenced database transaction]
    Tx --> Events[Remove other document event payloads and deliveries]
    Events --> Owner[Delete document owner]
    Owner --> Text[Extracted text and numbered lines]
    Text --> Index[All index generations: active and staged]
    Index --> Passages[Passages]
    Passages --> Vectors[Embedding vectors]
    Tx --> Keys[Retain compact event keys for duplicate protection]
```

### Two kinds of version number

Extraction/deletion processing versions and index generations count different things. Extraction version **1** might have active index generation **7** and staged replacement **8**. Deletion advances the document processing version to **2**. It still removes both indexes. Comparing 7 or 8 with 2 would be incorrect; cleanup follows document ownership and removes that document's other event payloads regardless of their generation.

### Files and database transactions behave differently

Removing a file cannot roll back with PostgreSQL. If the worker removes the original but loses its lease before database finalization finishes, the database changes roll back. The hidden document and its derived rows remain recoverable. A later attempt accepts the missing original and retries database cleanup. If file removal itself fails, database content stays hidden and the delivery uses its bounded retry policy.

Late workers meet two checks. While the hidden owner remains, visibility/source/generation guards reject old embedding writes and publication. After physical cleanup, the owner no longer exists and old delivery leases have been removed. Even a prepared effect cannot restore the content. Compact event keys retain only logical identity, so replaying an old event cannot recreate its removed payload or start another delivery. They contain no document text or vectors.

The [deletion delivery tests](../src/test/java/dev/secondbrain/events/DocumentDeletionDeliveryIT.java) exercise these boundaries with real database ownership rules and deterministic vectors. The [development guide](../docs/development.md#deleted-document-cleanup) documents the UI and focused verification command.

## 22. Ready data versus processing attempts

A provider response, saved vector batch, and ready index are different milestones. Readiness belongs to the committed active-version relationship. `secondbrain.index.active` counts eligible documents with that relationship. It reads PostgreSQL rather than increasing a counter when a worker runs, so a retry cannot count the same document twice. A fresh application process can recover the count from the same database.

```mermaid
flowchart TD
    Batch[Save vector batches] --> Staged[Staged generation]
    Staged --> Guard[Validate complete current generation]
    Guard --> Atomic[Select active generation and record publication fact]
    Atomic --> Finish{Transaction finishes}
    Finish -->|Rollback or lost lease| NoSample[No duration sample; no new active state]
    Finish -->|Commit| Active[Active pointer visible to other transactions]
    Active --> Gauge[Cached database count]
    Active --> Sample[Best-effort upload-to-ready timer sample]
```

`secondbrain.index.ready.duration` measures elapsed time from original upload acceptance to a newly committed activation. It includes waiting and retries. An already active repeat adds no sample; activating a replacement adds one. That differs from the provider request timer, which measures an individual inference attempt.

The timer uses Spring's `TransactionSynchronization.afterCommit` callback. Recording it inside the publication transaction would produce misleading samples if the final acknowledgment or lease check later rolled back. The callback reads in its own short transaction because Spring can still have the completed transaction's connection bound during the callback. The gauge also uses an independent read transaction so it cannot see another caller's uncommitted pointer.

The count and `secondbrain.index.active.available` share a five-second cache. A failed database read produces an unknown count (`NaN`) and availability 0, rather than a false zero. This cache can briefly show the previous count after publication or deletion. A compatible old active index stays counted while a replacement is staged; hidden documents and obsolete extraction sources do not count.

Duration samples are local diagnostics. They reset on restart, and a crash after database success can lose one. Telemetry failure never changes publication success. The database remains the authority for ready data. These index metrics are separate from the application's HTTP health readiness probe and contain no document names, IDs, or text in their labels.

See [IndexReadinessMetrics](../src/main/java/dev/secondbrain/documents/IndexReadinessMetrics.java) for the collector and callback, and [metric usage](../docs/development.md#index-readiness-telemetry) for the read-only curl queries.

## 23. Following a document through the complete pipeline

The upload request saves the original, metadata, and `DocumentAccepted` request together, then returns. It does not wait for the embedding model. A separate automatic worker claims deliveries from PostgreSQL and invokes the registered handlers.

```mermaid
sequenceDiagram
    participant Browser
    participant App as Upload endpoint
    participant DB as PostgreSQL
    participant Worker as Background worker
    participant Model as Embedding provider
    Browser->>App: Upload a document
    App->>DB: Save metadata and durable accepted event
    App-->>Browser: 202 Accepted, QUEUED
    Worker->>DB: Claim accepted delivery
    Worker->>DB: Save extracted text and TextExtracted
    Worker->>DB: Select generation and ReindexRequested
    Worker->>DB: Save passages and PassagesPrepared
    loop Missing batches only
        Worker->>Model: Request bounded embeddings outside transaction
        Model-->>Worker: Vectors
        Worker->>DB: Save batch under delivery/document fences
    end
    Worker->>DB: Activate complete generation, record IndexPublished, acknowledge atomically
```

Extraction version and index generation identify different stages. A document can show `EXTRACTED` while its embedding work is still pending. Only its complete committed active generation is ready for future retrieval. No semantic-search endpoint exists yet.

Every handoff retains a correlation ID identifying the overall operation and a causation ID identifying the immediately preceding event. Each delivery attempt has its own trace span linked to its producer's persisted trace context. This lets you follow the chain without storing document text in diagnostics. Retries are additional attempts, not additional ready documents.

If a batch fails with a quota delay, already stored batches remain. Restarting does not erase eligibility time or attempt count. Once eligible, the worker requests only missing batches. A crash after a model response but before saving can still repeat that remote request. Deletion hides the owner immediately; a delayed response cannot restore it, and cleanup removes its original and derived data.

[DocumentIndexingWorkflowIT](../src/test/java/dev/secondbrain/events/DocumentIndexingWorkflowIT.java) uses the actual HTTP endpoint and automatic worker with an isolated database and fake embeddings. Its inference gate proves that upload returns before model work finishes. Other checks follow restart and deletion races. A fake vector verifies storage/control flow, not the quality of semantic meaning; that requires a separate retrieval evaluation after search exists.

## 24. Representing a search before executing it

[SearchQuery](../src/main/java/dev/secondbrain/search/SearchQuery.java) is a Java record carrying query text, scope, and result limit. Its constructor checks the limits and valid Unicode before any provider or database work. This protects every future caller, including a web controller or answering service. The two-argument constructor supplies the default limit of five; the full constructor permits one through twenty.

[SearchScope](../src/main/java/dev/secondbrain/search/SearchScope.java) is a `sealed interface`: Java permits exactly the two declared implementations. That expresses two different intentions without a boolean flag or ambiguous null value:

| Value | Meaning |
|---|---|
| `SearchScope.all()` | Search the eligible library |
| `SearchScope.selected(Set.of(id))` | Search only the selected ID |
| `SearchScope.selected(Set.of())` | No documents were selected; this is still not all documents |

The selected scope copies its set. Clearing the caller's original set cannot silently change the request. The collection returned from the record cannot be modified either. Records make their fields final; defensive copies make these collection contents safe to share.

[SearchResult](../src/main/java/dev/secondbrain/search/SearchResult.java) is a purpose-specific read value. It carries located passage matches, not document persistence objects or raw provider responses. A match has stable IDs, an index version, text, source coordinates, and a similarity score. Locations use the same conventions as stored passages, while remaining a separate public search contract. A higher cosine score means closer vector direction; it is not a probability that an answer is correct.

Records normally generate a `toString()` containing all fields. These text-bearing records override it with summaries so casually printing a request/result does not reveal queries, passages, or document IDs. Validation messages also omit private input.

These types validate and carry data. QueryEmbedding uses them for query preparation and SearchService passes the resulting vector to the local SQL reader described below. [SearchContractsTest](../src/test/java/dev/secondbrain/search/SearchContractsTest.java) checks Unicode/result bounds, explicit empty scope, immutable collections, valid coordinates/scores, and safe summaries. [The development guide](../docs/development.md#search-query-contracts) includes a JShell example for exploring them.

## 25. Turning one query into a compatible vector

[QueryEmbedding](../src/main/java/dev/secondbrain/search/QueryEmbedding.java) is the search feature's internal provider operation. Constructor injection supplies the same `EmbeddingProvider` that background indexing uses. It keeps the provider and its model in final fields; each query's state stays in local variables. [SearchConfiguration](../src/main/java/dev/secondbrain/search/SearchConfiguration.java) enables this operation with embeddings, without making any startup request.

```mermaid
flowchart TD
    Query[Validated SearchQuery] --> Empty{Explicit empty selection?}
    Empty -->|Yes| Skip[Skip inference]
    Empty -->|No| Match{Provider model matches index model?}
    Match -->|No| Error[Safe typed failure]
    Match -->|Yes| Guard[Check transaction and interruption]
    Guard --> Call[One provider request containing only query text]
    Call --> Response{Exactly one compatible vector?}
    Response -->|Yes| Vector[Vector for later SQL retrieval]
    Response -->|No or provider failure| Error
```

A model mismatch stops before sending the query. The query vector must have the same model and dimensions as the stored passage vectors, otherwise comparing them would have no valid meaning. The shared `EmbeddingVector` already checks dimensions, finite values, and a nonzero vector; query preparation checks response count and model association as well.

Only the question/query needs a new vector. The stored document vectors are reused by the later SQL step. This operation neither re-embeds documents nor asks a chat model for an answer. It prepares the query vector; SearchService owns the index-availability preflight and the SQL retrieval step described below.

`Optional.empty()` has one meaning here: the caller explicitly selected no documents. A provider failure throws [SearchFailure](../src/main/java/dev/secondbrain/search/SearchFailure.java) instead, with a small nested `Reason` enum. That keeps “nothing selected,” “no matching evidence,” and “the provider could not answer” from becoming the same state. Timeout, rate limit, quota, and interruption remain distinguishable. A retry timestamp is information for a later request, not an instruction to block until tomorrow or create a background job.

A blocking operation can clear the Java thread's interrupt flag when it throws `InterruptedException`. The catch restores it with `Thread.currentThread().interrupt()` before returning a safe failure. The operation also checks interruption before and after the provider call, so it cannot accept a response after cancellation. Provider calls inside an active database transaction are rejected before network work.

The original diagnostic cause is kept privately for debugging but excluded from the normal exception cause chain. Printing a failure therefore does not accidentally expose an HTTP response, token, or query. Request observations remain in the existing provider adapter; the preparation layer adds no duplicate log or retry loop.

[QueryEmbeddingTest](../src/test/java/dev/secondbrain/search/QueryEmbeddingTest.java) exercises one-query calls, model/response validation, empty-selection avoidance, failure mapping and cancellation with a fake provider. Configuration tests prove startup makes no inference request. [The development guide](../docs/development.md#search-query-contracts) lists the focused verification command.

## 26. Finding passages in PostgreSQL

[SearchService](../src/main/java/dev/secondbrain/search/SearchService.java) connects the query input, shared provider, and local passage reader. It uses no chat model. An empty selected scope returns immediately. Otherwise it first asks whether a compatible complete active index exists in that scope, avoiding a provider request when there is nothing eligible to search.

```mermaid
flowchart TD
    Query[SearchQuery] --> Empty{Empty selected scope?}
    Empty -->|Yes| Skip[Empty result; no database or provider call]
    Empty -->|No| Preflight[Read compatible active-index availability]
    Preflight -->|None| Missing[NO_COMPATIBLE_INDEX]
    Preflight -->|Available| Embed[Embed only the query outside a transaction]
    Embed --> Read[Read eligible stored passages and vectors again]
    Read --> Rank[Exact cosine scores, threshold, stable ordering, limit]
    Rank --> Result[Immutable located matches, possibly empty]
```

[JdbcPassageSearch](../src/main/java/dev/secondbrain/search/JdbcPassageSearch.java) owns an approved read-only projection over document tables. It neither imports private document repositories nor writes document state. The source document must be visible and extracted; its active generation must match the current source, supported model, and passage configuration. Counts and contiguous passage sequences must agree, with a vector for every passage. The selected-document filter is part of this eligibility step, before the result limit.

The reader uses the active generation, not simply the newest or desired generation. A complete active version can stay useful while its replacement is still being prepared. Staged replacements, old extraction sources, missing vectors, and deleted documents cannot contribute matches.

The vector operator `<=>` measures cosine distance. Similarity is `1 - distance`; higher scores rank first. The reader accepts only scores in [-1,1], excludes undefined/nonfinite numerical results, uses an inclusive minimum score (initially 0.3), and applies the requested bounded limit. Ties use document ID, passage sequence, and passage ID. The threshold is configurable and still needs evaluation against real semantic queries. There is no approximate vector index yet.

Availability and ranking each have their own short read-only transaction. They close before/after provider I/O rather than holding a database connection across the remote call. The second read observes deletion or a source/version change during inference. After ranking, the service performs the fresh candidate visibility check described below before returning matches.

No compatible index, no matches above threshold, and a database/provider failure have different outcomes. That distinction lets future UI code show useful feedback without treating an unavailable service as absent evidence. [SearchProperties](../src/main/java/dev/secondbrain/search/SearchProperties.java) validates the threshold; [SearchFailure](../src/main/java/dev/secondbrain/search/SearchFailure.java) exposes safe failure reasons without SQL/query/vector details.

[JdbcPassageSearchIT](../src/test/java/dev/secondbrain/search/JdbcPassageSearchIT.java) uses known vectors in real PostgreSQL/pgvector to check mechanics and filtering. Those synthetic vectors prove ordering rules, not the quality of semantic search with a real embedding model. [The development guide](../docs/development.md#local-passage-retrieval) lists the focused checks.

## 27. Checking again just before returning evidence

A passage can be valid when ranked and become invalid before the service returns it. [SearchService](../src/main/java/dev/secondbrain/search/SearchService.java) therefore asks `JdbcPassageSearch.retainVisible` to validate the bounded candidate list against a fresh committed-state snapshot.

```mermaid
sequenceDiagram
    participant Search as SearchService
    participant DB as PostgreSQL
    participant Delete as Concurrent change
    Search->>DB: Rank eligible passages
    DB-->>Search: Candidate matches
    Delete->>DB: Hide/delete document or change source/active version
    DB-->>Delete: Change committed
    Search->>DB: Recheck candidate IDs and original scope in a fresh read
    DB-->>Search: IDs still eligible
    Search->>Search: Keep eligible candidates in their original order
```

The final read checks visibility, source, active generation, model, and completeness again. It is limited to the candidate documents and passage IDs. It also intersects their documents with the original selected scope. It reads identifiers rather than fetching another copy of the text.

The service preserves the remaining scores/order and may return fewer results. It does not refill the list or make another model call. If validation fails, it reports a database failure rather than returning unvalidated private text. A deletion committed before the final snapshot is observed; one committed afterward cannot retroactively recall text. Opening a source or generating a later answer therefore needs another visibility check at that later boundary.

## 28. Observing retrieval without recording its contents

[SearchTelemetry](../src/main/java/dev/secondbrain/search/SearchTelemetry.java) measures one service operation, including the final check. It receives only a fixed outcome and a bounded match count. It never receives `SearchQuery`, passage text, document selections, or a raw exception.

The request counter answers “how many searches ended this way?” The timer answers “how long did the whole operation take?” The unlabelled result distribution answers “how many visible matches did completed searches return?” Only the final list contributes counts: a filtered result of one match records one even if ranking originally found two. Successful empty selections/no-matches record zero; a failure records no result sample.

An `Operation` is a per-call object used with try-with-resources. It stores that request's outcome, and `close()` records measurements once. Singleton telemetry holds only shared registries. Recording failures are isolated so a broken metric, observation handler, exporter, or logger cannot replace a correct search outcome. Default diagnostics use safe fixed names; raw exceptions are not attached to tracing.

The observation inherits the caller's current trace without changing its thread-local scope. Search and provider observations remain correlated under that caller. Metric labels contain only fixed outcomes and the framework's safe error tag; result counts are values rather than document-specific labels. Logs may include existing safe caller correlation context, but no query/excerpt/vector or result IDs.

[SearchTelemetryTest](../src/test/java/dev/secondbrain/search/SearchTelemetryTest.java) checks timing/counts, fixed failures, caller context and broken telemetry. The [Actuator test](../src/test/java/dev/secondbrain/search/SearchTelemetryEndpointTest.java) checks real metric responses. Coordinated PostgreSQL tests pause after ranking and commit a change before final validation. [The development guide](../docs/development.md#retrieval-telemetry) lists metric queries and focused verification.

## 29. Following a document into a search

A document goes through the background pipeline once per indexing generation. A search reads its published passages and vectors; only the query needs a new vector.

```mermaid
flowchart TD
    Upload[Accept TXT, Markdown or PDF] --> Extract[Extract located text]
    Extract --> Split[Prepare stable passages]
    Split --> Embed[Embed passages in background]
    Embed --> Publish[Publish complete active index in PostgreSQL]
    Query[SearchQuery plus document scope] --> Check[Check for an eligible active index]
    Publish --> Check
    Check --> QueryVector[Embed only this query]
    QueryVector --> Rank[Compare with saved passage vectors]
    Publish --> Rank
    Rank --> Validate[Recheck candidate visibility]
    Validate --> Matches[Located matches or no matches]
```

[SearchWorkflowIT](../src/test/java/dev/secondbrain/search/SearchWorkflowIT.java) follows this complete path through the application's services and real workers. The test provider assigns ALPHA passages one direction and BETA passages another. A matching query points in the same direction; an unrelated query points away. Because those directions are chosen by the test, expected matches are predictable. This verifies the connections between components, not whether a real model understands language.

The workflow snapshots saved vectors and delivery records before searching and checks that querying leaves them unchanged. After an application restart, the same passage IDs, locations and scores remain available with one new query embedding. A timeout, rate limit or exhausted quota is a failure rather than evidence that no passage exists. The application makes no hidden retry: a later explicit request may try again.

An empty selection costs no inference. A scope with no compatible index also avoids inference but reports that distinct problem. Deleting a document immediately excludes it; cleanup can continue in the background. A successful search can still return no matches above the threshold. These different outcomes give the web layer enough information to explain what happened.

## 30. Connecting a browser form to the search service

[SearchController](../src/main/java/dev/secondbrain/web/SearchController.java) is the HTTP adapter. Spring calls its `form()` method for `GET /search` and `submit()` for a form-urlencoded `POST /search`. Opening a page reads a bounded set of document choices and renders the form without inference. Submitting Search creates a validated `SearchQuery` and calls the existing service once; browsing picker pages does not call it.

```mermaid
sequenceDiagram
    participant Browser
    participant Controller as SearchController
    participant Service as SearchService
    participant View as Thymeleaf
    Browser->>Controller: GET /search
    Controller->>View: Blank form model
    View-->>Browser: Search form
    Browser->>Controller: POST q, scope and optional documentId values
    Controller->>Controller: Validate HTTP fields and construct SearchQuery
    Controller->>Service: search(query)
    Service-->>Controller: SearchResult or typed SearchFailure
    Controller->>View: Input, scope and named excerpts or safe feedback
    View-->>Browser: HTML response
```

`@RequestParam MultiValueMap<String, String>` preserves every value for each form field. That lets the adapter reject two different `scope` values instead of silently choosing one. It maps UUID strings into an immutable application scope; `SearchQuery` still owns text/result limits, and the service still owns eligibility, inference, ranking and visibility. The controller contains no SQL or provider calls.

An `ObjectProvider<SearchService>` is a Spring handle for an optional bean. Embeddings are disabled by default, so there may be no search service to inject. `getIfAvailable()` lets the page explain that state instead of preventing default application startup. It does not create a second service or inference provider.

The [template](../src/main/resources/templates/search.html) uses escaped Thymeleaf values to redisplay valid input. Query text travels in a POST body rather than the form action URL. No query session/history is created, and cache/referrer headers restrict unintended browser reuse. Default diagnostics contain fixed HTTP/search outcomes, not form text. Those choices cannot control what a user deliberately saves or what the configured provider receives.

The page displays a match count plus escaped filenames, excerpts, line ranges and physical PDF page numbers. The picker supports explicit whole-library or selected-document scope. An empty selection remains empty, and provider failures do not become zero matches. MVC tests replace search and document-reading services, checking the adapter separately from the real PostgreSQL workflow tests.

## 31. Keeping form state separate from an actual search

[SearchForm](../src/main/java/dev/secondbrain/web/SearchForm.java) represents a browser interaction: text, scope, selected IDs, document page and action. It is an immutable record with copied selections and safe diagnostics. Its text may be blank because browsing documents does not require a query. `SearchQuery` is stricter: it represents a real retrieval request and rejects blank text. That distinction keeps paging from making inference calls or weakening the search contract.

```mermaid
flowchart TD
    Form[POST: bounded SearchForm] --> Action{Which action?}
    Action -->|Previous or next| Choices[Read 50 document choices and selected labels]
    Choices --> Render[Render form; no query embedding]
    Action -->|Search| Query[Construct SearchQuery]
    Query --> Retrieval[SearchService: one interactive retrieval]
    Retrieval --> Names[Batch visible document-name lookup]
    Names --> Results[Escaped named excerpts and source locations]
```

Native form controls carry state between requests. Each document page submits its checked boxes alongside selected documents from other pages. Paging buttons use `formnovalidate` so a blank query can remain blank. No browser framework, JavaScript state store, or server query session is needed. The explicit scope decides retrieval: checked boxes are remembered in whole-library mode, but constrain the query only in selected-document mode. Empty selected mode never means all documents.

[DocumentInspectionService.names](../src/main/java/dev/secondbrain/documents/DocumentInspectionService.java) returns an immutable map of visible IDs to filenames, capped at 100 IDs. The repository selects only those labels and excludes tombstoned rows. It does not load original files, extracted text, progress or vectors. Picker labels and result labels are separate bounded reads; the web controller never accesses SQL itself.

After retrieval, the controller joins result IDs to visible labels and preserves ranking. If a document was hidden before this label read, its excerpt is omitted; a failed lookup produces safe feedback rather than partial evidence. This is another snapshot check, not a lock spanning delivery. Small view records combine a name with a match and redact their diagnostics. Thymeleaf escapes all text; markup in a document remains reference text, not executable HTML.

The page shows source line ranges and physical PDF pages. Each result links to its exact source passage using document and passage IDs. That route rechecks the current active generation and document visibility before displaying text, as described below. The separate full-document link opens the current document detail page.

## 32. Reopening an exact passage safely

A search result is a snapshot, not permanent permission to read its text. A document may be deleted or replaced after a search. [DocumentSourceController](../src/main/java/dev/secondbrain/web/DocumentSourceController.java) therefore takes only document/passage IDs from the link and asks `DocumentInspectionService.source` to resolve them again. The URL supplies no filename or excerpt that could be trusted accidentally.

```mermaid
sequenceDiagram
    participant Browser
    participant Web as DocumentSourceController
    participant Documents as DocumentInspectionService
    participant DB as PostgreSQL
    Browser->>Web: Open document ID / passage ID
    Web->>Web: Validate both UUID strings
    Web->>Documents: source(documentId, passageId)
    Documents->>DB: Fresh bounded read-only transaction
    DB->>DB: Require visible owner, current source, complete active generation
    alt Exact passage is eligible
        DB-->>Documents: Immutable located source
        Documents-->>Web: PassageSource
        Web-->>Browser: Escaped source page
    else Missing, deleted, replaced or incomplete
        DB-->>Documents: No source
        Web-->>Browser: 404; no old text
    end
```

The stored passage ID includes the document, indexing generation and sequence in its stable identity. A different generation has different passage IDs. The lookup requires that exact stored passage to belong to the document's active generation, whose extraction version must still be current. It repeats passage/vector completeness and supported model/configuration checks. An old active generation can remain useful while a replacement is only staged; once replaced, its links stop resolving.

`REQUIRES_NEW` suspends a caller's transaction and opens a fresh one. `READ_COMMITTED` observes the state committed before this SQL statement. This matters if a caller already holds a repeatable-read snapshot from before deletion: ordinary reads in that old transaction can still see old data. The public source operation uses its own read, so it cannot accidentally serve that old snapshot. Read-only policy and five-second limits keep the operation short; no lock spans HTML delivery.

The public `PassageSource` record contains only the filename, bounded passage, source coordinates and identity needed by the view. It is separate from a ranked search match and contains no score/vector/provider response. Required fields, text budget, Unicode and source coordinates are validated; diagnostics omit text, names and IDs. A source page needs no model call and can open an eligible saved link even when embeddings are disabled.

The controller maps absent/stale sources to the same 404, malformed links to 400 and read failures to 503. Error pages contain no old content. The [template](../src/main/resources/templates/document-source.html) escapes all text, and responses disable caching/referrer forwarding. These measures cannot recall content already delivered before a later deletion; they ensure each new request checks again. MVC tests verify rendering and safe responses; PostgreSQL tests check actual ownership, completeness, replacement/deletion and the old-snapshot case.

## 33. Explaining search outcomes without changing retrieval

[SearchFeedback](../src/main/java/dev/secondbrain/web/SearchFeedback.java) is a closed enum of web messages and HTTP statuses. It maps trusted `SearchFailure.Reason` values into useful next actions. Its strings contain no provider body, exception message, filename, query or excerpt. It is presentation policy; `SearchService` still decides eligibility, model calls, ranking and final visibility.

| Meaning | Browser feedback | HTTP |
|---|---|---|
| The selected set is empty | Select documents or choose Whole library | 200 |
| Retrieval ran but found no evidence above the threshold | Change wording or scope | 200 |
| Nothing in this scope has a compatible completed index | Wait for indexing or choose other documents | 200 |
| Provider is limiting requests or the allowance is exhausted | Wait and submit explicitly later | 429 |
| The embedding request timed out | Try again later | 504 |
| Storage/provider/configuration is unavailable | Follow the specific guidance | 503 |

A missing index is a meaningful application outcome, even though the HTTP request itself is valid. That is why the page can return HTTP 200 with **No searchable documents**, while service telemetry records `no_compatible_index`. A provider failure never becomes an empty successful result. Zero-match output is reserved for completed retrieval or an explicit empty selection; a later visibility loss has its own explanation.

The controller restores a parsed `SearchForm` before trying retrieval. This preserves valid text, scope, checked IDs and page when a provider or database operation fails. If labels cannot load, the page uses generic checked selection controls rather than losing those IDs or displaying old filenames. This state lives only in the request's view model; it is not query history or a server session.

Blank text is valid while browsing choices but invalid when converted into `SearchQuery`. That failure can preserve the rest of the valid form without any database/provider call. Malformed or ambiguous fields cannot form a trusted `SearchForm`; the page explicitly clears them and chooses selected/empty scope instead of accidentally broadening to all documents.

An optional `Instant` retry hint is rendered as a UTC time for relevant provider failures. No raw message is interpreted to invent a reset date. The application does not wait or automatically resubmit: each explicit search can make at most one service attempt. A new request can succeed without retaining an earlier quota message because feedback is per request, not mutable singleton state.

MVC tests supply controlled service outcomes and render the real Thymeleaf page. They check HTTP statuses, helpful messages, escaped retained input, fixed diagnostics and explicit retry behavior without consuming a real allowance. They establish presentation behavior; evaluating whether a real model finds relevant study material remains a separate task.

## 34. Giving retrieval a small answer key

The [reference collection](../src/test/resources/retrieval/reference-v1/collection.json) contains example Java study notes and a hand-labeled answer key. The ArrayList and HashSet notes describe different handling of duplicates and ordering. A personal review-plan example supplies evidence for a question that needs two documents. Other cases ask about facts absent from the notes or excluded by scope/deletion. They are sample uploads for search tests; they introduce no student accounts or library-management feature.

For example, a direct question asks whether ArrayList allows duplicate values. A paraphrase asks whether the same value can be kept more than once. Both should identify the same evidence in the ArrayList note. A comparison asks how ArrayList and HashSet differ, requiring evidence from both notes. The sample collection rules follow the Java 25 [ArrayList](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ArrayList.html), [List](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/List.html), and [HashSet](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/HashSet.html) documentation.

```mermaid
flowchart LR
    Files[Example Java study notes] --> Hashes[Verify exact UTF-8 bytes and hashes]
    Labels[Stable source labels and line ranges] --> Quotes[Verify quotes against source lines]
    Cases[Queries, scopes, deletions and expected facts] --> Rules[Verify references and evidence consistency]
    Hashes --> Valid[Consistent reference data]
    Quotes --> Valid
    Rules --> Valid
```

[RetrievalReferenceCollection](../src/test/java/dev/secondbrain/search/RetrievalReferenceCollection.java) is test-only code. Its immutable records describe documents, evidence spans, explicit scopes and query cases. The manifest version and file digests make the input reproducible. A source label has one-based inclusive line bounds and an exact quote. Expected facts are verbatim source statements; an absent-evidence case has no expected source or fact. The validator rejects, for example, an expected source that was deleted or lies outside the selected documents.

A logical label such as `arraylist-behavior` is different from a database UUID. It identifies evidence in the answer key across runs, while actual document/index/passage identities belong to a particular imported run. Several labeled facts can share a single passage after splitting. Counting those aliases as separate retrieved passages would inflate or distort a recall calculation, so the two identities must stay distinct.

The validator does not call `SearchService` or an embedding provider. It proves that the answer key points to real bytes and legal source locations, not that retrieval finds them. It also cannot prove natural-language relevance merely because a fact appears in a quote. The [fixture tests](../src/test/java/dev/secondbrain/search/RetrievalReferenceCollectionTest.java) check declared scenarios and deliberately corrupt labels/hashes/scopes to ensure inconsistent data is rejected without consuming a model allowance.

## References and related notes

The source files are the authority for current behavior. [The local architecture](../.task/architecture.md) explains design decisions; [the testing strategy](../.task/testing-strategy.md) describes verification responsibilities. [The development guide](../docs/development.md#embedding-configuration) lists implemented configuration settings.

Provider/model references: [Cloudflare compatible endpoint](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/), [BGE-M3 model specification](https://huggingface.co/BAAI/bge-m3), and [Cloudflare free-plan pricing rules](https://developers.cloudflare.com/workers-ai/platform/pricing/). Model documentation and pricing are external facts; recheck them before live inference.
