package com.parul.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {"GROQ_API_KEY=test-key", "GEMINI_API_KEY=test-key",
        "POSTGRES_PASSWORD=unused", "ADMIN_TOKEN=test-admin-token"})
@Import(TestcontainersConfig.class)
class LlmgatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}