# Kafka Consumer Read Workflow and Demo

## Recap of Consumer Read Workflow
- Consumers read messages from Kafka topics.
- They poll Kafka for new data and commit offsets to keep track of what they've successfully processed.

## 08:25 - Setup of Kafka Consumer
- **Dependencies**: Uses `spring-kafka` and `jackson-databind`.
- **Deserializer**: Configured to convert byte arrays back into Java objects.
- **Consumer Group**: Consumers sharing the same group ID coordinate to read from the topic partitions.

## 18:09 - Demo Implementation
This directory contains a runnable Spring Boot project demonstrating a Kafka Consumer with deep-dive configurations.

### Project Structure
- `KafkaConsumerConfig.java`: Explicitly configures the consumer stages.
- `OrderConsumer.java`: A `@KafkaListener` that processes `orders` topic messages.
- `OrderJsonDeserializer.java`: Custom Jackson-based deserializer.

### Key Deep-Dive Configs (Mapping to Chapter 9)
| Config | Value | Purpose |
|--------|-------|---------|
| `fetch.min.bytes` | 1 | Stage 3: Minimal data to wait for before responding to a fetch. |
| `max.poll.records` | 50 | Stage 3: Max records returned in a single `poll()` call to prevent processing lag. |
| `enable.auto.commit` | true | Stage 4: Background offset management. |
| `session.timeout.ms` | 45000 | Stage 5: Timeout for detecting consumer crashes. |
| `heartbeat.interval.ms` | 3000 | Stage 5: Frequency of "pings" to the Group Coordinator. |

### Quick Start
1. **Start Kafka**:
   ```bash
   docker compose up -d
   ```
2. **Run Consumer**:
   ```bash
   mvn spring-boot:run
   ```
3. **Produce Data**:
   Use the `kafka-producer-demo` or `kafka-console-producer.sh`:
   ```bash
   docker exec -it kafka-demo-consumer kafka-console-producer.sh --bootstrap-server localhost:9092 --topic orders
   # Paste: {"orderId":"ORD-123","region":"EU","item":"Laptop","quantity":1,"price":1200.0}
   ```

## 24:47 - Internal Working Deep Dive
- **The Fetcher**: In `KafkaConsumerConfig`, we tuned `fetch.min.bytes` and `fetch.max.wait.ms`. This controls the trade-off between throughput and latency.
- **The Coordinator**: Kafka hashes the `group.id` ("notification-service") to find the leader of `__consumer_offsets` partition to act as the group's coordinator.
- **Heartbeat Thread**: Operates independently of the `poll()` loop. If the application thread hangs (e.g., slow processing), `max.poll.interval.ms` (5 mins) will eventually kick it out, even if heartbeats are still being sent.
