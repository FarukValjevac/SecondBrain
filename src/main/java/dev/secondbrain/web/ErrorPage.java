package dev.secondbrain.web;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.servlet.ModelAndView;

/**
 * The styled error page (templates/error.html) with a fixed, safe message. Spring's own errors
 * render the same template with a message chosen by status; nothing from an exception is shown.
 */
final class ErrorPage {
    private ErrorPage() {}

    static ModelAndView of(HttpStatusCode status, String message) {
        var page = new ModelAndView("error", status);
        page.addObject("status", status.value());
        page.addObject("errorMessage", message);
        return page;
    }
}
