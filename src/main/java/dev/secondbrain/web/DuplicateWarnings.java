package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * After an upload or import, flashes the earlier copies of each new document, keyed by the new
 * document's ID, so the library can say "already in your library". A failed check only skips the
 * warning; it never affects the upload.
 */
final class DuplicateWarnings {
    private DuplicateWarnings() {}

    static void flash(
            List<UUID> saved,
            ObjectProvider<DocumentInspectionService> documents,
            RedirectAttributes flash) {
        var service = documents.getIfAvailable();
        if (service == null || saved.isEmpty()) return;
        var found = new LinkedHashMap<UUID, List<DocumentInspectionService.Duplicate>>();
        try {
            for (var id : saved) {
                var copies = service.duplicates(id);
                if (!copies.isEmpty()) found.put(id, copies);
            }
        } catch (DataAccessException | TransactionException unavailable) {
            return;
        }
        if (!found.isEmpty()) flash.addFlashAttribute("uploadDuplicates", Map.copyOf(found));
    }
}
