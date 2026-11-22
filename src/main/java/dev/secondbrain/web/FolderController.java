package dev.secondbrain.web;

import dev.secondbrain.folders.Folder;
import dev.secondbrain.folders.FolderColor;
import dev.secondbrain.folders.FolderException;
import dev.secondbrain.folders.FolderService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Creating, editing and deleting folders, and filing documents into them. */
@Controller
class FolderController {
    private final ObjectProvider<FolderService> folders;

    FolderController(ObjectProvider<FolderService> folders) {
        this.folders = folders;
    }

    @GetMapping("/folders")
    String manage(@RequestParam(name = "parent", required = false) String parent, Model model) {
        service();
        model.addAttribute("colors", List.of(FolderColor.values()));
        model.addAttribute("newParent", FolderForm.choice(parent).orElse(null));
        return "folders";
    }

    @PostMapping("/folders")
    RedirectView create(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "parent", required = false) String parent,
            @RequestParam(name = "color", required = false) String color,
            RedirectAttributes flash) {
        var parentId = FolderForm.choice(parent);
        try {
            var folder = service().create(name, parentId, FolderColor.parse(color));
            flash.addFlashAttribute("folderMessage", "Folder created.");
            return redirect(FolderForm.library(Optional.of(folder.folderId())));
        } catch (FolderException refused) {
            flash.addFlashAttribute("folderError", refused.reason().message());
            return redirect("/folders" + parentId.map(id -> "?parent=" + id).orElse(""));
        }
    }

    @PostMapping("/folders/{id}")
    RedirectView update(
            @PathVariable UUID id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "color", required = false) String color,
            RedirectAttributes flash) {
        try {
            service().update(id, name, FolderColor.parse(color));
            flash.addFlashAttribute("folderMessage", "Folder updated.");
        } catch (FolderException refused) {
            flash.addFlashAttribute("folderError", refused.reason().message());
        }
        return redirect("/folders");
    }

    @GetMapping("/folders/{id}/delete")
    String confirmDeletion(@PathVariable UUID id, Model model) {
        model.addAttribute("folder", existing(id));
        return "folder-delete";
    }

    @PostMapping("/folders/{id}/delete")
    RedirectView delete(@PathVariable UUID id, RedirectAttributes flash) {
        Optional<UUID> parent;
        try {
            parent = existing(id).parentId();
            service().delete(id);
            flash.addFlashAttribute(
                    "folderMessage", "Folder deleted. Its documents and subfolders were kept.");
        } catch (FolderException refused) {
            flash.addFlashAttribute(
                    "folderError",
                    refused.reason() == FolderException.Reason.DUPLICATE_NAME
                            ? "A subfolder has the same name as a folder one level up. Rename it first."
                            : refused.reason().message());
            parent = Optional.of(id);
        }
        return redirect(FolderForm.library(parent));
    }

    @PostMapping("/documents/{id}/folder")
    RedirectView move(
            @PathVariable UUID id,
            @RequestParam(name = "folder", required = false) String folder,
            @RequestParam(name = "return", required = false) String back,
            RedirectAttributes flash) {
        var target = FolderForm.choice(folder);
        try {
            if (!service().move(id, target))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            flash.addFlashAttribute(
                    "folderMessage",
                    target.isPresent()
                            ? "Document moved to the folder."
                            : "Document moved to Unfiled.");
        } catch (FolderException refused) {
            flash.addFlashAttribute("folderError", refused.reason().message());
        }
        // Only two fixed destinations: never redirect to a caller-supplied address.
        return redirect("library".equals(back) ? FolderForm.library(target) : "/documents/" + id);
    }

    private Folder existing(UUID id) {
        return service()
                .find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private FolderService service() {
        var service = folders.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return service;
    }

    private static RedirectView redirect(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return redirect;
    }
}
