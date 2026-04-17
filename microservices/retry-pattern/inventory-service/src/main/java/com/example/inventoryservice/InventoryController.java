package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flaky inventory endpoint — simulates transient failures to drive retry demos.
 *
 * HOW IT WORKS
 * ------------
 * Each productId has an independent call counter. Pass ?failTimes=N on the
 * FIRST call for a productId to configure N failures before success.
 * Retries from the order-service keep passing the same ?failTimes=N so the
 * service ignores the re-initialization (counter already exists) and just
 * increments the counter.
 *
 * Example (failTimes=2):
 *   Call #1 → counter=1, 1 ≤ 2 → HTTP 500
 *   Call #2 → counter=2, 2 ≤ 2 → HTTP 500
 *   Call #3 → counter=3, 3 > 2 → HTTP 200 ✓ (counter reset for next run)
 *
 * Reset endpoint: DELETE /inventory/reset/{productId}
 */
@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    // Per-productId call counters
    private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    // Per-productId configured fail counts (set only on first call)
    private final ConcurrentHashMap<String, Integer> maxFailTimes = new ConcurrentHashMap<>();

    @GetMapping("/inventory/{productId}")
    public ResponseEntity<String> checkInventory(
            @PathVariable String productId,
            @RequestParam(required = false) Integer failTimes) {

        // Initialize failTimes only if this productId has no existing config.
        // This lets retries pass the same ?failTimes param without resetting the counter.
        if (failTimes != null) {
            maxFailTimes.putIfAbsent(productId, failTimes);
            callCounts.putIfAbsent(productId, new AtomicInteger(0));
        }

        int callNumber = callCounts
                .computeIfAbsent(productId, k -> new AtomicInteger(0))
                .incrementAndGet();
        int maxFails = maxFailTimes.getOrDefault(productId, 0);

        if (callNumber <= maxFails) {
            logger.warn("[{}] call #{} — FAILING (configured to fail {} time(s))",
                    productId, callNumber, maxFails);
            return ResponseEntity.status(500)
                    .body("Inventory temporarily unavailable for " + productId
                            + " (call #" + callNumber + " of " + maxFails + " planned failures)");
        }

        // Success — clear counters so the next demo run starts fresh
        callCounts.remove(productId);
        maxFailTimes.remove(productId);

        logger.info("[{}] call #{} — SUCCESS (recovered after {} failure(s))",
                productId, callNumber, callNumber - 1);
        return ResponseEntity.ok(
                "Product " + productId + " is IN STOCK"
                        + (callNumber > 1 ? " (recovered after " + (callNumber - 1) + " failure(s))" : ""));
    }

    /**
     * Manually reset a productId's counter — useful between demo runs.
     */
    @DeleteMapping("/inventory/reset/{productId}")
    public String reset(@PathVariable String productId) {
        callCounts.remove(productId);
        maxFailTimes.remove(productId);
        logger.info("[{}] counter reset", productId);
        return "Reset call counter for " + productId;
    }
}
