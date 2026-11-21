package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentInspectionService;
import dev.secondbrain.documents.ExtractionState;
import dev.secondbrain.questions.ChatFailure;
import dev.secondbrain.study.Flashcard;
import dev.secondbrain.study.FlashcardService;
import dev.secondbrain.study.ReviewScheduler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(FlashcardController.class)
class FlashcardControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FlashcardService flashcards;
    @MockitoBean private DocumentInspectionService documents;
    private final UUID document = UUID.randomUUID();

    @Test
    void generatingReportsHowManyCardsWereMadeOrWhyNone() throws Exception {
        known();
        when(flashcards.generate(document)).thenReturn(5);
        mvc.perform(post("/documents/" + document + "/flashcards"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/flashcards"))
                .andExpect(
                        flash().attribute(
                                        "studyMessage", "Created 5 flashcards. They are due now."));
        var quota = mock(ChatFailure.class);
        when(quota.reason()).thenReturn(ChatFailure.Reason.QUOTA_EXHAUSTED);
        when(flashcards.generate(document)).thenThrow(quota);
        mvc.perform(post("/documents/" + document + "/flashcards"))
                .andExpect(
                        flash().attribute(
                                        "studyMessage",
                                        "The free allowance is used up for now. Try again later."));
    }

    @Test
    void showsTheDueCardWithAHiddenAnswerSourceLinkAndGrades() throws Exception {
        var card =
                new Flashcard(
                        UUID.randomUUID(),
                        document,
                        UUID.randomUUID(),
                        "Does <i>ArrayList</i> allow duplicates?",
                        "Yes.",
                        ReviewScheduler.Schedule.initial(Instant.EPOCH));
        when(flashcards.dueCount()).thenReturn(3);
        when(flashcards.nextDue()).thenReturn(Optional.of(card));
        when(documents.names(any())).thenReturn(Map.of(document, "notes.md"));
        when(documents.source(card.documentId(), card.passageId())).thenReturn(Optional.empty());
        var html =
                mvc.perform(get("/flashcards"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Cards due now: 3",
                        "From notes.md",
                        "Does &lt;i&gt;ArrayList&lt;/i&gt; allow duplicates?",
                        "<summary>Show answer</summary>",
                        "The source passage is no longer available.",
                        "action=\"/flashcards/" + card.cardId() + "/review\"",
                        "value=\"again\"",
                        "value=\"easy\"")
                .doesNotContain("<i>ArrayList");
    }

    @Test
    void reviewsByGradeAndRejectsUnknownGrades() throws Exception {
        var id = UUID.randomUUID();
        when(flashcards.review(id, ReviewScheduler.Grade.HARD)).thenReturn(true);
        mvc.perform(post("/flashcards/" + id + "/review").param("grade", "hard"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/flashcards"));
        verify(flashcards).review(id, ReviewScheduler.Grade.HARD);
        mvc.perform(post("/flashcards/" + id + "/review").param("grade", "perfect"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmptyQueueSaysNothingIsDue() throws Exception {
        when(flashcards.nextDue()).thenReturn(Optional.empty());
        assertThat(mvc.perform(get("/flashcards")).andReturn().getResponse().getContentAsString())
                .contains("Nothing due");
    }

    private void known() {
        when(documents.find(document))
                .thenReturn(
                        Optional.of(
                                new DocumentInspectionService.DocumentView(
                                        document,
                                        "notes.md",
                                        1,
                                        ExtractionState.EXTRACTED,
                                        "",
                                        List.of(new DocumentInspectionService.Line(1, "Text")))));
    }
}
