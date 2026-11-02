package com.parul.llmgateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    // Deletes the lock ONLY if we still own it (checked and deleted in one atomic step)
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private enum LockResult { ACQUIRED, TAKEN, UNAVAILABLE }

    private final GroqClient groqClient;
    private final StringRedisTemplate redis;
    private final Duration answerTtl;
    private final String keyVersion;
    private final Duration lockTtl;
    private final Duration waitTimeout;
    private final Duration pollInterval;

    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter cacheErrors;
    private final Counter stampedeWaits;
    private final Timer llmTimer;

    public AskService(GroqClient groqClient,
                      StringRedisTemplate redis,
                      MeterRegistry meterRegistry,
                      @Value("${gateway.cache.answer-ttl}") Duration answerTtl,
                      @Value("${gateway.cache.key-version}") String keyVersion,
                      @Value("${gateway.stampede.lock-ttl}") Duration lockTtl,
                      @Value("${gateway.stampede.wait-timeout}") Duration waitTimeout,
                      @Value("${gateway.stampede.poll-interval}") Duration pollInterval) {
        this.groqClient = groqClient;
        this.redis = redis;
        this.answerTtl = answerTtl;
        this.keyVersion = keyVersion;
        this.lockTtl = lockTtl;
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;

        this.cacheHits = Counter.builder("gateway.cache.requests")
                .tag("result", "hit").description("Answers served from cache")
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("gateway.cache.requests")
                .tag("result", "miss").description("Answers not found in cache")
                .register(meterRegistry);
        this.cacheErrors = Counter.builder("gateway.cache.errors")
                .description("Cache operations that failed")
                .register(meterRegistry);
        this.stampedeWaits = Counter.builder("gateway.stampede.waits")
                .description("Requests that waited for another request to fetch the answer")
                .register(meterRegistry);
        this.llmTimer = Timer.builder("gateway.llm.calls")
                .description("Calls to the AI provider")
                .register(meterRegistry);
    }

    public AskResult ask(String question) {
        String key = buildKey(question);

        // 1. Normal cache check
        String cached = readFromCache(key);
        if (cached != null) {
            cacheHits.increment();
            return new AskResult(cached, "cache");
        }
        cacheMisses.increment();

        // 2. Try to become the ONE request that fetches from the AI
        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        LockResult lock = tryAcquireLock(lockKey, token);

        // 3. Someone else holds the lock: wait for their answer
        if (lock == LockResult.TAKEN) {
            stampedeWaits.increment();
            String waited = waitForAnswer(key);
            if (waited != null) {
                return new AskResult(waited, "cache-after-wait");
            }
            log.warn("Waited {} for another request's answer, calling AI directly", waitTimeout);
        }

        try {
            // 4. Double check: the answer may have arrived while we were getting the lock
            if (lock == LockResult.ACQUIRED) {
                String justArrived = readFromCache(key);
                if (justArrived != null) {
                    return new AskResult(justArrived, "cache-after-wait");
                }
            }

            // 5. Ask the AI and save the answer
            String answer = llmTimer.record(() -> groqClient.ask(question));
            writeToCache(key, answer);
            return new AskResult(answer, "llm");
        } finally {
            // 6. Always release the lock if we own it, even if the AI call failed
            if (lock == LockResult.ACQUIRED) {
                releaseLock(lockKey, token);
            }
        }
    }

    private LockResult tryAcquireLock(String lockKey, String token) {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, lockTtl);
            return Boolean.TRUE.equals(acquired) ? LockResult.ACQUIRED : LockResult.TAKEN;
        } catch (DataAccessException ex) {
            cacheErrors.increment();
            log.warn("Lock unavailable, continuing without it: {}", ex.getMessage());
            return LockResult.UNAVAILABLE;
        }
    }

    private void releaseLock(String lockKey, String token) {
        try {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        } catch (DataAccessException ex) {
            cacheErrors.increment();
            log.warn("Lock release failed, it will expire on its own: {}", ex.getMessage());
        }
    }

    private String waitForAnswer(String key) {
        long deadline = System.currentTimeMillis() + waitTimeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
            String answer = readFromCache(key);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    private String buildKey(String question) {
        String normalized = question.trim().toLowerCase().replaceAll("\\s+", " ");
        return "answer:" + keyVersion + ":" + groqClient.model() + ":" + sha256(normalized);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String readFromCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException ex) {
            cacheErrors.increment();
            log.warn("Cache read failed, continuing without cache: {}", ex.getMessage());
            return null;
        }
    }

    private void writeToCache(String key, String answer) {
        try {
            redis.opsForValue().set(key, answer, answerTtl);
        } catch (DataAccessException ex) {
            cacheErrors.increment();
            log.warn("Cache write failed, answer not cached: {}", ex.getMessage());
        }
    }

    public record AskResult(String answer, String source) {}
}