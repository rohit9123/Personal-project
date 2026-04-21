package com.example.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Demo token generator — makes the JWT demo self-contained.
 *
 * In a real system, token issuance lives in a dedicated Auth Service (Keycloak,
 * Okta, custom OAuth2 server). The gateway only VALIDATES tokens — it never
 * issues them. This endpoint is here purely to avoid needing jwt.io or a
 * separate tool during the demo.
 *
 * This path (/token) is whitelisted in JwtAuthFilter — no token needed to get a token.
 *
 * Usage:
 *   # Get a token for user "rohit" (valid 1 hour)
 *   curl "http://localhost:8080/token?user=rohit"
 *
 *   # Use it in subsequent calls
 *   curl -H "Authorization: Bearer <token>" http://localhost:8080/api/inventory/item-1
 */
@RestController
public class TokenController {

    private static final Logger logger = LoggerFactory.getLogger(TokenController.class);

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @GetMapping("/token")
    public Mono<String> generateToken(
            @RequestParam(defaultValue = "demo-user") String user) {

        String token = Jwts.builder()
                .setSubject(user)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000L)) // 1 hour
                .signWith(
                        Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256
                )
                .compact();

        logger.info("Issued JWT for user={}", user);

        // Mono.just wraps the blocking JWT build in the reactive pipeline —
        // acceptable here since jjwt's builder is CPU-bound, not I/O-bound
        return Mono.just(token);
    }
}
