package com.example.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }

    /**
     * RestTemplateBuilder (auto-configured by Spring Boot) adds a
     * TracingClientHttpRequestInterceptor before build() is called.
     * This interceptor injects the W3C "traceparent" header on every
     * outbound call, propagating the active trace to inventory-service.
     */
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
