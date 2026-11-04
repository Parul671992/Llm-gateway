package com.parul.llmgateway;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.parul.llmgateway.tenant.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "groq.api-key=test-key", "POSTGRES_PASSWORD=unused", "ADMIN_TOKEN=test-admin-token",
        "gateway.rate-limit.requests-per-minute=3"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class FilterIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ApiKeyService apiKeyService;
    @Autowired StringRedisTemplate redis;
    @MockitoBean AskService askService;

    ApiKeyService.CreatedTenant tenant;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("*"));
        tenant = apiKeyService.createTenantWithKey("tenant-" + UUID.randomUUID());
        when(askService.ask(anyLong(), anyString())).thenReturn(new AskService.AskResult("ok", "cache"));
    }

    private ResultActions ask(String apiKey) throws Exception {
        return mockMvc.perform(post("/v1/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", apiKey)
                .content("{\"question\":\"hi\"}"));
    }

    @Test
    void overRateLimit_returns429_withRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            ask(tenant.apiKey()).andExpect(status().isOk());
        }
        ask(tenant.apiKey())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void revokedKey_isRejectedImmediately_evenAfterBeingCached() throws Exception {
        ask(tenant.apiKey()).andExpect(status().isOk());       // key is now cached in Redis

        apiKeyService.revokeKey(tenant.keyId());

        ask(tenant.apiKey()).andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_withoutToken_returns401_evenWithInvalidBody() throws Exception {
        mockMvc.perform(put("/admin/v1/tenants/" + tenant.tenantId() + "/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyBudgetUsd\":-5}"))
                .andExpect(status().isUnauthorized());
    }
}