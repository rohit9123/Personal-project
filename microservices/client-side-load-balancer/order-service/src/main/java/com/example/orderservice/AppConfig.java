package com.example.orderservice;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Wires up the @LoadBalanced RestTemplate and registers the custom LB config.
 *
 * @LoadBalancerClient scopes LoadBalancerConfig (Random algorithm) to
 * "inventory-service" only. Remove this annotation to fall back to the
 * default Round Robin for all services.
 *
 * To use Round Robin instead, comment out @LoadBalancerClient below.
 */
@Configuration
@LoadBalancerClient(name = "inventory-service", configuration = LoadBalancerConfig.class)
public class AppConfig {

    @Bean
    @LoadBalanced   // intercepts RestTemplate calls and resolves logical names via LB
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
