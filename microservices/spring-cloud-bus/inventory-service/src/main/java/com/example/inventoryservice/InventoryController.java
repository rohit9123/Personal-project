package com.example.inventoryservice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryConfigProperties config;

    public InventoryController(InventoryConfigProperties config) {
        this.config = config;
    }

    /**
     * GET /inventory/config — shows current config (live-updated on /actuator/busrefresh).
     *
     * Demo:
     *   1. GET /inventory/config              — note current greeting and stockThreshold
     *   2. Edit config-repo/inventory-service.yml (change greeting)
     *   3. POST http://localhost:9081/actuator/busrefresh   — broadcasts refresh to ALL services
     *   4. GET /inventory/config              — greeting updated, no restart needed
     *      (same on order-service: GET http://localhost:9081/orders/config also updated)
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "greeting",       config.getGreeting(),
            "stockThreshold", config.getStockThreshold()
        );
    }
}
