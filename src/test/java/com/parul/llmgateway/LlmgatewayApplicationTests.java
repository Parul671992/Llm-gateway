package com.parul.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {"groq.api-key=test-key", "POSTGRES_PASSWORD=unused"})
@Import(TestcontainersConfig.class)
class LlmgatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
