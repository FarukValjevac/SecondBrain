package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.conversations.Conversation;
import dev.secondbrain.conversations.ConversationService;
import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.questions.QuestionAnswerService;
import dev.secondbrain.search.SearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Saving turns, follow-up context, reopening and managing chats. */
@WebMvcTest(
        controllers = {QuestionController.class, HomeController.class},
        properties = "spring.config.import=")
@Import(AnswerSignature.class)
class ConversationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private QuestionAnswerService answers;
    @MockitoBean private DocumentInspectionService documents;
    @MockitoBean private ConversationService conversations;

    private final UUID chat = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(documents.list(anyInt()))
                .thenReturn(new DocumentInspectionService.LibraryPage(List.of(), 0, false));
        when(documents.names(any())).thenReturn(Map.of());
        when(conversations.recent(anyInt())).thenReturn(List.of());
        when(answers.answer(any()))
                .thenReturn(
                        new QuestionAnswerService.Answer(
                                QuestionAnswerService.Outcome.INSUFFICIENT_INFORMATION,
                                "",
                                List.of()));
    }

    @Test
    void aFirstQuestionStartsAChatAndTheTurnCarriesItsId() throws Exception {
        when(conversations.save(any(), any(), any(), any(), any(), any())).thenReturn(chat);
        var html =
                mvc.perform(
                                post("/questions/turn")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .param("q", "Was ist Anker?")
                                        .param("scope", "all")
                                        .param("conversation", "")
                                        .param("action", "ask"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html).contains("data-conversation=\"" + chat + "\"");
        verify(conversations)
                .save(
                        eq(Optional.empty()),
                        eq("Was ist Anker?"),
                        eq("all"),
                        eq(Conversation.Outcome.INSUFFICIENT),
                        eq(""),
                        eq(List.of()));
    }

    @Test
    void aFollowUpUsesThePreviousQuestionButShowsAndSavesOnlyTheNewOne() throws Exception {
        when(conversations.lastQuestion(chat)).thenReturn(Optional.of("Was ist Anker?"));
        when(conversations.save(any(), any(), any(), any(), any(), any())).thenReturn(chat);
        var html =
                mvc.perform(
                                post("/questions/turn")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .param("q", "Und warum?")
                                        .param("scope", "all")
                                        .param("conversation", chat.toString())
                                        .param("action", "ask"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var asked = ArgumentCaptor.forClass(SearchQuery.class);
        verify(answers).answer(asked.capture());
        assertThat(asked.getValue().text())
                .contains(
                        "Earlier question in this conversation: Was ist Anker?",
                        "Current question: Und warum?");
        assertThat(html).contains(">Und warum?<").doesNotContain("Earlier question");
        verify(conversations)
                .save(eq(Optional.of(chat)), eq("Und warum?"), any(), any(), any(), any());
    }

    @Test
    void withoutJavaScriptAskingSavesAndOpensTheConversation() throws Exception {
        when(conversations.save(any(), any(), any(), any(), any(), any())).thenReturn(chat);
        mvc.perform(
                        post("/chats/ask")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("q", "Was ist Anker?")
                                .param("scope", "all")
                                .param("conversation", "")
                                .param("action", "ask"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/chats/" + chat));
    }

    @Test
    void rejectedQuestionsAreNotSaved() throws Exception {
        mvc.perform(
                        post("/questions/turn")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("q", " ")
                                .param("scope", "all")
                                .param("action", "ask"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/questions/turn")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("q", "Frage")
                                .param("scope", "all")
                                .param("conversation", "not-an-id")
                                .param("action", "ask"))
                .andExpect(status().isBadRequest());
        verify(conversations, never()).save(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aReopenedChatShowsItsTurnsAndMarksDeletedSourcesUnavailable() throws Exception {
        var gone = new Conversation.SourceRef(UUID.randomUUID(), UUID.randomUUID());
        when(conversations.find(chat))
                .thenReturn(
                        Optional.of(
                                new Conversation(
                                        chat,
                                        "Anker <b>",
                                        Instant.now(),
                                        List.of(
                                                new Conversation.Turn(
                                                        1,
                                                        "Was ist <i>Anker</i>?",
                                                        "all",
                                                        Conversation.Outcome.ANSWERED,
                                                        "Ein Bezugspunkt [1].",
                                                        List.of(gone),
                                                        Instant.now())))));
        when(documents.sources(any())).thenReturn(Map.of());
        var html =
                mvc.perform(get("/chats/" + chat))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Anker &lt;b&gt;",
                        "Was ist &lt;i&gt;Anker&lt;/i&gt;?",
                        "Ein Bezugspunkt ",
                        "href=\"#saved-1-source-1\"",
                        "Source 1: no longer available",
                        "name=\"conversation\" value=\"" + chat + "\"",
                        "Ask a follow-up",
                        "/chats/" + chat + "/delete")
                .doesNotContain("<i>Anker", "What do you want to know?");
        mvc.perform(get("/chats/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void renamesAndDeletesChats() throws Exception {
        when(conversations.rename(chat, "Neu")).thenReturn(true);
        mvc.perform(post("/chats/" + chat + "/rename").param("title", "Neu"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/chats/" + chat));
        mvc.perform(post("/chats/" + chat + "/delete")).andExpect(redirectedUrl("/chats"));
        verify(conversations).delete(chat);
        when(conversations.deleteAll()).thenReturn(3);
        mvc.perform(post("/chats/delete-all")).andExpect(redirectedUrl("/chats"));
        assertThat(
                        mvc.perform(get("/chats/delete-all"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .contains("Delete all chats?", "class=\"danger-button\"");
    }
}
