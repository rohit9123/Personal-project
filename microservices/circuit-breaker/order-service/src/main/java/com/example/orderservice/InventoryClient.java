package com.example.orderservice;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Inventory client protected by a Resilience4j circuit breaker.
 *
 * HOW THE CIRCUIT BREAKER WORKS HERE
 * ------------------------------------
 * The @CircuitBreaker annotation wraps checkStock() in an AOP proxy.
 * When the circuit is CLOSED (healthy), calls pass through normally.
 * When enough calls fail (failure-rate-threshold), the proxy opens the circuit
 * and immediately throws CallNotPermittedException instead of hitting the network.
 * The fallbackMethod is invoked in all failure cases — both real failures
 * and fast-fail rejections from an open circuit.
 *
 * CLOSED  →  too many failures  →  OPEN
 * OPEN    →  wait-duration expires  →  HALF_OPEN
 * HALF_OPEN  →  probe calls succeed  →  CLOSED
 * HALF_OPEN  →  probe calls fail     →  OPEN  (back to waiting)
 */
@Component
public class InventoryClient {

    private static final Logger logger = LoggerFactory.getLogger(InventoryClient.class);

    private final RestTemplate restTemplate;
    private final String inventoryServiceUrl;

    public InventoryClient(RestTemplate restTemplate,
                           @Value("${inventory.service.url}") String inventoryServiceUrl) {
        this.restTemplate = restTemplate;
        this.inventoryServiceUrl = inventoryServiceUrl;
    }

    /**
     * Call inventory-service. If the circuit is OPEN, this method is never
     * executed — the AOP proxy short-circuits directly to the fallback.
     *
     * @param productId the product to check
     * @return stock status from inventory-service
     */
    @CircuitBreaker(name = "inventory-cb", fallbackMethod = "fallback")
    public String checkStock(String productId) {
        String url = inventoryServiceUrl + "/inventory/" + productId;
        logger.info("[{}] CB is CLOSED/HALF_OPEN — calling inventory-service at {}", productId, url);
        return restTemplate.getForObject(url, String.class);
    }

    /**
     * Fallback invoked when:
     *   1. The actual call throws an exception (connection failure, 5xx)
     *   2. The circuit is OPEN — Resilience4j throws CallNotPermittedException,
     *      which is also caught here
     *
     * In production this would return cached data, a degraded response, or
     * queue the order for later fulfillment. Here we return a static message
     * that makes the fallback path obvious in the demo.
     */
    public String fallback(String productId, Throwable t) {
        logger.warn("[{}] FALLBACK triggered — cause: {}", productId, t.getClass().getSimpleName());
        return "[FALLBACK] Inventory unavailable for " + productId
                + " — showing cached stock data. (cause: " + t.getClass().getSimpleName() + ")";
    }
}
