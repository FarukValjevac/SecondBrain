package dev.secondbrain.conversations;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Conversations in PostgreSQL: creation, turns, ordering, follow-up context, rename, delete. */
class ConversationIT {
    private static final TestDatabase POSTGRES = TestDatabase.create();
    @TempDir private Path storage;

    @AfterAll
    static void close() {
        POSTGRES.close();
    }

    @Test
    void savesContinuesRenamesAndDeletesConversations() {
        try (var context =
                new SpringApplicationBuilder(SecondBrainApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                POSTGRES.arguments(
                                        "--secondbrain.telemetry.otlp-endpoint=",
                                        "--secondbrain.events.polling-enabled=false",
                                        "--secondbrain.documents.storage-directory=" + storage))) {
            var service = context.getBean(ConversationService.class);
            var source = new Conversation.SourceRef(UUID.randomUUID(), UUID.randomUUID());

            var first =
                    service.save(
                            Optional.empty(),
                            "Was ist eine tragfähige Arbeitsbeziehung im Beratungskontext und warum gilt sie als Wirkfaktor?",
                            "all",
                            Conversation.Outcome.ANSWERED,
                            "Sie ist die Grundlage [1].",
                            List.of(source));
            // A follow-up goes into the same conversation and sees the previous question.
            assertThat(service.lastQuestion(first))
                    .hasValueSatisfying(q -> assertThat(q).startsWith("Was ist"));
            var same =
                    service.save(
                            Optional.of(first),
                            "Und warum?",
                            "folder:" + UUID.randomUUID(),
                            Conversation.Outcome.INSUFFICIENT,
                            "",
                            List.of());
            assertThat(same).isEqualTo(first);
            // An unknown conversation id starts a new one instead of failing.
            var second =
                    service.save(
                            Optional.of(UUID.randomUUID()),
                            "Zweite Frage",
                            "all",
                            Conversation.Outcome.ANSWERED,
                            "Antwort",
                            List.of());
            assertThat(second).isNotEqualTo(first);

            var saved = service.find(first).orElseThrow();
            assertThat(saved.title()).endsWith("…").hasSizeLessThanOrEqualTo(80);
            assertThat(saved.turns()).extracting(Conversation.Turn::position).containsExactly(1, 2);
            assertThat(saved.turns().getFirst().sources()).containsExactly(source);
            assertThat(saved.turns().get(1).outcome()).isEqualTo(Conversation.Outcome.INSUFFICIENT);
            assertThat(saved.lastScope()).startsWith("folder:");
            assertThat(service.recent(10))
                    .extracting(Conversation.Summary::conversationId)
                    .containsExactly(second, first);

            assertThat(service.rename(first, "  Beratung  ")).isTrue();
            assertThat(service.find(first).orElseThrow().title()).isEqualTo("Beratung");
            assertThat(service.rename(UUID.randomUUID(), "x")).isFalse();

            assertThat(service.delete(first)).isTrue();
            assertThat(service.find(first)).isEmpty();
            assertThat(service.deleteAll()).isEqualTo(1);
            assertThat(service.recent(10)).isEmpty();
        }
    }
}
