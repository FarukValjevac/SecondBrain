package dev.secondbrain.web;

import dev.secondbrain.search.SearchFailure;

import org.springframework.http.HttpStatus;

/** Closed presentation policy: no provider messages or private request data enter feedback. */
enum SearchFeedback {
    SUCCESS(HttpStatus.OK, "Search completed", "Open a passage to inspect its source."),
    EMPTY_SELECTION(
            HttpStatus.OK,
            "No documents selected",
            "Select one or more documents, or choose Whole library."),
    NO_MATCHES(
            HttpStatus.OK,
            "No matching passages",
            "Try different wording or choose a broader document scope."),
    SOURCES_CHANGED(
            HttpStatus.OK,
            "Search sources changed",
            "The matching sources are no longer available. Search again for current passages."),
    NO_INDEX(
            HttpStatus.OK,
            "No searchable documents",
            "This scope has no completed, compatible search index. Wait for indexing to finish or choose other documents."),
    INVALID_QUERY(
            HttpStatus.BAD_REQUEST,
            "Check your query",
            "Enter a nonblank query of up to 1,200 characters. Your document selections are retained."),
    INVALID_FORM(
            HttpStatus.BAD_REQUEST,
            "Check the search form",
            "Enter a valid query, choose a scope and select at most 100 documents and use a valid document page. Invalid form values have been cleared; start a new search if needed."),
    DISABLED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Search unavailable",
            "Enable embeddings and configure the provider, then restart SecondBrain. See the setup documentation."),
    DATABASE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Document storage unavailable",
            "Document information could not be read. Try again later; your valid search input is retained."),
    CONFIGURATION(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Search configuration needs attention",
            "The embedding model is incompatible with the saved index. Check the embedding configuration before retrying."),
    INVALID_RESPONSE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Unusable embedding response",
            "The embedding provider returned an unusable response. Try again later."),
    PROVIDER(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Embedding provider unavailable",
            "Check your connection and provider configuration, then submit a new search."),
    RATE_LIMIT(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests",
            "The embedding provider is temporarily rate limiting requests. Wait before submitting another search."),
    QUOTA(
            HttpStatus.TOO_MANY_REQUESTS,
            "Free allowance exhausted",
            "The provider's free allowance has been exhausted. Wait until it resets before searching again."),
    TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "Search timed out",
            "The embedding request took too long. Try again later."),
    INTERRUPTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Search interrupted",
            "The search was interrupted. Submit a new search when you are ready.");

    private final HttpStatus status;
    private final String title;
    private final String message;

    SearchFeedback(HttpStatus status, String title, String message) {
        this.status = status;
        this.title = title;
        this.message = message;
    }

    static SearchFeedback from(SearchFailure.Reason reason) {
        return switch (reason) {
            case NO_COMPATIBLE_INDEX -> NO_INDEX;
            case DATABASE_UNAVAILABLE -> DATABASE;
            case INCOMPATIBLE_MODEL -> CONFIGURATION;
            case INVALID_EMBEDDING -> INVALID_RESPONSE;
            case PROVIDER_UNAVAILABLE -> PROVIDER;
            case RATE_LIMITED -> RATE_LIMIT;
            case QUOTA_EXHAUSTED -> QUOTA;
            case TIMED_OUT -> TIMEOUT;
            case INTERRUPTED -> INTERRUPTED;
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
        return this == INVALID_QUERY;
    }

    boolean acceptsRetryHint() {
        return this == RATE_LIMIT || this == QUOTA || this == PROVIDER;
    }
}
