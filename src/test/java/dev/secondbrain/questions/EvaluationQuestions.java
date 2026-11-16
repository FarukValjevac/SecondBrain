package dev.secondbrain.questions;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.search.SearchService;

/** Test-only composition; keeps evaluation seams out of the application's public API. */
public final class EvaluationQuestions {
    private EvaluationQuestions() {}

    public static QuestionAnswerService answers(
            SearchService search,
            ChatProvider chat,
            DocumentInspectionService documents,
            QuestionTelemetry telemetry) {
        return new QuestionAnswerService(
                new QuestionService(search, chat, telemetry), documents, telemetry);
    }
}
