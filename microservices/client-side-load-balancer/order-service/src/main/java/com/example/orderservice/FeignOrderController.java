package com.example.orderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approach 2: FeignClient
 *
 * Feign generates the HTTP client implementation from the InventoryFeignClient
 * interface at startup. Load balancing is transparent — no @LoadBalanced,
 * no URL construction. Feign integrates with Spring Cloud LoadBalancer directly.
 *
 * Compare with RestTemplateOrderController:
 *   RestTemplate → explicit URL string + @LoadBalanced interceptor
 *   Feign        → no URL, no interceptor wiring — just call the interface method
 */
@RestController
@RequestMapping("/order/feign")
public class FeignOrderController {

    private static final Logger logger = LoggerFactory.getLogger(FeignOrderController.class);

    private final InventoryFeignClient inventoryClient;

    public FeignOrderController(InventoryFeignClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @GetMapping("/{id}")
    public String placeOrder(@PathVariable String id) {
        logger.info("Placing order {} via Feign", id);

        String stock = inventoryClient.checkInventory(id);

        return "Order " + id + " | " + stock;
    }
}
