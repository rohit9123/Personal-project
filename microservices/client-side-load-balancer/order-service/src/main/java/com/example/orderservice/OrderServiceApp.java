package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Order Service — demonstrates all three ways to use Spring Cloud LoadBalancer:
 *
 *   GET /order/resttemplate/{id}  → @LoadBalanced RestTemplate
 *   GET /order/feign/{id}         → FeignClient (declarative, LB built-in)
 *
 * Instance list is static (application.yml), so no Eureka is needed.
 * Start two inventory-service instances to observe round-robin in action.
 */
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
