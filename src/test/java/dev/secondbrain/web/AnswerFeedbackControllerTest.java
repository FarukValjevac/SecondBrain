package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.questions.AnswerFeedback;
import dev.secondbrain.questions.AnswerFeedbackService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(controllers = AnswerFeedbackController.class, properties = "spring.config.import=")
@Import(AnswerSignature.class)
@ExtendWith(OutputCaptureExtension.class)
class AnswerFeedbackControllerTest {
    private static final String QUESTION = "private-question-sentinel";
    private static final String ANSWER = "private-answer-sentinel [1].";
    @Autowired private MockMvc mvc;
    @Autowired private AnswerSignature signature;
    @MockitoBean private AnswerFeedbackService feedback;
    private final UUID document = UUID.randomUUID();
    private final UUID passage = UUID.randomUUID();

    @Test
    void recordsARatingForASignedAnswerAndConfirmsWithoutRepeatingIt(CapturedOutput output)
            throws Exception {
        when(feedback.record(anyBoolean(), any(), anyString(), anyString(), any()))
                .thenReturn(true);
        mvc.perform(rating("not-helpful", "INCOMPLETE", signed()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/questions"))
                .andExpect(
                        flash().attribute(
                                        "feedbackMessage",
                                        "Thanks, your feedback was saved on this computer."));
        verify(feedback)
                .record(
                        false,
                        Optional.of(AnswerFeedback.Reason.INCOMPLETE),
                        QUESTION,
                        ANSWER,
                        List.of(new AnswerFeedback.Source(document, passage)));
        assertThat(output).doesNotContain(QUESTION, "private-answer");
    }

    @Test
    void refusesUnsignedOrAlteredAnswersAndUnknownRatings() throws Exception {
        mvc.perform(rating("helpful", "", "forged")).andExpect(status().isBadRequest());
        mvc.perform(rating("maybe", "", signed())).andExpect(status().isBadRequest());
        mvc.perform(rating("not-helpful", "BAD_REASON", signed()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        rating("helpful", "", signed())
                                .param("source", UUID.randomUUID() + ":" + UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(feedback);
    }

    @Test
    void explainsWhenACitedDocumentWasDeletedMeanwhile() throws Exception {
        when(feedback.record(anyBoolean(), any(), anyString(), anyString(), any()))
                .thenReturn(false);
        var body =
                mvc.perform(rating("helpful", "", signed()))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).contains("source of this answer was deleted");
    }

    @Test
    void exportsStoredFeedbackAsAPrivateJsonDownload() throws Exception {
        when(feedback.export())
                .thenReturn(
                        List.of(
                                new AnswerFeedback(
                                        UUID.randomUUID(),
                                        false,
                                        Optional.of(AnswerFeedback.Reason.WRONG_SOURCE),
                                        "Question?",
                                        "Answer.",
                                        List.of(new AnswerFeedback.Source(document, passage)),
                                        Instant.parse("2026-11-21T10:00:00Z"))));
        mvc.perform(get("/questions/feedback/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        "attachment; filename=\"answer-feedback.json\""))
                .andExpect(jsonPath("$[0].helpful").value(false))
                .andExpect(jsonPath("$[0].reason").value("WRONG_SOURCE"))
                .andExpect(jsonPath("$[0].question").value("Question?"))
                .andExpect(jsonPath("$[0].citations[0].passageId").value(passage.toString()));
    }

    private String signed() {
        return signature.sign(QUESTION, ANSWER, List.of(document + ":" + passage));
    }

    private MockHttpServletRequestBuilder rating(String rating, String reason, String signed) {
        return post("/questions/feedback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("q", QUESTION)
                .param("answer", ANSWER)
                .param("source", document + ":" + passage)
                .param("signature", signed)
                .param("rating", rating)
                .param("reason", reason);
    }
}
