package com.parul.llmgateway;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import com.parul.llmgateway.tenant.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

@WebMvcTest(AskController.class)
class AskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AskService askService;

    @MockitoBean
    ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        // The fake key "test-key" belongs to tenant 1
        when(apiKeyService.findTenantIdForKey("test-key")).thenReturn(Optional.of(1L));
    }

    @Test
    void validQuestion_returnsAnswer() throws Exception {
        when(askService.ask("What is Redis?"))
                .thenReturn(new AskService.AskResult("Redis is an in-memory store.", "cache"));

        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("{\"question\":\"What is Redis?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Redis is an in-memory store."))
                .andExpect(jsonPath("$.source").value("cache"));
    }

    @Test
    void emptyQuestion_returns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("question must not be empty"));

        verify(askService, never()).ask(anyString());
    }

    @Test
    void tooLongQuestion_returns400() throws Exception {
        String longQuestion = "a".repeat(2001);

        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("{\"question\":\"" + longQuestion + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("question must be at most 2000 characters"));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("{\"question\":\"hi\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void aiProviderRateLimited_returns503() throws Exception {
        when(askService.ask(anyString()))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "test-key")
                        .content("{\"question\":\"What is Redis?\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("AI provider rate limited"));
    }

    @Test
    void missingApiKey_returns401() throws Exception {
        mockMvc.perform(post("/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is Redis?\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Missing API key"));

        verify(askService, never()).ask(anyString());
    }
}