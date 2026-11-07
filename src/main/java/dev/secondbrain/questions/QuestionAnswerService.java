package dev.secondbrain.questions;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Public question use case. Accepted identities/visibility do not establish factual support. */
public final class QuestionAnswerService {
    private final QuestionService generation;
    private final DocumentInspectionService documents;
    private final QuestionTelemetry telemetry;

    QuestionAnswerService(
            QuestionService generation,
            DocumentInspectionService documents,
            QuestionTelemetry telemetry) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public Answer answer(SearchQuery question) {
        Objects.requireNonNull(question, "question");
        try (var operation = telemetry.startAnswer()) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                operation.outcome(QuestionTelemetry.Outcome.REJECTED);
                throw new IllegalStateException("Questions must run outside a caller transaction");
            }
            try {
                var candidate = generation.generate(question);
                checkInterrupted(QuestionFailure.Stage.CITATIONS);
                validateCitations(candidate);
                var answer = resolve(candidate);
                checkInterrupted(QuestionFailure.Stage.SOURCES);
                operation.outcome(
                        answer.outcome() == Outcome.ANSWERED
                                ? QuestionTelemetry.Outcome.ANSWERED
                                : QuestionTelemetry.Outcome.INSUFFICIENT_INFORMATION);
                return answer;
            } catch (QuestionFailure failure) {
                operation.failure(failure.reason());
                throw failure;
            }
        }
    }

    private static void validateCitations(QuestionService.Candidate candidate) {
        if (candidate.reply().outcome() == ChatProvider.Outcome.INSUFFICIENT_INFORMATION) return;
        if (candidate.reply().citations().isEmpty())
            throw citationFailure(QuestionFailure.Reason.MISSING_CITATIONS);
        var supplied = new HashSet<UUID>();
        candidate
                .context()
                .orElseThrow()
                .sources()
                .forEach(source -> supplied.add(source.passageId()));
        var seen = new HashSet<UUID>();
        for (var citation : candidate.reply().citations()) {
            if (!supplied.contains(citation))
                throw citationFailure(QuestionFailure.Reason.INVALID_CITATION);
            if (!seen.add(citation))
                throw citationFailure(QuestionFailure.Reason.DUPLICATE_CITATION);
        }
    }

    private Answer resolve(QuestionService.Candidate candidate) {
        if (candidate.context().isEmpty()) return Answer.insufficientInformation();
        var supplied = candidate.context().orElseThrow().sources();
        var owners = new LinkedHashMap<UUID, UUID>();
        for (var source : supplied) owners.put(source.passageId(), source.documentId());
        checkInterrupted(QuestionFailure.Stage.SOURCES);
        var visible = readSources(owners);
        checkInterrupted(QuestionFailure.Stage.SOURCES);
        if (!visible.keySet().equals(owners.keySet())) throw unavailableSource(null);
        // Check every supplied passage, including uncited text that could have influenced the
        // model.
        for (var source : supplied) {
            if (!sameEvidence(source, visible.get(source.passageId())))
                throw unavailableSource(null);
        }
        if (candidate.reply().outcome() == ChatProvider.Outcome.INSUFFICIENT_INFORMATION)
            return Answer.insufficientInformation();
        var citations = new ArrayList<DocumentInspectionService.PassageSource>();
        for (var id : candidate.reply().citations()) citations.add(visible.get(id));
        return new Answer(Outcome.ANSWERED, candidate.reply().answer(), citations);
    }

    private Map<UUID, DocumentInspectionService.PassageSource> readSources(Map<UUID, UUID> owners) {
        try {
            return Map.copyOf(documents.sources(Map.copyOf(owners)));
        } catch (DataAccessException | TransactionException unavailable) {
            throw new QuestionFailure(
                    QuestionFailure.Stage.SOURCES,
                    QuestionFailure.Reason.DATABASE_UNAVAILABLE,
                    null,
                    unavailable);
        } catch (RuntimeException unavailable) {
            throw unavailableSource(unavailable);
        }
    }

    private static boolean sameEvidence(
            SearchResult.Match expected, DocumentInspectionService.PassageSource actual) {
        if (actual == null) return false;
        var before = expected.location();
        var after = actual.location();
        return expected.passageId().equals(actual.passageId())
                && expected.documentId().equals(actual.documentId())
                && expected.indexingVersion() == actual.indexingVersion()
                && expected.text().equals(actual.text())
                && before.startLine() == after.startLine()
                && before.startColumn() == after.startColumn()
                && before.endLine() == after.endLine()
                && before.endColumn() == after.endColumn()
                && before.pageNumber().equals(after.pageNumber());
    }

    private static QuestionFailure citationFailure(QuestionFailure.Reason reason) {
        return new QuestionFailure(QuestionFailure.Stage.CITATIONS, reason, null, null);
    }

    private static QuestionFailure unavailableSource(Throwable cause) {
        return new QuestionFailure(
                QuestionFailure.Stage.SOURCES,
                QuestionFailure.Reason.SOURCE_UNAVAILABLE,
                null,
                cause);
    }

    private static void checkInterrupted(QuestionFailure.Stage stage) {
        if (Thread.currentThread().isInterrupted())
            throw new QuestionFailure(stage, QuestionFailure.Reason.INTERRUPTED, null, null);
    }

    public enum Outcome {
        ANSWERED,
        INSUFFICIENT_INFORMATION
    }

    /** Immutable display projection; citation identity/visibility are distinct from truth. */
    public record Answer(
            Outcome outcome, String text, List<DocumentInspectionService.PassageSource> citations) {
        public Answer {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(citations, "citations");
            if (citations.size() > 5)
                throw new IllegalArgumentException("Too many answer citations");
            citations = List.copyOf(citations);
            if (outcome == Outcome.INSUFFICIENT_INFORMATION) {
                if (!text.isEmpty() || !citations.isEmpty())
                    throw new IllegalArgumentException(
                            "Insufficient information cannot contain answer content");
            } else {
                if (text.isBlank()
                        || text.length() > 16000
                        || text.codePointCount(0, text.length()) > 8000
                        || text.indexOf('\0') >= 0
                        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                        || citations.isEmpty())
                    throw new IllegalArgumentException("Invalid sourced answer");
                var ids = new HashSet<UUID>();
                for (var citation : citations)
                    if (!ids.add(citation.passageId()))
                        throw new IllegalArgumentException("Duplicate answer citations");
            }
        }

        static Answer insufficientInformation() {
            return new Answer(Outcome.INSUFFICIENT_INFORMATION, "", List.of());
        }

        @Override
        public String toString() {
            return "QuestionAnswer[outcome=" + outcome + ", citations=" + citations.size() + "]";
        }
    }
}
