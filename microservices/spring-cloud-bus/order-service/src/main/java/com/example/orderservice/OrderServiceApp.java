package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.bus.jackson.RemoteApplicationEventScan;

// @RemoteApplicationEventScan tells Spring Cloud Bus to register
// OrderCreatedEvent for Jackson serialization/deserialization.
// Without this, custom events published to the bus are not recognised
// by the receiving service when it tries to deserialize them.
@SpringBootApplication
@ConfigurationPropertiesScan
@RemoteApplicationEventScan(basePackages = "com.example.orderservice.events")
public class OrderServiceApp {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApp.class, args);
    }
}
