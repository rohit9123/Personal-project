package com.example.kafkaproducer.producer;

import com.example.kafkaproducer.model.Order;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Three send patterns — each shows a different trade-off between latency,
 * throughput, and caller visibility into the broker ack.
 */
@Component
public class OrderProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderProducer.class);
    private static final String TOPIC = "orders";

    private final KafkaTemplate<String, Order> kafkaTemplate;

    public OrderProducer(KafkaTemplate<String, Order> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Async send — returns immediately; callback fires on the Sender Thread
     * once the broker has acknowledged.
     *
     * Use for: high-throughput pipelines where you want to log success/failure
     * but the application does not need to wait for the ack before proceeding.
     */
    public CompletableFuture<RecordMetadata> sendAsync(Order order) {
        return kafkaTemplate.send(TOPIC, order.getRegion(), order)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[ASYNC] FAILED  order={}  error={}", order.getOrderId(), ex.getMessage());
                    } else {
                        RecordMetadata meta = result.getRecordMetadata();
                        log.info("[ASYNC] OK      order={}  partition={}  offset={}",
                                order.getOrderId(), meta.partition(), meta.offset());
                    }
                })
                .thenApply(SendResult::getRecordMetadata);
    }

    /**
     * Sync send — blocks the calling thread until the broker acks (or timeout).
     *
     * Use for: cases where you must know the offset before committing a DB record,
     * or in test code where you need deterministic ordering of log output.
     * Throughput is bounded by broker round-trip latency (typically 1–10 ms each).
     */
    public RecordMetadata sendSync(Order order)
            throws ExecutionException, InterruptedException, TimeoutException {
        RecordMetadata meta = kafkaTemplate.send(TOPIC, order.getRegion(), order)
                .get(10, TimeUnit.SECONDS)
                .getRecordMetadata();
        log.info("[SYNC]  OK      order={}  partition={}  offset={}",
                order.getOrderId(), meta.partition(), meta.offset());
        return meta;
    }

    /**
     * Fire and forget — no callback, no waiting.
     *
     * The Sender Thread still retries on failure and idempotence still applies,
     * but the application never observes the outcome. Use only where occasional
     * undetected loss is acceptable (metrics, non-critical events).
     */
    public void sendFireAndForget(Order order) {
        kafkaTemplate.send(TOPIC, order.getRegion(), order);
        log.info("[F&F]   QUEUED  order={}", order.getOrderId());
    }
}
