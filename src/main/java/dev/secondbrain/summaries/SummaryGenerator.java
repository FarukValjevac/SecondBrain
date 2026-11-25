package dev.secondbrain.summaries;

import dev.secondbrain.events.DeliveryFailure;
import dev.secondbrain.events.EventHandler;
import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.questions.ChatProvider;
import dev.secondbrain.questions.CitationMarkers;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds one summary from a document's most typical passages with a single bounded chat request.
 * Provider I/O happens in {@link #prepare}; the returned effect stores the result only if the
 * document and its index generation are still current.
 */
final class SummaryGenerator {
    static final String REQUEST =
            "Summarize the main points of this document in at most five short sentences."
                    + " Write the summary in the language the document is written in.";
    static final int MAXIMUM_SUMMARY_LENGTH = 4000;
    private final SearchService search;
    private final ChatProvider chat;
    private final JdbcSummaryRepository summaries;
    private final Clock clock;
    private final Optional<MeterRegistry> meters;

    SummaryGenerator(
            SearchService search,
            ChatProvider chat,
            JdbcSummaryRepository summaries,
            Clock clock,
            Optional<MeterRegistry> meters) {
        this.search = Objects.requireNonNull(search, "search");
        this.chat = Objects.requireNonNull(chat, "chat");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    EventHandler.Effect prepare(UUID documentId) throws InterruptedException {
        var passages = search.representative(documentId, ChatProvider.Request.MAXIMUM_PASSAGES);
        if (passages.matches().isEmpty()) {
            count("skipped");
            return () -> EventHandler.Outcome.SKIPPED;
        }
        long version = passages.matches().getFirst().indexingVersion();
        var evidence = evidence(passages.matches());
        final ChatProvider.Reply reply;
        try {
            reply = chat.generate(new ChatProvider.Request(REQUEST, evidence));
        } catch (ChatFailure failure) {
            count("failed");
            throw new DeliveryFailure(
                    failure, temporary(failure.reason()), failure.retryAt().orElse(null));
        }
        if (reply.outcome() == ChatProvider.Outcome.INSUFFICIENT_INFORMATION) {
            count("insufficient");
            return () -> EventHandler.Outcome.SKIPPED;
        }
        var cited = validate(reply, evidence);
        // Repeats of a passage are merged; their markers move to its first number.
        var unique = new java.util.LinkedHashMap<UUID, Integer>();
        var numbers = new int[reply.citations().size()];
        for (int index = 0; index < numbers.length; index++)
            numbers[index] =
                    unique.computeIfAbsent(reply.citations().get(index), id -> unique.size() + 1);
        var text =
                CitationMarkers.retainValid(
                        CitationMarkers.renumber(reply.answer().strip(), numbers), cited.size());
        if (text.isBlank() || text.length() > MAXIMUM_SUMMARY_LENGTH) {
            count("failed");
            throw new DeliveryFailure(new IllegalArgumentException("Unusable summary length"));
        }
        var at = clock.instant();
        return () -> {
            if (!summaries.save(documentId, version, text, cited, at)) {
                count("skipped");
                return EventHandler.Outcome.SKIPPED;
            }
            count("stored");
            return EventHandler.Outcome.COMPLETED;
        };
    }

    /** Quota and outages delay this stage with backoff; indexing itself is already complete. */
    static boolean temporary(ChatFailure.Reason reason) {
        return switch (reason) {
            case RATE_LIMITED, QUOTA_EXHAUSTED, UNAVAILABLE, TIMED_OUT -> true;
            case ACCESS_DENIED, MODEL_UNAVAILABLE, INVALID_INPUT, INVALID_RESPONSE, OUTPUT_LIMIT ->
                    false;
        };
    }

    /** Best passages first, as many as fit the chat request's text budget. */
    private static List<ChatProvider.Passage> evidence(List<SearchResult.Match> matches) {
        var selected = new ArrayList<ChatProvider.Passage>();
        int bytes = REQUEST.getBytes(StandardCharsets.UTF_8).length;
        for (var match : matches) {
            int size = match.text().getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > ChatProvider.Request.MAXIMUM_INPUT_BYTES) continue;
            selected.add(new ChatProvider.Passage(match.passageId(), match.text()));
            bytes += size;
        }
        if (selected.isEmpty())
            throw new DeliveryFailure(new IllegalStateException("No passage fits the budget"));
        return selected;
    }

    /** The same rules as answers: cite only supplied passages; repeats count once. */
    private List<UUID> validate(ChatProvider.Reply reply, List<ChatProvider.Passage> evidence) {
        var supplied = new HashSet<UUID>();
        for (var passage : evidence) supplied.add(passage.passageId());
        for (var citation : reply.citations())
            if (!supplied.contains(citation)) {
                count("failed");
                throw new DeliveryFailure(new IllegalArgumentException("Invalid summary citation"));
            }
        if (reply.citations().isEmpty()) {
            count("failed");
            throw new DeliveryFailure(new IllegalArgumentException("Summary without citations"));
        }
        return List.copyOf(new java.util.LinkedHashSet<>(reply.citations()));
    }

    private void count(String outcome) {
        meters.ifPresent(
                registry -> {
                    try {
                        registry.counter("secondbrain.summaries.generated", "outcome", outcome)
                                .increment();
                    } catch (RuntimeException unavailable) {
                        LoggerFactory.getLogger(SummaryGenerator.class)
                                .atWarn()
                                .addKeyValue("code", "TELEMETRY_FAILED")
                                .log("Summary metric unavailable");
                    }
                });
    }
}
