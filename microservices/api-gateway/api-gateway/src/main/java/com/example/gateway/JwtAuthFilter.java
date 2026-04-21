package com.example.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT Authentication GlobalFilter — runs for every route.
 *
 * WHAT IT DOES:
 *   1. Skips JWT check for whitelisted paths (/token, /fallback/**, /actuator/**)
 *   2. Reads the Authorization: Bearer <token> header
 *   3. Validates the JWT: signature (HS256) + expiry
 *   4. On success: extracts the subject and forwards X-Authenticated-User header
 *      to downstream services so they know WHO made the request
 *   5. On failure: returns 401 Unauthorized immediately — request never reaches
 *      the downstream service
 *
 * FILTER ORDER:
 *   LoggingFilter runs at order -1 (outer layer — wraps everything, logs all requests)
 *   JwtAuthFilter runs at order  0 (inner layer — auth check happens inside logging)
 *
 *   This means BOTH the "IN" and "OUT" log lines appear for every request,
 *   including ones rejected with 401 — the status line shows the auth failure.
 *
 * WHY A GLOBALFILTER FOR JWT:
 *   JWT validation is a cross-cutting concern — every route needs it. A GlobalFilter
 *   applies automatically to ALL routes without adding it to each route's filter list.
 *   Route-specific exceptions (like /token itself) are handled by the WHITELIST.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthFilter.class);

    /**
     * Paths that bypass JWT validation.
     * - /token:       the demo token generation endpoint itself
     * - /fallback/**:  circuit breaker fallback (gateway-internal, not from clients)
     * - /actuator/**:  Spring Boot Actuator endpoints
     */
    private static final List<String> WHITELIST = List.of(
            "/token",
            "/fallback/",
            "/actuator/"
    );

    /**
     * The HMAC-SHA256 signing secret. Must be >= 32 bytes (256 bits) for HS256.
     * Loaded from application.yml → app.jwt.secret.
     *
     * In production: store in a secrets manager (Vault, AWS Secrets Manager),
     * not in a config file.
     */
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ── 1. Whitelist check ────────────────────────────────────────────────
        if (isWhitelisted(path)) {
            logger.debug("JWT check skipped for whitelisted path: {}", path);
            return chain.filter(exchange);
        }

        // ── 2. Extract Bearer token ───────────────────────────────────────────
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("REJECTED [{}] — missing or malformed Authorization header", path);
            return writeUnauthorized(exchange, "Missing Authorization: Bearer <token> header");
        }

        String token = authHeader.substring(7); // strip "Bearer "

        // ── 3. Validate JWT ───────────────────────────────────────────────────
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(
                            Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))
                    )
                    .build()
                    .parseClaimsJws(token)  // throws if signature invalid or expired
                    .getBody();

            String subject = claims.getSubject();
            logger.info("JWT VALID  [{}] — user={}", path, subject);

            // ── 4. Propagate identity to downstream services ──────────────────
            //
            // Downstream services can read X-Authenticated-User from the request
            // headers to know who made the call — without doing their own JWT parse.
            // This is the "token relay" pattern in its simplest form.
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-Authenticated-User", subject)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (JwtException e) {
            // Covers: SignatureException, ExpiredJwtException, MalformedJwtException
            logger.warn("REJECTED [{}] — JWT invalid: {}", path, e.getMessage());
            return writeUnauthorized(exchange, "Invalid or expired token");
        }
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }

    /**
     * Write a 401 response and complete the exchange.
     * WWW-Authenticate header tells the client what auth scheme is expected.
     */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders()
                .add(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"api-gateway\", error=\"" + reason + "\"");
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        // Run AFTER LoggingFilter (order -1) so that all requests — including
        // rejected ones — appear in the gateway logs with their response status.
        return 0;
    }
}
