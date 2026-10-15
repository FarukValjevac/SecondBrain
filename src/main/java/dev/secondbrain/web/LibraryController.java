package dev.secondbrain.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class LibraryController {

    @GetMapping("/")
    String library() {
        return "library";
    }
}
