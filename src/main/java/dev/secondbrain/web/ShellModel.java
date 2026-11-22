package dev.secondbrain.web;

import dev.secondbrain.folders.FolderService;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.study.FlashcardService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Tells the shared page shell which optional areas exist, so navigation never dead-ends. */
@ControllerAdvice
class ShellModel {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<FlashcardService> flashcards;
    private final ObjectProvider<FolderService> folders;

    ShellModel(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<FlashcardService> flashcards,
            ObjectProvider<FolderService> folders) {
        this.answers = answers;
        this.flashcards = flashcards;
        this.folders = folders;
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
    record Navigation(boolean ask, boolean study) {}

    @ModelAttribute("nav")
    Navigation navigation() {
        return new Navigation(
                answers.getIfAvailable() != null, flashcards.getIfAvailable() != null);
    }

    private static final FileTypeLabel FILE_TYPE = new FileTypeLabel();

    @ModelAttribute("fileType")
    FileTypeLabel fileType() {
        return FILE_TYPE;
    }
}
