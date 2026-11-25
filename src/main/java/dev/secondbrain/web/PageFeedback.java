package dev.secondbrain.web;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;

import java.time.Instant;
import java.util.Optional;

/** A fixed outcome message shown on the search or question page, with its HTTP status. */
interface PageFeedback {
    HttpStatus getStatus();

    /** Whether a provider's "try again after" time may be shown with this message. */
    boolean acceptsRetryHint();

    /**
     * Shows {@code feedback} under {@code attribute} on {@code view}: sets the status, removes any
     * result attributes, and keeps a retry time only where it is meaningful.
     */
    static String show(
            Model model,
            HttpServletResponse response,
            String view,
            String attribute,
            PageFeedback feedback,
            Optional<Instant> retryAt,
            String... results) {
        response.setStatus(feedback.getStatus().value());
        for (var result : results) model.asMap().remove(result);
        model.addAttribute(attribute, feedback);
        model.addAttribute("retryAt", feedback.acceptsRetryHint() ? retryAt : Optional.empty());
        return view;
    }
}
