package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.folders.Folder;
import dev.secondbrain.folders.FolderColor;
import dev.secondbrain.folders.FolderException;
import dev.secondbrain.folders.FolderService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(FolderController.class)
class FolderControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FolderService folders;

    private final Folder sport =
            new Folder(UUID.randomUUID(), Optional.empty(), "Sport <b>", FolderColor.GREEN, 1);

    @Test
    void managePageEscapesNamesAndOffersColoursAndNesting() throws Exception {
        when(folders.tree())
                .thenReturn(
                        new FolderService.FolderTree(List.of(sport), Map.of(sport.folderId(), 1)));
        var html =
                mvc.perform(get("/folders"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Sport &lt;b&gt;",
                        "1 document",
                        "name=\"color\" value=\"violet\"",
                        "value=\"slate\"",
                        "class=\"visually-hidden\">Rose</span>",
                        "action=\"/folders/" + sport.folderId() + "\"",
                        "href=\"/folders/" + sport.folderId() + "/delete\"",
                        "href=\"/library?folder=" + sport.folderId() + "\"")
                .doesNotContain("Sport <b>");
    }

    @Test
    void createsAFolderAndOpensIt() throws Exception {
        when(folders.create("Sport", Optional.empty(), FolderColor.GREEN)).thenReturn(sport);
        mvc.perform(
                        post("/folders")
                                .param("name", "Sport")
                                .param("parent", "")
                                .param("color", "green"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/library?folder=" + sport.folderId()));
    }

    @Test
    void refusedChangesComeBackAsSafeMessages() throws Exception {
        when(folders.create(any(), any(), any()))
                .thenThrow(new FolderException(FolderException.Reason.DUPLICATE_NAME));
        mvc.perform(post("/folders").param("name", "Sport").param("color", "green"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/folders"))
                .andExpect(
                        flash().attribute(
                                        "folderError",
                                        FolderException.Reason.DUPLICATE_NAME.message()));
        mvc.perform(post("/folders").param("name", "Sport").param("color", "neon"))
                .andExpect(
                        flash().attribute(
                                        "folderError",
                                        FolderException.Reason.INVALID_COLOR.message()));
    }

    @Test
    void rejectsMalformedFolderIdsWithoutCallingTheService() throws Exception {
        mvc.perform(
                        post("/folders")
                                .param("name", "X")
                                .param("parent", "1-1-1-1-1")
                                .param("color", "green"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/documents/" + UUID.randomUUID() + "/folder")
                                .param("folder", "not-an-id"))
                .andExpect(status().isBadRequest());
        verify(folders, never()).create(any(), any(), any());
        verify(folders, never()).move(any(), any());
    }

    @Test
    void movesADocumentAndReturnsOnlyToKnownPages() throws Exception {
        var document = UUID.randomUUID();
        when(folders.move(document, Optional.of(sport.folderId()))).thenReturn(true);
        mvc.perform(
                        post("/documents/" + document + "/folder")
                                .param("folder", sport.folderId().toString())
                                .param("return", "https://example.com"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/documents/" + document));
        mvc.perform(
                        post("/documents/" + document + "/folder")
                                .param("folder", sport.folderId().toString())
                                .param("return", "library"))
                .andExpect(redirectedUrl("/library?folder=" + sport.folderId()));
    }

    @Test
    void deletionAsksFirstAndExplainsThatDocumentsStay() throws Exception {
        when(folders.find(sport.folderId())).thenReturn(Optional.of(sport));
        var html =
                mvc.perform(get("/folders/" + sport.folderId() + "/delete"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Delete folder?", "No document is deleted.", "class=\"danger-button\"");
    }
}
