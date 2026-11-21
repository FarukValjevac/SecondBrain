package dev.secondbrain.web;

import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.study.FlashcardService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Tells the shared page shell which optional areas exist, so navigation never dead-ends. */
@ControllerAdvice
class ShellModel {
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<FlashcardService> flashcards;

    ShellModel(
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<FlashcardService> flashcards) {
        this.answers = answers;
        this.flashcards = flashcards;
    }

    /** Which navigation entries are available in this configuration. */
    record Navigation(boolean ask, boolean study) {}

    @ModelAttribute("nav")
    Navigation navigation() {
        return new Navigation(
                answers.getIfAvailable() != null, flashcards.getIfAvailable() != null);
    }
}
