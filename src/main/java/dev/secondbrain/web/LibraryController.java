package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;

@Controller
class LibraryController {
    private final boolean uploadsEnabled;
    private final String maximumUploadSize;
    private final ObjectProvider<DocumentInspectionService> documents;

    LibraryController(
            @Value("${secondbrain.events.enabled:true}") boolean uploadsEnabled,
            @Value("${secondbrain.documents.max-upload-size}") String maximumUploadSize,
            ObjectProvider<DocumentInspectionService> documents) {
        this.uploadsEnabled = uploadsEnabled;
        this.maximumUploadSize = maximumUploadSize;
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    @GetMapping("/")
    String library(@RequestParam(defaultValue = "0") int page, Model model) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        model.addAttribute("uploadsEnabled", uploadsEnabled);
        model.addAttribute("maximumUploadSize", maximumUploadSize);
        model.addAttribute("libraryAvailable", uploadsEnabled);
        if (uploadsEnabled) {
            var service = documents.getIfAvailable();
            if (service == null) throw new IllegalStateException("Document service unavailable");
            model.addAttribute("library", service.list(page));
        }
        return "library";
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class,
        IllegalStateException.class
    })
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    String unavailable() {
        return "Documents are temporarily unavailable. Try again later.";
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    String invalidPage() {
        return "Use a non-negative page number.";
    }
}
