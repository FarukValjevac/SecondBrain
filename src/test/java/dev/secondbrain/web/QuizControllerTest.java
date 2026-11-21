package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.study.Quiz;
import dev.secondbrain.study.QuizParser;
import dev.secondbrain.study.QuizService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(QuizController.class)
class QuizControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private QuizService quizzes;
    private final UUID document = UUID.randomUUID();
    private final UUID quiz = UUID.randomUUID();

    @Test
    void startingAQuizOpensItsQuestions() throws Exception {
        when(quizzes.create(List.of(document))).thenReturn(Optional.of(quiz));
        mvc.perform(
                        post("/quizzes")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("documentId", document.toString()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/quizzes/" + quiz));
    }

    @Test
    void rejectsMissingOrMalformedDocumentSelections() throws Exception {
        mvc.perform(post("/quizzes").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/quizzes")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("documentId", "1-1-1-1-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(quizzes);
    }

    @Test
    void anUnansweredQuizShowsAFormWithOneFieldPerQuestion() throws Exception {
        when(quizzes.find(quiz)).thenReturn(Optional.of(quizWith(Optional.empty())));
        assertThat(page())
                .contains(
                        "1. Does &lt;b&gt;ArrayList&lt;/b&gt; allow duplicates?",
                        "name=\"answer-1\"",
                        "Check my answers")
                .doesNotContain("<b>ArrayList", "Results");
    }

    @Test
    void aGradedQuizShowsVerdictsExplanationsAndSources() throws Exception {
        when(quizzes.find(quiz))
                .thenReturn(Optional.of(quizWith(Optional.of(QuizParser.Verdict.UNGRADED))));
        assertThat(page())
                .contains(
                        "Results",
                        "Cannot be graded from the sources",
                        "Your answer:",
                        "Check the source passage")
                .doesNotContain("Check my answers");
    }

    @Test
    void submitsAnswersByQuestionPosition() throws Exception {
        when(quizzes.grade(any(), any())).thenReturn(true);
        mvc.perform(
                        post("/quizzes/" + quiz + "/answers")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("answer-1", "Yes")
                                .param("answer-2", "No"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/quizzes/" + quiz));
        verify(quizzes).grade(quiz, Map.of(1, "Yes", 2, "No"));
    }

    private Quiz quizWith(Optional<QuizParser.Verdict> verdict) {
        return new Quiz(
                quiz,
                Instant.EPOCH,
                List.of(
                        new Quiz.Question(
                                1,
                                document,
                                UUID.randomUUID(),
                                "Does <b>ArrayList</b> allow duplicates?",
                                verdict.map(value -> "Yes"),
                                verdict,
                                verdict.map(value -> "The source does not say."))));
    }

    private String page() throws Exception {
        return mvc.perform(get("/quizzes/" + quiz))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
