package dev.secondbrain.web;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DocumentUploadConfiguration {
    @Bean
    MultipartConfigElement multipartConfigElement(
            @Value("${secondbrain.documents.max-upload-size}") DataSize maximumUploadSize) {
        if (maximumUploadSize.toBytes() < 1) {
            throw new IllegalArgumentException("Maximum upload size must be positive");
        }
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(maximumUploadSize);
        // Bound the complete request while allowing multipart headers and small form fields.
        factory.setMaxRequestSize(
                DataSize.ofBytes(
                        Math.addExact(
                                maximumUploadSize.toBytes(), DataSize.ofMegabytes(1).toBytes())));
        return factory.createMultipartConfig();
    }
}
