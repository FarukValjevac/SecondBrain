package dev.secondbrain.web;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.DocumentInspectionService.LibraryPage;
import dev.secondbrain.search.SearchFailure;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchResult;
import dev.secondbrain.search.SearchService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** HTTP mapping only; query invariants and retrieval remain in the search feature. */
@Controller
class SearchController {
    private final ObjectProvider<SearchService> search;
    private final ObjectProvider<DocumentInspectionService> documents;

    SearchController(
            ObjectProvider<SearchService> search,
            ObjectProvider<DocumentInspectionService> documents) {
        this.search = search;
        this.documents = documents;
    }

    @ModelAttribute
    void prepare(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        model.addAttribute("queryText", "");
        model.addAttribute("scope", "all");
        model.addAttribute("documentIds", List.of());
        model.addAttribute("documentPage", new LibraryPage(List.of(), 0, false));
        model.addAttribute("otherSelections", List.of());
        model.addAttribute(
                "searchEnabled",
                search.getIfAvailable() != null && documents.getIfAvailable() != null);
    }

    @GetMapping("/search")
    String form(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        final int page;
        try {
            var pages = fields.getOrDefault("page", List.of("0"));
            if (pages.size() != 1) throw new IllegalArgumentException("Invalid document page");
            page = SearchForm.page(pages.getFirst());
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        if (Boolean.TRUE.equals(model.getAttribute("searchEnabled")))
            populatePicker(SearchForm.blank(page), model);
        return "search";
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String submit(
            @RequestParam MultiValueMap<String, String> fields,
            Model model,
            HttpServletResponse response) {
        final SearchForm form;
        final Optional<SearchQuery> query;
        try {
            form = SearchForm.read(fields);
            query =
                    form.action() == SearchForm.Action.SEARCH
                            ? Optional.of(form.toQuery())
                            : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return invalid(model, response);
        }
        model.addAttribute("queryText", form.text());
        model.addAttribute("scope", form.scope() == SearchForm.Scope.ALL ? "all" : "selected");
        model.addAttribute("documentIds", form.documentIds().stream().sorted().toList());
        var service = search.getIfAvailable();
        if (service == null || documents.getIfAvailable() == null) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return "search";
        }
        populatePicker(form, model);
        if (form.action() == SearchForm.Action.BROWSE) return "search";
        var request = query.orElseThrow();
        model.addAttribute("queryText", request.text());
        try {
            var result = service.search(request);
            var ids =
                    result.matches().stream()
                            .map(SearchResult.Match::documentId)
                            .collect(Collectors.toSet());
            var names = documents.getObject().names(ids);
            var hits = new ArrayList<Hit>();
            for (var match : result.matches()) {
                var name = names.get(match.documentId());
                // A document hidden since retrieval must not acquire a stale label/excerpt in the
                // view.
                if (name != null) hits.add(new Hit(name, match));
            }
            model.addAttribute(
                    "searchResult", new SearchResult(hits.stream().map(Hit::match).toList()));
            model.addAttribute("searchHits", List.copyOf(hits));
        } catch (SearchFailure unavailable) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            model.addAttribute("searchError", "Search could not be completed. Try again later.");
        }
        return "search";
    }

    private void populatePicker(SearchForm form, Model model) {
        var service = documents.getObject();
        var page = service.list(form.page());
        var visibleIds =
                page.documents().stream()
                        .map(document -> document.documentId())
                        .collect(Collectors.toSet());
        var otherIds =
                form.documentIds().stream()
                        .filter(id -> !visibleIds.contains(id))
                        .collect(Collectors.toSet());
        var names = service.names(otherIds);
        var others =
                otherIds.stream()
                        .sorted()
                        .map(id -> new Choice(id, names.getOrDefault(id, "Document unavailable")))
                        .toList();
        model.addAttribute("documentPage", page);
        model.addAttribute("otherSelections", others);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    String unavailable(Model model, HttpServletResponse response) {
        prepare(model, response);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        model.asMap().remove("searchResult");
        model.asMap().remove("searchHits");
        model.addAttribute("searchError", "Search could not be completed. Try again later.");
        return "search";
    }

    private static String invalid(Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute(
                "searchError",
                "Enter a valid query (up to 1,200 characters), document scope and page.");
        return "search";
    }

    record Hit(String name, SearchResult.Match match) {
        Hit {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(match, "match");
        }

        @Override
        public String toString() {
            return "SearchHit";
        }
    }

    record Choice(UUID documentId, String name) {
        Choice {
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "DocumentChoice";
        }
    }
}
