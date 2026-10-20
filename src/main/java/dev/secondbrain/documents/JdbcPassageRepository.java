package dev.secondbrain.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/** Immutable staged passages. The caller owns the stage/event/acknowledgment transaction. */
final class JdbcPassageRepository {
    private final JdbcTemplate jdbc;
    private final JdbcIndexingRepository indexes;
    private final TransactionTemplate mandatory;

    JdbcPassageRepository(
            JdbcTemplate jdbc,
            JdbcIndexingRepository indexes,
            PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        mandatory = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** Returns false for an identical stored snapshot; conflicting/stale input is rejected. */
    boolean insert(PreparedPassages prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.passages().size() > PassageSplitter.MAXIMUM_PASSAGES) {
            throw new IllegalArgumentException("Document exceeds the passage count limit");
        }
        // Construct/validate all values before writing any rows.
        var passages =
                prepared.passages().stream()
                        .map(passage -> StoredPassage.from(prepared.version(), passage))
                        .toList();
        return Objects.requireNonNull(
                mandatory.execute(
                        status -> {
                            var version = prepared.version();
                            var current =
                                    indexes.lockEligibleDesired(
                                            version.documentId(), version.number());
                            if (current.isEmpty()
                                    || current.orElseThrow().extractionVersion()
                                            != version.extractionVersion()) {
                                throw new IllegalStateException(
                                        "Passage index generation is no longer eligible");
                            }
                            var configuration =
                                    jdbc.queryForObject(
                                            """
                    SELECT passage_configuration_version FROM document_index_versions
                    WHERE document_id = ? AND indexing_version = ?
                    """,
                                            Integer.class,
                                            version.documentId(),
                                            version.number());
                            if (configuration != null) {
                                if (configuration != prepared.configurationVersion()
                                        || !findByDocumentVersion(
                                                        version.documentId(), version.number())
                                                .equals(passages)) {
                                    throw new IllegalStateException(
                                            "Stored passage snapshot cannot change within an index generation");
                                }
                                return false;
                            }
                            jdbc.batchUpdate(
                                    """
                    INSERT INTO document_passages
                        (passage_id, document_id, indexing_version, sequence_number, passage_text,
                         start_line, start_column, end_line, end_column, page_number)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                                    passages,
                                    100,
                                    (statement, passage) -> {
                                        statement.setObject(1, passage.passageId());
                                        statement.setObject(2, passage.documentId());
                                        statement.setLong(3, passage.indexingVersion());
                                        statement.setInt(4, passage.sequence());
                                        statement.setString(5, passage.text());
                                        statement.setInt(6, passage.location().startLine());
                                        statement.setInt(7, passage.location().startColumn());
                                        statement.setInt(8, passage.location().endLine());
                                        statement.setInt(9, passage.location().endColumn());
                                        if (passage.location().pageNumber().isPresent()) {
                                            statement.setInt(
                                                    10, passage.location().pageNumber().getAsInt());
                                        } else {
                                            statement.setNull(10, Types.INTEGER);
                                        }
                                    });
                            jdbc.update(
                                    """
                    UPDATE document_index_versions
                    SET passage_configuration_version = ?, passage_count = ?
                    WHERE document_id = ? AND indexing_version = ?
                    """,
                                    prepared.configurationVersion(),
                                    passages.size(),
                                    version.documentId(),
                                    version.number());
                            return true;
                        }));
    }

    /** Internal staging read, including old generations; never a search/visibility projection. */
    List<StoredPassage> findByDocumentVersion(UUID documentId, long version) {
        Objects.requireNonNull(documentId, "documentId");
        if (version < 1) throw new IllegalArgumentException("Index version must be positive");
        return Objects.requireNonNull(
                jdbc.query(
                        """
                SELECT i.passage_configuration_version, i.passage_count, p.*
                FROM document_index_versions i
                LEFT JOIN document_passages p ON p.document_id = i.document_id
                    AND p.indexing_version = i.indexing_version
                WHERE i.document_id = ? AND i.indexing_version = ?
                ORDER BY p.sequence_number
                """,
                        statement -> {
                            statement.setQueryTimeout(5);
                            statement.setObject(1, documentId);
                            statement.setLong(2, version);
                        },
                        JdbcPassageRepository::readPassages));
    }

    private static List<StoredPassage> readPassages(ResultSet rows) throws SQLException {
        if (!rows.next()) return List.of();
        var count = rows.getObject("passage_count", Integer.class);
        if (count == null && rows.getObject("passage_id") == null) return List.of();
        var passages = new ArrayList<StoredPassage>();
        do {
            if (count == null
                    || rows.getObject("passage_id") == null
                    || rows.getInt("sequence_number") != passages.size() + 1) {
                throw new IllegalStateException("Stored passage snapshot is incomplete");
            }
            var page = rows.getObject("page_number", Integer.class);
            passages.add(
                    new StoredPassage(
                            rows.getObject("passage_id", UUID.class),
                            rows.getObject("document_id", UUID.class),
                            rows.getLong("indexing_version"),
                            rows.getInt("sequence_number"),
                            rows.getString("passage_text"),
                            new PreparedPassages.Location(
                                    rows.getInt("start_line"),
                                    rows.getInt("start_column"),
                                    rows.getInt("end_line"),
                                    rows.getInt("end_column"),
                                    page == null ? OptionalInt.empty() : OptionalInt.of(page))));
        } while (rows.next());
        if (passages.size() != count)
            throw new IllegalStateException("Stored passage snapshot is incomplete");
        return List.copyOf(passages);
    }
}
