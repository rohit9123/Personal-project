package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order Service — a second downstream microservice behind the API Gateway.
 *
 * Demonstrates that the gateway can route to multiple different services
 * based on path prefix:
 *   /api/orders/**  →  order-service (this service, port 9082)
 *   /api/inventory/** → inventory-service (port 9081 / 9083)
 */
@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
