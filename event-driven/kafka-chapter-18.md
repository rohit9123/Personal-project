# Kafka — Chapter 18: Kafka Streams

> A consumer + producer loop lets you *move* data. Kafka Streams lets you *process* it — stateful joins, aggregations, and windows — with exactly-once guarantees, all as a plain Java library with no separate cluster to run.

---

## 1. What Kafka Streams Is (and Isn't)

Kafka Streams is a **client-side Java library** for building stream-processing applications on top of Kafka. Your app reads from input topics, transforms/aggregates/joins, and writes to output topics — and it's *just a JAR in your service*, not a cluster you deploy and babysit.

### Why it exists

The naive approach is a `KafkaConsumer` poll loop that mutates some local state and produces results. That works until you need:
- **Stateful aggregations** that survive restarts (counts, sums, running balances)
- **Joins** between two streams or a stream and a table
- **Windowing** (events grouped by time)
- **Exactly-once** processing across read → process → write
- **Fault tolerance** — state must not be lost when an instance dies

Hand-rolling all of that correctly is enormous. Kafka Streams gives it to you out of the box.

### How it differs from alternatives

| | Kafka Streams | Flink / Spark Streaming | Raw consumer/producer |
|---|---|---|---|
| Deployment | A library in your app | Separate cluster + job manager | A library in your app |
| Scaling | Add app instances (same consumer group) | Cluster slots/parallelism | Add consumers manually |
| State | Local RocksDB + changelog topics | Managed state backends | You build it yourself |
| Source/Sink | Kafka only | Kafka, files, DBs, many | Kafka only |
| Exactly-once | Built-in (EOS v2) | Built-in | You build it yourself |

**Use Kafka Streams when** your source and sink are both Kafka and you want zero extra infrastructure. **Reach for Flink** when you need many non-Kafka sources/sinks, very large state, or complex event-time processing across a big cluster.

---

## 2. The Core Duality — Streams vs Tables

This is the single most important mental model in Kafka Streams.

### KStream — a stream of facts (an event log)

A `KStream` is an **unbounded, append-only sequence of records**. Every record is an independent fact that *happened*. Two records with the same key are **both** kept — they're two separate events.

```
("alice", deposit $100)
("alice", deposit $50)
```
→ KStream = two events. Alice deposited twice.

### KTable — a changelog / current state

A `KTable` is a **materialized view** where each key maps to its **latest value**. A new record with an existing key is an **update (UPSERT)**, not an append. A `null` value is a **tombstone** that **deletes** the key.

```
("alice", balance=100)
("alice", balance=150)
```
→ KTable = `{alice: 150}`. Only the latest survives.

### The duality

- **Aggregating a stream produces a table** (e.g. count events per key → a table of counts).
- **Reading a table's changelog produces a stream** (each update is an event).

This "stream ⇄ table duality" is why a compacted topic (Ch.17 §3.2) is the natural storage for a KTable: compaction keeps the latest value per key, exactly matching table semantics.

### GlobalKTable

A `KTable` is **partitioned** — each instance holds only the partitions it owns. A `GlobalKTable` is **fully replicated** — every instance holds *all* the data. It's used for small, reference-style data (country codes, product catalog) so you can join **without co-partitioning** (see §4).

---

## 3. The Processing Topology

A Kafka Streams app is a **topology**: a DAG of processing nodes (source → processors → sink). You build it with one of two APIs.

### The DSL (high-level — most common)

Fluent, declarative operators: `map`, `filter`, `groupBy`, `aggregate`, `join`, `windowedBy`.

```java
StreamsBuilder builder = new StreamsBuilder();

KStream<String, Order> orders = builder.stream("orders",
        Consumed.with(Serdes.String(), orderSerde));

orders.filter((key, order) -> order.getAmount() > 0)
      .mapValues(order -> order.getAmount())
      .groupByKey()
      .reduce(Long::sum)                       // KTable<String, Long>
      .toStream()
      .to("revenue-per-customer",
          Produced.with(Serdes.String(), Serdes.Long()));

KafkaStreams streams = new KafkaStreams(builder.build(), props);
streams.start();
Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
```

### The Processor API (low-level)

Imperative `process()`/`transform()` with direct access to state stores and the record context. Use it when the DSL can't express your logic (custom punctuation/timers, fine-grained state control). More power, more boilerplate.

### Stateless vs Stateful operators

