package dev.secondbrain.conversations;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Saves Ask conversations locally. A conversation is created by its first answered question and
 * titled from it; later questions are appended. Nothing here calls a provider or logs content.
 */
public final class ConversationService {
    public static final int MAXIMUM_TURNS = 500;
    public static final int TITLE_LENGTH = 80;
    private static final Pattern SCOPE = Pattern.compile("all|folder:[0-9a-f-]{36}");

    private final JdbcConversationRepository conversations;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ConversationService(
            JdbcConversationRepository conversations,
            PlatformTransactionManager manager,
            Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.clock = Objects.requireNonNull(clock, "clock");
        transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        transactions.setTimeout(5);
    }

    /** Most recently used first, without questions or answers. */
    public List<Conversation.Summary> recent(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("Invalid limit");
        return Objects.requireNonNull(transactions.execute(status -> conversations.recent(limit)));
    }

    public Optional<Conversation> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(
                        status ->
                                conversations
                                        .find(id)
                                        .map(
                                                summary ->
                                                        new Conversation(
                                                                summary.conversationId(),
                                                                summary.title(),
                                                                summary.updatedAt(),
                                                                conversations.turns(id)))));
    }

    /** The previous question, used as context for a follow-up; empty for a new conversation. */
    public Optional<String> lastQuestion(UUID id) {
        Objects.requireNonNull(id, "id");
        return Objects.requireNonNull(
                transactions.execute(status -> conversations.lastQuestion(id)));
    }

    /**
     * Saves one answered question. With an empty or unknown conversation a new one is started,
     * titled from the question. Returns the conversation the turn was saved in.
     */
    public UUID save(
            Optional<UUID> conversation,
            String question,
            String scope,
            Conversation.Outcome outcome,
            String answer,
            List<Conversation.SourceRef> sources) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(outcome, "outcome");
        var cleanQuestion = Objects.requireNonNull(question, "question").strip();
        if (cleanQuestion.isEmpty()
                || cleanQuestion.codePointCount(0, cleanQuestion.length()) > 1200)
            throw new IllegalArgumentException("Invalid question");
        if (scope == null || !SCOPE.matcher(scope).matches())
            throw new IllegalArgumentException("Invalid scope");
        Objects.requireNonNull(answer, "answer");
        return Objects.requireNonNull(
                transactions.execute(
                        status -> {
                            var now = clock.instant();
                            var id =
                                    conversation.filter(
                                            found -> conversations.lock(found).isPresent());
                            final UUID target;
                            if (id.isPresent()) {
                                target = id.get();
                            } else {
                                target = UUID.randomUUID();
                                conversations.insert(target, title(cleanQuestion), now);
                            }
                            int position = conversations.nextPosition(target);
                            if (position > MAXIMUM_TURNS)
                                throw new IllegalStateException("Conversation is full");
                            conversations.insertTurn(
                                    target,
                                    new Conversation.Turn(
                                            position,
                                            cleanQuestion,
                                            scope,
                                            outcome,
                                            answer,
                                            sources,
                                            now));
                            return target;
                        }));
    }

    /** Returns false for an unknown conversation. */
    public boolean rename(UUID id, String title) {
        Objects.requireNonNull(id, "id");
        var clean = title(Objects.requireNonNullElse(title, ""));
        return Boolean.TRUE.equals(
                transactions.execute(
                        status -> {
                            if (conversations.lock(id).isEmpty()) return false;
                            conversations.rename(id, clean, clock.instant());
                            return true;
                        }));
    }

    public boolean delete(UUID id) {
        Objects.requireNonNull(id, "id");
        return Boolean.TRUE.equals(transactions.execute(status -> conversations.delete(id)));
    }

    public int deleteAll() {
        return Objects.requireNonNull(transactions.execute(status -> conversations.deleteAll()));
    }

    /** First line, whitespace collapsed, cut at a word near 80 characters. */
    static String title(String text) {
        var line = text.strip().lines().findFirst().orElse("").replaceAll("\\s+", " ");
        if (line.isEmpty()) line = "Untitled chat";
        if (line.codePointCount(0, line.length()) <= TITLE_LENGTH) return line;
        var cut = line.substring(0, line.offsetByCodePoints(0, TITLE_LENGTH - 1));
        int space = cut.lastIndexOf(' ');
        return (space > 40 ? cut.substring(0, space) : cut).strip() + "…";
    }
}
