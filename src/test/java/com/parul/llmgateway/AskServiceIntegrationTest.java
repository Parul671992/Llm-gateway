package com.parul.llmgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.parul.llmgateway.llm.LlmResult;
import com.parul.llmgateway.llm.LlmRouter;
import com.parul.llmgateway.tenant.SpendService;
import com.parul.llmgateway.tenant.Tenant;
import com.parul.llmgateway.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"GROQ_API_KEY=test-key", "GEMINI_API_KEY=test-key",
        "POSTGRES_PASSWORD=unused", "ADMIN_TOKEN=test-admin-token"})
@Import(TestcontainersConfig.class)
class AskServiceIntegrationTest {

    @MockitoBean LlmRouter llmRouter;
    @Autowired AskService askService;
    @Autowired SpendService spendService;
    @Autowired TenantRepository tenantRepository;
    @Autowired StringRedisTemplate redis;

    Long tenantId;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("*"));
        when(llmRouter.primaryModel()).thenReturn("test-model");
        tenantId = tenantRepository.save(new Tenant("tenant-" + UUID.randomUUID())).getId();
    }

    @Test
    void secondRequest_isServedFromCache_andSpendIsRecorded() {
        when(llmRouter.ask("What is Redis?")).thenReturn(new LlmResult("real answer", "groq", 68));

        assertThat(askService.ask(tenantId, "What is Redis?").source()).isEqualTo("llm");
        assertThat(askService.ask(tenantId, "What is Redis?").source()).isEqualTo("cache");
        verify(llmRouter, times(1)).ask(anyString());

        assertThat(spendService.getSpentMicros(tenantId)).isEqualTo(68);
    }

    @Test
    void concurrentIdenticalRequests_callAiOnlyOnce_andLockIsReleased() throws Exception {
        when(llmRouter.ask(anyString())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return new LlmResult("shared answer", "groq", 1);
        });

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<AskService.AskResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startLine.await();
                return askService.ask(tenantId, "Popular question");
            }));
        }
        startLine.countDown();
        for (Future<AskService.AskResult> result : results) {
            assertThat(result.get().answer()).isEqualTo("shared answer");
        }
        pool.shutdown();

        verify(llmRouter, times(1)).ask(anyString());
        assertThat(redis.keys("lock:*")).isEmpty();
    }
}