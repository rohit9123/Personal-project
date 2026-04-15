package com.example.inventoryservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The port is included in the response so you can see which instance
 * the load balancer picked for each request.
 *
 * Run two instances to observe load balancing:
 *   Instance 1 (default): mvn spring-boot:run
 *   Instance 2:           mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9083
 */
@RestController
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @Value("${server.port}")
    private int serverPort;

    @GetMapping("/inventory/{productId}")
    public String checkInventory(@PathVariable String productId) {
        logger.info("Inventory check for product {} (port {})", productId, serverPort);
        return "Product " + productId + " is IN STOCK (served by port " + serverPort + ")";
    }
}
