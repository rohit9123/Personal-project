package com.example.kafkaproducer.serializer;

import com.example.kafkaproducer.model.Order;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Stage 1 — Serializer
 *
 * Converts an Order to UTF-8 JSON bytes. The broker stores and forwards raw bytes;
 * it has no knowledge of Java types. Any consumer (Java, Python, Go) that understands
 * JSON can decode this payload without sharing a class definition.
 */
public class OrderJsonSerializer implements Serializer<Order> {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public byte[] serialize(String topic, Order data) {
        if (data == null) return null;
        try {
            return mapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize Order to JSON", e);
        }
    }
}
