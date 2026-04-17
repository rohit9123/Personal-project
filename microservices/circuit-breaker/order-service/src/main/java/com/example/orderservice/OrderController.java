package com.example.orderservice;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final InventoryClient inventoryClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public OrderController(InventoryClient inventoryClient,
                           CircuitBreakerRegistry circuitBreakerRegistry) {
        this.inventoryClient = inventoryClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * Place an order for a product.
     * Internally calls inventory-service through the circuit breaker.
     * Returns the fallback response when the circuit is OPEN.
     */
    @GetMapping("/order/{productId}")
    public Map<String, Object> placeOrder(@PathVariable String productId) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("inventory-cb");
        String cbState = cb.getState().name();

        logger.info("=== ORDER request: product={}, CB state={} ===", productId, cbState);

        String stockResult = inventoryClient.checkStock(productId);

        return Map.of(
                "product", productId,
                "inventoryResponse", stockResult,
                "circuitBreakerState", cb.getState().name()
        );
    }

    /**
     * Inspect the current circuit breaker state and metrics without making an inventory call.
     * Useful for polling CB state during the demo.
     */
    @GetMapping("/order/circuit-state")
    public Map<String, Object> circuitState() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("inventory-cb");
        CircuitBreaker.Metrics metrics = cb.getMetrics();

        return Map.of(
                "state", cb.getState().name(),
                "failureRate", metrics.getFailureRate() + "%",
                "bufferedCalls", metrics.getNumberOfBufferedCalls(),
                "failedCalls", metrics.getNumberOfFailedCalls(),
                "successfulCalls", metrics.getNumberOfSuccessfulCalls(),
                "notPermittedCalls", metrics.getNumberOfNotPermittedCalls()
        );
    }
}
