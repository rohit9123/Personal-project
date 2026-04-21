package com.example.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global logging filter — runs for every request the gateway handles.
 *
 * A GlobalFilter applies across ALL routes (unlike a GatewayFilter which
 * you attach to a specific route). Here we log:
 *   - Incoming method + path (before forwarding)
 *   - Response status + latency (after the downstream response arrives)
 *
 * Execution order:
 *   getOrder() = -1 means this runs before the built-in filters (which
 *   start at Ordered.LOWEST_PRECEDENCE). Lower number = higher priority.
 *
 * Because Gateway is reactive (WebFlux), the "after" logic must use
 * then(Mono.fromRunnable(...)) — not a regular post-filter callback.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startMs = System.currentTimeMillis();

        logger.info(">>> GATEWAY IN  [{} {}] from {}",
                request.getMethod(),
                request.getURI().getPath(),
                request.getRemoteAddress());

        /*
         * chain.filter(exchange) sends the request downstream.
         * .then() schedules the lambda to run after the response has been written.
         * Mono.fromRunnable is synchronous inside the reactive pipeline — safe here
         * because we're only doing a log write, not blocking I/O.
         */
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long durationMs = System.currentTimeMillis() - startMs;
            logger.info("<<< GATEWAY OUT [{} {}] → {} ({}ms)",
                    request.getMethod(),
                    request.getURI().getPath(),
                    exchange.getResponse().getStatusCode(),
                    durationMs);
        }));
    }

    @Override
    public int getOrder() {
        return -1;   // run before built-in routing filters
    }
}
