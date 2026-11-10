package com.parul.llmgateway.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(Provider primary, Provider fallback, CircuitBreaker circuitBreaker) {

    public record Provider(String name, String baseUrl, String apiKey, String model,
                           Duration connectTimeout, Duration readTimeout, int maxTokens,
                           long inputMicrosPerMillion, long outputMicrosPerMillion) {}

    public record CircuitBreaker(int failureRateThreshold, int slidingWindowSize,
                                 int minimumNumberOfCalls, Duration waitInOpenState,
                                 int callsInHalfOpenState) {}
}