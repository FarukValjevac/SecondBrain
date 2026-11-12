package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentCleanupService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

@Controller
@ConditionalOnProperty(
        name = "secondbrain.events.enabled",
        havingValue = "true",
        matchIfMissing = true)
class DocumentCleanupController {
    private final DocumentCleanupService cleanup;

    DocumentCleanupController(DocumentCleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @ModelAttribute
    void privacy(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/documents/cleanup")
    String status(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("cleanup", cleanup.read(page));
        return "document-cleanup";
    }

    @PostMapping("/documents/cleanup/{id}/retry")
    Object retry(
            @PathVariable UUID id,
            @RequestParam MultiValueMap<String, String> fields,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes feedback) {
        final UUID delivery;
        final int cycle;
        try {
            var ids = fields.get("deliveryId");
            var cycles = fields.get("cycle");
            if (ids == null || ids.size() != 1 || cycles == null || cycles.size() != 1)
                throw new IllegalArgumentException("Missing cleanup selection");
            delivery = UUID.fromString(ids.getFirst());
            if (!delivery.toString().equalsIgnoreCase(ids.getFirst())
                    || !cycles.getFirst().matches("[0-9]{1,10}"))
                throw new IllegalArgumentException("Invalid cleanup selection");
            cycle = Integer.parseInt(cycles.getFirst());
        } catch (IllegalArgumentException invalid) {
            return invalid();
        }
        if (!cleanup.retry(id, delivery, cycle, correlationId))
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(
                            "Cleanup cannot be retried. Refresh its status; it may be queued, paused or already complete.");
        feedback.addFlashAttribute(
                "cleanupMessage", "Cleanup retry queued. The document stays removed.");
        var redirect = new RedirectView("/documents/cleanup", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseBody
    ResponseEntity<String> invalid() {
        return ResponseEntity.badRequest()
                .body("Refresh cleanup status and use a non-negative page number.");
    }

    @ExceptionHandler({
        DataAccessException.class,
        TransactionException.class,
        IllegalStateException.class
    })
    @ResponseBody
    ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Cleanup status is temporarily unavailable. Deleted documents stay removed.");
    }
}
