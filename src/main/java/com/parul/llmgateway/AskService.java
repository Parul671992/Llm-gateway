package com.parul.llmgateway;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AskService {

    private static final Duration ANSWER_TTL = Duration.ofHours(1);

    private final GroqClient groqClient;
    private final StringRedisTemplate redis;

    public AskService(GroqClient groqClient, StringRedisTemplate redis) {
        this.groqClient = groqClient;
        this.redis = redis;
    }

    public AskResult ask(String question) {
        String key = "answer:" + question.trim().toLowerCase();

        // 1. Look in Redis first
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return new AskResult(cached, "cache");      // HIT
        }

        // 2. MISS: ask the AI
        String answer = groqClient.ask(question);

        // 3. Save it in Redis with a TTL
        redis.opsForValue().set(key, answer, ANSWER_TTL);

        // 4. Return it
        return new AskResult(answer, "llm");
    }

    public record AskResult(String answer, String source) {}
}