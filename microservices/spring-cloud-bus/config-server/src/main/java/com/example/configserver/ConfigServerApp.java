package com.example.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

// @EnableConfigServer turns this app into a Spring Cloud Config Server.
// spring-cloud-starter-bus-amqp on the classpath auto-configures Bus —
// no extra annotation needed. At startup this service joins the
// 'springCloudBus' RabbitMQ exchange and can relay bus events.
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApp {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApp.class, args);
    }
}
