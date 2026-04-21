package com.example.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fallback endpoints — called by the CircuitBreaker GatewayFilter when the CB
 * is OPEN (downstream service has failed too many times).
 *
 * The CircuitBreaker filter config in application.yml:
 *   fallbackUri: forward:/fallback/inventory
 *
 * When the circuit opens, the filter does an internal forward to this controller
 * instead of attempting the real downstream call. The client receives a 503
 * with a helpful message rather than waiting for a connection timeout.
 *
 * IMPORTANT: These paths must be whitelisted in JwtAuthFilter — the internal
 * forward from the CB filter does NOT carry the original Authorization header,
 * so JWT validation would fail on the forwarded request.
 *
 * Why 503 and not 200?
 *   Returning 200 with an empty body hides the degradation from monitoring.
 *   503 is honest — the service is unavailable. Clients and dashboards can act on it.
 *   (Some teams return 200 with a "cached last known value" — that's also valid,
 *   but then you must distinguish "fresh" from "degraded" in the response body.)
 */
@RestController
public class FallbackController {

    private static final Logger logger = LoggerFactory.getLogger(FallbackController.class);

    @GetMapping("/fallback/inventory")
    public Mono<ResponseEntity<String>> inventoryFallback(ServerWebExchange exchange) {
        // The CB filter stores the exception that triggered the fallback
        Throwable cause = exchange.getAttribute(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);

        String reason = cause != null ? cause.getClass().getSimpleName() : "unknown";
        logger.warn("FALLBACK /inventory — circuit OPEN, cause={}", reason);

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Inventory service is temporarily unavailable (circuit breaker is OPEN). " +
                      "Please try again later. [cause=" + reason + "]"));
    }

    @GetMapping("/fallback/orders")
    public Mono<ResponseEntity<String>> ordersFallback(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);

        String reason = cause != null ? cause.getClass().getSimpleName() : "unknown";
        logger.warn("FALLBACK /orders — circuit OPEN, cause={}", reason);

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Order service is temporarily unavailable (circuit breaker is OPEN). " +
                      "Please try again later. [cause=" + reason + "]"));
    }
}
