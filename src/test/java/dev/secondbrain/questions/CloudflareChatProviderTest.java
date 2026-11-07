package dev.secondbrain.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.time.Duration;

class CloudflareChatProviderTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://other.invalid/chat",
                "http://localhost/chat",
                "http://127.0.0.2/chat",
                "http://user@127.0.0.1/chat",
                "http://127.0.0.1/chat?token=x",
                "http://127.0.0.1/chat#fragment"
            })
    void rejectsAlternateCredentialDestinationsBeforeCreatingTransport(String endpoint) {
        assertThatThrownBy(
                        () ->
                                new CloudflareChatProvider(
                                        CloudflareChatProtocolTest.settings(Duration.ofSeconds(2)),
                                        CloudflareChatProtocolTest.CLOCK,
                                        URI.create(endpoint)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesDatabaseTransactionsBeforeAnyNetworkRequest() {
        try (var provider = provider()) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    @Test
    void preservesAnExistingInterruptionBeforeAnyNetworkRequest() {
        try (var provider = provider()) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                        .isInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void nullRequestsFailSafelyBeforeAnyNetworkRequest() {
        try (var provider = provider()) {
            assertThatThrownBy(() -> provider.generate(null))
                    .isInstanceOfSatisfying(
                            ChatFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(ChatFailure.Reason.INVALID_INPUT));
        }
    }

    @Test
    void closedTransportCannotStartAnotherRequestOrExposeItsPrivateCause() {
        var provider = provider();
        provider.close();
        assertThatThrownBy(() -> provider.generate(CloudflareChatProtocolTest.input()))
                .isInstanceOfSatisfying(
                        ChatFailure.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(ChatFailure.Reason.UNAVAILABLE);
                            assertThat(failure.getCause()).isNull();
                        });
        provider.close();
    }

    private static CloudflareChatProvider provider() {
        return new CloudflareChatProvider(
                CloudflareChatProtocolTest.settings(Duration.ofSeconds(2)),
                CloudflareChatProtocolTest.CLOCK,
                URI.create("http://127.0.0.1:1/chat-guard-test"));
    }
}
