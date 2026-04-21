package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service — a downstream microservice registered in Eureka.
 *
 * Run a second instance on a different port to prove round-robin load balancing:
 *   mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9083
 *
 * Both instances register as "inventory-service" in Eureka.
 * The API Gateway will alternate between them on each request.
 */
@SpringBootApplication
public class InventoryServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
