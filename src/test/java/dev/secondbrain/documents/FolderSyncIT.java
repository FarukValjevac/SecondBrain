package dev.secondbrain.documents;

import static dev.secondbrain.TestHttp.ready;

import static org.assertj.core.api.Assertions.assertThat;

import dev.secondbrain.SecondBrainApplication;
import dev.secondbrain.TestDatabase;
import dev.secondbrain.embeddings.EmbeddingModel;
import dev.secondbrain.embeddings.EmbeddingProvider;
import dev.secondbrain.embeddings.EmbeddingVector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Folder import through the real pipeline, with a deterministic embedding provider. */
@ExtendWith(OutputCaptureExtension.class)
class FolderSyncIT {
    private static final EmbeddingModel MODEL = new EmbeddingModel(EmbeddingModel.BGE_M3_ID, 1024);
    @TempDir private Path folder;
    @TempDir private Path outside;
    @TempDir private Path storage;

    @Test
    void importsOnceReplacesChangedFilesAfterTheyAreReadyAndRespectsDeletions(CapturedOutput output)
            throws Exception {
        write("notes/arraylist-private-name.md", "ArrayList keeps order.");
        write("hashset.txt", "HashSet keeps one copy.");
        write(".obsidian/settings.md", "Hidden settings.");
        write("tool.exe", "Not a document.");
        Files.writeString(outside.resolve("secret.md"), "Outside the folder.");
        Files.createSymbolicLink(folder.resolve("link.md"), outside.resolve("secret.md"));
        try (var postgres = TestDatabase.create()) {
            UUID replacement;
            try (var context = start(postgres)) {
                var sync = context.getBean(FolderSync.class);
                assertThat(sync.scan()).isEqualTo(2);
                ready(context, 2);
                // Unchanged files are never imported again.
                assertThat(sync.scan()).isZero();
                assertThat(names(context))
                        .containsExactlyInAnyOrder("arraylist-private-name.md", "hashset.txt");

                var original = documentFor(context, "notes/arraylist-private-name.md");
                write(
                        "notes/arraylist-private-name.md",
                        "ArrayList keeps order and permits duplicates.");
                assertThat(sync.scan()).isEqualTo(1);
                replacement = documentFor(context, "notes/arraylist-private-name.md");
                assertThat(replacement).isNotEqualTo(original);
                // The old version stays searchable until the new one is ready.
                assertThat(visible(context, original)).isTrue();
                ready(context, 3);
                sync.scan();
                assertThat(visible(context, original)).isFalse();
                assertThat(visible(context, replacement)).isTrue();

                // A document deleted in the app is not brought back by the next scan.
                var hashset = documentFor(context, "hashset.txt");
                context.getBean(DocumentDeletionService.class).request(hashset, UUID.randomUUID());
                assertThat(sync.scan()).isZero();
                assertThat(visible(context, hashset)).isFalse();

                // Without remove-missing, deleting a file leaves its document alone.
                Files.delete(folder.resolve("notes/arraylist-private-name.md"));
                sync.scan();
                assertThat(visible(context, replacement)).isTrue();
                assertThat(sync.status().problem()).isEmpty();
                assertThat(sync.status().tracked()).isPositive();
            }

            // State lives in the database: after a restart nothing is imported twice, and with
            // remove-missing a file deleted from disk removes its document.
            try (var restarted = start(postgres, "--secondbrain.folder-sync.remove-missing=true")) {
                var again = restarted.getBean(FolderSync.class);
                assertThat(again.scan()).isZero();
                assertThat(visible(restarted, replacement)).isFalse();
            }
        }
        assertThat(output).doesNotContain("arraylist-private-name", "secret.md");
    }

    private void write(String relative, String text) throws Exception {
        var file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<String> names(ConfigurableApplicationContext context) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT original_name FROM documents WHERE deleted_at IS NULL")
                .query(String.class)
                .list();
    }

    private static UUID documentFor(ConfigurableApplicationContext context, String path) {
        return context.getBean(JdbcClient.class)
                .sql("SELECT document_id FROM folder_files WHERE relative_path = :path")
                .param("path", path)
                .query(UUID.class)
                .single();
    }

    private static boolean visible(ConfigurableApplicationContext context, UUID document) {
        return context.getBean(JdbcClient.class)
                        .sql(
                                "SELECT count(*) FROM documents WHERE document_id = :id AND deleted_at IS NULL")
                        .param("id", document)
                        .query(Integer.class)
                        .single()
                == 1;
    }

    private ConfigurableApplicationContext start(TestDatabase postgres, String... extra) {
        EmbeddingProvider embeddings =
                new EmbeddingProvider() {
                    @Override
                    public EmbeddingModel model() {
                        return MODEL;
                    }

                    @Override
                    public List<EmbeddingVector> embed(List<String> inputs) {
                        return inputs.stream()
                                .map(
                                        text -> {
                                            var values = new float[1024];
                                            values[0] = 1;
                                            return new EmbeddingVector(MODEL, values);
                                        })
                                .toList();
                    }
                };
        return new SpringApplicationBuilder(SecondBrainApplication.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .initializers(
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton("folderSyncEmbeddings", embeddings))
                .run(
                        postgres.arguments(
                                withExtra(
                                        extra,
                                        "--secondbrain.telemetry.otlp-endpoint=",
                                        "--secondbrain.embeddings.enabled=true",
                                        "--secondbrain.events.polling-enabled=true",
                                        "--secondbrain.events.poll-interval=50ms",
                                        "--secondbrain.folder-sync.directory=" + folder,
                                        // The test drives scans itself; the timer would only race
                                        // it.
                                        "--secondbrain.folder-sync.scan-interval=1h",
                                        "--secondbrain.documents.storage-directory=" + storage)));
    }

    private static String[] withExtra(String[] extra, String... settings) {
        var all = java.util.Arrays.copyOf(settings, settings.length + extra.length);
        System.arraycopy(extra, 0, all, settings.length, extra.length);
        return all;
    }
}
