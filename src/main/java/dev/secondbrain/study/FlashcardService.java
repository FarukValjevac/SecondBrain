package dev.secondbrain.study;

import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.search.SearchService;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Generates cited flashcards with one bounded chat request; reviewing never calls a provider. */
public final class FlashcardService {
    static final String REQUEST =
            """
            Write up to 8 study flashcards from the evidence. Put each card on its own line in
            exactly this form: Q: question || A: short answer [n]
            where n is the position of the supporting passage_id in your citations array, counting
            from 1. Use only facts stated in the evidence.""";
    private final SearchService search;
    private final ChatProvider chat;
    private final JdbcFlashcardRepository cards;
    private final Clock clock;
    private final Optional<MeterRegistry> meters;

    FlashcardService(
            SearchService search,
            ChatProvider chat,
            JdbcFlashcardRepository cards,
            Clock clock,
            Optional<MeterRegistry> meters) {
        this.search = Objects.requireNonNull(search, "search");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.cards = Objects.requireNonNull(cards, "cards");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * Creates cards from the document's most typical passages and returns how many were kept. Zero
     * means the document is not ready, was deleted meanwhile, or no usable card came back. Provider
     * failures propagate as {@link dev.secondbrain.questions.ChatFailure}.
     */
    public int generate(UUID documentId) throws InterruptedException {
        var passages = search.representative(documentId, ChatProvider.Request.MAXIMUM_PASSAGES);
        if (passages.matches().isEmpty()) return count(0);
        long version = passages.matches().getFirst().indexingVersion();
        var evidence = new ArrayList<ChatProvider.Passage>();
        int bytes = REQUEST.getBytes(StandardCharsets.UTF_8).length;
        for (var match : passages.matches()) {
            int size = match.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > ChatProvider.Request.MAXIMUM_INPUT_BYTES) continue;
            evidence.add(new ChatProvider.Passage(match.passageId(), match.text()));
            bytes += size;
        }
        if (evidence.isEmpty()) return count(0);
        var reply = chat.generate(new ChatProvider.Request(REQUEST, evidence));
        if (reply.outcome() != ChatProvider.Outcome.ANSWERED) return count(0);
        // The same citation rule as answers: only supplied passages, each once.
        var supplied = new HashSet<UUID>();
        for (var passage : evidence) supplied.add(passage.passageId());
        var seen = new HashSet<UUID>();
        for (var citation : reply.citations())
            if (!supplied.contains(citation) || !seen.add(citation)) return count(0);
        var now = clock.instant();
        var parsed = FlashcardParser.parse(reply.answer(), reply.citations());
        var created = new ArrayList<Flashcard>(parsed.size());
        for (var card : parsed)
            created.add(
                    new Flashcard(
                            UUID.randomUUID(),
                            documentId,
                            card.passageId(),
                            card.question(),
                            card.answer(),
                            ReviewScheduler.Schedule.initial(now)));
        if (created.isEmpty() || !cards.insert(documentId, version, created, now)) return count(0);
        return count(created.size());
    }

    public Optional<Flashcard> nextDue() {
        return cards.nextDue(clock.instant());
    }

    public int dueCount() {
        return cards.countDue(clock.instant());
    }

    /** Grades a card; false if it was deleted or already reviewed in another tab. */
    public boolean review(UUID cardId, ReviewScheduler.Grade grade) {
        var card = cards.find(cardId);
        if (card.isEmpty()) return false;
        var now = clock.instant();
        var seen = card.orElseThrow().schedule();
        boolean updated = cards.reschedule(cardId, seen, ReviewScheduler.next(seen, grade, now));
        if (updated)
            metric(
                    "secondbrain.study.reviews",
                    "grade",
                    grade.name().toLowerCase(java.util.Locale.ROOT));
        return updated;
    }

    private int count(int created) {
        metric("secondbrain.study.flashcards", "outcome", created > 0 ? "created" : "none");
        return created;
    }

    private void metric(String name, String tag, String value) {
        meters.ifPresent(
                registry -> {
                    try {
                        registry.counter(name, tag, value).increment();
                    } catch (RuntimeException unavailable) {
                        LoggerFactory.getLogger(FlashcardService.class)
                                .atWarn()
                                .addKeyValue("code", "TELEMETRY_FAILED")
                                .log("Study metric unavailable");
                    }
                });
    }
}
