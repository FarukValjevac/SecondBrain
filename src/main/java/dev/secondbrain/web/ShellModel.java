package dev.secondbrain.web;

import dev.secondbrain.conversations.Conversation;
import dev.secondbrain.conversations.ConversationService;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.questions.QuestionAnswerService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Tells the shared page shell which optional areas exist, so navigation never dead-ends. */
@ControllerAdvice
class ShellModel {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<FolderService> folders;
    private final ObjectProvider<ConversationService> conversations;

    ShellModel(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<FolderService> folders,
            ObjectProvider<ConversationService> conversations) {
        this.answers = answers;
        this.folders = folders;
        this.conversations = conversations;
    }

    /** The most recent chats for the sidebar, titles only; empty when unavailable. */
    @ModelAttribute("recentChats")
    java.util.List<Conversation.Summary> recentChats() {
        var service = conversations.getIfAvailable();
        if (service == null) return java.util.List.of();
        try {
            return service.recent(12);
        } catch (DataAccessException | TransactionException unavailable) {
            return java.util.List.of();
        }
    }

    /**
     * Every folder (at most 200) for navigation and folder choosers. Folders are a convenience:
     * when they are off or cannot be read, pages render without them.
     */
    @ModelAttribute("folderTree")
    FolderService.FolderTree folderTree() {
        var service = folders.getIfAvailable();
        if (service == null) return null;
        try {
            return service.tree();
        } catch (DataAccessException | TransactionException unavailable) {
            return null;
        }
    }

    /** Which navigation entries are available in this configuration. */
    record Navigation(boolean ask) {}

    @ModelAttribute("nav")
    Navigation navigation() {
        return new Navigation(answers.getIfAvailable() != null);
    }

    private static final FileTypeLabel FILE_TYPE = new FileTypeLabel();

    @ModelAttribute("fileType")
    FileTypeLabel fileType() {
        return FILE_TYPE;
    }

    private final DateLabels dates = new DateLabels(java.time.Clock.systemDefaultZone());

    /** Readable dates for lists, in this computer's time zone. */
    @ModelAttribute("dates")
    DateLabels dates() {
        return dates;
    }
}
