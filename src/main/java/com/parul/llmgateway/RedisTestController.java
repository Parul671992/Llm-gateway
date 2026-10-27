package com.parul.llmgateway;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedisTestController {

    private final StringRedisTemplate redis;

    public RedisTestController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @GetMapping("/redis/set")
    public String set(@RequestParam String key,
                      @RequestParam String value,
                      @RequestParam long seconds) {
        redis.opsForValue().set(key, value, Duration.ofSeconds(seconds));
        return "Saved " + key + " for " + seconds + " seconds";
    }

    @GetMapping("/redis/get")
    public String get(@RequestParam String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? "Not found (expired or never saved)" : value;
    }
}