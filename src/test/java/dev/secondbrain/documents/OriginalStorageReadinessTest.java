package dev.secondbrain.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(
        classes = OriginalStorageReadinessTest.HealthApplication.class,
        properties = {
            "spring.config.import=",
            // This fixture isolates real storage from database/worker availability.
            "management.endpoint.health.group.readiness.include=readinessState,originalStorage"
        })
@AutoConfigureMockMvc
class OriginalStorageReadinessTest {
    @TempDir static Path temporaryDirectory;
    private final MockMvc mvc;

    @Autowired
    OriginalStorageReadinessTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "secondbrain.documents.storage-directory", () -> storageDirectory().toString());
    }

    @AfterEach
    void removeOnlyTheTemporaryFixture() throws IOException {
        var directory = storageDirectory();
        if (Files.isDirectory(directory)) {
            try (var entries = Files.newDirectoryStream(directory)) {
                for (var entry : entries) {
                    Files.delete(entry);
                }
            }
        }
        Files.deleteIfExists(directory);
    }

    @Test
    void createsPrivateStorageAndLeavesNoProbeFilesOrChangedOriginals() throws Exception {
        expectHealth("/actuator/health/readiness", 200, "UP");
        var directory = storageDirectory();
        assertThat(directory).isDirectory();
        try (var entries = Files.list(directory)) {
            assertThat(entries).isEmpty();
        }
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(directory))
                    .isEqualTo(
                            Set.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE));
        }
        var original = directory.resolve(UUID.randomUUID() + ".original");
        Files.writeString(original, "sentinel-private-document");
        expectHealth("/actuator/health/readiness", 200, "UP");
        expectHealth("/actuator/health/liveness", 200, "UP");
        assertThat(Files.readString(original)).isEqualTo("sentinel-private-document");
        try (var entries = Files.list(directory)) {
            assertThat(entries).containsExactly(original);
        }
    }

    @Test
    void storageFailureChangesReadinessAndRecoversWithoutChangingLiveness() throws Exception {
        var blocker = Files.writeString(storageDirectory(), "sentinel-private-blocker");
        expectHealth("/actuator/health/readiness", 503, "DOWN");
        expectHealth("/actuator/health/liveness", 200, "UP");
        assertThat(Files.readString(blocker)).isEqualTo("sentinel-private-blocker");
        Files.delete(blocker);
        expectHealth("/actuator/health/readiness", 200, "UP");
        expectHealth("/actuator/health/liveness", 200, "UP");
    }

    private void expectHealth(String path, int expectedStatus, String expectedHealth)
            throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andExpect(
                        content()
                                .json(
                                        "{\"status\":\"" + expectedHealth + "\"}",
                                        JsonCompareMode.STRICT));
    }

    private static Path storageDirectory() {
        return temporaryDirectory.resolve("sentinel-private-originals");
    }

    // Explicit test source without a component stereotype, so other tests cannot scan it.
    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({
        OriginalFileStore.class,
        DocumentUploadPolicy.class,
        OriginalStorageHealthIndicator.class
    })
    static class HealthApplication {}
}