| Stateless | Stateful (need a state store) |
|-----------|-------------------------------|
| `filter`, `map`, `mapValues` | `aggregate`, `reduce`, `count` |
| `flatMap`, `branch`, `merge` | joins (windowed) |
| `foreach`, `peek`, `selectKey` | windowing |

Stateful operators trigger a **repartition** if you changed the key, and they keep a **state store** backed by a changelog topic.

---

## 4. Joins

Joins are where co-partitioning and the stream/table distinction really matter.

### Join types

| Join | Meaning | Output |
|------|---------|--------|
| **KStream-KStream** | Two event streams within a **time window** | Stream of matched pairs |
| **KStream-KTable** | Enrich each event with the current table value | Stream |
| **KStream-GlobalKTable** | Enrich without co-partitioning | Stream |
| **KTable-KTable** | Keep two materialized views joined | Table |

### Co-partitioning requirement (the classic gotcha)

For `KStream-KTable` and `KStream-KStream` joins, both sides must be **co-partitioned**:
1. Same number of partitions, **and**
2. Same partitioning strategy (same key + same partitioner).

Why: a stream task only sees the partitions it owns. If `orders` partition 3 must join `customers`, the matching customer **must also be in partition 3** of the customers topic — otherwise the task can't find it locally. If they don't line up, Kafka Streams must **repartition** one side (an internal topic) so keys realign.

**GlobalKTable sidesteps this** — since every instance has all the data, no co-partitioning is needed. That's the whole point of it for reference data.

### Windowed stream-stream joins

Two streams only "match" if their records fall within a join window:

```java
orders.join(
    shipments,
    (order, shipment) -> new OrderShipment(order, shipment),
    JoinWindows.ofTimeDifferenceWithNoGrace(Duration.ofMinutes(10)),
    StreamJoined.with(Serdes.String(), orderSerde, shipmentSerde)
);
```
→ An order and a shipment join only if they arrive within 10 minutes of each other.

---

## 5. Windowing

Aggregations over a stream are usually **windowed** — grouped into time buckets — because an unbounded stream has no natural "end" to aggregate over.

| Window type | Shape | Example use |
|-------------|-------|-------------|
| **Tumbling** | Fixed-size, non-overlapping | "Orders per 1-minute bucket" |
| **Hopping** | Fixed-size, overlapping (advance < size) | "5-min count, updated every 1 min" |
| **Sliding** | Window defined by record proximity | "Events within 10s of each other" |
| **Session** | Activity-gap based (closes after inactivity) | "User session = clicks <30 min apart" |

```java
orders.groupByKey()
      .windowedBy(TimeWindows.ofSizeAndGrace(
              Duration.ofMinutes(1), Duration.ofSeconds(10)))
      .count()
      .toStream()
      .to("orders-per-minute");
```

### Event time, grace, and late records

