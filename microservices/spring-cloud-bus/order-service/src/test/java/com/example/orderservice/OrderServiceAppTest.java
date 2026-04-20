package com.example.orderservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.annotation.Bean;

// src/test/resources/application.yml overrides the main one to:
//   - set spring.config.import=optional:configserver: (suppresses mandatory import)
//   - disable Config Server and Bus (no RabbitMQ or Config Server needed)
//   - supply order.* properties normally served by Config Server
//
// BusPropertiesStub provides a BusProperties bean because spring.cloud.bus.enabled=false
// prevents the Bus autoconfiguration from registering one, yet OrderController
// still declares it as a constructor dependency.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrderServiceAppTest {

    @TestConfiguration
    static class BusPropertiesStub {
        @Bean
        BusProperties busProperties() {
            BusProperties props = new BusProperties();
            props.setId("order-service:0:test");
            return props;
        }
    }

    @Test
    void contextLoads() {}
}
