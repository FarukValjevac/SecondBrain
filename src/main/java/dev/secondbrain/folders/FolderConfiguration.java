package dev.secondbrain.folders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Folders share the document library's switch: without the database there is nothing to file. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class FolderConfiguration {
    @Bean
    FolderService folderService(
            JdbcClient jdbc, PlatformTransactionManager transactions, Clock clock) {
        return new FolderService(new JdbcFolderRepository(jdbc), transactions, clock);
    }
}
