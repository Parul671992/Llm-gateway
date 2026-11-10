package com.parul.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.parul.llmgateway.llm.LlmProperties;
import com.parul.llmgateway.llm.LlmResult;
import com.parul.llmgateway.llm.LlmRouter;
import com.parul.llmgateway.llm.OpenAiCompatibleClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@ExtendWith(MockitoExtension.class)
class LlmRouterTest {

    @Mock OpenAiCompatibleClient primary;
    @Mock OpenAiCompatibleClient fallback;

    LlmRouter router;

    @BeforeEach
    void setUp() {
        when(primary.name()).thenReturn("groq");
        lenient().when(fallback.name()).thenReturn("gemini");

        // Small numbers so the breaker opens quickly: judge the last 4 calls, decide after 2
        LlmProperties properties = new LlmProperties(null, null,
                new LlmProperties.CircuitBreaker(50, 4, 2, Duration.ofSeconds(30), 1));
        router = new LlmRouter(primary, fallback, properties, new SimpleMeterRegistry());
    }

    @Test
    void primaryWorks_noFailover() {
        when(primary.ask("q")).thenReturn(new LlmResult("from groq", "groq", 1));

        assertThat(router.ask("q").provider()).isEqualTo("groq");
        verify(fallback, never()).ask(anyString());
    }

    @Test
    void primaryReturns503_failsOverToFallback() {
        when(primary.ask("q")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(fallback.ask("q")).thenReturn(new LlmResult("from gemini", "gemini", 1));

        assertThat(router.ask("q").provider()).isEqualTo("gemini");
    }

    @Test
    void primaryReturns400_doesNotFailOver() {
        when(primary.ask("q")).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> router.ask("q")).isInstanceOf(HttpClientErrorException.class);
        verify(fallback, never()).ask(anyString());
    }

    @Test
    void repeatedFailures_openTheCircuit_andPrimaryIsSkipped() {
        when(primary.ask("q")).thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        when(fallback.ask("q")).thenReturn(new LlmResult("from gemini", "gemini", 1));

        router.ask("q");   // failure 1
        router.ask("q");   // failure 2 → 100% failure rate → OPEN
        assertThat(router.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        router.ask("q");   // breaker is open: primary is NOT called
        verify(primary, times(2)).ask("q");
        verify(fallback, times(3)).ask("q");
    }
}