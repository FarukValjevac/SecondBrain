package dev.secondbrain.web;

import dev.secondbrain.documents.UploadRejectedException;
import dev.secondbrain.imports.PageImportException;
import dev.secondbrain.imports.WebPageImportService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.UUID;

/** Imports one web page from the library form; feedback reuses the upload notices. */
@Controller
class WebImportController {
    private final ObjectProvider<WebPageImportService> imports;

    WebImportController(ObjectProvider<WebPageImportService> imports) {
        this.imports = imports;
    }

    @PostMapping(
            value = "/documents/import",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    RedirectView importPage(
            @RequestParam MultiValueMap<String, String> fields,
            @RequestAttribute(RequestCorrelationFilter.ATTRIBUTE) UUID correlationId,
            RedirectAttributes flash) {
        var service = imports.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var urls = fields.get("url");
        if (urls == null || urls.size() != 1) {
            flash.addFlashAttribute(
                    "uploadError", PageImportException.Reason.INVALID_URL.message());
            return library();
        }
        try {
            flash.addFlashAttribute(
                    "uploadReceipt", service.importPage(urls.getFirst(), correlationId));
        } catch (PageImportException failed) {
            flash.addFlashAttribute("uploadError", failed.reason().message());
        } catch (UploadRejectedException rejected) {
            flash.addFlashAttribute("uploadError", rejected.reason().message());
        } catch (IOException | DataAccessException | TransactionException unavailable) {
            flash.addFlashAttribute("uploadError", "The page could not be saved. Try again later.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return library();
    }

    private static RedirectView library() {
        var redirect = new RedirectView("/library", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
