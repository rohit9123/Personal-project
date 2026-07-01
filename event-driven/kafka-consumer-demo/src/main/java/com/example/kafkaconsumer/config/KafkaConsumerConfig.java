package com.example.kafkaconsumer.config;

import com.example.kafkaconsumer.deserializer.OrderJsonDeserializer;
import com.example.kafkaconsumer.model.Order;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Order> consumerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,          "notification-service");

        // ── Deserializers ─────────────────────────────────────────────────────
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, OrderJsonDeserializer.class);

        // ── Stage 3: Fetcher Configuration (from Chapter 9) ───────────────────
        // fetch.min.bytes: Wait until at least 1 byte is ready.
        // fetch.max.wait.ms: If min.bytes isn't met, wait up to 500ms.
        // max.poll.records: Return up to 50 records per poll() call.
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG,       1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,     500);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,      50);

        // ── Stage 4: Offset Management ────────────────────────────────────────
        // enable.auto.commit: Periodically commit offsets in the background.
        // auto.commit.interval.ms: Commit every 5 seconds.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,      true);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);
        
        // Where to start if no offset is found (latest | earliest)
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,       "earliest");

        // ── Stage 5: Group Coordination (Heartbeats & Rebalancing) ────────────
        // session.timeout.ms: Broker kicks consumer if no heartbeat in 45s.
        // heartbeat.interval.ms: Send heartbeat every 3s.
        // max.poll.interval.ms: Kick consumer if poll() isn't called for 5 mins.
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,      45000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,   3000);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,    300000);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Order> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Order> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        
        // Strategy for rebalancing (default is RangeAssignor)
        // Can be customized here if needed.
        
        return factory;
    }
}
