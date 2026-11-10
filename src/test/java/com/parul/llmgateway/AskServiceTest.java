package com.parul.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.parul.llmgateway.llm.LlmResult;
import com.parul.llmgateway.llm.LlmRouter;
import com.parul.llmgateway.tenant.BudgetExceededException;
import com.parul.llmgateway.tenant.SpendService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AskServiceTest {

    @Mock LlmRouter llmRouter;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SpendService spendService;

    AskService askService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(llmRouter.primaryModel()).thenReturn("test-model");

        askService = new AskService(
                llmRouter, redis, new SimpleMeterRegistry(), spendService,
                Duration.ofHours(1), "v1",
                Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofMillis(10));
    }

    @Test
    void cacheHit_returnsCachedAnswer_withoutCallingAi() {
        when(valueOps.get(anyString())).thenReturn("cached answer");

        assertThat(askService.ask(1L, "What is Redis?").source()).isEqualTo("cache");
        verify(llmRouter, never()).ask(anyString());
    }

    @Test
    void cacheMiss_callsAi_savesAnswer_andRecordsSpend() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(llmRouter.ask("What is Redis?")).thenReturn(new LlmResult("fresh answer", "groq", 68));

        AskService.AskResult result = askService.ask(1L, "What is Redis?");

        assertThat(result.source()).isEqualTo("llm");
        verify(valueOps).set(startsWith("answer:v1:1:test-model:"), eq("fresh answer"), eq(Duration.ofHours(1)));
        verify(spendService).recordSpend(1L, 68L);
    }

    @Test
    void overBudget_cacheMiss_throws_andAiIsNotCalled() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(spendService.isOverBudget(1L)).thenReturn(true);

        assertThatThrownBy(() -> askService.ask(1L, "What is Redis?"))
                .isInstanceOf(BudgetExceededException.class);
        verify(llmRouter, never()).ask(anyString());
    }

    @Test
    void redisDown_stillAnswersFromAi() {
        RedisConnectionFailureException redisDown = new RedisConnectionFailureException("Redis is down");
        when(valueOps.get(anyString())).thenThrow(redisDown);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(redisDown);
        doThrow(redisDown).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(llmRouter.ask("What is Redis?")).thenReturn(new LlmResult("answer despite outage", "groq", 1));

        assertThat(askService.ask(1L, "What is Redis?").answer()).isEqualTo("answer despite outage");
    }

    @Test
    void lockTaken_waiterGetsAnswerFromCache_withoutCallingAi() {
        when(valueOps.get(anyString())).thenReturn(null, "answer from leader");
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(askService.ask(1L, "What is Redis?").source()).isEqualTo("cache-after-wait");
        verify(llmRouter, never()).ask(anyString());
    }

    @Test
    void lockTaken_answerNeverArrives_fallsBackToAi() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(llmRouter.ask("What is Redis?")).thenReturn(new LlmResult("fallback answer", "groq", 1));

        assertThat(askService.ask(1L, "What is Redis?").source()).isEqualTo("llm");
        verify(llmRouter).ask("What is Redis?");
    }
}