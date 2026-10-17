package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

class JdbcDocumentRepositoryTest {
    @Test
    void bindsDocumentTimesAsJdbcTimestampsWithoutLosingTheInstant() throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        // Model the pgJDBC restriction at the driver boundary, not a mocked repository.
        doAnswer(
                        invocation -> {
                            if (invocation.getArgument(1) instanceof Instant) {
                                throw new SQLException("Unsupported Instant binding", "07006");
                            }
                            return null;
                        })
                .when(statement)
                .setObject(anyInt(), any());

        var createdAt = Instant.parse("2026-10-16T12:00:00.123456Z");
        var updatedAt = createdAt.plusSeconds(5);
        var document =
                new DocumentMetadata(
                        UUID.randomUUID(),
                        "notes.txt",
                        DocumentType.TXT,
                        UUID.randomUUID(),
                        "a".repeat(64),
                        27,
                        1,
                        ExtractionState.QUEUED,
                        createdAt,
                        updatedAt);

        new JdbcDocumentRepository(JdbcClient.create(dataSource)).insert(document);

        var created = ArgumentCaptor.forClass(Timestamp.class);
        var updated = ArgumentCaptor.forClass(Timestamp.class);
        verify(statement).setObject(eq(9), created.capture());
        verify(statement).setObject(eq(10), updated.capture());
        assertThat(created.getValue().toInstant()).isEqualTo(createdAt);
        assertThat(updated.getValue().toInstant()).isEqualTo(updatedAt);
        verify(statement).executeUpdate();
    }
}
