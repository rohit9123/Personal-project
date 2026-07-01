package com.example.kafkaconsumer.consumer;

import com.example.kafkaconsumer.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    @KafkaListener(topics = "orders", groupId = "order-consumer-group", containerFactory = "kafkaListenerContainerFactory")
    public void consumeOrder(
            @Payload Order order,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        
        log.info("[CONSUMER] Received Order: {} | Partition: {} | Offset: {}", order.getOrderId(), partition, offset);
        
        // Simulate processing
        try {
            Thread.sleep(500); // simulate some work
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}