# Kafka — Chapter 5: Advanced Flows: Transactions, Rebalancing & Schema Registry

Topics covered: Exactly-Once Transactions · Consumer Rebalance Protocol · Schema Registry Interaction · Error Handling & DLQ

---

## 1. Exactly-Once Transactions Flow

Exactly-Once Semantics (EOS) ensures that even if a producer retries or a consumer restarts, the end result is as if the data was processed exactly once.

### The Transaction Coordinator

Kafka uses a **Transaction Coordinator** (a specific broker) and an internal topic `__transaction_state` to manage the lifecycle of a transaction.

### The "Consume-Transform-Produce" Flow

This is the most common transactional pattern: reading from Topic A and writing to Topic B atomically.

```
Producer                     Transaction Coordinator            Kafka Brokers
   │                                  │                              │
   ├── ① Find Coordinator ───────────►│                              │
   │                                  │                              │
   ├── ② InitTransactions ──────────►│ (Assigns PID, fenced old)    │
   │                                  │                              │
   ├── ③ BeginTransaction             │                              │
   │                                  │                              │
   ├── ④ Produce Data ────────────────┼─────────────────────────────►│ (Write with Marker)
   │                                  │                              │
   ├── ⑤ Add Partitions to Tx ───────►│ (Records in __tx_state)      │
   │                                  │                              │
   ├── ⑥ Commit Offsets to Tx ───────►│ (Links consumer offset to Tx)│
   │                                  │                              │
   └── ⑦ Commit Transaction ─────────►│                              │
                                      ├── ⑧ Write Commit Marker ────►│
                                      └── ⑨ Complete Tx             │
```

### Key Design Details
- **Control Markers**: When a transaction is committed, a special "Commit Marker" is written to the data partitions.
- **Consumer Isolation**: Consumers must set `isolation.level=read_committed`. They will only see messages that have a corresponding Commit Marker. If a transaction is aborted, the markers are "Abort Markers," and the consumer skips those records.
- **Zombies / Fencing**: If a producer hangs and a new one starts with the same `transactional.id`, the Coordinator increments the **Producer Epoch**. When the old producer tries to commit, it is "fenced" with a `ProducerFencedException`.

---

## 2. Consumer Rebalance Protocol

A rebalance happens when the Group Coordinator (a broker) decides to redistribute partition ownership among consumers in a group (e.g., a consumer joins, leaves, or a topic's partition count changes).

### Eager Rebalance (The "Stop-the-World" Flow)

This was the only strategy pre-Kafka 2.4.

1. **Member Revocation**: Every consumer stops processing and gives up its current partitions.
2. **JoinGroup**: All consumers send a `JoinGroup` request to the Coordinator.
3. **SyncGroup**: The designated "Leader" consumer calculates the new assignments and sends them to the Coordinator, who relays them to all members.
4. **Resumption**: Consumers start fetching from their new partitions.

**Problem**: The entire group is idle during the rebalance. If the group is large, this "Stop-the-World" pause can last many seconds.

### Incremental Cooperative Rebalance

Introduced in Kafka 2.4+ (default in recent versions).

1. **Member Revocation**: Only the partitions that *need* to move are revoked. 
2. **Continued Processing**: Consumers keep processing the partitions they already own if they aren't changing owners.
3. **Multi-round**: The rebalance may happen in a few quick steps to ensure no partition is owned by two people at once.

---

## 3. Schema Registry Interaction Flow

Kafka only stores `byte[]`. The Schema Registry provides a way to manage schemas (Avro, Protobuf, JSON Schema) and ensure compatibility.

### The Write Path (Producer)
1. **Fetch/Register**: Producer checks if the schema is in its local cache. If not, it sends the schema to the Registry.
2. **Schema ID**: The Registry returns a unique 4-byte **Schema ID**.
3. **Payload Construction**: Producer prepends a "Magic Byte" (0) + 4-byte ID to the actual data bytes.
4. **Send**: Producer sends the combined bytes to Kafka.

### The Read Path (Consumer)
1. **Extract ID**: Consumer reads the first 5 bytes to get the Schema ID.
2. **Fetch Schema**: If the schema isn't in cache, the consumer fetches the full schema definition from the Registry using the ID.
3. **Deserialize**: Consumer uses the schema to convert bytes back into a Java/C# object.

---

## 4. Error Handling & Retry Patterns

In a distributed system, things will fail (Database down, Downstream API timeout). "Silently failing" is not an option.

### The Dead Letter Queue (DLQ) Pattern

If a message is physically "bad" (malformed JSON), it will never succeed. Don't retry it infinitely (the "Poison Pill").
- **Action**: Catch the exception and publish the original message to a `topic-name-DLQ` for manual inspection.

### Retries with Backoff (Retry Topics)

If the failure is temporal (API Timeout):
1. **Retry Topic**: Publish the message to `topic-name-retry-5m`.
2. **Delayed Consumer**: A separate consumer reads from this topic with a 5-minute wait.
3. **Exponential Backoff**: If it fails again, move it to `topic-name-retry-1h`, then finally to the `DLQ`.

---

## Interview Angles

**Q: How does Kafka achieve Exactly-Once Semantics (EOS)?**
A: EOS is achieved through an Idempotent Producer (handles retries without duplicates) and the Transaction API. The Transaction Coordinator manages a 2-phase commit process by writing commit/abort markers to the log. Consumers using `read_committed` only see records that have been successfully committed.

**Q: What is a "Poison Pill" in Kafka?**
A: A message that causes a consumer to fail every time it is processed. If the consumer retries and fails again, it stays stuck on that offset, causing "Consumer Lag" to skyrocket. Fix: Use a Try-Catch block and move the message to a Dead Letter Queue (DLQ).

**Q: Why is Cooperative Rebalancing better than Eager Rebalancing?**
A: Eager rebalancing requires all consumers to stop consuming all partitions, causing a global pause. Cooperative rebalancing only stops consumption for the specific partitions that are moving owners, allowing the rest of the group to continue working.

**Q: What is "Schema Evolution" and why do we need the Schema Registry?**
A: Schema Evolution is the process of changing the data format (adding a field). We need the Schema Registry to enforce compatibility rules (e.g., Backward Compatibility: new code can read old data). It ensures that producers don't publish data that will crash all existing consumers.

**Q: How does a Kafka Producer handle "Zombie" instances in a transaction?**
A: By using a `transactional.id` and an "Epoch." When a new producer instance starts with the same ID, the Coordinator increments the epoch. Any requests from the old "Zombie" instance (with an older epoch) are rejected, preventing data corruption.
