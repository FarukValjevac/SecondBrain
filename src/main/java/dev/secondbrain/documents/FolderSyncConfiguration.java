package dev.secondbrain.documents;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/** Active only when a folder is configured and documents can be accepted. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "!'${secondbrain.folder-sync.directory:}'.isBlank() and '${secondbrain.events.enabled:true}' == 'true'")
@EnableConfigurationProperties(FolderSyncProperties.class)
class FolderSyncConfiguration {
    @Bean
    FolderSync folderSync(
            FolderSyncProperties settings,
            DocumentUploadPolicy policy,
            DocumentAcceptanceService acceptance,
            DocumentDeletionService deletion,
            JdbcClient jdbc,
            Clock clock) {
        return new FolderSync(
                settings, policy, acceptance, deletion, new JdbcFolderFileRepository(jdbc), clock);
    }

    @Bean
    FolderSyncScheduler folderSyncScheduler(FolderSync sync) {
        return new FolderSyncScheduler(sync);
    }

    static final class FolderSyncScheduler {
        private final FolderSync sync;

        FolderSyncScheduler(FolderSync sync) {
            this.sync = sync;
        }

        @Scheduled(
                fixedDelayString = "${secondbrain.folder-sync.scan-interval:60s}",
                initialDelayString = "${secondbrain.folder-sync.scan-interval:60s}")
        void scan() {
            try {
                sync.scan();
            } catch (RuntimeException unavailable) {
                LoggerFactory.getLogger(FolderSync.class)
                        .atWarn()
                        .addKeyValue("operation", "folder_sync")
                        .addKeyValue("code", "SCAN_FAILED")
                        .log("Folder sync will retry");
            }
        }
    }
}
