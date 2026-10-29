package dev.secondbrain.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Stable isolated-test bindings. This short corpus deliberately fits one passage per document. */
record ReferenceIndex(
        RetrievalReferenceCollection collection,
        List<Document> documents,
        Map<String, UUID> sourcePassages) {
    static final long INDEXING_VERSION = 1;
    static final int PASSAGE_CONFIGURATION = 1;
    private static final int MAXIMUM_PASSAGE_CODE_POINTS = 1200;

    ReferenceIndex {
        Objects.requireNonNull(collection, "collection");
        documents = List.copyOf(documents);
        sourcePassages = Map.copyOf(sourcePassages);
    }

    static ReferenceIndex prepare(RetrievalReferenceCollection collection) throws IOException {
        collection.validate();
        var documents = new ArrayList<Document>();
        for (var input : collection.documents()) {
            var content = collection.content(input);
            var lines = content.lines().toList();
            var text = String.join("\n", lines);
            if (text.codePointCount(0, text.length()) > MAXIMUM_PASSAGE_CODE_POINTS)
                throw new IllegalArgumentException(
                        "Reference document needs multiple passages; update the binding fixture");
            var documentId =
                    stableId(
                            "secondbrain:reference:"
                                    + collection.corpusVersion()
                                    + ":"
                                    + input.id());
            var passageId =
                    stableId(
                            "secondbrain:passage:v1:" + documentId + ":" + INDEXING_VERSION + ":1");
            var last = lines.getLast();
            documents.add(
                    new Document(
                            input.id(),
                            documentId,
                            passageId,
                            input.file().substring("documents/".length()),
                            input.sha256(),
                            content.getBytes(StandardCharsets.UTF_8).length,
                            text,
                            new SearchResult.SourceLocation(
                                    1,
                                    0,
                                    lines.size(),
                                    last.codePointCount(0, last.length()),
                                    OptionalInt.empty())));
        }
        var sources = new HashMap<String, UUID>();
        for (var source : collection.sources()) {
            var owner =
                    documents.stream()
                            .filter(document -> document.label().equals(source.document()))
                            .findFirst()
                            .orElseThrow();
            if (!owner.text().contains(source.quote()))
                throw new IllegalArgumentException(
                        "Reference passage does not contain its gold evidence");
            sources.put(source.id(), owner.passageId());
        }
        return new ReferenceIndex(collection, documents, sources);
    }

    Document document(String label) {
        return documents.stream()
                .filter(document -> document.label().equals(label))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("Unknown reference document label"));
    }

    Set<UUID> passagesForSources(List<String> labels) {
        var ids = new HashSet<UUID>();
        for (var label : labels) {
            var id = sourcePassages.get(label);
            if (id == null) throw new IllegalArgumentException("Unknown reference source label");
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    SearchScope scopeFor(RetrievalReferenceCollection.Scope scope) {
        if (scope.mode() == RetrievalReferenceCollection.Mode.ALL) {
            if (!scope.documents().isEmpty())
                throw new IllegalArgumentException(
                        "Whole-library scope cannot contain document labels");
            return SearchScope.all();
        }
        return SearchScope.selected(
                scope.documents().stream()
                        .map(label -> document(label).documentId())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "ReferenceIndex[corpus=" + collection.corpusVersion() + "]";
    }

    record Document(
            String label,
            UUID documentId,
            UUID passageId,
            String name,
            String sha256,
            long originalSize,
            String text,
            SearchResult.SourceLocation location) {
        Document {
            Objects.requireNonNull(label);
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(passageId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(sha256);
            Objects.requireNonNull(text);
            Objects.requireNonNull(location);
        }

        UUID storageId() {
            return stableId("secondbrain:reference:original:" + documentId);
        }

        String type() {
            return name.endsWith(".md") ? "MARKDOWN" : "TXT";
        }

        @Override
        public String toString() {
            return "ReferenceDocument[label=" + label + "]";
        }
    }
}
