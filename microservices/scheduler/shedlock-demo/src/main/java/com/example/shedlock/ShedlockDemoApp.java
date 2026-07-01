package com.example.shedlock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ShedLock Demo — Distributed Scheduler Locking
 *
 * Run one instance:
 *   mvn spring-boot:run
 *
 * Simulate two pods (they share the H2 file DB):
 *   Terminal 1: mvn spring-boot:run
 *   Terminal 2: mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
 *
 * Watch the logs: only one pod logs "LOCK ACQUIRED" per trigger window.
 * Inspect the lock table: http://localhost:8080/h2-console
 *   JDBC URL: jdbc:h2:file:./data/shedlock-demo
 *   Query:    SELECT * FROM shedlock;
 */
@SpringBootApplication
public class ShedlockDemoApp {

    public static void main(String[] args) {
        SpringApplication.run(ShedlockDemoApp.class, args);
    }
}
