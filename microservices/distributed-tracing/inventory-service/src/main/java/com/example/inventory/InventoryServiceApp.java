package com.example.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryServiceApp {

    /*
     * No RestTemplate @Bean here — inventory-service only receives requests; it does not
     * make outbound HTTP calls. Compare with OrderServiceApp, which defines a RestTemplate
     * built via RestTemplateBuilder to get the tracing interceptor that propagates traceparent.
     */
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
