package com.parul.llmgateway.llm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final OpenAiCompatibleClient primary;
    private final OpenAiCompatibleClient fallback;
    private final CircuitBreaker breaker;
    private final Counter failoversOnError;
    private final Counter failoversOnOpenCircuit;

    public LlmRouter(@Qualifier("primaryLlm") OpenAiCompatibleClient primary,
                     @Qualifier("fallbackLlm") OpenAiCompatibleClient fallback,
                     LlmProperties properties,
                     MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;

        LlmProperties.CircuitBreaker settings = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .waitDurationInOpenState(settings.waitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.callsInHalfOpenState())
                .recordException(LlmRouter::isRetryable)
                .build();
        this.breaker = CircuitBreaker.of(primary.name(), config);

        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker for {}: {}", primary.name(), event.getStateTransition()));

        Gauge.builder("gateway.llm.circuit.state", breaker, b -> b.getState().getOrder())
                .description("Primary provider circuit: 0 closed, 1 open, 2 half-open")
                .register(meterRegistry);
        this.failoversOnError = Counter.builder("gateway.llm.failovers")
                .tag("reason", "error").register(meterRegistry);
        this.failoversOnOpenCircuit = Counter.builder("gateway.llm.failovers")
                .tag("reason", "circuit_open").register(meterRegistry);
    }

    public LlmResult ask(String question) {
        try {
            // Call the primary THROUGH the breaker, so it can count successes and failures
            return breaker.executeSupplier(() -> primary.ask(question));

        } catch (CallNotPermittedException ex) {
            // Breaker is OPEN: don't even try the primary, go straight to the backup
            failoversOnOpenCircuit.increment();
            return fallback.ask(question);

        } catch (RuntimeException ex) {
            if (!isRetryable(ex)) {
                throw ex;                       // "my problem": failing over would not help
            }
            log.warn("{} failed ({}), failing over to {}", primary.name(), ex.getMessage(), fallback.name());
            failoversOnError.increment();
            return fallback.ask(question);
        }
    }

    public String primaryModel() {
        return primary.model();
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    // "Their problem" = worth trying elsewhere
    static boolean isRetryable(Throwable ex) {
        if (ex instanceof ResourceAccessException) {
            return true;                                        // timeout, connection refused
        }
        if (ex instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status >= 500;              // rate limited, server errors
        }
        return false;                                           // 400, 401: don't hide bugs
    }
}