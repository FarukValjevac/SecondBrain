package dev.secondbrain.web;

import dev.secondbrain.conversations.Conversation;
import dev.secondbrain.conversations.ConversationService;
import dev.secondbrain.search.SearchQuery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved conversations for the ask flow: the previous question as context for a follow-up, and
 * saving one answered turn. Saving is a convenience: when chats are off or the database refuses,
 * the answer is still shown, just not saved.
 */
final class ChatTurns {
    private final ObjectProvider<ConversationService> conversations;

    ChatTurns(ObjectProvider<ConversationService> conversations) {
        this.conversations = conversations;
    }

    /** The conversation's last question, used only to help retrieval for a follow-up. */
    Optional<String> previousQuestion(Optional<UUID> conversation) {
        var service = conversations.getIfAvailable();
        if (service == null || conversation.isEmpty()) return Optional.empty();
        try {
            return service.lastQuestion(conversation.get());
        } catch (DataAccessException | TransactionException unavailable) {
            return Optional.empty();
        }
    }

    /** Saves a turn that belongs in a chat; returns the chat it was saved in. */
    Optional<UUID> save(Optional<UUID> conversation, QuestionController.Asked asked) {
        var service = conversations.getIfAvailable();
        if (service == null || !asked.saveable()) return Optional.empty();
        var sources = new ArrayList<Conversation.SourceRef>();
        asked.answer()
                .ifPresent(
                        answer -> {
                            for (var source : answer.citations())
                                sources.add(
                                        new Conversation.SourceRef(
                                                source.documentId(), source.passageId()));
                        });
        try {
            return Optional.of(
                    service.save(
                            conversation,
                            asked.question().orElseThrow(),
                            asked.scope().orElseThrow(),
                            asked.feedback() == QuestionFeedback.ANSWERED
                                    ? Conversation.Outcome.ANSWERED
                                    : Conversation.Outcome.INSUFFICIENT,
                            asked.answer().map(answer -> answer.text()).orElse(""),
                            sources));
        } catch (DataAccessException
                | TransactionException
                | IllegalArgumentException
                | IllegalStateException unsaved) {
            return Optional.empty();
        }
    }

    /**
     * Adds the previous question so a follow-up like "and why?" finds the right passages. The
     * combined text stays within the question limit; the shown and saved question is unchanged.
     */
    static SearchQuery withContext(String previous, SearchQuery request) {
        var question = request.text();
        var prefix = "Earlier question in this conversation: ";
        var middle = "\n\nCurrent question: ";
        int budget =
                SearchQuery.MAXIMUM_CODE_POINTS
                        - question.codePointCount(0, question.length())
                        - prefix.length()
                        - middle.length();
        if (budget < 20) return request;
        var earlier = previous.strip();
        if (earlier.codePointCount(0, earlier.length()) > budget)
            earlier = earlier.substring(0, earlier.offsetByCodePoints(0, budget));
        return new SearchQuery(
                prefix + earlier + middle + question, request.scope(), request.limit());
    }
}
