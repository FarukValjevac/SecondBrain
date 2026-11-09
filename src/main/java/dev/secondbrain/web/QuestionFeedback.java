package dev.secondbrain.web;

import dev.secondbrain.questions.QuestionFailure;

import org.springframework.http.HttpStatus;

/** Fixed presentation messages; never use provider bodies, query text or exception messages. */
enum QuestionFeedback {
    ANSWERED(
            HttpStatus.OK,
            "Answer from your documents",
            "Inspect the source passages to assess the answer."),
    INSUFFICIENT_INFORMATION(
            HttpStatus.OK,
            "Insufficient information",
            "The selected documents do not provide enough evidence to answer this question. Try different wording or a broader scope."),
    EMPTY_SELECTION(
            HttpStatus.OK,
            "No documents selected",
            "Select one or more documents, or choose Whole library."),
    NO_INDEX(
            HttpStatus.OK,
            "No searchable documents",
            "This scope has no completed, compatible search index. Wait for indexing to finish or choose other documents."),
    INVALID_QUESTION(
            HttpStatus.BAD_REQUEST,
            "Check your question",
            "Enter a nonblank question of up to 1,200 characters. Your document selections are retained."),
    INVALID_FORM(
            HttpStatus.BAD_REQUEST,
            "Check the question form",
            "Choose a valid scope, select at most 100 documents and use a valid document page. Invalid form values have been cleared."),
    DISABLED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Questions unavailable",
            "Enable embeddings and questions and configure the provider, then restart SecondBrain. See the setup documentation."),
    DATABASE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Document storage unavailable",
            "Document information could not be read. Try again later; your valid question input is retained."),
    CONFIGURATION(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Question configuration needs attention",
            "Check the provider credentials, model and embedding configuration before trying again."),
    INVALID_CITATIONS(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Answer sources could not be verified",
            "The generated source references could not be validated. Submit a new question."),
    SOURCES_CHANGED(
            HttpStatus.CONFLICT,
            "Answer sources changed",
            "Evidence changed or became unavailable while answering. Ask again using current sources."),
    INVALID_RESPONSE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Unusable provider response",
            "The provider returned an unusable response. Try again later."),
    OUTPUT_LIMIT(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Answer was cut short",
            "The provider stopped before completing a valid answer. Try a narrower question."),
    PROVIDER(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Answer provider unavailable",
            "Check your connection and provider configuration, then submit a new question."),
    RATE_LIMIT(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests",
            "The provider is temporarily rate limiting requests. Wait before asking again."),
    QUOTA(
            HttpStatus.TOO_MANY_REQUESTS,
            "Free allowance exhausted",
            "The provider's shared free allowance has been exhausted. Wait until it resets before asking again."),
    TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "Question timed out",
            "The provider took too long. Try again later."),
    INTERRUPTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Question interrupted",
            "The question was interrupted. Submit a new question when you are ready.");

    private final HttpStatus status;
    private final String title;
    private final String message;

    QuestionFeedback(HttpStatus status, String title, String message) {
        this.status = status;
        this.title = title;
        this.message = message;
    }

    static QuestionFeedback from(QuestionFailure.Reason reason) {
        return switch (reason) {
            case NO_COMPATIBLE_INDEX -> NO_INDEX;
            case DATABASE_UNAVAILABLE -> DATABASE;
            case INCOMPATIBLE_MODEL, ACCESS_DENIED, MODEL_UNAVAILABLE, INVALID_INPUT ->
                    CONFIGURATION;
            case MISSING_CITATIONS, INVALID_CITATION, DUPLICATE_CITATION -> INVALID_CITATIONS;
            case SOURCE_UNAVAILABLE, INVALID_EVIDENCE -> SOURCES_CHANGED;
            case INVALID_EMBEDDING, INVALID_RESPONSE -> INVALID_RESPONSE;
            case OUTPUT_LIMIT -> OUTPUT_LIMIT;
            case RATE_LIMITED -> RATE_LIMIT;
            case QUOTA_EXHAUSTED -> QUOTA;
            case TIMED_OUT -> TIMEOUT;
            case INTERRUPTED -> INTERRUPTED;
            case UNAVAILABLE -> PROVIDER;
        };
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public boolean isError() {
        return status.isError();
    }

    public boolean isQueryInvalid() {
        return this == INVALID_QUESTION;
    }

    boolean acceptsRetryHint() {
        return this == RATE_LIMIT || this == QUOTA || this == PROVIDER;
    }
}
