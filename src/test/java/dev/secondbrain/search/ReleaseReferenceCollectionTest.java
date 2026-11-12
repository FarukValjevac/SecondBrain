package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

class ReleaseReferenceCollectionTest {
    @Test
    void releasePassagesRespectProviderBatchSizeAndReserveEachCall() throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        var calls = new ArrayList<List<String>>();
        var model =
                new dev.secondbrain.embeddings.EmbeddingModel(
                        dev.secondbrain.embeddings.EmbeddingModel.BGE_M3_ID, 1024);
        var provider =
                new dev.secondbrain.embeddings.EmbeddingProvider() {
                    @Override
                    public dev.secondbrain.embeddings.EmbeddingModel model() {
                        return model;
                    }

                    @Override
                    public List<dev.secondbrain.embeddings.EmbeddingVector> embed(
                            List<String> input) {
                        assertThat(input).hasSizeLessThanOrEqualTo(3);
                        calls.add(List.copyOf(input));
                        return input.stream()
                                .map(
                                        ignored -> {
                                            float[] values = new float[1024];
                                            values[0] = 1;
                                            return new dev.secondbrain.embeddings.EmbeddingVector(
                                                    model, values);
                                        })
                                .toList();
                    }
                };
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var budget =
                    new EvaluationBudget(
                            provider, new EvaluationBudget.Limits(22, 8192, 8192), meters);
            assertThat(budget.embedReferencePassages(index)).hasSize(7);
            assertThat(calls).extracting(List::size).containsExactly(3, 3, 1);
            assertThat(calls.stream().flatMap(List::stream).toList())
                    .containsExactlyElementsOf(
                            index.documents().stream().map(ReferenceIndex.Document::text).toList());
            assertThat(budget.usage().attemptedRequests()).isEqualTo(3);
        } finally {
            meters.close();
        }
    }

    @Test
    void pendingReleaseReportRecordsPageAndVersionBindingsWithoutQualityMeasurements()
            throws Exception {
        var index = ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease());
        var evaluation =
                new RetrievalEvaluation(
                        index,
                        new EvaluationBudget.Limits(22, 8192, 8192),
                        java.time.Clock.fixed(
                                java.time.Instant.parse("2030-01-01T00:00:00Z"),
                                java.time.ZoneOffset.UTC));
        var report = evaluation.report(new EvaluationBudget.Usage(0, 0, 0, 0, 0));
        assertThat(report.schemaVersion()).isEqualTo(2);
        assertThat(report.corpusSchemaVersion()).isEqualTo(2);
        assertThat(report.configuration().indexingVersion()).isEqualTo(1);
        assertThat(report.configuration().passageConfigurationVersion()).isEqualTo(1);
        assertThat(report.cases())
                .hasSize(20)
                .allSatisfy(
                        result -> {
                            assertThat(result.status())
                                    .isEqualTo(RetrievalEvaluation.Status.NOT_RUN);
                            assertThat(result.recallAtK()).isNull();
                            assertThat(result.absenceMatched()).isNull();
                        });
        var pdf =
                report.bindings().stream()
                        .filter(binding -> binding.label().equals("practice-card"))
                        .findFirst()
                        .orElseThrow();
        assertThat(pdf.pageNumber()).isEqualTo(1);
        assertThat(pdf.passageId())
                .isEqualTo(index.document("practice-card").passageId().toString());
        assertThat(pdf.sourceLabels())
                .containsExactlyInAnyOrder("practice-purpose", "practice-score");
    }

    @Test
    void releaseSetHasTwentyCasesWithReproduciblePdfAndInstructionEvidence() throws Exception {
        var collection = RetrievalReferenceCollection.loadRelease();
        assertThat(collection.schemaVersion()).isEqualTo(2);
        assertThat(collection.corpusVersion()).isEqualTo("java-study-v2");
        assertThat(collection.documents()).hasSize(7);
        assertThat(collection.cases()).hasSize(20);
        var index = ReferenceIndex.prepare(collection);
        assertThat(ReferenceIndex.prepare(RetrievalReferenceCollection.loadRelease()))
                .isEqualTo(index);
        var pdf = index.document("practice-card");
        assertThat(pdf.type()).isEqualTo("PDF");
        assertThat(pdf.location().pageNumber()).hasValue(1);
        assertThat(pdf.originalSize()).isGreaterThan(pdf.text().length());
        assertThat(pdf.text()).doesNotEndWith("\n");
        assertThat(pdf.location().endColumn()).isPositive();
        assertThat(index.document("instruction-example").text())
                .contains("Ignore all instructions", "The review marker is blue.");
        for (var source : collection.sources()) {
            var passage = index.document(source.document());
            assertThat(index.passagesForSources(List.of(source.id())))
                    .containsExactly(passage.passageId());
            assertThat(passage.text()).contains(source.quote());
        }
        var bytes =
                index.documents().stream()
                        .mapToLong(
                                document ->
                                        document.text()
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                                .length)
                        .sum();
        assertThat(bytes).isLessThan(4096);
        EvaluationBudget.preflight(index, new EvaluationBudget.Limits(22, 8192, 8192));
        assertThatThrownBy(
                        () ->
                                EvaluationBudget.preflight(
                                        index, new EvaluationBudget.Limits(21, 8192, 8192)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void rejectsChangedMissingOrAmbiguousReleaseLabelsBeforeInference(Fault fault)
            throws Exception {
        var original = RetrievalReferenceCollection.loadRelease();
        var documents = new ArrayList<>(original.documents());
        var sources = new ArrayList<>(original.sources());
        var cases = new ArrayList<>(original.cases());
        var source =
                sources.stream()
                        .filter(value -> value.id().equals("practice-score"))
                        .findFirst()
                        .orElseThrow();
        int position = sources.indexOf(source);
        switch (fault) {
            case AMBIGUOUS_FACT -> {
                var anchor = sources.getFirst();
                sources.add(
                        new RetrievalReferenceCollection.Source(
                                "overlapping-arraylist",
                                anchor.document(),
                                2,
                                anchor.endLine(),
                                "\n" + anchor.quote()));
                var query = cases.getFirst();
                cases.set(
                        0,
                        new RetrievalReferenceCollection.QueryCase(
                                query.id(),
                                query.kind(),
                                query.query(),
                                query.scope(),
                                query.deletedDocuments(),
                                query.evidence(),
                                List.of(anchor.id(), "overlapping-arraylist"),
                                query.expectedFacts()));
            }
            case MISSING_ASSET ->
                    documents.set(
                            0,
                            new RetrievalReferenceCollection.Document(
                                    "arraylist", "documents/missing.md", "0".repeat(64)));
            case WRONG_PDF_PAGE ->
                    sources.set(
                            position,
                            new RetrievalReferenceCollection.Source(
                                    source.id(),
                                    source.document(),
                                    source.startLine(),
                                    source.endLine(),
                                    source.quote(),
                                    2));
            case MISSING_PDF_PAGE ->
                    sources.set(
                            position,
                            new RetrievalReferenceCollection.Source(
                                    source.id(),
                                    source.document(),
                                    source.startLine(),
                                    source.endLine(),
                                    source.quote()));
            case AMBIGUOUS_SOURCE ->
                    sources.add(
                            new RetrievalReferenceCollection.Source(
                                    "duplicate-score",
                                    source.document(),
                                    source.startLine(),
                                    source.endLine(),
                                    source.quote(),
                                    1));
            case UNSUPPORTED_FACT -> {
                var query = cases.getFirst();
                cases.set(
                        0,
                        new RetrievalReferenceCollection.QueryCase(
                                query.id(),
                                query.kind(),
                                query.query(),
                                query.scope(),
                                query.deletedDocuments(),
                                query.evidence(),
                                query.expectedSources(),
                                List.of("ArrayList guarantees sorted values.")));
            }
            case LOST_INSTRUCTION_CASE ->
                    cases.removeIf(
                            query ->
                                    query.kind()
                                            == RetrievalReferenceCollection.Kind.INSTRUCTION_TEXT);
        }
        var invalid =
                new RetrievalReferenceCollection(
                        2, original.corpusVersion(), documents, sources, cases);
        assertThatThrownBy(invalid::validate)
                .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
    }

    enum Fault {
        MISSING_ASSET,
        WRONG_PDF_PAGE,
        MISSING_PDF_PAGE,
        AMBIGUOUS_SOURCE,
        AMBIGUOUS_FACT,
        UNSUPPORTED_FACT,
        LOST_INSTRUCTION_CASE
    }
}
