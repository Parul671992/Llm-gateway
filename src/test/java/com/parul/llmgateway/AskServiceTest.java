package com.parul.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

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

    @Mock GroqClient groqClient;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    AskService askService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(groqClient.model()).thenReturn("test-model");

        askService = new AskService(
                groqClient, redis, new SimpleMeterRegistry(),
                Duration.ofHours(1),        // answer TTL
                "v1",                       // key version
                Duration.ofSeconds(10),     // lock TTL
                Duration.ofMillis(200),     // wait timeout (short, so tests stay fast)
                Duration.ofMillis(10));     // poll interval
    }

    @Test
    void cacheHit_returnsCachedAnswer_withoutCallingAi() {
        when(valueOps.get(anyString())).thenReturn("cached answer");

        AskService.AskResult result = askService.ask("What is Redis?");

        assertThat(result.answer()).isEqualTo("cached answer");
        assertThat(result.source()).isEqualTo("cache");
        verify(groqClient, never()).ask(anyString());
    }

    @Test
    void cacheMiss_callsAi_andSavesAnswerWithTtl() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(groqClient.ask("What is Redis?")).thenReturn("fresh answer");

        AskService.AskResult result = askService.ask("What is Redis?");

        assertThat(result.answer()).isEqualTo("fresh answer");
        assertThat(result.source()).isEqualTo("llm");
        verify(valueOps).set(startsWith("answer:v1:test-model:"), eq("fresh answer"), eq(Duration.ofHours(1)));
    }

    @Test
    void redisDown_stillAnswersFromAi() {
        RedisConnectionFailureException redisDown = new RedisConnectionFailureException("Redis is down");
        when(valueOps.get(anyString())).thenThrow(redisDown);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(redisDown);
        doThrow(redisDown).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(groqClient.ask("What is Redis?")).thenReturn("answer despite outage");

        AskService.AskResult result = askService.ask("What is Redis?");

        assertThat(result.answer()).isEqualTo("answer despite outage");
        assertThat(result.source()).isEqualTo("llm");
    }

    @Test
    void lockTaken_waiterGetsAnswerFromCache_withoutCallingAi() {
        // First cache check: empty. Next checks (while waiting): the leader's answer has arrived.
        when(valueOps.get(anyString())).thenReturn(null, "answer from leader");
        // Someone else already holds the lock
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        AskService.AskResult result = askService.ask("What is Redis?");

        assertThat(result.answer()).isEqualTo("answer from leader");
        assertThat(result.source()).isEqualTo("cache-after-wait");
        verify(groqClient, never()).ask(anyString());
    }

    @Test
    void lockTaken_answerNeverArrives_fallsBackToAi() {
        // Cache stays empty the whole time (the leader crashed or is too slow)
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(groqClient.ask("What is Redis?")).thenReturn("fallback answer");

        AskService.AskResult result = askService.ask("What is Redis?");

        assertThat(result.answer()).isEqualTo("fallback answer");
        assertThat(result.source()).isEqualTo("llm");
        verify(groqClient).ask("What is Redis?");
    }
}