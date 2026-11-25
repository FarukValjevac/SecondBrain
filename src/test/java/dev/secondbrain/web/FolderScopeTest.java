package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.folders.Folder;
import dev.secondbrain.folders.FolderColor;
import dev.secondbrain.folders.FolderException;
import dev.secondbrain.folders.FolderService;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.search.SearchQuery;
import dev.secondbrain.search.SearchScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/** A folder scope becomes the folder's documents; empty, oversized or missing folders explain. */
@WebMvcTest(
        controllers = {QuestionController.class, HomeController.class},
        properties = "spring.config.import=")
class FolderScopeTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private QuestionAnswerService answers;
    @MockitoBean private DocumentInspectionService documents;
    @MockitoBean private FolderService folders;

    private final Folder psychology =
            new Folder(UUID.randomUUID(), Optional.empty(), "Psychology", FolderColor.VIOLET, 1);

    @BeforeEach
    void setUp() {
        when(documents.list(anyInt()))
                .thenReturn(new DocumentInspectionService.LibraryPage(List.of(), 0, false));
        when(documents.names(any())).thenReturn(Map.of());
        when(folders.tree())
                .thenReturn(new FolderService.FolderTree(List.of(psychology), Map.of()));
        when(answers.answer(any()))
                .thenReturn(
                        new QuestionAnswerService.Answer(
                                QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION,
                                "",
                                List.of()));
    }

    @Test
    void parsesBothFolderScopeSpellingsAndRejectsMalformedOnes() {
        var id = psychology.folderId();
        var fromMenu = form("scope", "folder:" + id);
        assertThat(DocumentQueryForm.read(fromMenu, "ask").folder()).contains(id);
        var fromPicker = form("scope", "folder", "folder", id.toString());
        assertThat(DocumentQueryForm.read(fromPicker, "ask").scope())
                .isEqualTo(DocumentQueryForm.Scope.FOLDER);
        // The always-submitted folder menu is ignored for other scopes.
        assertThat(DocumentQueryForm.read(form("scope", "all", "folder", "junk"), "ask").folder())
                .isEmpty();
        for (var bad :
                List.of(
                        form("scope", "folder"),
                        form("scope", "folder:1-1-1-1-1"),
                        form("scope", "folder", "folder", "not-an-id")))
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> DocumentQueryForm.read(bad, "ask"))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFolderQuestionSearchesExactlyTheFolderDocuments() throws Exception {
        var inside = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(folders.documentsWithin(psychology.folderId(), 10_001)).thenReturn(inside);
        var html =
                ask("folder:" + psychology.folderId())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var question = ArgumentCaptor.forClass(SearchQuery.class);
        verify(answers).answer(question.capture());
        assertThat(question.getValue().scope())
                .isEqualTo(SearchScope.selected(new java.util.HashSet<>(inside)));
        assertThat(html).contains("Insufficient information");
    }

    @Test
    void aFolderWithMoreThanAHundredDocumentsCanBeAsked() throws Exception {
        var many = IntStream.range(0, 250).mapToObj(i -> UUID.randomUUID()).toList();
        when(folders.documentsWithin(psychology.folderId(), 10_001)).thenReturn(many);
        ask("folder:" + psychology.folderId());
        var question = ArgumentCaptor.forClass(SearchQuery.class);
        verify(answers).answer(question.capture());
        assertThat(
                        ((dev.secondbrain.search.SearchScope.SelectedDocuments)
                                        question.getValue().scope())
                                .documentIds())
                .hasSize(250);
    }

    @Test
    void emptyOversizedAndMissingFoldersExplainInsteadOfSearchingEverything() throws Exception {
        var id = psychology.folderId();
        when(folders.documentsWithin(id, 10_001)).thenReturn(List.of());
        assertThat(ask("folder:" + id).andReturn().getResponse().getContentAsString())
                .contains("No documents in this folder");
        when(folders.documentsWithin(id, 10_001))
                .thenReturn(IntStream.range(0, 10_001).mapToObj(i -> UUID.randomUUID()).toList());
        assertThat(ask("folder:" + id).andReturn().getResponse().getContentAsString())
                .contains("Folder too large");
        when(folders.documentsWithin(id, 10_001))
                .thenThrow(new FolderException(FolderException.Reason.NOT_FOUND));
        ask("folder:" + id).andExpect(status().isNotFound());
        verify(answers, never()).answer(any());
    }

    @Test
    void folderLinksPreselectTheFolderOnTheQuestionAndHomePages() throws Exception {
        var id = psychology.folderId();
        assertThat(
                        mvc.perform(get("/questions").param("folder", id.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .containsPattern("value=\"folder\"\\s+checked=\"checked\"")
                .contains("Folder: Psychology (including subfolders)");
        assertThat(
                        mvc.perform(get("/").param("folder", id.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .containsPattern("value=\"folder:" + id + "\"\\s+selected=\"selected\"")
                .contains("<option value=\"all\">Whole library</option>");
    }

    private org.springframework.test.web.servlet.ResultActions ask(String scope) throws Exception {
        return mvc.perform(
                post("/questions")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("q", "What is anchoring?")
                        .param("scope", scope)
                        .param("action", "ask"));
    }

    private static LinkedMultiValueMap<String, String> form(String... pairs) {
        var fields = new LinkedMultiValueMap<String, String>();
        fields.add("q", "Question");
        for (int i = 0; i < pairs.length; i += 2) fields.add(pairs[i], pairs[i + 1]);
        return fields;
    }
}
