package dev.secondbrain.search;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Non-sensitive gold labels and source bytes, not an embedding provider or quality evaluator. */
record RetrievalReferenceCollection(
        int schemaVersion,
        String corpusVersion,
        List<Document> documents,
        List<Source> sources,
        List<QueryCase> cases) {
    private static final String ROOT = "/retrieval/reference-v1/";
    private static final int MAXIMUM_RESOURCE_BYTES = 65_536;

    RetrievalReferenceCollection {
        Objects.requireNonNull(corpusVersion, "corpusVersion");
        documents = List.copyOf(documents);
        sources = List.copyOf(sources);
        cases = List.copyOf(cases);
    }

    static RetrievalReferenceCollection load() throws IOException {
        var collection =
                JsonMapper.builder()
                        .build()
                        .readValue(resource("collection.json"), RetrievalReferenceCollection.class);
        collection.validate();
        return collection;
    }

    void validate() throws IOException {
        require(
                schemaVersion == 1 && corpusVersion.equals("java-study-v1"),
                "Unsupported reference version");
        require(!documents.isEmpty() && documents.size() <= 10, "Invalid reference document count");
        require(!sources.isEmpty() && sources.size() <= 32, "Invalid reference source count");
        require(!cases.isEmpty() && cases.size() <= 32, "Invalid reference case count");
        var texts = validateDocuments();
        var labeledSources = validateSources(texts);
        var caseIds = new HashSet<String>();
        var coveredKinds = EnumSet.noneOf(Kind.class);
        var usedSources = new HashSet<String>();
        for (var query : cases) {
            identifier(query.id());
            require(caseIds.add(query.id()), "Duplicate case label");
            require(
                    new SearchQuery(query.query(), SearchScope.all()).text().equals(query.query()),
                    "Reference query is not normalized");
            validateScope(query, texts.keySet());
            validateEvidence(query, labeledSources);
            coveredKinds.add(query.kind());
            usedSources.addAll(query.expectedSources());
        }
        require(
                coveredKinds.equals(EnumSet.allOf(Kind.class)),
                "Reference case coverage is incomplete");
        require(usedSources.equals(labeledSources.keySet()), "Unused reference source");
        require(
                sources.stream()
                        .map(Source::document)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(texts.keySet()),
                "Document has no labeled evidence");
    }

    private Map<String, List<String>> validateDocuments() throws IOException {
        var documentIds = new HashSet<String>();
        var files = new HashSet<String>();
        var texts = new HashMap<String, List<String>>();
        for (var document : documents) {
            identifier(document.id());
            require(documentIds.add(document.id()), "Duplicate document label");
            require(files.add(document.file()), "Duplicate document file");
            texts.put(document.id(), content(document).lines().toList());
        }
        return texts;
    }

    private Map<String, Source> validateSources(Map<String, List<String>> texts) {
        var labeledSources = new HashMap<String, Source>();
        for (var source : sources) {
            identifier(source.id());
            require(
                    labeledSources.putIfAbsent(source.id(), source) == null,
                    "Duplicate source label");
            var lines = texts.get(source.document());
            require(lines != null, "Source refers to an unknown document");
            require(
                    source.startLine() >= 1
                            && source.endLine() >= source.startLine()
                            && source.endLine() <= lines.size(),
                    "Source line range is outside its document");
            require(
                    !source.quote().isBlank()
                            && source.quote()
                                    .equals(
                                            String.join(
                                                    "\n",
                                                    lines.subList(
                                                            source.startLine() - 1,
                                                            source.endLine()))),
                    "Source quote does not match its line range");
        }
        return labeledSources;
    }

    private static void validateScope(QueryCase query, java.util.Set<String> documentIds) {
        require(
                query.scope().mode() == Mode.SELECTED || query.scope().documents().isEmpty(),
                "All-library scope cannot contain a filter");
        uniqueKnown(query.scope().documents(), documentIds, "Unknown or duplicate filter document");
        uniqueKnown(query.deletedDocuments(), documentIds, "Unknown or duplicate deleted document");
        require(
                query.kind() == Kind.DELETION
                        ? !query.deletedDocuments().isEmpty()
                        : query.deletedDocuments().isEmpty(),
                "Deletion action does not match case kind");
        if (query.kind() == Kind.EMPTY_SELECTION)
            require(
                    query.scope().mode() == Mode.SELECTED && query.scope().documents().isEmpty(),
                    "Empty-selection case must stay explicitly empty");
        if (query.kind() == Kind.DOCUMENT_FILTER)
            require(
                    query.scope().mode() == Mode.SELECTED && !query.scope().documents().isEmpty(),
                    "Filter case requires selected documents");
    }

    private static void validateEvidence(QueryCase query, Map<String, Source> labeledSources) {
        require(
                query.evidence() == Evidence.ABSENT
                        ? query.expectedSources().isEmpty() && query.expectedFacts().isEmpty()
                        : !query.expectedSources().isEmpty() && !query.expectedFacts().isEmpty(),
                "Evidence expectation is inconsistent");
        uniqueKnown(
                query.expectedSources(),
                labeledSources.keySet(),
                "Unknown or duplicate expected source");
        for (var label : query.expectedSources()) {
            var source = labeledSources.get(label);
            require(
                    !query.deletedDocuments().contains(source.document()),
                    "Expected source was deleted");
            require(
                    query.scope().mode() == Mode.ALL
                            || query.scope().documents().contains(source.document()),
                    "Expected source is outside selected scope");
        }
        require(
                new HashSet<>(query.expectedFacts()).size() == query.expectedFacts().size(),
                "Duplicate expected fact");
        for (var fact : query.expectedFacts())
            require(
                    !fact.isBlank()
                            && query.expectedSources().stream()
                                    .anyMatch(
                                            label ->
                                                    labeledSources
                                                            .get(label)
                                                            .quote()
                                                            .contains(fact)),
                    "Expected fact is not anchored in labeled evidence");
        for (var label : query.expectedSources())
            require(
                    query.expectedFacts().stream()
                            .anyMatch(fact -> labeledSources.get(label).quote().contains(fact)),
                    "Expected source has no supporting fact");
        if (query.kind() == Kind.CROSS_DOCUMENT || query.kind() == Kind.SIMILAR_DOCUMENTS)
            require(
                    query.expectedSources().stream()
                                    .map(label -> labeledSources.get(label).document())
                                    .distinct()
                                    .count()
                            >= 2,
                    "Comparison requires evidence from multiple documents");
        if (query.kind() == Kind.ABSENT_EVIDENCE || query.kind() == Kind.EMPTY_SELECTION)
            require(query.evidence() == Evidence.ABSENT, "Absent case cannot expect evidence");
    }

    String content(Document document) throws IOException {
        require(
                document.file().matches("documents/[a-z][a-z0-9-]*\\.(txt|md)"),
                "Unsafe reference resource path");
        require(document.sha256().matches("[a-f0-9]{64}"), "Invalid reference checksum");
        var bytes = resource(document.file());
        try {
            require(
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                            .equals(document.sha256()),
                    "Reference document checksum changed");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
        var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        require(
                !text.isBlank()
                        && text.endsWith("\n")
                        && !text.contains("\r")
                        && text.indexOf('\0') < 0,
                "Reference documents require nonempty UTF-8 and LF lines");
        return text;
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = RetrievalReferenceCollection.class.getResourceAsStream(ROOT + name)) {
            if (input == null) throw new IOException("Missing reference resource");
            var bytes = input.readNBytes(MAXIMUM_RESOURCE_BYTES + 1);
            require(bytes.length <= MAXIMUM_RESOURCE_BYTES, "Reference resource exceeds budget");
            return bytes;
        }
    }

    private static void identifier(String value) {
        require(value.matches("[a-z][a-z0-9-]{0,63}"), "Invalid reference label");
    }

    private static void uniqueKnown(
            List<String> values, java.util.Set<String> known, String message) {
        require(
                new HashSet<>(values).size() == values.size() && known.containsAll(values),
                message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    @Override
    public String toString() {
        return "ReferenceCollection[version=" + corpusVersion + ", cases=" + cases.size() + "]";
    }

    record Document(String id, String file, String sha256) {
        Document {
            Objects.requireNonNull(id);
            Objects.requireNonNull(file);
            Objects.requireNonNull(sha256);
        }

        @Override
        public String toString() {
            return "ReferenceDocument[id=" + id + "]";
        }
    }

    record Source(String id, String document, int startLine, int endLine, String quote) {
        Source {
            Objects.requireNonNull(id);
            Objects.requireNonNull(document);
            Objects.requireNonNull(quote);
        }

        @Override
        public String toString() {
            return "ReferenceSource[id=" + id + "]";
        }
    }

    record Scope(Mode mode, List<String> documents) {
        Scope {
            Objects.requireNonNull(mode);
            documents = List.copyOf(documents);
        }
    }

    record QueryCase(
            String id,
            Kind kind,
            String query,
            Scope scope,
            List<String> deletedDocuments,
            Evidence evidence,
            List<String> expectedSources,
            List<String> expectedFacts) {
        QueryCase {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(query);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(evidence);
            deletedDocuments = List.copyOf(deletedDocuments);
            expectedSources = List.copyOf(expectedSources);
            expectedFacts = List.copyOf(expectedFacts);
        }

        @Override
        public String toString() {
            return "ReferenceCase[id=" + id + ", kind=" + kind + "]";
        }
    }

    enum Mode {
        ALL,
        SELECTED
    }

    enum Evidence {
        EXPECTED,
        ABSENT
    }

    enum Kind {
        DIRECT,
        PARAPHRASE,
        SIMILAR_DOCUMENTS,
        CROSS_DOCUMENT,
        ABSENT_EVIDENCE,
        DOCUMENT_FILTER,
        EMPTY_SELECTION,
        DELETION
    }
}
