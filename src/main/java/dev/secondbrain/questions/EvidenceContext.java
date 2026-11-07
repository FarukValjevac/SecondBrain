package dev.secondbrain.questions;

import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchScope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact provider evidence plus its retained source metadata, selected without I/O. */
record EvidenceContext(ChatProvider.Request request, List<SearchResult.Match> sources) {
    EvidenceContext {
        Objects.requireNonNull(request, "request");
        sources = List.copyOf(sources);
        if (request.evidence().size() != sources.size())
            throw new IllegalArgumentException("Evidence and source counts differ");
        for (int index = 0; index < sources.size(); index++) {
            var passage = request.evidence().get(index);
            var source = sources.get(index);
            if (!passage.passageId().equals(source.passageId())
                    || !passage.text().equals(source.text()))
                throw new IllegalArgumentException("Evidence does not match its source");
        }
    }

    static Optional<EvidenceContext> select(SearchQuery query, SearchResult retrieved) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(retrieved, "retrieved");
        if (query.scope() instanceof SearchScope.SelectedDocuments selected
                && selected.documentIds().isEmpty()) return Optional.empty();
        if (retrieved.matches().size() > query.limit())
            throw new IllegalArgumentException("Retrieved evidence exceeds the query limit");
        if (query.scope() instanceof SearchScope.SelectedDocuments selected) {
            for (var match : retrieved.matches())
                if (!selected.documentIds().contains(match.documentId()))
                    throw new IllegalArgumentException(
                            "Retrieved evidence is outside the selected scope");
        }

        int remainingBytes =
                ChatProvider.Request.MAXIMUM_INPUT_BYTES
                        - query.text().getBytes(StandardCharsets.UTF_8).length;
        var passages = new ArrayList<ChatProvider.Passage>();
        var sources = new ArrayList<SearchResult.Match>();
        for (var match : retrieved.matches()) {
            if (passages.size() == ChatProvider.Request.MAXIMUM_PASSAGES) break;
            int bytes = match.text().getBytes(StandardCharsets.UTF_8).length;
            // Keep the original passage and its coordinates intact; try a later whole passage.
            if (bytes > remainingBytes) continue;
            passages.add(new ChatProvider.Passage(match.passageId(), match.text()));
            sources.add(match);
            remainingBytes -= bytes;
        }
        return passages.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        new EvidenceContext(
                                new ChatProvider.Request(query.text(), passages), sources));
    }

    @Override
    public String toString() {
        return "EvidenceContext[passages=" + sources.size() + "]";
    }
}
