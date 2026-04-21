package com.example.eurekaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka Server — the central service registry for this demo.
 *
 * Every service (inventory-service, order-service, api-gateway) registers here
 * on startup and sends a heartbeat every 30s to stay listed.
 *
 * The API Gateway queries this registry to resolve lb://inventory-service
 * and lb://order-service into real host:port addresses at call time.
 *
 * Dashboard: http://localhost:8761
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApp {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApp.class, args);
    }
}
