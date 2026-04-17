package com.example.orderservice;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to circuit breaker events and logs every state transition.
 *
 * Resilience4j publishes events on the CircuitBreaker's EventPublisher:
 *   - onStateTransition  → CLOSED → OPEN → HALF_OPEN → CLOSED
 *   - onCallNotPermitted → fired when a call is rejected by an OPEN circuit
 *   - onSuccess / onError → fired for every call outcome
 *
 * Subscribing here (vs. inside InventoryClient) keeps the client clean
 * and makes observability a separate concern — same idea as the
 * RetryEventLogger in the retry-pattern demo.
 *
 * Registration happens on ApplicationReadyEvent so the CircuitBreakerRegistry
 * is fully initialized before we try to look up the named instance.
 */
@Component
public class CircuitBreakerEventLogger {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerEventLogger.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerEventLogger(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerListeners() {
        CircuitBreaker cb = registry.circuitBreaker("inventory-cb");

        // STATE TRANSITIONS — the most important events to watch during the demo
        cb.getEventPublisher().onStateTransition(event ->
                logger.warn(">>> CB STATE CHANGE: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState())
        );

        // CALL REJECTED — fired every time an OPEN circuit fast-fails a call
        cb.getEventPublisher().onCallNotPermitted(event ->
                logger.warn(">>> CB REJECTED call (circuit is OPEN) — fast-failing to fallback")
        );

        // SUCCESS — log when calls go through cleanly
        cb.getEventPublisher().onSuccess(event ->
                logger.info(">>> CB call SUCCESS (duration: {}ms)",
                        event.getElapsedDuration().toMillis())
        );

        // ERROR — log failures that count toward the failure-rate threshold
        cb.getEventPublisher().onError(event ->
                logger.error(">>> CB call FAILED — {} (failure rate may be rising)",
                        event.getThrowable().getClass().getSimpleName())
        );
    }
}
