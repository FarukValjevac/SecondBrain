package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentAcceptanceService;
import dev.secondbrain.documents.UploadRejectedException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
final class DocumentUploadFormController {
    private final DocumentAcceptanceService acceptanceService;

    DocumentUploadFormController(DocumentAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RedirectView upload(
            @RequestPart("file") List<MultipartFile> files,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes redirectAttributes)
            throws IOException {
        if (files.size() != 1) {
            throw new UploadRejectedException(UploadRejectedException.Reason.SINGLE_FILE_REQUIRED);
        }
        var file = files.getFirst();
        DocumentAcceptanceService.Receipt receipt;
        try (var input = file.getInputStream()) {
            receipt =
                    acceptanceService.accept(
                            file.getOriginalFilename(), file.getSize(), input, correlationId);
        }
        redirectAttributes.addFlashAttribute("uploadReceipt", receipt);
        var redirect = new RedirectView("/", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
