package com.parul.llmgateway.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public OpenAiCompatibleClient primaryLlm(LlmProperties properties) {
        return new OpenAiCompatibleClient(properties.primary());
    }

    @Bean
    public OpenAiCompatibleClient fallbackLlm(LlmProperties properties) {
        return new OpenAiCompatibleClient(properties.fallback());
    }
}