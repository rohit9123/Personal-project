package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inventory controller — extended with break/fix endpoints to demonstrate
 * the Retry and CircuitBreaker GatewayFilters.
 *
 * DEMO MODES
 * ──────────
 *   healthy (default) → GET /inventory/{id} returns HTTP 200
 *   broken            → GET /inventory/{id} returns HTTP 500
 *
 * Toggle via:
 *   POST /inventory/break  — sets mode to broken (simulates service failure)
 *   POST /inventory/fix    — sets mode back to healthy
 *
 * WHAT TO OBSERVE THROUGH THE GATEWAY
 * ─────────────────────────────────────
 *   Retry:  after breaking the service, a single gateway request triggers 3
 *           retry attempts (visible in inventory-service logs as 3 "500" lines
 *           per single client request). After all retries fail the CB records
 *           the outcome as one failure.
 *
 *   CircuitBreaker: after enough accumulated failures (failure-rate >= 60% of
 *   last 5 calls), the CB opens. Subsequent gateway requests return the fallback
 *   response in microseconds without hitting this service at all.
 *
 * X-Authenticated-User header:
 *   If JwtAuthFilter validated a token successfully, the gateway adds this header.
 *   We log it here so you can see the identity propagation end-to-end.
 */
@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @Value("${server.port}")
    private String port;

    // AtomicBoolean for thread-safe reads/writes without synchronization
    private final AtomicBoolean broken = new AtomicBoolean(false);

    /**
     * Main stock-check endpoint.
     *
     * When broken=true returns HTTP 500 so the gateway's Retry filter
     * will attempt the call up to 3 times, and the CircuitBreaker will
     * track the failure in its sliding window.
     */
    @GetMapping("/inventory/{productId}")
    public ResponseEntity<String> checkInventory(
            @PathVariable String productId,
            @RequestHeader(value = "X-Authenticated-User", required = false) String user) {

        if (broken.get()) {
            logger.warn("[port={}] [user={}] /inventory/{} → BROKEN — returning 500",
                    port, user, productId);
            return ResponseEntity.status(500)
                    .body("Inventory service UNAVAILABLE for " + productId +
                          " [instance port=" + port + "]");
        }

        logger.info("[port={}] [user={}] /inventory/{} → HEALTHY — returning stock",
                port, user, productId);
        return ResponseEntity.ok(
                "Product '" + productId + "' is IN STOCK  [served by instance on port " + port + "]"
        );
    }

    /** Simulate an outage — triggers Retry and, eventually, the CircuitBreaker. */
    @PostMapping("/inventory/break")
    public String breakService() {
        broken.set(true);
        logger.warn("=== [port={}] Inventory set to BROKEN mode ===", port);
        return "Inventory service is now BROKEN — GET /inventory/** returns HTTP 500";
    }

    /** Simulate recovery — lets the CircuitBreaker transition back to CLOSED. */
    @PostMapping("/inventory/fix")
    public String fixService() {
        broken.set(false);
        logger.info("=== [port={}] Inventory set to HEALTHY mode ===", port);
        return "Inventory service is now HEALTHY — GET /inventory/** returns HTTP 200";
    }
}
