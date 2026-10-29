package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.secondbrain.search.RetrievalReferenceCollection.Document;
import dev.secondbrain.search.RetrievalReferenceCollection.Evidence;
import dev.secondbrain.search.RetrievalReferenceCollection.Kind;
import dev.secondbrain.search.RetrievalReferenceCollection.Mode;
import dev.secondbrain.search.RetrievalReferenceCollection.QueryCase;
import dev.secondbrain.search.RetrievalReferenceCollection.Scope;
import dev.secondbrain.search.RetrievalReferenceCollection.Source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

/** Validates gold data only; does not retrieve, embed, call chat or measure semantic quality. */
class RetrievalReferenceCollectionTest {
    @Test
    void loadsPinnedStudyNotesAndDeclaredScenarioExpectations() throws Exception {
        var collection = RetrievalReferenceCollection.load();
        assertThat(collection.schemaVersion()).isEqualTo(1);
        assertThat(collection.corpusVersion()).isEqualTo("java-study-v1");
        assertThat(collection.documents())
                .extracting(Document::id)
                .containsExactly("arraylist", "hashset", "study-plan");
        assertThat(collection.sources()).hasSize(4);
        assertThat(collection.cases()).hasSize(11);
        assertThat(find(collection, "duplicates-direct").expectedSources())
                .containsExactly("arraylist-behavior");
        assertThat(find(collection, "duplicates-paraphrase").expectedSources())
                .containsExactly("arraylist-behavior");
        assertThat(find(collection, "duplicate-comparison").expectedSources())
                .containsExactly("arraylist-behavior", "hashset-behavior");
        assertThat(find(collection, "duplicate-comparison").expectedFacts())
                .containsExactly(
                        "ArrayList keeps values in list order and permits duplicates.",
                        "HashSet stores each distinct value only once.");
        assertThat(find(collection, "collections-and-review").expectedSources())
                .containsExactly("arraylist-behavior", "review-schedule");
        assertThat(find(collection, "hashset-filter").scope())
                .isEqualTo(new Scope(Mode.SELECTED, List.of("hashset")));
        assertThat(find(collection, "wrong-scope").evidence()).isEqualTo(Evidence.ABSENT);
        assertThat(find(collection, "empty-selection").scope())
                .isEqualTo(new Scope(Mode.SELECTED, List.of()));
        assertThat(find(collection, "removed-arraylist").deletedDocuments())
                .containsExactly("arraylist");
        assertThat(find(collection, "removed-arraylist").expectedSources()).isEmpty();
        assertThat(find(collection, "hashset-survives").expectedSources())
                .containsExactly("hashset-behavior");
    }

