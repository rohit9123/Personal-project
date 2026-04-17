package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service — deliberately flaky to demonstrate retry strategies.
 *
 * Pass ?failTimes=N on the first call for a given productId to configure
 * how many times that productId should return HTTP 500 before succeeding.
 * The order-service uses this to trigger retries and show each strategy.
 */
@SpringBootApplication
public class InventoryServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
