package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controllable inventory endpoint — lets you toggle the service between healthy
 * and broken states so you can watch the circuit breaker open and recover in real time.
 *
 * STATE MACHINE
 * -------------
 *   healthy (default) → POST /inventory/break → broken
 *   broken            → POST /inventory/fix   → healthy
 *
 * While broken, every GET /inventory/{productId} returns HTTP 500.
 * While healthy, every GET /inventory/{productId} returns HTTP 200.
 *
 * CIRCUIT BREAKER DEMO SCRIPT
 * ----------------------------
 *   1. Start both services (inventory is healthy by default).
 *   2. Call GET /order/{id} a few times — all succeed, CB stays CLOSED.
 *   3. POST /inventory/break — inventory now returns 500.
 *   4. Call GET /order/{id} five times — see failures accumulate.
 *      After 3 failures in the 5-call window (60% threshold), CB opens.
 *   5. Subsequent calls to GET /order/{id} fast-fail immediately (no HTTP hop)
 *      and return the fallback response.
 *   6. After 10 seconds the CB moves to HALF_OPEN automatically.
 *   7. POST /inventory/fix — inventory returns 200 again.
 *   8. The 2 probe calls in HALF_OPEN succeed → CB closes again.
 */
@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    // Shared mutable flag — safe because writes happen via single admin endpoints,
    // not under concurrent load. AtomicBoolean used for visibility across threads.
    private final AtomicBoolean broken = new AtomicBoolean(false);

    @GetMapping("/inventory/{productId}")
    public ResponseEntity<String> checkInventory(@PathVariable String productId) {
        if (broken.get()) {
            logger.warn("[{}] inventory is BROKEN — returning HTTP 500", productId);
            return ResponseEntity.status(500)
                    .body("Inventory service unavailable for " + productId);
        }
        logger.info("[{}] inventory is HEALTHY — returning stock", productId);
        return ResponseEntity.ok("Product " + productId + " is IN STOCK");
    }

    /** Simulate an outage — all subsequent stock checks will return HTTP 500. */
    @PostMapping("/inventory/break")
    public String breakService() {
        broken.set(true);
        logger.warn("=== Inventory service set to BROKEN mode ===");
        return "Inventory service is now BROKEN (returning 500 for all requests)";
    }

    /** Simulate recovery — all subsequent stock checks will return HTTP 200. */
    @PostMapping("/inventory/fix")
    public String fixService() {
        broken.set(false);
        logger.info("=== Inventory service set to HEALTHY mode ===");
        return "Inventory service is now HEALTHY (returning 200 for all requests)";
    }
}