- Kafka Streams aggregates by **event time** (the record's timestamp), not wall-clock — so out-of-order events still land in the correct window.
- A **grace period** keeps a window open a little longer to accept late arrivals. After the grace period closes, late records for that window are **dropped**.
- Trade-off: a longer grace = more correct results but later/larger emissions and more state retained.

---

## 6. State Stores & Fault Tolerance

This is what makes Streams more than a fancy consumer loop.

### Local state — RocksDB

Stateful operators keep state in a **local RocksDB** store (an embedded key-value DB on the instance's disk). Local reads/writes are fast and don't hit the network.

### Durability — changelog topics

Every state store is backed by a **compacted changelog topic** in Kafka. Every state update is also written to this changelog. The flow:

```
update state store (RocksDB)  ──►  also append to changelog topic (compacted)
```

If an instance crashes, another instance **rebuilds the exact state** by replaying the changelog topic into a fresh local RocksDB. This is why state survives restarts and rebalances — the source of truth is in Kafka, RocksDB is just a fast local cache.

### Standby replicas

`num.standby.replicas` keeps **warm copies** of state stores on other instances, continuously fed by the changelog. On failover, the standby is already (nearly) up to date, so recovery is fast instead of a full changelog replay.

---

## 7. Scaling, Tasks & Threads

### Tasks = the unit of parallelism

Kafka Streams splits the topology into **tasks**, one per input partition. With 6 input partitions you get 6 tasks. Tasks are the *fixed* unit of work — their number is set by partition count.

### Distributing tasks

- Tasks are distributed across **threads** (`num.stream.threads`) within an instance, and across **instances** (which share one Kafka consumer group — the `application.id`).
- Scale **out** by starting more app instances; Kafka rebalances tasks across them automatically — same mechanics as a consumer group (Ch.9).
- **Max parallelism = number of input partitions.** More instances than partitions → idle instances. (Same ceiling rule as consumers.)

### application.id

`application.id` is the identity of a Streams app. It's used as the **consumer group id**, the **prefix for internal topics** (changelogs, repartition topics), and the **client id base**. All instances of the same app share one `application.id`.

---

## 8. Exactly-Once Semantics (EOS)

Kafka Streams gives **exactly-once** processing with a single config:

```java
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG,
          StreamsConfig.EXACTLY_ONCE_V2);   // "exactly_once_v2"
```

Under the hood it uses Kafka **transactions** (Ch.12/Ch.13): the consumer offset commit, the state-store changelog writes, and the output-topic writes are all wrapped in **one atomic transaction**. Either everything commits or nothing does — no partial updates, no double-counting on retry.

- **EOS v2** (Kafka 2.5+) is the efficient version — one producer per instance instead of one per task, far less overhead than the original `exactly_once`.
- The default is `at_least_once`, which is faster but can reprocess (and thus double-count in aggregations) after a failure.

---

## 9. Interactive Queries

Because state lives in local RocksDB stores, you can **query it directly** from your app — turning a Streams app into a lightweight, queryable store without an external DB.

```java
ReadOnlyKeyValueStore<String, Long> store =
    streams.store(StoreQueryParameters.fromNameAndType(
        "revenue-per-customer", QueryableStoreTypes.keyValueStore()));

Long alice = store.get("alice");
```

Since state is partitioned across instances, a key you want may live on **another instance**. Kafka Streams metadata (`streams.metadataForKey(...)`) tells you which instance hosts a key so you can forward the request (e.g. over HTTP) — the basis of "queryable microservices" built on Streams.

---

## 10. Internal Topics (know they exist)

Kafka Streams auto-creates internal topics, prefixed with your `application.id`:

| Topic kind | Purpose | Cleanup policy |
|------------|---------|----------------|
| **Changelog** (`...-changelog`) | Durably back a state store | `compact` |
| **Repartition** (`...-repartition`) | Re-key/redistribute data before a stateful op or join | `delete` |

These count against your cluster's partition budget and disk — a Streams app with many stateful steps can create a surprising number of internal topics.

---

## 11. Interview Angles

**Q: What's the difference between a KStream and a KTable?**
A: A `KStream` is an append-only stream of facts — every record is an independent event, and two records with the same key are both retained. A `KTable` is a materialized view of the *latest* value per key — a new record with an existing key is an upsert, and a null value is a tombstone that deletes the key. The mental model is stream/table duality: aggregating a stream gives you a table, and reading a table's changelog gives you a stream. A compacted topic is the natural backing store for a KTable because compaction keeps exactly the latest value per key.

**Q: When would you use Kafka Streams instead of Flink or a plain consumer?**
A: Kafka Streams when both my source and sink are Kafka and I don't want to run separate infrastructure — it's just a library in my service that scales by adding instances to the same consumer group. A plain consumer loop is fine for stateless or simple work, but Streams gives me stateful aggregations, joins, windowing, fault-tolerant state, and exactly-once for free. I'd reach for Flink when I have many non-Kafka sources/sinks, very large state, or heavy event-time processing that wants a dedicated cluster.

**Q: Explain co-partitioning. Why do stream-table joins require it?**
A: A Streams task only sees the partitions it owns. For a KStream-KTable join, the matching table record must live in the *same* partition as the stream record, otherwise the task can't find it locally. That requires both topics to have the same partition count and the same key + partitioner — that's co-partitioning. If they don't line up, Kafka Streams inserts a repartition topic to realign keys. A GlobalKTable avoids the whole problem because every instance holds all the data, which is why it's used for small reference data.

**Q: How does Kafka Streams keep state fault-tolerant?**
A: Stateful operators keep local state in an embedded RocksDB store for fast access, but the source of truth is a compacted **changelog topic** in Kafka — every state update is also appended there. If an instance dies, another instance rebuilds the exact state by replaying that changelog into a fresh RocksDB. Standby replicas keep warm copies fed by the changelog so failover is fast instead of a full replay. So RocksDB is just a local cache; durability lives in Kafka.

**Q: How does Kafka Streams achieve exactly-once?**
A: With `processing.guarantee=exactly_once_v2`, it wraps the offset commit, the state-store changelog writes, and the output writes in a single Kafka transaction (the same transactional machinery from the producer/consumer EOS chapters). Either all three commit atomically or none do, so a retry after failure can't double-count an aggregation or emit a duplicate. EOS v2 uses one producer per instance rather than per task, which is why it's efficient enough to enable in production.

**Q: How does Kafka Streams scale, and what caps parallelism?**
A: The topology is split into tasks, one per input partition, and tasks are spread across threads within an instance and across instances that share one `application.id` (which is the consumer group id). I scale out by starting more instances and Kafka rebalances tasks across them automatically. The ceiling is the input partition count — just like consumers, if I run more instances than partitions, the extras sit idle. So partition count is the real limit on Streams parallelism.

**Q: What are windowing and grace periods for?**
A: Aggregations over an unbounded stream need time buckets, so you window — tumbling (fixed, non-overlapping), hopping (overlapping), sliding (proximity-based), or session (activity-gap). Streams aggregates by event time, not wall-clock, so out-of-order records still land in the right window. The grace period keeps a window open a bit longer to accept late arrivals; after it closes, later records for that window are dropped. Longer grace means more correct results but later emissions and more retained state.

---

## 12. Practical Code Examples

Here are three complete patterns for Kafka Streams.

### Example 1: Word Count (Stateful Aggregation)
Reads a stream of text lines, splits them into words, and counts occurrences of each word.

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;

import java.util.Arrays;
import java.util.Properties;

public class WordCountApp {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "word-count-app");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        StreamsBuilder builder = new StreamsBuilder();

        // 1. Read input stream of lines
        KStream<String, String> textLines = builder.stream("text-input");

        // 2. Process: split, lowercase, group, and count
        KTable<String, Long> wordCounts = textLines
                .flatMapValues(value -> Arrays.asList(value.toLowerCase().split("\\W+")))
                .groupBy((key, word) -> word)
                .count(Materialized.as("counts-store")); // materialized in local RocksDB

        // 3. Write back to output topic
        wordCounts.toStream().to("word-counts-output", Produced.with(Serdes.String(), Serdes.Long()));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
```

### Example 2: KStream-KTable Join (Data Enrichment)
Enriches a real-time stream of `Order` events with matching `UserProfile` details from a KTable. Both topics must be co-partitioned (same key and partition count).

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.Produced;

public class OrderEnrichmentTopology {
    public static void build(StreamsBuilder builder) {
        // Custom JSON Serdes (assume pre-defined)
        var orderSerde = new JsonSerde<>(Order.class);
        var userSerde = new JsonSerde<>(UserProfile.class);
        var enrichedSerde = new JsonSerde<>(EnrichedOrder.class);

        // 1. Read the Orders (Stream) - keyed by userId
        KStream<String, Order> orders = builder.stream("orders", 
                Consumed.with(Serdes.String(), orderSerde));

        // 2. Read User Profiles (Table) - keyed by userId
        KTable<String, UserProfile> userProfiles = builder.table("user-profiles", 
                Consumed.with(Serdes.String(), userSerde));

        // 3. Join the stream and table
        KStream<String, EnrichedOrder> enrichedOrders = orders.join(
                userProfiles,
                (order, profile) -> new EnrichedOrder(order, profile), // ValueJoiner
                Joined.with(Serdes.String(), orderSerde, userSerde)
        );

        // 4. Write enriched orders out
        enrichedOrders.to("enriched-orders", Produced.with(Serdes.String(), enrichedSerde));
    }
}
```

### Example 3: Tumbling Time Window (Aggregations over Time)
Counts user click events in fixed, non-overlapping 5-minute windows.

```java
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Materialized;

import java.time.Duration;

public class WindowedClickCountTopology {
    public static void build(StreamsBuilder builder) {
        KStream<String, String> clicks = builder.stream("user-clicks", 
                Consumed.with(Serdes.String(), Serdes.String()));

        clicks.groupByKey()
              // Define a 5-minute tumbling window with a 30-second grace period
              .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5), Duration.ofSeconds(30)))
              .count(Materialized.as("windowed-click-counts"))
              .toStream()
              // The windowed key contains the original key plus the window start/end timestamps
              .selectKey((windowedKey, value) -> windowedKey.key())
              .to("clicks-per-5-min", Produced.with(Serdes.String(), Serdes.Long()));
    }
}
```
