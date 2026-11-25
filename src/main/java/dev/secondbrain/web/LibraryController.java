package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentProcessingService;
import dev.secondbrain.documents.FolderSync;
import dev.secondbrain.folders.Folder;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.questions.QuestionAnswerService;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
class LibraryController {
    private final boolean uploadsEnabled;
    private final String maximumUploadSize;
    private final int maximumFilesPerUpload;
    private final ObjectProvider<DocumentInspectionService> documents;
    private final ObjectProvider<DocumentProcessingService> processing;
    private final ObjectProvider<QuestionAnswerService> answers;
    private final ObjectProvider<FolderSync> folderSync;
    private final ObjectProvider<FolderService> folders;

    LibraryController(
            @Value("${secondbrain.events.enabled:true}") boolean uploadsEnabled,
            @Value("${secondbrain.documents.max-upload-size}") String maximumUploadSize,
            @Value("${secondbrain.documents.max-files-per-upload:10}") int maximumFilesPerUpload,
            ObjectProvider<DocumentInspectionService> documents,
            ObjectProvider<DocumentProcessingService> processing,
            ObjectProvider<QuestionAnswerService> answers,
            ObjectProvider<FolderSync> folderSync,
            ObjectProvider<FolderService> folders) {
        this.uploadsEnabled = uploadsEnabled;
        this.maximumUploadSize = maximumUploadSize;
        this.maximumFilesPerUpload = maximumFilesPerUpload;
        this.documents = Objects.requireNonNull(documents, "documents");
        this.processing = Objects.requireNonNull(processing, "processing");
        this.answers = Objects.requireNonNull(answers, "answers");
        this.folderSync = Objects.requireNonNull(folderSync, "folderSync");
        this.folders = Objects.requireNonNull(folders, "folders");
    }

    @GetMapping("/library")
    String library(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "folder", required = false) String folderParameter,
            Model model) {
        if (page < 0) throw new IllegalArgumentException("Page must not be negative");
        var folder = FolderForm.choice(folderParameter);
        // Read here because the page structure depends on it; null when folders are off.
        var folderTree = ShellModel.tree(folders);
        // Reuse it for the shell instead of reading the tree a second time.
        model.addAttribute("folderTree", folderTree);
        if (folder.isPresent()) {
            // An unknown folder (deleted, or folders unavailable) is a missing page, not the root.
            var current =
                    (folderTree == null ? Optional.<Folder>empty() : folderTree.find(folder.get()))
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            model.addAttribute("currentFolder", current);
            model.addAttribute("folderPath", folderTree.path(current.folderId()));
            model.addAttribute("subfolders", folderTree.childrenOf(current));
        } else if (folderTree != null) {
            model.addAttribute("subfolders", folderTree.topLevel());
        }
        model.addAttribute("uploadsEnabled", uploadsEnabled);
        model.addAttribute("maximumUploadSize", maximumUploadSize);
        model.addAttribute("maximumFilesPerUpload", maximumFilesPerUpload);
        model.addAttribute("libraryAvailable", uploadsEnabled);
        model.addAttribute("questionsEnabled", answers.getIfAvailable() != null);
        var sync = folderSync.getIfAvailable();
        if (sync != null) model.addAttribute("folderSync", sync.status());
        if (uploadsEnabled) {
            var service = documents.getIfAvailable();
            if (service == null) throw new IllegalStateException("Document service unavailable");
            var library = service.listFolder(page, folder);
            model.addAttribute("library", library);
            var stages = processing.getIfAvailable();
            model.addAttribute(
                    "processing",
                    stages == null
                            ? java.util.Map.of()
                            : stages.read(
                                    library.documents().stream()
                                            .map(
                                                    DocumentInspectionService.DocumentSummary
                                                            ::documentId)
                                            .collect(Collectors.toSet())));
            if (stages != null) model.addAttribute("cleanup", stages.cleanup());
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
