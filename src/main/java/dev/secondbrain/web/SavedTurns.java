package dev.secondbrain.web;

import dev.secondbrain.conversations.Conversation;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.questions.CitationMarkers;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * View of saved turns: the stored answer with its citation markers, and each cited passage looked
 * up again now. A passage whose document was deleted shows as unavailable.
 */
public final class SavedTurns {
    private SavedTurns() {}

    /** One cited source; {@code passage} is null when it is no longer available. */
    public record Source(int number, DocumentInspectionService.PassageSource passage) {
        public boolean isAvailable() {
            return passage != null;
        }
    }

    public record Turn(
            int position,
            String question,
            boolean answered,
            List<CitationMarkers.Segment> segments,
            List<Source> sources) {
        public Turn {
            segments = List.copyOf(segments);
            sources = List.copyOf(sources);
        }
    }

    static List<Turn> of(Conversation conversation, DocumentInspectionService documents) {
        var turns = new ArrayList<Turn>();
        for (var turn : conversation.turns()) {
            var owners = new LinkedHashMap<java.util.UUID, java.util.UUID>();
            for (var source : turn.sources()) owners.put(source.passageId(), source.documentId());
            java.util.Map<java.util.UUID, DocumentInspectionService.PassageSource> found;
            try {
                found = documents == null ? java.util.Map.of() : documents.sources(owners);
            } catch (DataAccessException
                    | TransactionException
                    | IllegalArgumentException missing) {
                found = java.util.Map.of();
            }
            var sources = new ArrayList<Source>();
            int number = 1;
            for (var source : turn.sources())
                sources.add(new Source(number++, found.get(source.passageId())));
            boolean answered = turn.outcome() == Conversation.Outcome.ANSWERED;
            turns.add(
                    new Turn(
                            turn.position(),
                            turn.question(),
                            answered,
                            answered
                                    ? CitationMarkers.segments(turn.answer(), sources.size())
                                    : List.of(),
                            sources));
        }
        return turns;
    }
}
