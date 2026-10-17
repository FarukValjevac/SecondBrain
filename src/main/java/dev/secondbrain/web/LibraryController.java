package dev.secondbrain.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class LibraryController {
    private final boolean uploadsEnabled;
    private final String maximumUploadSize;

    LibraryController(
            @Value("${secondbrain.events.enabled:true}") boolean uploadsEnabled,
            @Value("${secondbrain.documents.max-upload-size}") String maximumUploadSize) {
        this.uploadsEnabled = uploadsEnabled;
        this.maximumUploadSize = maximumUploadSize;
    }

    @GetMapping("/")
    String library(Model model) {
        model.addAttribute("uploadsEnabled", uploadsEnabled);
        model.addAttribute("maximumUploadSize", maximumUploadSize);
        return "library";
    }
}
