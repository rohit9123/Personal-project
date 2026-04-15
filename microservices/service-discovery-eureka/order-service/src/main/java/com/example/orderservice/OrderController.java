package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Order Controller — uses @LoadBalanced RestTemplate.
 *
 * Instead of manually resolving instances via DiscoveryClient,
 * we just use the logical service name "inventory-service" in the URL.
 * Spring Cloud LoadBalancer intercepts and picks an instance.
 */
@RestController
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final RestTemplate restTemplate;

    public OrderController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/order/{id}")
    public String getOrder(@PathVariable String id) {
        logger.info("Received request for order ID: {}", id);

        /*
         * "inventory-service" is resolved by Spring Cloud LoadBalancer:
         *   1. Looks up instances of "inventory-service" from Eureka cache
         *   2. Picks one using Round Robin (default) algorithm
         *   3. Replaces "inventory-service" with the real host:port
         *   4. Sends the actual HTTP call
         */
        String inventoryResponse = restTemplate.getForObject(
            "http://inventory-service/inventory/" + id,
            String.class
        );

        logger.info("Inventory response: {}", inventoryResponse);

        return "Order " + id + " | Inventory check: " + inventoryResponse;
    }
}
