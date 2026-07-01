package com.example.kafkaproducer.config;

import com.example.kafkaproducer.model.Order;
import com.example.kafkaproducer.partitioner.RegionPartitioner;
import com.example.kafkaproducer.serializer.OrderJsonSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires up the full 5-stage producer pipeline explicitly.
 * Every config key maps to a stage in kafka-chapter-8.md.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Order> producerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // ── Stage 1: Serializer ────────────────────────────────────────────────
        // Key stays a plain string; value uses our custom JSON serializer.
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, OrderJsonSerializer.class);

        // ── Stage 2: Partitioner ───────────────────────────────────────────────
        // RegionPartitioner pins EU→0, US→1, APAC→2 for strict per-region ordering.
        props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, RegionPartitioner.class);

        // ── Stage 3: RecordAccumulator ─────────────────────────────────────────
        // batch.size: flush when a batch reaches 32 KB (default 16 KB).
        // linger.ms:  also flush after 10 ms even if the batch isn't full.
        // buffer.memory: total in-memory budget for all batches (32 MB).
        // max.block.ms: how long send() blocks when buffer is full before throwing.
        props.put(ProducerConfig.BATCH_SIZE_CONFIG,    32_768);
        props.put(ProducerConfig.LINGER_MS_CONFIG,     10);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33_554_432L);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG,  60_000);

        // ── Stage 4: Compression ───────────────────────────────────────────────
        // lz4: best throughput/CPU trade-off for high-volume JSON.
        // The broker stores and replicates compressed bytes as-is (no re-compression).
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        // ── Stage 5: Sender Thread ─────────────────────────────────────────────
        // acks=all: wait for all ISR members — no data loss if leader crashes.
        // max.in.flight: up to 5 unacked requests per broker connection in parallel.
        // delivery.timeout.ms: hard deadline from send() to final ack or failure.
        props.put(ProducerConfig.ACKS_CONFIG,                             "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,   5);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,              120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,               30_000);

        // ── Reordering fix: Idempotence ────────────────────────────────────────
        // Assigns a Producer ID + per-partition sequence numbers.
        // Broker rejects out-of-order/duplicate sequences → safe retries with
        // 5 in-flight requests and no reordering.
        // Auto-enforces: acks=all, retries=MAX_VALUE (consistent with what we set above).
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Order> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // KafkaAdmin (auto-configured by Spring Boot) picks this up and creates
    // the topic on startup if it doesn't already exist.
    @Bean
    public NewTopic ordersTopic() {
        return TopicBuilder.name("orders")
                .partitions(3)
                .replicas(2)
                .build();
    }
}
