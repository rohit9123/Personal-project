package com.example.actuatordemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Simple controller with a few endpoints so that /actuator/mappings
 * shows something meaningful, and so you can drive the demo interactively.
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    @GetMapping("/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        log.info("Fetching order: {}", id);         // change to DEBUG via /actuator/loggers
        return Map.of("orderId", id, "status", "CONFIRMED", "amount", 250.00);
    }

    @PostMapping
    public Map<String, Object> placeOrder(@RequestBody Map<String, Object> body) {
        log.info("Placing order: {}", body);
        return Map.of("orderId", "ORD-999", "status", "CREATED");
    }

    @GetMapping
    public List<Map<String, Object>> listOrders() {
        return List.of(
                Map.of("orderId", "ORD-001", "status", "CONFIRMED"),
                Map.of("orderId", "ORD-002", "status", "SHIPPED")
        );
    }

    // ── Demo helpers ──────────────────────────────────────────────────────────

    /** Simulates a payment service outage — watch /actuator/health change to DOWN */
    @PostMapping("/payment/break")
    public String breakPayment() {
        PaymentServiceHealthIndicator.healthy.set(false);
        return "Payment service is now DOWN — check /actuator/health";
    }

    /** Restores the simulated payment service */
    @PostMapping("/payment/fix")
    public String fixPayment() {
        PaymentServiceHealthIndicator.healthy.set(true);
        return "Payment service is now UP — check /actuator/health";
    }
}
