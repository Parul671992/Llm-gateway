package com.parul.llmgateway.tenant;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Add 1, and set the expiry only on the first request of the window (one atomic step)
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current",
            Long.class);

    private final StringRedisTemplate redis;
    private final int requestsPerMinute;
    private final Counter rejected;

    public RateLimitFilter(StringRedisTemplate redis,
                           MeterRegistry meterRegistry,
                           @Value("${gateway.rate-limit.requests-per-minute}") int requestsPerMinute) {
        this.redis = redis;
        this.requestsPerMinute = requestsPerMinute;
        this.rejected = Counter.builder("gateway.ratelimit.rejected")
                .description("Requests rejected by the per-tenant rate limit")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object tenantId = request.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE);

        long nowSeconds = Instant.now().getEpochSecond();
        long currentMinute = nowSeconds / 60;
        long secondsLeftInWindow = 60 - (nowSeconds % 60);
        String key = "ratelimit:" + tenantId + ":" + currentMinute;

        Long count;
        try {
            count = redis.execute(INCREMENT_SCRIPT, List.of(key), "60");
        } catch (DataAccessException ex) {
            log.warn("Rate limiter unavailable, allowing request: {}", ex.getMessage());
            chain.doFilter(request, response);
            return;
        }
        if (count == null) {
        chain.doFilter(request, response);
        return;
        }

        long remaining = Math.max(0, requestsPerMinute - count);
        response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerMinute));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (count > requestsPerMinute) {
            rejected.increment();
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(secondsLeftInWindow));
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Rate limit of "
                            + requestsPerMinute + " requests per minute exceeded\",\"instance\":\""
                            + request.getRequestURI() + "\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}