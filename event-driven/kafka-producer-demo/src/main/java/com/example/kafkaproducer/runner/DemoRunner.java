package com.example.kafkaproducer.runner;

import com.example.kafkaproducer.model.Order;
import com.example.kafkaproducer.producer.OrderProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs once on startup, demonstrates all three send patterns, then exits.
 *
 * Pre-requisite: Kafka running on localhost:9092 (see chapter 7 single-node setup).
 *   cd event-driven/kafka_2.13-4.2.0
 *   bin/kafka-server-start.sh config/server.properties
 */
@Component
public class DemoRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final OrderProducer producer;
    private final KafkaTemplate<String, Order> kafkaTemplate;

    public DemoRunner(OrderProducer producer, KafkaTemplate<String, Order> kafkaTemplate) {
        this.producer = producer;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("════════════════════════════════════════════════");
        log.info(" Kafka Producer Pipeline Demo  (chapter 8)");
        log.info("════════════════════════════════════════════════");

        // ── Demo 1: RegionPartitioner (Stage 2) ───────────────────────────────
        // Each region goes to a fixed partition.  Watch the partition= in the log.
        log.info("── Demo 1: RegionPartitioner — EU→p0, US→p1, APAC→p2 ──");
        List<CompletableFuture<RecordMetadata>> regionFutures = List.of(
                producer.sendAsync(new Order("ORD-001", "EU",   "Laptop",    1, 999.99)),
                producer.sendAsync(new Order("ORD-002", "US",   "Phone",     2, 499.00)),
                producer.sendAsync(new Order("ORD-003", "APAC", "Tablet",    1, 349.00))
        );

        // ── Demo 2: Ordered stream (Stage 2 + Stage 3) ───────────────────────
        // All 3 EU orders → same partition → consumer sees them in this exact order.
        log.info("── Demo 2: Ordered EU stream — all land on partition 0 ──");
        List<CompletableFuture<RecordMetadata>> orderedFutures = List.of(
                producer.sendAsync(new Order("ORD-004", "EU", "Mouse",    1,  29.99)),
                producer.sendAsync(new Order("ORD-005", "EU", "Keyboard", 1,  79.99)),
                producer.sendAsync(new Order("ORD-006", "EU", "Monitor",  1, 299.00))
        );

        // Wait for all async sends to be acked before the sync demo.
        CompletableFuture.allOf(
                regionFutures.toArray(new CompletableFuture[0])
        ).get();
        CompletableFuture.allOf(
                orderedFutures.toArray(new CompletableFuture[0])
        ).get();

        // ── Demo 3: Sync send (Stage 5 — blocks on broker ack) ───────────────
        // Use when you need the partition+offset before doing further work.
        log.info("── Demo 3: Sync send — caller blocks until ack ──");
        RecordMetadata syncMeta = producer.sendSync(
                new Order("ORD-007", "US", "Headphones", 3, 149.00));
        log.info("[SYNC]  Confirmed at partition={} offset={}", syncMeta.partition(), syncMeta.offset());

        // ── Demo 4: Fire and forget ───────────────────────────────────────────
        log.info("── Demo 4: Fire and forget — no ack tracking ──");
        producer.sendFireAndForget(new Order("ORD-008", "APAC", "Camera", 1, 599.00));

        // flush() drains any remaining batches from the RecordAccumulator (Stage 3)
        // to the network before the app exits.
        kafkaTemplate.flush();
        log.info("════════════════════════════════════════════════");
        log.info(" Done. All batches flushed.");
        log.info("════════════════════════════════════════════════");
    }
}
