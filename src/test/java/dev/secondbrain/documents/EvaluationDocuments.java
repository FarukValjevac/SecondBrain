package dev.secondbrain.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Test-only composition of the real source visibility reader; no application workers. */
public final class EvaluationDocuments {
    private EvaluationDocuments() {}

    public static DocumentInspectionService inspection(
            DataSource source, PlatformTransactionManager manager) {
        return new DocumentInspectionService(
                new JdbcDocumentRepository(JdbcClient.create(source)),
                new JdbcExtractedTextRepository(new JdbcTemplate(source), manager),
                manager);
    }
}
