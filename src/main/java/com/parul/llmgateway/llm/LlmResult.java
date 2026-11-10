package com.parul.llmgateway.llm;

public record LlmResult(String content, String provider, long costMicros) {}