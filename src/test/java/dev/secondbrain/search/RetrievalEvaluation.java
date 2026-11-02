package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Reports only reference IDs, settings and numeric measurements; no query, excerpt or credentials.
 */
final class RetrievalEvaluation {
    enum Status {
        NOT_RUN,
        COMPLETED,
        FAILED
    }

    record ReturnedPassage(String passageId, double score) {}

    record CaseResult(
            String id,
            String scope,
            List<String> selectedDocuments,
            List<String> deletedDocuments,
            List<String> expectedPassages,
            Status status,
            List<ReturnedPassage> returnedPassages,
            Double recallAtK,
            Boolean absenceMatched,
            Double durationSeconds,
            String failureCode,
            String retryAt) {
        CaseResult {
            selectedDocuments = List.copyOf(selectedDocuments);
            deletedDocuments = List.copyOf(deletedDocuments);
            expectedPassages = List.copyOf(expectedPassages);
            returnedPassages = List.copyOf(returnedPassages);
        }
    }

    record Binding(
            String label,
            String sha256,
            String documentId,
            String passageId,
            List<String> sourceLabels,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn) {
        Binding {
            sourceLabels = List.copyOf(sourceLabels);
        }
    }

    record Configuration(
            String databaseImage,
            String model,
            int dimensions,
            long extractionVersion,
            long indexingVersion,
            int passageConfigurationVersion,
            int topK,
            double minimumScore,
            EvaluationBudget.Limits limits,
            int connectTimeoutSeconds,
            int requestTimeoutSeconds) {}

    record Report(
            int schemaVersion,
            String runKind,
            String corpusVersion,
            int corpusSchemaVersion,
            String startedAt,
            String finishedAt,
            Status status,
            String failureCode,
            String retryAt,
            Configuration configuration,
            EvaluationBudget.Usage usage,
            List<Binding> bindings,
            List<CaseResult> cases,
            List<String> limitations) {
        Report {
            bindings = List.copyOf(bindings);
            cases = List.copyOf(cases);
            limitations = List.copyOf(limitations);
        }
    }

    static final String DATABASE_IMAGE = "pgvector/pgvector:0.8.7-pg18-bookworm";
    static final double MINIMUM_SCORE = 0.3;
    private final ReferenceIndex index;
    private final EvaluationBudget.Limits limits;
    private final Clock clock;
    private final Instant started;
    private final List<CaseResult> cases = new ArrayList<>();
    private Status status = Status.NOT_RUN;
    private String failureCode;
    private String retryAt;
    private String finishedAt;

    RetrievalEvaluation(ReferenceIndex index, EvaluationBudget.Limits limits, Clock clock) {
        this.index = index;
        this.limits = limits;
        this.clock = clock;
        started = clock.instant();
        for (var reference : index.collection().cases())
            cases.add(result(reference, Status.NOT_RUN, List.of(), null, null, null, null, null));
    }

    void run(
            Function<SearchQuery, SearchResult> search,
            Consumer<List<String>> applyDeletions,
            EvaluationBudget budget,
            MeterRegistry meters) {
        for (int i = 0; i < index.collection().cases().size(); i++) {
            var reference = index.collection().cases().get(i);
            var before = measurement(meters);
            try {
                applyDeletions.accept(reference.deletedDocuments());
                var found =
                        search.apply(
                                new SearchQuery(
                                        reference.query(), index.scopeFor(reference.scope())));
                var after = measurement(meters);
                if (after.count() - before.count() != 1)
                    throw new IllegalStateException("Missing search observation");
                cases.set(i, score(reference, found, after.seconds() - before.seconds()));
            } catch (SearchFailure failed) {
                String code = budget.stopped() ? "BUDGET_EXHAUSTED" : failed.reason().name();
                var duration = measuredFailure(meters, before);
                cases.set(
                        i,
                        result(
                                reference,
                                Status.FAILED,
                                List.of(),
                                null,
                                null,
                                duration,
                                code,
                                failed.retryAt().map(Instant::toString).orElse(null)));
                fail(code, failed.retryAt().map(Instant::toString).orElse(null));
                return; // A failed call never becomes a no-evidence result or an automatic retry.
            } catch (RuntimeException unavailable) {
                String code = budget.stopped() ? "BUDGET_EXHAUSTED" : "EVALUATION_UNAVAILABLE";
                cases.set(
                        i,
                        result(
                                reference,
                                Status.FAILED,
                                List.of(),
                                null,
                                null,
                                measuredFailure(meters, before),
                                code,
                                null));
                fail(code, null);
                return;
            }
        }
        status = Status.COMPLETED;
        finishedAt = clock.instant().toString();
    }

