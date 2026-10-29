package dev.secondbrain.web;

import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;
import dev.secondbrain.search.SearchService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** HTTP mapping only; query invariants and retrieval remain in the search feature. */
@Controller
class SearchController {
    private final ObjectProvider<SearchService> search;

    SearchController(ObjectProvider<SearchService> search) {
        this.search = search;
    }

    @ModelAttribute
    void prepare(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        model.addAttribute("queryText", "");
        model.addAttribute("scope", "all");
        model.addAttribute("documentIds", List.of());
        model.addAttribute("searchEnabled", search.getIfAvailable() != null);
    }

    @GetMapping("/search")
    String form() {
        return "search";
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String submit(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        final SearchQuery query;
        try {
            query = readQuery(fields);
        } catch (IllegalArgumentException invalid) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute(
                    "searchError",
                    "Enter a valid query (up to 1,200 characters) and a valid document scope.");
            return "search";
        }
        model.addAttribute("queryText", query.text());
        if (query.scope() instanceof SearchScope.SelectedDocuments selected) {
            model.addAttribute("scope", "selected");
            model.addAttribute("documentIds", selected.documentIds().stream().sorted().toList());
        }
        var service = search.getIfAvailable();
        if (service == null) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return "search";
        }
        try {
            model.addAttribute("searchResult", service.search(query));
        } catch (SearchFailure unavailable) {
            // Never attach the request or provider exception to diagnostics or the view.
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            model.addAttribute("searchError", "Search could not be completed. Try again later.");
        }
        return "search";
    }

    private static SearchQuery readQuery(MultiValueMap<String, String> fields) {
        String text = single(fields, "q");
        String scope = single(fields, "scope");
        var values = fields.getOrDefault("documentId", List.of());
        if (values.size() > SearchScope.MAXIMUM_SELECTED_DOCUMENTS)
            throw new IllegalArgumentException("Too many document selections");
        SearchScope selection;
        switch (scope) {
            case "all" -> {
                if (!values.isEmpty())
                    throw new IllegalArgumentException("Ambiguous document scope");
                selection = SearchScope.all();
            }
            case "selected" -> {
                var ids = new HashSet<UUID>();
                for (String value : values) {
                    if (value.length() != 36)
                        throw new IllegalArgumentException("Invalid document selection");
                    var id = UUID.fromString(value);
                    if (!id.toString().equalsIgnoreCase(value))
                        throw new IllegalArgumentException("Invalid document selection");
                    ids.add(id);
                }
                selection = SearchScope.selected(ids);
            }
            default -> throw new IllegalArgumentException("Invalid document scope");
        }
        return new SearchQuery(text, selection);
    }

    private static String single(MultiValueMap<String, String> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.size() != 1)
            throw new IllegalArgumentException("A single form value is required");
        return values.getFirst();
    }
}
