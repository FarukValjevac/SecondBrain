package dev.secondbrain.imports;

import dev.secondbrain.documents.DocumentAcceptanceService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.unit.DataSize;

import java.net.InetAddress;
import java.time.Clock;

/** Web page import shares the upload switch; each import is one explicit user request. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class ImportConfiguration {
    @Bean
    WebPageImportService webPageImportService(
            DocumentAcceptanceService acceptance,
            JdbcClient jdbc,
            Clock clock,
            @Value("${secondbrain.documents.max-upload-size}") DataSize maximumUploadSize) {
        return new WebPageImportService(
                new PageFetcher(
                        AddressGuard.publicOnly(),
                        InetAddress::getAllByName,
                        maximumUploadSize.toBytes(),
                        clock),
                acceptance,
                jdbc);
    }
}
