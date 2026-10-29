package dev.secondbrain.search;

import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

/**
 * Published read fixtures for databases owned by evaluation Testcontainers, never the local
 * library.
 */
record ReferenceDatabase(JdbcClient jdbc, JdbcTransactionManager transactions) {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);

    static ReferenceDatabase migrate(DataSource source) {
        Flyway.configure().dataSource(source).load().migrate();
        return new ReferenceDatabase(JdbcClient.create(source), new JdbcTransactionManager(source));
    }

    void seed(ReferenceIndex index, List<EmbeddingVector> vectors, Instant at) {
        if (vectors.size() != index.documents().size()
                || vectors.stream().anyMatch(vector -> !MODEL.equals(vector.model())))
            throw new IllegalArgumentException(
                    "Reference vectors must match the collection and model");
        var write = new TransactionTemplate(transactions);
        write.setTimeout(5);
        write.executeWithoutResult(
                status -> {
                    for (int i = 0; i < index.documents().size(); i++)
                        seed(index.documents().get(i), vectors.get(i), at);
                });
    }

    void applyDeletions(ReferenceIndex index, List<String> labels, Instant at) {
        var write = new TransactionTemplate(transactions);
        write.setTimeout(5);
        write.executeWithoutResult(
                status -> {
                    jdbc.sql("UPDATE documents SET deleted_at = NULL").update();
                    for (var label : labels)
                        jdbc.sql("UPDATE documents SET deleted_at = :at WHERE document_id = :id")
                                .param("at", Timestamp.from(at))
                                .param("id", index.document(label).documentId())
                                .update();
                });
    }

    private void seed(ReferenceIndex.Document document, EmbeddingVector direction, Instant at) {
        jdbc.sql(
                        """
                INSERT INTO documents(document_id, original_name, document_type, storage_key, content_sha256,
                    original_size_bytes, processing_version, extraction_state, created_at, updated_at)
                VALUES (:id, :name, :type, :storage, :hash, :bytes, 1, 'EXTRACTED', :at, :at)
                """)
                .param("id", document.documentId())
                .param("name", document.name())
                .param("type", document.type())
                .param("storage", document.storageId())
                .param("hash", document.sha256())
                .param("bytes", document.originalSize())
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql(
                        "INSERT INTO document_extractions(document_id, processing_version, extracted_at) VALUES (:id, 1, :at)")
                .param("id", document.documentId())
                .param("at", Timestamp.from(at))
                .update();
        var lines = document.text().lines().toList();
        for (int i = 0; i < lines.size(); i++)
            jdbc.sql(
                            "INSERT INTO document_text_lines(document_id, processing_version, line_number, line_text) VALUES (:id, 1, :line, :text)")
                    .param("id", document.documentId())
                    .param("line", i + 1)
                    .param("text", lines.get(i))
                    .update();
        jdbc.sql(
                        """
                INSERT INTO document_index_versions(document_id, indexing_version, extraction_version, created_at,
                    passage_configuration_version, passage_count, embedding_model_id, embedding_dimensions)
                VALUES (:id, :version, 1, :at, :configuration, 1, :model, 1024)
                """)
                .param("id", document.documentId())
                .param("version", ReferenceIndex.INDEXING_VERSION)
                .param("at", Timestamp.from(at))
                .param("configuration", ReferenceIndex.PASSAGE_CONFIGURATION)
                .param("model", MODEL.id())
                .update();
        var location = document.location();
        jdbc.sql(
                        """
                INSERT INTO document_passages(passage_id, document_id, indexing_version, sequence_number,
                    passage_text, start_line, start_column, end_line, end_column, page_number)
                VALUES (:passage, :id, :version, 1, :text, :start, 0, :end, :column, NULL)
                """)
                .param("passage", document.passageId())
                .param("id", document.documentId())
                .param("version", ReferenceIndex.INDEXING_VERSION)
                .param("text", document.text())
                .param("start", location.startLine())
                .param("end", location.endLine())
                .param("column", location.endColumn())
                .update();
        jdbc.sql(
                        """
                INSERT INTO document_embeddings(passage_id, document_id, indexing_version, model_id, dimensions, embedding)
                VALUES (:passage, :id, :version, :model, 1024, CAST(:vector AS vector(1024)))
                """)
                .param("passage", document.passageId())
                .param("id", document.documentId())
                .param("version", ReferenceIndex.INDEXING_VERSION)
                .param("model", MODEL.id())
                .param("vector", java.util.Arrays.toString(direction.values()))
                .update();
        jdbc.sql(
                        "UPDATE documents SET active_indexing_version = :version, desired_indexing_version = :version WHERE document_id = :id")
                .param("version", ReferenceIndex.INDEXING_VERSION)
                .param("id", document.documentId())
                .update();
    }
}
