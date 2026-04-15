package com.example.orderservice;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Approach 3: @LoadBalanced WebClient (reactive / non-blocking)
 *
 * Use this when your service uses Spring WebFlux.
 * The @LoadBalanced annotation works on WebClient.Builder the same way
 * it does on RestTemplate — installs the LB interceptor.
 *
 * Usage in a reactive service:
 *
 *   @Autowired WebClient.Builder webClientBuilder;
 *
 *   public Mono<String> checkStock(String productId) {
 *       return webClientBuilder.build()
 *           .get()
 *           .uri("http://inventory-service/inventory/" + productId)
 *           .retrieve()
 *           .bodyToMono(String.class);
 *   }
 *
 * This demo uses spring-boot-starter-web (servlet), so WebClient is not
 * wired to a controller here. The bean is included to show the configuration.
 * To use it, add spring-boot-starter-webflux to the pom and add a controller.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
