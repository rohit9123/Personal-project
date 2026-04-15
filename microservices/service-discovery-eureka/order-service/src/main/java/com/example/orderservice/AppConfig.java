package com.example.orderservice;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Load-balanced RestTemplate configuration.
 *
 * @LoadBalanced tells Spring Cloud to intercept calls made through
 * this RestTemplate and resolve logical service names (e.g. "inventory-service")
 * to actual host:port using the load balancer.
 *
 * Default algorithm: Round Robin (cycles through instances in order).
 */
@Configuration
public class AppConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
