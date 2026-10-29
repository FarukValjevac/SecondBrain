package dev.secondbrain.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.secondbrain.embeddings.EmbeddingFailure;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class QueryEmbeddingTest {
    private final EmbeddingModel model = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    private final EmbeddingProvider provider = mock(EmbeddingProvider.class);

    private QueryEmbedding embedding() {
        when(provider.model()).thenReturn(model);
        return new QueryEmbedding(provider);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void embedsOnlyTheNormalizedQueryOnceUsingTheSharedIndexModel(boolean selected)
            throws Exception {
        var query =
                new SearchQuery(
                        "  private-query-sentinel  ",
                        selected
                                ? SearchScope.selected(Set.of(UUID.randomUUID()))
                                : SearchScope.all());
        var vector = vector(model);
        when(provider.embed(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat((List<?>) call.getArgument(0))
                                    .isEqualTo(List.of("private-query-sentinel"));
                            return List.of(vector);
                        });
        var embedding = embedding();
        assertThat(embedding.model()).isEqualTo(model);
        assertThat(embedding.embed(query, model)).containsSame(vector);
        verify(provider).model();
        verify(provider).embed(List.of("private-query-sentinel"));
        verifyNoMoreInteractions(provider);
    }

    @Test
    void explicitEmptySelectionUsesNoInferenceAndDoesNotBecomeAllDocuments() throws Exception {
        var query = new SearchQuery("private-query-sentinel", SearchScope.selected(Set.of()));
        assertThat(embedding().embed(query, model)).isEmpty();
        verify(provider, never()).embed(any());
    }

    @Test
    void incompatibleIndexMetadataFailsBeforeInference() throws Exception {
        var incompatible = mock(EmbeddingModel.class);
        when(incompatible.id()).thenReturn(model.id());
        when(incompatible.dimensions()).thenReturn(768);
        assertFailure(
                () -> embedding().embed(query(), incompatible),
                SearchFailure.Reason.INCOMPATIBLE_MODEL);
        verify(provider, never()).embed(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void rejectsInvalidVectorCountNullsOrModelInsteadOfReturningNoResults(int invalid)
            throws Exception {
        var incompatible = mock(EmbeddingModel.class);
        when(incompatible.dimensions()).thenReturn(768);
        List<EmbeddingVector> response =
                switch (invalid) {
                    case 0 -> null;
                    case 1 -> List.of();
                    case 2 -> List.of(vector(model), vector(model));
                    case 3 -> Collections.singletonList(null);
                    default -> List.of(vector(incompatible));
                };
        when(provider.embed(any())).thenReturn(response);
        assertFailure(
                () -> embedding().embed(query(), model), SearchFailure.Reason.INVALID_EMBEDDING);
        verify(provider).embed(any());
    }

    @ParameterizedTest
    @EnumSource(EmbeddingFailure.Reason.class)
    void preservesSafeTimeoutQuotaAndRateLimitOutcomesWithoutRetrying(
            EmbeddingFailure.Reason reason) throws Exception {
        var failure = mock(EmbeddingFailure.class);
        var retry = Instant.parse("2030-01-02T00:00:00Z");
        when(failure.reason()).thenReturn(reason);
        when(failure.retryAt()).thenReturn(Optional.of(retry));
        when(failure.getMessage()).thenReturn("private-provider-token-sentinel");
        when(provider.embed(any())).thenThrow(failure);
        var expected =
                switch (reason) {
                    case RATE_LIMITED -> SearchFailure.Reason.RATE_LIMITED;
                    case QUOTA_EXHAUSTED -> SearchFailure.Reason.QUOTA_EXHAUSTED;
                    case TIMED_OUT -> SearchFailure.Reason.TIMED_OUT;
                    case INVALID_RESPONSE -> SearchFailure.Reason.INVALID_EMBEDDING;
                    default -> SearchFailure.Reason.PROVIDER_UNAVAILABLE;
                };
        var translated = assertFailure(() -> embedding().embed(query(), model), expected);
        assertThat(translated.retryAt()).contains(retry);
        verify(provider).embed(any());
    }

    @Test
    void unexpectedProviderExceptionsStayPrivateAndDoNotBecomeAnEmptyResult() throws Exception {
        when(provider.embed(any()))
                .thenThrow(new IllegalStateException("private-provider-token-sentinel"));
        var failure =
                assertFailure(
                        () -> embedding().embed(query(), model),
                        SearchFailure.Reason.PROVIDER_UNAVAILABLE);
        assertThat(failure.retryAt()).isEmpty();
        verify(provider).embed(any());
    }

    @Test
    void interruptionFromTheProviderRestoresTheThreadFlagWithoutRetrying() throws Exception {
        when(provider.embed(any()))
                .thenThrow(new InterruptedException("private-provider-token-sentinel"));
        try {
            assertFailure(
                    () -> embedding().embed(query(), model), SearchFailure.Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(provider).embed(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anAlreadyInterruptedCallerMakesNoInferenceRequest() throws Exception {
        var embedding = embedding();
        try {
            Thread.currentThread().interrupt();
            assertFailure(() -> embedding.embed(query(), model), SearchFailure.Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(provider, never()).embed(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aResponseAfterInterruptionIsDiscardedAndTheFlagRemainsSet() throws Exception {
        when(provider.embed(any()))
                .thenAnswer(
                        call -> {
                            Thread.currentThread().interrupt();
                            return List.of(vector(model));
                        });
        try {
            assertFailure(
                    () -> embedding().embed(query(), model), SearchFailure.Reason.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void refusesInferenceInsideACallerTransaction() throws Exception {
        var embedding = embedding();
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> embedding.embed(query(), model))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("private-query-sentinel");
            verify(provider, never()).embed(any());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static SearchFailure assertFailure(Runnable operation, SearchFailure.Reason expected) {
        try {
            operation.run();
        } catch (SearchFailure failure) {
            assertThat(failure.reason()).isEqualTo(expected);
            assertThat(failure.getCause()).isNull();
            var printed = new StringWriter();
            failure.printStackTrace(new PrintWriter(printed));
            assertThat(printed.toString())
                    .doesNotContain("private-query-sentinel", "private-provider-token-sentinel");
            return failure;
        }
        throw new AssertionError("Expected a safe search failure");
    }

    private static SearchQuery query() {
        return new SearchQuery("private-query-sentinel", SearchScope.all());
    }

    private static EmbeddingVector vector(EmbeddingModel model) {
        var values = new float[model.dimensions()];
        values[0] = 1;
        return new EmbeddingVector(model, values);
    }
}
