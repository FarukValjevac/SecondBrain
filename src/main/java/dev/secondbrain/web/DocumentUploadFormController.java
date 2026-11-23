package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.UploadRejectedException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
final class DocumentUploadFormController {
    private final DocumentAcceptanceService acceptanceService;
    private final ObjectProvider<DocumentInspectionService> documents;
    private final int maximumFiles;

    DocumentUploadFormController(
            DocumentAcceptanceService acceptanceService,
            ObjectProvider<DocumentInspectionService> documents,
            @Value("${secondbrain.documents.max-files-per-upload:10}") int maximumFiles) {
        if (maximumFiles < 1 || maximumFiles > 100)
            throw new IllegalArgumentException("Files per upload must be between 1 and 100");
        this.acceptanceService = acceptanceService;
        this.documents = documents;
        this.maximumFiles = maximumFiles;
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RedirectView upload(
            @RequestPart("file") List<MultipartFile> files,
            @RequestParam(name = "folder", required = false) String folder,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes redirectAttributes)
            throws IOException {
        var folderId = FolderForm.choice(folder);
        if (files.isEmpty() || files.size() > maximumFiles)
            throw new UploadRejectedException(UploadRejectedException.Reason.TOO_MANY_FILES);
        if (files.size() == 1) {
            var receipt = accept(files.getFirst(), correlationId, folderId);
            redirectAttributes.addFlashAttribute("uploadReceipt", receipt);
            DuplicateWarnings.flash(List.of(receipt.documentId()), documents, redirectAttributes);
        } else {
            // Each file is accepted on its own; one rejected file never blocks the others.
            var results = new ArrayList<UploadResult>(files.size());
            for (var file : files) results.add(acceptOne(file, correlationId, folderId));
            redirectAttributes.addFlashAttribute("uploadResults", results);
            DuplicateWarnings.flash(
                    results.stream().flatMap(result -> result.documentId().stream()).toList(),
                    documents,
                    redirectAttributes);
        }
        var redirect = new RedirectView(FolderForm.library(folderId), true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    private DocumentAcceptanceService.Receipt accept(
            MultipartFile file, UUID correlationId, Optional<UUID> folderId) throws IOException {
        try (var input = file.getInputStream()) {
            return acceptanceService.accept(
                    file.getOriginalFilename(), file.getSize(), input, correlationId, folderId);
        }
    }

    private UploadResult acceptOne(
            MultipartFile file, UUID correlationId, Optional<UUID> folderId) {
        var name = file.getOriginalFilename() == null ? "Unnamed file" : file.getOriginalFilename();
        try {
            return UploadResult.saved(name, accept(file, correlationId, folderId).documentId());
        } catch (UploadRejectedException rejected) {
            return UploadResult.rejected(name, rejected.reason().message());
        } catch (IOException | DataAccessException | TransactionException unavailable) {
            return UploadResult.rejected(name, "This file could not be saved. Try again later.");
        }
    }
}