    @Test
    void keepsReferenceValuesImmutableAndDiagnosticsFreeOfQuestionsAndQuotes() throws Exception {
        var collection = RetrievalReferenceCollection.load();
        var query = find(collection, "hashset-filter");
        var source = collection.sources().getFirst();
        assertThatThrownBy(() -> collection.documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> collection.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> collection.cases().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> query.expectedFacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> query.scope().documents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var mutable = new ArrayList<>(List.of("hashset"));
        var copied = new Scope(Mode.SELECTED, mutable);
        mutable.clear();
        assertThat(copied.documents()).containsExactly("hashset");
        assertThat(collection.toString() + query + source)
                .doesNotContain(query.query(), source.quote(), query.expectedFacts().getFirst());
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void rejectsInconsistentGoldDataBeforeAnyRetrieval(Fault fault) throws Exception {
        var original = RetrievalReferenceCollection.load();
        var documents = new ArrayList<>(original.documents());
        var sources = new ArrayList<>(original.sources());
        var cases = new ArrayList<>(original.cases());
        int schema = original.schemaVersion();
        var first = cases.getFirst();
        var source = sources.getFirst();
        switch (fault) {
            case DUPLICATE_DOCUMENT -> documents.add(documents.getFirst());
            case DUPLICATE_SOURCE -> sources.add(sources.getFirst());
            case DUPLICATE_CASE -> cases.add(first);
            case WRONG_HASH -> {
                var document = documents.getFirst();
                documents.set(0, new Document(document.id(), document.file(), "0".repeat(64)));
            }
            case UNSAFE_PATH -> {
                var document = documents.getFirst();
                documents.set(0, new Document(document.id(), "../.env", document.sha256()));
            }
            case UNKNOWN_SOURCE_DOCUMENT ->
                    sources.set(
                            0,
                            new Source(
                                    source.id(),
                                    "missing-document",
                                    source.startLine(),
                                    source.endLine(),
                                    source.quote()));
            case BAD_LINE_RANGE ->
                    sources.set(
                            0,
                            new Source(source.id(), source.document(), 999, 999, source.quote()));
            case WRONG_QUOTE ->
                    sources.set(
                            0,
                            new Source(
                                    source.id(),
                                    source.document(),
                                    source.startLine(),
                                    source.endLine(),
                                    "Not the source text"));
            case UNKNOWN_EXPECTED_SOURCE ->
                    cases.set(
                            0,
                            expected(
                                    first,
                                    Evidence.EXPECTED,
                                    List.of("missing-source"),
                                    first.expectedFacts()));
            case OUTSIDE_SCOPE ->
                    cases.set(0, scoped(first, new Scope(Mode.SELECTED, List.of("hashset"))));
            case DELETED_SOURCE -> {
                int index = cases.indexOf(find(original, "hashset-survives"));
                var query = cases.get(index);
                cases.set(
                        index,
                        new QueryCase(
                                query.id(),
                                query.kind(),
                                query.query(),
                                query.scope(),
                                List.of("hashset"),
                                query.evidence(),
                                query.expectedSources(),
                                query.expectedFacts()));
            }
            case UNSUPPORTED_FACT ->
                    cases.set(
                            0,
                            expected(
                                    first,
                                    Evidence.EXPECTED,
                                    first.expectedSources(),
                                    List.of("ArrayList always sorts its elements automatically.")));
            case ABSENT_WITH_FACTS ->
                    cases.set(
                            0,
                            expected(
                                    first,
                                    Evidence.ABSENT,
                                    first.expectedSources(),
                                    first.expectedFacts()));
            case EMPTY_SCOPE_EXPANDS -> {
                int index = cases.indexOf(find(original, "empty-selection"));
                cases.set(index, scoped(cases.get(index), new Scope(Mode.ALL, List.of())));
            }
            case LOST_CATEGORY -> cases.removeIf(query -> query.kind() == Kind.PARAPHRASE);
            case UNSUPPORTED_VERSION -> schema++;
        }
        var invalid =
                new RetrievalReferenceCollection(
                        schema, original.corpusVersion(), documents, sources, cases);
        assertThatThrownBy(invalid::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fault.message);
    }

    private static QueryCase find(RetrievalReferenceCollection collection, String id) {
        return collection.cases().stream()
                .filter(query -> query.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static QueryCase expected(
            QueryCase query, Evidence evidence, List<String> sources, List<String> facts) {
        return new QueryCase(
                query.id(),
                query.kind(),
                query.query(),
                query.scope(),
                query.deletedDocuments(),
                evidence,
                sources,
                facts);
    }

    private static QueryCase scoped(QueryCase query, Scope scope) {
        return new QueryCase(
                query.id(),
                query.kind(),
                query.query(),
                scope,
                query.deletedDocuments(),
                query.evidence(),
                query.expectedSources(),
                query.expectedFacts());
    }

    enum Fault {
        DUPLICATE_DOCUMENT("Duplicate document"),
        DUPLICATE_SOURCE("Duplicate source"),
        DUPLICATE_CASE("Duplicate case"),
        WRONG_HASH("checksum changed"),
        UNSAFE_PATH("Unsafe reference resource path"),
        UNKNOWN_SOURCE_DOCUMENT("unknown document"),
        BAD_LINE_RANGE("outside its document"),
        WRONG_QUOTE("quote does not match"),
        UNKNOWN_EXPECTED_SOURCE("Unknown or duplicate expected source"),
        OUTSIDE_SCOPE("outside selected scope"),
        DELETED_SOURCE("Expected source was deleted"),
        UNSUPPORTED_FACT("not anchored"),
        ABSENT_WITH_FACTS("expectation is inconsistent"),
        EMPTY_SCOPE_EXPANDS("must stay explicitly empty"),
        LOST_CATEGORY("coverage is incomplete"),
        UNSUPPORTED_VERSION("Unsupported reference version");
        private final String message;

        Fault(String message) {
            this.message = message;
        }
    }
}
