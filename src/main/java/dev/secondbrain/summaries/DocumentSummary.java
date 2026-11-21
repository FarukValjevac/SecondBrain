package dev.secondbrain.summaries;

import dev.secondbrain.documents.DocumentInspectionService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A stored summary with its numbered sources, resolved against the current active index. */
public record DocumentSummary(
        String text, Instant generatedAt, List<DocumentInspectionService.PassageSource> sources) {
    public DocumentSummary {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(generatedAt, "generatedAt");
        sources = List.copyOf(sources);
    }

    @Override
    public String toString() {
        // Summary text and sources are private document content.
        return "DocumentSummary[sources=" + sources.size() + "]";
    }
}
