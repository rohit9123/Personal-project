package com.example.orderservice;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative HTTP client for inventory-service.
 *
 * You define the interface; Spring generates the implementation at runtime.
 * Load balancing is built-in — Feign resolves "inventory-service" via
 * Spring Cloud LoadBalancer automatically, no @LoadBalanced annotation needed.
 *
 * name must match a key under spring.cloud.discovery.client.simple.instances
 * (or spring.application.name if using Eureka).
 */
@FeignClient(name = "inventory-service")
public interface InventoryFeignClient {

    @GetMapping("/inventory/{productId}")
    String checkInventory(@PathVariable("productId") String productId);
}
