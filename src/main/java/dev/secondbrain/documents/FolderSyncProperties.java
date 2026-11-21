package dev.secondbrain.documents;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Optional folder import; an empty directory turns it off. */
@ConfigurationProperties("secondbrain.folder-sync")
record FolderSyncProperties(
        @DefaultValue("") String directory,
        @DefaultValue("60s") Duration scanInterval,
        @DefaultValue("50") int maxImportsPerScan,
        @DefaultValue("10000") int maxFilesPerScan,
        @DefaultValue("false") boolean removeMissing) {
    FolderSyncProperties {
        if (maxImportsPerScan < 1 || maxImportsPerScan > 1000)
            throw new IllegalArgumentException("Imports per scan must be between 1 and 1000");
        if (maxFilesPerScan < 1 || maxFilesPerScan > 100_000)
            throw new IllegalArgumentException("Files per scan must be between 1 and 100000");
        if (scanInterval.compareTo(Duration.ofSeconds(5)) < 0)
            throw new IllegalArgumentException("Scan interval must be at least five seconds");
    }

    boolean enabled() {
        return !directory.isBlank();
    }
}
