package dev.secondbrain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.secondbrain.documents.DocumentCleanupService;
import dev.secondbrain.documents.DocumentProcessingService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@WebMvcTest(DocumentCleanupController.class)
class DocumentCleanupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DocumentCleanupService cleanup;

    @Test
    void rendersBoundedCleanupStatusAgeRetryAndPaging() throws Exception {
        var id = UUID.randomUUID();
        var delivery = UUID.randomUUID();
        when(cleanup.read(1))
                .thenReturn(
                        new DocumentCleanupService.Page(
                                List.of(
                                        new DocumentCleanupService.Request(
                                                id,
                                                Instant.EPOCH,
                                                90,
                                                DocumentCleanupService.State.FAILED,
                                                5,
                                                Optional.of(
                                                        new DocumentProcessingService.Retry(
                                                                delivery, 2)))),
                                1,
                                true,
                                true));
        var html =
                mvc.perform(get("/documents/cleanup").param("page", "1"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains(
                        "Needs attention",
                        "Waiting 90 seconds; 5 attempts.",
                        "Retry cleanup",
                        "Previous",
                        "Next",
                        delivery.toString(),
                        "value=\"2\"");
        verify(cleanup, never()).retry(any(), any(), anyInt(), any());
    }

    @Test
    void emptyPausedPageHasNoRetryButton() throws Exception {
        when(cleanup.read(0))
                .thenReturn(new DocumentCleanupService.Page(List.of(), 0, false, false));
        var html =
                mvc.perform(get("/documents/cleanup"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(html)
                .contains("Cleanup is paused", "No outstanding cleanup")
                .doesNotContain("Retry cleanup");
    }

    @Test
    void retryUsesTheObservedCycleAndRequestCorrelationAndRedirects() throws Exception {
        var id = UUID.randomUUID();
        var delivery = UUID.randomUUID();
        var correlation = UUID.randomUUID();
        when(cleanup.retry(id, delivery, 2, correlation)).thenReturn(true);
        mvc.perform(
                        post("/documents/cleanup/" + id + "/retry")
                                .header("X-Correlation-ID", correlation)
                                .param("deliveryId", delivery.toString())
                                .param("cycle", "2"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/documents/cleanup"))
                .andExpect(
                        flash().attribute(
                                        "cleanupMessage",
                                        "Cleanup retry queued. The document stays removed."));
        verify(cleanup).retry(id, delivery, 2, correlation);
    }

    @Test
    void staleRetryReturnsUsefulConflictWithoutEchoingInput() throws Exception {
        var response =
                mvc.perform(
                                post("/documents/cleanup/" + UUID.randomUUID() + "/retry")
                                        .param("deliveryId", UUID.randomUUID().toString())
                                        .param("cycle", "0"))
                        .andExpect(status().isConflict())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).contains("Refresh its status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483648", "private-sentinel", "1.0"})
    void invalidRetryNeverReachesService(String cycle) throws Exception {
        mvc.perform(
                        post("/documents/cleanup/" + UUID.randomUUID() + "/retry")
                                .param("deliveryId", UUID.randomUUID().toString())
                                .param("cycle", cycle))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cleanup);
    }

    @Test
    void duplicateOrMissingFieldsAreRejected() throws Exception {
        var route = "/documents/cleanup/" + UUID.randomUUID() + "/retry";
        mvc.perform(
                        post(route)
                                .param("deliveryId", UUID.randomUUID().toString())
                                .param("cycle", "0", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(route)).andExpect(status().isBadRequest());
        verifyNoInteractions(cleanup);
    }

    @Test
    void databaseFailuresDoNotExposePrivateDetails() throws Exception {
        when(cleanup.read(0))
                .thenThrow(new DataAccessResourceFailureException("private-password-sentinel"));
        var response =
                mvc.perform(get("/documents/cleanup"))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response)
                .contains("Deleted documents stay removed")
                .doesNotContain("private-password-sentinel");
    }
}
