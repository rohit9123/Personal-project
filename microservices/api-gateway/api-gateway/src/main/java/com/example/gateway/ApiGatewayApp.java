package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — the single entry point for all client traffic.
 *
 * All routing is configured in application.yml. No Java route config needed
 * for this demo — yml-based routes keep the code readable and change-trackable.
 *
 * What happens on startup:
 *   1. Registers itself with Eureka (as "api-gateway")
 *   2. Fetches the full service registry from Eureka
 *   3. Starts the reactive Netty server on port 8080
 *   4. Begins processing requests through the filter chain
 *
 * Call flow for GET /api/inventory/item-1:
 *   Client → Gateway (8080)
 *     → RoutePredicateHandlerMapping: matches route id=inventory-route (Path=/api/inventory/**)
 *     → StripPrefix filter: strips "/api" → /inventory/item-1
 *     → ReactiveLoadBalancerClientFilter: resolves lb://inventory-service via Eureka
 *         → picks an instance (e.g. 127.0.0.1:9081) using Round Robin
 *     → LoggingFilter: logs the forwarded request
 *     → NettyRoutingFilter: forwards HTTP request to 127.0.0.1:9081/inventory/item-1
 *     → response flows back through the filter chain to the client
 */
@SpringBootApplication
public class ApiGatewayApp {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApp.class, args);
    }
}
