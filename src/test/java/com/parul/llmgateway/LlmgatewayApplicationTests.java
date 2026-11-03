package com.parul.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {"groq.api-key=test-key", "POSTGRES_PASSWORD=unused", "ADMIN_TOKEN=test-admin-token"})
@Import(TestcontainersConfig.class)
class LlmgatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
