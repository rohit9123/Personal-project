# Kafka Producer Demo

Runnable Spring Boot project for **chapter 8: Producer Internals**.

Every config key in `KafkaProducerConfig` is labelled with the pipeline stage it controls.
Run the demo and watch each order land on the correct partition with its offset in the logs.

---

## Quick Start

### 1 — Start Kafka

```bash
# from this directory
docker compose up -d
```

Kafka is ready when `docker compose ps` shows `(healthy)`. Usually ~15 s.

> **Alternative**: use the local binary from chapter 7
> ```bash
> cd ../kafka_2.13-4.2.0
> bin/kafka-server-start.sh config/server.properties
> ```

### 2 — Run the Demo

```bash
mvn spring-boot:run
```

### 3 — Expected Log Output

```
── Demo 1: RegionPartitioner — EU→p0, US→p1, APAC→p2 ──
[ASYNC] OK  order=ORD-001  partition=0  offset=0    ← EU  always lands on p0
[ASYNC] OK  order=ORD-002  partition=1  offset=0    ← US  always lands on p1
[ASYNC] OK  order=ORD-003  partition=2  offset=0    ← APAC always lands on p2

── Demo 2: Ordered EU stream — all land on partition 0 ──
[ASYNC] OK  order=ORD-004  partition=0  offset=1
[ASYNC] OK  order=ORD-005  partition=0  offset=2
[ASYNC] OK  order=ORD-006  partition=0  offset=3    ← EU orders arrive in strict write order

── Demo 3: Sync send — caller blocks until ack ──
[SYNC]  OK  order=ORD-007  partition=1  offset=1    ← US, offset known before moving on

── Demo 4: Fire and forget ──
[F&F]   QUEUED  order=ORD-008                       ← queued; no ack tracking
```

### 4 — Stop Kafka

```bash
docker compose down
```

---

## Project Structure

```
kafka-producer-demo/
├── docker-compose.yml
├── pom.xml
└── src/main/java/com/example/kafkaproducer/
    ├── KafkaProducerDemoApplication.java
    ├── config/
    │   └── KafkaProducerConfig.java      ← all 5 stage configs in one place
    ├── model/
    │   └── Order.java
    ├── serializer/
    │   └── OrderJsonSerializer.java      ← Stage 1: custom serializer plugin
    ├── partitioner/
    │   └── RegionPartitioner.java        ← Stage 2: custom partitioner plugin
    ├── producer/
    │   └── OrderProducer.java            ← 3 send patterns
    └── runner/
        └── DemoRunner.java               ← drives the demo on startup
```

---

## Pipeline → Code Map

| Stage | What it does | Config key | Our code |
|-------|-------------|-----------|----------|
| 1 — Serializer | `Order` → `byte[]` | `value.serializer` | `OrderJsonSerializer` |
| 2 — Partitioner | pick partition | `partitioner.class` | `RegionPartitioner` |
| 3 — RecordAccumulator | buffer into batches | `batch.size`, `linger.ms` | `KafkaProducerConfig` |
| 4 — Compression | compress full batch | `compression.type=lz4` | `KafkaProducerConfig` |
| 5 — Sender Thread | drain → ProduceRequest | `acks=all`, `enable.idempotence` | `KafkaProducerConfig` |

---

## Why Does the `serializer/` Folder Exist?

The 5 stages are Kafka's **fixed internal pipeline** — you cannot change the order or skip them. But **Stages 1 and 2 are plugin slots**: Kafka calls into whatever class you configure via `value.serializer` and `partitioner.class`.

```
Stage 1 slot  →  value.serializer  =  OrderJsonSerializer.class  (our code)
Stage 2 slot  →  partitioner.class =  RegionPartitioner.class    (our code)
Stages 3–5    →  no plugin needed  =  configured via properties only
```

Think of it like a power outlet. The wiring in the wall (the pipeline) is fixed. The `serializer/` folder is the plug we built to fit into socket 1. Kafka supplies the socket; we supply the plug.

If you used built-in types — `String` values and Spring's `JsonSerializer` — you'd need **zero custom code** in those folders because Kafka already ships those plugs.

---

## Key Configs Explained

```properties
# Stage 3 — how batching behaves
batch.size=32768      # flush when batch hits 32 KB
linger.ms=10          # also flush after 10 ms even if batch is partially full

# Stage 4 — compression
compression.type=lz4  # compress the full batch before sending; broker never decompresses

# Stage 5 — durability + reordering fix
acks=all              # wait for all ISR members to ack
enable.idempotence=true  # PID + sequence numbers → no duplicates or reordering on retry
```

---

## Useful Kafka CLI Commands (while demo is running)

```bash
# Consume from the orders topic and see raw JSON + partition
docker exec kafka-demo /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic orders \
  --from-beginning \
  --property print.partition=true \
  --property print.offset=true

# Inspect topic partition layout
docker exec kafka-demo /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic orders
```
