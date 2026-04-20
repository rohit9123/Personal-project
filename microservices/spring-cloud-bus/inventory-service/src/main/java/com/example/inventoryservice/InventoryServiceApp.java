package com.example.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;

// @RemoteApplicationEventScan registers OrderCreatedEvent for deserialization.
// Spring Cloud Bus uses the simple class name from the message type header and
// scans the registered package to find the matching class.
@SpringBootApplication
@ConfigurationPropertiesScan
@RemoteApplicationEventScan(basePackages = "com.example.inventoryservice.events")
public class InventoryServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApp.class, args);
    }
}
