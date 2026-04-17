package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service — simulates a downstream dependency that can go down and recover.
 *
 * Control endpoints (used during the circuit-breaker demo):
 *   POST /inventory/break  →  puts the service into "broken" mode (returns HTTP 500)
 *   POST /inventory/fix    →  restores the service to healthy mode (returns HTTP 200)
 *   GET  /inventory/{id}   →  check stock for a product (affected by broken/fix state)
 */
@SpringBootApplication
public class InventoryServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
