package com.parul.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "groq.api-key=test-key")
@Testcontainers
class AskServiceIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @MockitoBean
    GroqClient groqClient;

    @Autowired
    AskService askService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("*"));
        when(groqClient.model()).thenReturn("test-model");
    }

    @Test
    void secondRequest_isServedFromRealRedis_withTtl() {
        when(groqClient.ask("What is Redis?")).thenReturn("real answer");

        AskService.AskResult first = askService.ask("What is Redis?");
        AskService.AskResult second = askService.ask("What is Redis?");

        assertThat(first.source()).isEqualTo("llm");
        assertThat(second.source()).isEqualTo("cache");
        verify(groqClient, times(1)).ask(anyString());

        String key = redis.keys("answer:v1:test-model:*").iterator().next();
        assertThat(redis.getExpire(key)).isPositive();
    }

    @Test
    void concurrentIdenticalRequests_callAiOnlyOnce_andLockIsReleased() throws Exception {
        when(groqClient.ask(anyString())).thenAnswer(invocation -> {
            Thread.sleep(300);              // pretend the AI takes 300ms
            return "shared answer";
        });

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<AskService.AskResult>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startLine.await();
                return askService.ask("Popular question");
            }));
        }
        startLine.countDown();              // everyone go!

        for (Future<AskService.AskResult> result : results) {
            assertThat(result.get().answer()).isEqualTo("shared answer");
        }
        pool.shutdown();

        verify(groqClient, times(1)).ask(anyString());
        assertThat(redis.keys("lock:*")).isEmpty();
    }
}