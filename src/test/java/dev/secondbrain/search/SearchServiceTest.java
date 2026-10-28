package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingVector;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

class SearchServiceTest {
    private final QueryEmbedding embedding = mock(QueryEmbedding.class);
    private final JdbcPassageSearch passages = mock(JdbcPassageSearch.class);
    private final EmbeddingModel model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private final SearchQuery query =
            new SearchQuery(
                    "private-query-sentinel", SearchScope.selected(Set.of(UUID.randomUUID())), 3);
    private final SearchService search =
            new SearchService(
                    embedding, passages, new SearchProperties(0.4), SearchTelemetry.disabled());

    @Test
    void finalValidationFailureCannotReturnThePreviouslyRankedText() {
        var ranked = new SearchResult(List.of(match(1, 1)));
        ready(ranked);
        when(passages.retainVisible(query.scope(), model, ranked))
                .thenThrow(new DataAccessResourceFailureException("private-query-sentinel"));
        assertThatThrownBy(() -> search.search(query))
                .isInstanceOfSatisfying(
                        SearchFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(SearchFailure.Reason.DATABASE_UNAVAILABLE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void reportsTheFinalVisibleCountRatherThanTheOriginalCandidateCount(int count) {
        var second = match(2, 0.8);
        var ranked = new SearchResult(List.of(match(1, 1), second));
        ready(ranked);
        var visible = new SearchResult(count == 0 ? List.of() : List.of(second));
        when(passages.retainVisible(query.scope(), model, ranked)).thenReturn(visible);
        var meters = new SimpleMeterRegistry();
        try {
            var observations = ObservationRegistry.create();
            observations
                    .observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meters));
            var measured =
                    new SearchService(
                            embedding,
                            passages,
                            new SearchProperties(0.4),
                            new SearchTelemetry(meters, observations));
            assertThat(measured.search(query)).isSameAs(visible);
            assertThat(meters.get("secondbrain.search.results").summary().count()).isEqualTo(1);
            assertThat(meters.get("secondbrain.search.results").summary().totalAmount())
                    .isEqualTo(count);
            assertThat(
                            meters.get("secondbrain.search.requests")
                                    .tag("outcome", count == 0 ? "no_results" : "success")
                                    .counter()
                                    .count())
                    .isEqualTo(1);
        } finally {
            meters.close();
        }
    }

    @Test
    void interruptionAfterRankingStopsBeforeAnotherDatabaseRead() {
        var ranked = new SearchResult(List.of(match(1, 1)));
        var vector = ready(ranked);
        when(passages.retrieve(query, vector, 0.4))
                .thenAnswer(
                        call -> {
                            Thread.currentThread().interrupt();
                            return ranked;
                        });
        try {
            assertThatThrownBy(() -> search.search(query))
                    .isInstanceOfSatisfying(
                            SearchFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(SearchFailure.Reason.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(passages, never()).retainVisible(any(), any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    private EmbeddingVector ready(SearchResult candidates) {
        var values = new float[1024];
        values[0] = 1;
        var vector = new EmbeddingVector(model, values);
        when(embedding.model()).thenReturn(model);
        when(passages.hasCompatibleIndex(query.scope(), model)).thenReturn(true);
        when(embedding.embed(query, model)).thenReturn(Optional.of(vector));
        when(passages.retrieve(query, vector, 0.4)).thenReturn(candidates);
        return vector;
    }

    private SearchResult.Match match(int sequence, double score) {
        var id = ((SearchScope.SelectedDocuments) query.scope()).documentIds().iterator().next();
        return new SearchResult.Match(
                UUID.randomUUID(),
                id,
                7,
                sequence,
                "private-passage-sentinel",
                new SearchResult.SourceLocation(sequence, 0, sequence, 24, OptionalInt.empty()),
                score);
    }

    @Test
    void checksEligibleScopeBeforeInferenceThenReusesOneQueryVectorForRetrieval() {
        var values = new float[1024];
        values[0] = 1;
        var vector = new EmbeddingVector(model, values);
        when(embedding.model()).thenReturn(model);
        when(passages.hasCompatibleIndex(query.scope(), model)).thenReturn(true);
        when(embedding.embed(query, model)).thenReturn(Optional.of(vector));
        var result = new SearchResult(List.of());
        when(passages.retrieve(query, vector, 0.4)).thenReturn(result);
        when(passages.retainVisible(query.scope(), model, result)).thenReturn(result);
        assertThat(search.search(query)).isSameAs(result);
        var order = inOrder(passages, embedding);
        order.verify(embedding).model();
        order.verify(passages).hasCompatibleIndex(query.scope(), model);
        order.verify(embedding).embed(query, model);
        order.verify(passages).retrieve(query, vector, 0.4);
        order.verify(passages).retainVisible(query.scope(), model, result);
    }

    @Test
    void emptyExplicitSelectionReadsNothingAndMakesNoInferenceRequest() {
        assertThat(
                        search.search(new SearchQuery("Notes", SearchScope.selected(Set.of())))
                                .matches())
                .isEmpty();
        verifyNoInteractions(embedding, passages);
    }

    @Test
    void absenceOfACompatibleActiveIndexIsDistinctFromNoMatchesAndUsesNoInference() {
        when(embedding.model()).thenReturn(model);
        assertThatThrownBy(() -> search.search(query))
                .isInstanceOfSatisfying(
                        SearchFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(SearchFailure.Reason.NO_COMPATIBLE_INDEX));
        verify(embedding, never()).embed(any(), any());
        verify(passages, never()).retrieve(any(), any(), anyDouble());
    }

    @Test
    void providerFailuresRemainTypedAndCannotFallThroughToDatabaseRetrieval() {
        when(embedding.model()).thenReturn(model);
        when(passages.hasCompatibleIndex(query.scope(), model)).thenReturn(true);
        var failure = new SearchFailure(SearchFailure.Reason.QUOTA_EXHAUSTED, null, null);
        when(embedding.embed(query, model)).thenThrow(failure);
        assertThatThrownBy(() -> search.search(query)).isSameAs(failure);
        verify(passages, never()).retrieve(any(), any(), anyDouble());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hidesDatabaseErrorsBeforeOrAfterInference(boolean after) {
        when(embedding.model()).thenReturn(model);
        var privateError =
                new DataAccessResourceFailureException(
                        "private-query-sentinel private-vector-sentinel");
        if (after) {
            when(passages.hasCompatibleIndex(query.scope(), model)).thenReturn(true);
            var values = new float[1024];
            values[0] = 1;
            var vector = new EmbeddingVector(model, values);
            when(embedding.embed(query, model)).thenReturn(Optional.of(vector));
            when(passages.retrieve(query, vector, 0.4)).thenThrow(privateError);
        } else when(passages.hasCompatibleIndex(query.scope(), model)).thenThrow(privateError);
        assertThatThrownBy(() -> search.search(query))
                .isInstanceOfSatisfying(
                        SearchFailure.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .isEqualTo(SearchFailure.Reason.DATABASE_UNAVAILABLE);
                            assertThat(failure.getCause()).isNull();
                            assertThat(failure.toString())
                                    .doesNotContain(
                                            "private-query-sentinel", "private-vector-sentinel");
                        });
    }

    @Test
    void activeCallerTransactionIsRejectedBeforeReadingOrEmbedding() {
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> search.search(query))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(embedding, passages);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void interruptedCallerReadsNothingAndKeepsTheFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> search.search(query))
                    .isInstanceOfSatisfying(
                            SearchFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(SearchFailure.Reason.INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(embedding, passages);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(
            doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.01, 1.01})
    void rejectsInvalidRelevanceThresholds(double score) {
        assertThatThrownBy(() -> new SearchProperties(score))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
