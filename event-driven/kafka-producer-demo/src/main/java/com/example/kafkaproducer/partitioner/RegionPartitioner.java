package com.example.kafkaproducer.partitioner;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;

import java.util.Map;

/**
 * Stage 2 — Partitioner
 *
 * Routes orders to a fixed partition based on the record key (region string).
 * This guarantees total ordering per region: all EU events land on partition 0,
 * so a consumer reading partition 0 sees EU orders in strict write order.
 *
 * partition 0 → EU
 * partition 1 → US
 * partition 2 → APAC
 * anything else → hash % numPartitions (safe fallback)
 */
public class RegionPartitioner implements Partitioner {

    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        int numPartitions = cluster.partitionCountForTopic(topic);
        if (keyBytes == null) {
            return 0;
        }
        String region = new String(keyBytes);
        return switch (region) {
            case "EU"   -> 0 % numPartitions;
            case "US"   -> 1 % numPartitions;
            case "APAC" -> 2 % numPartitions;
            default     -> Math.abs(region.hashCode()) % numPartitions;
        };
    }

    @Override public void close()                          {}
    @Override public void configure(Map<String, ?> configs) {}
}