    private CaseResult score(
            RetrievalReferenceCollection.QueryCase reference, SearchResult found, double seconds) {
        Set<UUID> expected = index.passagesForSources(reference.expectedSources());
        var actual =
                found.matches().stream()
                        .map(SearchResult.Match::passageId)
                        .collect(java.util.stream.Collectors.toSet());
        double hits = expected.stream().filter(actual::contains).count();
        var returned =
                found.matches().stream()
                        .map(
                                match ->
                                        new ReturnedPassage(
                                                match.passageId().toString(), match.score()))
                        .toList();
        return result(
                reference,
                Status.COMPLETED,
                returned,
                expected.isEmpty() ? null : hits / expected.size(),
                expected.isEmpty() ? found.matches().isEmpty() : null,
                seconds,
                null,
                null);
    }

    private CaseResult result(
            RetrievalReferenceCollection.QueryCase reference,
            Status state,
            List<ReturnedPassage> returned,
            Double recall,
            Boolean absent,
            Double seconds,
            String code,
            String retry) {
        return new CaseResult(
                reference.id(),
                reference.scope().mode().name(),
                reference.scope().documents(),
                reference.deletedDocuments(),
                index.passagesForSources(reference.expectedSources()).stream()
                        .map(UUID::toString)
                        .sorted()
                        .toList(),
                state,
                returned,
                recall,
                absent,
                seconds,
                code,
                retry);
    }

    void fail(String code, String retry) {
        // Callers supply closed application codes, never raw exception messages.
        if (!code.matches("[A-Z_]{1,64}"))
            throw new IllegalArgumentException("Invalid evaluation failure code");
        status = Status.FAILED;
        failureCode = code;
        retryAt = retry;
        finishedAt = clock.instant().toString();
    }

    void embeddingFailed(EmbeddingFailure failed) {
        fail(failed.reason().name(), failed.retryAt().map(Instant::toString).orElse(null));
    }

    Report report(EvaluationBudget.Usage usage) {
        var bindings =
                index.documents().stream()
                        .map(
                                document -> {
                                    var labels =
                                            index.sourcePassages().entrySet().stream()
                                                    .filter(
                                                            entry ->
                                                                    entry.getValue()
                                                                            .equals(
                                                                                    document
                                                                                            .passageId()))
                                                    .map(java.util.Map.Entry::getKey)
                                                    .sorted()
                                                    .toList();
                                    var location = document.location();
                                    return new Binding(
                                            document.label(),
                                            document.sha256(),
                                            document.documentId().toString(),
                                            document.passageId().toString(),
                                            labels,
                                            location.startLine(),
                                            location.startColumn(),
                                            location.endLine(),
                                            location.endColumn());
                                })
                        .toList();
        return new Report(
                1,
                "LIVE_EMBEDDINGS",
                index.collection().corpusVersion(),
                index.collection().schemaVersion(),
                started.toString(),
                finishedAt,
                status,
                failureCode,
                retryAt,
                new Configuration(
                        DATABASE_IMAGE,
                        EmbeddingModel.BGE_M3_ID,
                        1024,
                        1,
                        ReferenceIndex.INDEXING_VERSION,
                        ReferenceIndex.PASSAGE_CONFIGURATION,
                        SearchQuery.DEFAULT_RESULTS,
                        MINIMUM_SCORE,
                        limits,
                        5,
                        20),
                usage,
                bindings,
                cases,
                List.of(
                        "Only this small synthetic corpus is evaluated; no chat or answer-grounding score.",
                        "Published read fixtures bypass extraction, passage preparation and background delivery.",
                        "Hosted model ID does not pin provider weights; date and settings are recorded.",
                        "Reported tokens sum only responses with usage; missing/failed response usage is unknown.",
                        "Request/input-byte limits apply before I/O; reported-token stop is post-response and can overshoot by one request.",
                        "No authoritative neuron balance or monetary cost is available; Workers Free is a user-confirmed prerequisite.",
                        "Completed means execution completed, not a quality pass; recall and absence checks require review.",
                        "Durations use the interactive search-service observation, excluding fixture setup and deletion setup."));
    }

    static void write(Path path, Report report) throws IOException {
        Files.writeString(
                path,
                JsonMapper.builder()
                        .build()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(report),
                StandardCharsets.UTF_8);
    }

    private record Measurement(long count, double seconds) {}

    private static Measurement measurement(MeterRegistry meters) {
        var timers = meters.find("secondbrain.search.duration").timers();
        return new Measurement(
                timers.stream().mapToLong(Timer::count).sum(),
                timers.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.SECONDS)).sum());
    }

    private static Double measuredFailure(MeterRegistry meters, Measurement before) {
        var after = measurement(meters);
        return after.count() - before.count() == 1 ? after.seconds() - before.seconds() : null;
    }
}
