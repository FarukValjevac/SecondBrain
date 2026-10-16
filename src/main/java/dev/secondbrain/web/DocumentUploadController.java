package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.documents.UploadRejectedException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
final class DocumentUploadController {
    private final DocumentAcceptanceService acceptanceService;

    DocumentUploadController(DocumentAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    @PostMapping(
            path = "/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    UploadResponse upload(
            @RequestPart("file") List<MultipartFile> files,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId)
            throws IOException {
        if (files.size() != 1) {
            throw new UploadRejectedException(UploadRejectedException.Reason.SINGLE_FILE_REQUIRED);
        }
        var file = files.getFirst();
        try (var input = file.getInputStream()) {
            var receipt =
                    acceptanceService.accept(
                            file.getOriginalFilename(), file.getSize(), input, correlationId);
            return new UploadResponse(receipt.documentId(), receipt.state());
        }
    }

    @ExceptionHandler({IOException.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<DocumentUploadErrors.ErrorResponse> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        new DocumentUploadErrors.ErrorResponse(
                                "UPLOAD_UNAVAILABLE",
                                "The upload could not be completed. Try again later."));
    }

    record UploadResponse(UUID documentId, ExtractionState state) {}
}
