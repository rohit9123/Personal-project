# 08 — Redis Pub/Sub & Streams: Consumer Groups, PEL & Kafka Trade-offs

> **Why this is Topic 8:** In high-throughput, real-time backend systems (like distributing live market-data ticks or processing trade execution confirmations), your choice of messaging system directly impacts reliability, latency, and memory utilization. If a subscriber experiences a minor 100ms Stop-the-World GC pause, does it lose data? Can it resume from its last processed offset? SDE2 interviewers will test your deep understanding of Redis Pub/Sub (fire-and-forget, zero storage overhead) versus Redis Streams (durable logs, consumer groups, PEL, and ACK), and how Redis Streams compares to dedicated message brokers like Apache Kafka.

---

## 1. WHAT

Redis offers two distinct messaging paradigms, each built for a different engineering trade-off:

### 1.1 Redis Pub/Sub
A **fire-and-forget** publisher-subscriber routing engine. 
* Channels are transient and have no database storage.
* Messages are pushed directly to active subscribers' sockets. 
* If a subscriber is offline, the message is lost forever.

```
Pub/Sub (Fire-and-Forget):
[Publisher] ──► (Channel: "ticks:RELIANCE") ──► [Subscriber A] (Active - Received)
                                              ├─► [Subscriber B] (Active - Received)
                                              x─► [Subscriber C] (Disconnected - Lost!)
```

### 1.2 Redis Streams
A **durable, append-only log** data structure (introduced in Redis 5.0).
* Every stream entry is a structured key-value map.
* Entries are persisted to disk (via RDB/AOF) and replicated.
* Every entry receives a unique, monotonically increasing ID (`<millisecondsTime>-<sequenceNumber>`).
* Supports **Consumer Groups**, allowing multiple workers to coordinate and load-balance message consumption while maintaining a record of in-flight, unacknowledged messages.

```
Streams (Durable Log):
[Producer] ──► [ Log: ID 1000-0 | ID 1000-1 | ID 1000-2 ] ◄── [Consumer Group]
                     ▲                                             │
                     └────────── Replay / Range Query ─────────────┼─► [Consumer A]
                                                                   └─► [Consumer B]
```

---

## 2. WHY (the trade-offs & use cases)

| Feature | Redis Pub/Sub | Redis Streams | Apache Kafka |
|---|---|---|---|
| **Storage Model** | Ephemeral (Fire-and-forget) | Durable Log (RAM-bound) | Durable Log (Disk-bound) |
| **Memory Cost** | Near Zero (No storage) | High (Stored in RAM) | Low-to-medium (Disk cache) |
| **Max Capacity** | Unlimited (No history) | Bound by physical RAM | Bound by Disk space (Petabytes) |
| **Delivery Model** | Push (Server-initiated) | Pull (Client-initiated) | Pull (Client-initiated) |
| **Delivery Guarantee**| Best-effort (At-most-once) | At-least-once (with PEL & ACK)| At-least-once / Exactly-once |
| **Scale Model** | Broad fan-out (O(N) CPU) | Dynamic worker consumption | Static Partition-based scaling |
| **Latency** | Sub-millisecond (Microseconds) | Sub-millisecond (RAM speed) | Low (1-10ms, disk/network) |

---

### 2.1 The Limits of Pub/Sub
1. **No Backlog Replay:** A client cannot request "all messages from 5 seconds ago." Disconnections cause data gaps.
2. **Buffer Overflow Risk:** When a subscriber is slower than the publisher, Redis buffers pending messages in the subscriber's private socket output buffer. If this buffer exceeds the threshold configured in `client-output-buffer-limit pubsub`, Redis immediately **kills the client connection** to prevent the Redis server from running out of memory.

### 2.2 The Power of Streams
1. **Durable Offsets:** Consumers can consume new messages (`>`), read starting from a specific ID, or query a historical window using `XRANGE`.
2. **Dynamic Work Distribution:** Unlike Apache Kafka—where a partition is statically mapped to a single consumer in a group—Redis Streams allows any consumer in a group to grab any message. Work is distributed on a first-come, first-served message basis.
3. **Memory Management (`XTRIM`):** Streams are kept within memory limits by capping their size:
   ```redis
   > XADD market:ticks MAXLEN ~ 10000 * tick-data "RELIANCE:2450.5"
   ```
   The `~` operator tells Redis to trim the stream approximately (at listpack boundaries) which runs in $O(1)$ time, avoiding expensive shifting of single records.

---

## 3. HOW (the internals)

### 3.1 Pub/Sub Internals
Redis maintains subscriber state in two global data structures:
* **`server.pubsub_channels`:** A hash table mapping channel name strings to a linked list of subscribing client pointers.
  * When `PUBLISH channel msg` is called, Redis does a hash lookup on `server.pubsub_channels` and loops through the client list, appending the message to each client's socket write buffer.
* **`server.pubsub_patterns`:** A linked list containing pattern subscriptions (e.g., `ticks:*`).
  * When publishing, Redis must iterate over this list and match the channel against each pattern.
  * **Critical:** If you have thousands of pattern subscriptions, publishing becomes an $O(N)$ CPU bottleneck on the single main thread.

---

### 3.2 Radix Tree (`Rax`) Internals of Streams
Redis Streams are represented internally as a **Radix Tree (Rax)**.
* **Prefix Compression:** Stream IDs share identical prefix sequences (e.g., `1687841680000-0`, `1687841680000-1`). A Radix Tree compresses these common prefixes, significantly reducing memory consumption compared to traditional dictionary hash tables.
* **Listpacks:** Within each Radix Tree leaf, multiple stream entries are packed sequentially into a single **listpack** node. This layout minimizes pointer overhead and improves CPU cache locality, making sequential iteration extremely fast.

---

### 3.3 Consumer Groups, PEL & Acknowledgment
To guarantee reliable delivery, Redis Streams use three mechanisms:

#### 1. Last Delivered ID (`last_id`)
The consumer group keeps track of the largest message ID delivered to any consumer in the group. When a consumer reads new messages via `XREADGROUP` with ID `>`, Redis increments this offset.

#### 2. Pending Entries List (PEL)
When a message is delivered to a consumer in a group, it is added to the **PEL** (both at the group level and the specific consumer level). The entry remains in the PEL until the consumer acknowledges it.
* The PEL records the message ID, the consumer that claimed it, the last delivery time, and the total delivery count.

#### 3. Acknowledgment (`XACK`)
Once a consumer completes processing the message, it calls `XACK`. Redis then:
1. Deletes the message ID from the consumer's local PEL.
2. Deletes the message ID from the group's global PEL.
3. Frees the PEL metadata memory.

```
                  [ Stream Log ] (last_id = 1000-2)
                         │
                         ├─► XREADGROUP ──► Send 1000-3 ──► Add to Consumer A's PEL
                         │
        ┌────────────────┴────────────────┐
        ▼                                 ▼
   Consumer A                        Consumer B
   (Crashes!)                        (Healthy)
        │                                 ▲
        ▼ (Message remains in PEL)        │
   Supervisor ──► XCLAIM (1000-3) ────────┘
```

#### 4. Handling Worker Failures (`XPENDING` & `XCLAIM`)
If Consumer A pulls a message, adds it to its PEL, and crashes, the message remains unacknowledged.
* Other consumers cannot read it using `>` because it has already been dispatched.
* **The Solution:** A supervisor process checks `XPENDING`. It finds messages in the PEL that have been idle for too long (e.g., > 10 seconds).
* The supervisor calls `XCLAIM` to assign the message to a healthy Consumer B. This resets the message's idle timer and updates the consumer ownership record in the PEL.

---

## 4. CODE / EXAMPLES (Exclusively Java)

### 4.1 Redis Pub/Sub with Jedis
Because `jedis.subscribe()` is a blocking operation, the subscriber listener must run in its own thread.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

public class JedisPubSubDemo {
    private static final String CHANNEL = "market.ticks";

    // 1. Define the Subscriber Listener
    public static class TickSubscriber extends JedisPubSub {
        @Override
        public void onMessage(String channel, String message) {
            System.out.println(String.format("[Subscriber] Received: %s from Channel: %s", message, channel));
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            System.out.println("[Subscriber] Subscribed to " + channel);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 2. Start the Subscriber in a Background Thread
        Thread subscriberThread = new Thread(() -> {
            try (Jedis jedis = new Jedis("localhost", 6379)) {
                TickSubscriber subscriber = new TickSubscriber();
                jedis.subscribe(subscriber, CHANNEL); // Blocks execution
            }
        });
        subscriberThread.start();

        Thread.sleep(1000); // Wait for subscriber to spin up

        // 3. Publish messages using a separate Jedis Connection
        try (Jedis publisherJedis = new Jedis("localhost", 6379)) {
            publisherJedis.publish(CHANNEL, "RELIANCE:2450.5");
            publisherJedis.publish(CHANNEL, "TCS:3210.0");
        }
    }
}
```

---

### 4.2 Redis Pub/Sub with Spring Data Redis
Using Spring Data Redis, we configure a message listener container to handle connection management and thread pooling automatically.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class SpringPubSubConfig {

    // 1. Define the Listener Logic
    public static class TickMessageListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String body = new String(message.getBody());
            String channel = new String(message.getChannel());
            System.out.println(String.format("[Spring Listener] Received: %s on channel: %s", body, channel));
        }
    }

    // 2. Register the Message Listener Container
    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory,
                                                         MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic("market.ticks"));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(TickMessageListener receiver) {
        return new MessageListenerAdapter(receiver);
    }

    @Bean
    public TickMessageListener receiver() {
        return new TickMessageListener();
    }
}
```

---

### 4.3 Production-Grade Redis Streams Worker with Jedis
This example demonstrates a complete producer-consumer lifecycle, including group initialization, auto-trimming, consumer processing, crash-recovery of pending entries (reading PEL first), and stale entry claiming.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamPendingEntry;

import java.util.*;

public class RedisStreamWorker {
    private static final String STREAM_KEY = "order:processing";
    private static final String GROUP_NAME = "order:processor:group";
    private final String consumerName;

    public RedisStreamWorker(String consumerName) {
        this.consumerName = consumerName;
    }

    public void initStreamAndGroup() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            // Create the consumer group and stream if they don't exist
            try {
                jedis.xgroupCreate(STREAM_KEY, GROUP_NAME, StreamEntryID.LAST_ENTRY, true);
                System.out.println("Consumer Group created successfully.");
            } catch (Exception e) {
                // Group might already exist, which is expected on restarts
                System.out.println("Group already exists or stream initialized.");
            }
        }
    }

    // Produce message with approximate trimming to limit memory usage
    public void produceOrder(String orderId, String userId, double amount) {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            Map<String, String> message = new HashMap<>();
            message.put("orderId", orderId);
            message.put("userId", userId);
            message.put("amount", String.valueOf(amount));

            // Approximate trimming: keep ~10,000 items in memory
            XAddParams params = XAddParams.xAddParams().maxLen(10000).approximateTrimming();
            StreamEntryID id = jedis.xadd(STREAM_KEY, StreamEntryID.NEW_ENTRY, message, params);
            System.out.println("Published order with Entry ID: " + id);
        }
    }

    // Main worker polling loop
    public void startConsuming() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            XReadGroupParams readParams = XReadGroupParams.xReadGroupParams().count(10).block(2000);

            while (!Thread.currentThread().isInterrupted()) {
                // STEP 1: Process own pending messages (e.g. recovering from a previous crash)
                // Passing the stream ID as the local consumer ID (not '>') queries the local PEL
                Map<String, StreamEntryID> pendingQuery = Collections.singletonMap(STREAM_KEY, new StreamEntryID(0, 0));
                List<Map.Entry<String, List<StreamEntry>>> pendingResult = 
                    jedis.xreadGroup(GROUP_NAME, consumerName, pendingQuery, readParams);

                if (hasMessages(pendingResult)) {
                    System.out.println(consumerName + " resolving crashed/pending messages first...");
                    processMessages(jedis, pendingResult);
                    continue; // Loop again to empty the PEL
                }

                // STEP 2: Read new messages (using ID '>')
                Map<String, StreamEntryID> newQuery = Collections.singletonMap(STREAM_KEY, StreamEntryID.UNRECEIVED_ENTRY);
                List<Map.Entry<String, List<StreamEntry>>> newResult = 
                    jedis.xreadGroup(GROUP_NAME, consumerName, newQuery, readParams);

                if (hasMessages(newResult)) {
                    processMessages(jedis, newResult);
                }

                // STEP 3: Active Claiming (Orphaned message cleanup)
                claimStaleMessages(jedis);
            }
        }
    }

    private boolean hasMessages(List<Map.Entry<String, List<StreamEntry>>> result) {
        return result != null && !result.isEmpty() && !result.get(0).getValue().isEmpty();
    }

    private void processMessages(Jedis jedis, List<Map.Entry<String, List<StreamEntry>>> result) {
        for (Map.Entry<String, List<StreamEntry>> entry : result) {
            for (StreamEntry msg : entry.getValue()) {
                try {
                    System.out.println(String.format("%s processing ID: %s | Data: %s", 
                        consumerName, msg.getID(), msg.getFields()));
                    
                    // Simulate task execution
                    executeOrderJob(msg.getFields());

                    // Acknowledge the message to remove it from PEL
                    jedis.xack(STREAM_KEY, GROUP_NAME, msg.getID());
                    
                } catch (Exception e) {
                    System.err.println("Failed to process message ID " + msg.getID() + ": " + e.getMessage());
                    // Do NOT call XACK so the message is preserved in PEL for retry or claiming
                }
            }
        }
    }

    // Scan for and claim messages that have been stuck in other consumers' PELs for > 15s
    private void claimStaleMessages(Jedis jedis) {
        long minIdleTimeMs = 15000;
        
        // Scan PEL for details
        List<StreamPendingEntry> pendingEntries = jedis.xpending(
            STREAM_KEY, GROUP_NAME, StreamEntryID.MIN_ENTRY, StreamEntryID.MAX_ENTRY, 10
        );

        for (StreamPendingEntry entry : pendingEntries) {
            if (entry.getElapsedTimeSinceLastDelivery() > minIdleTimeMs && !entry.getConsumerName().equals(consumerName)) {
                System.out.println(String.format("Found stale message: %s (idle %dms held by %s). Claiming...", 
                    entry.getID(), entry.getElapsedTimeSinceLastDelivery(), entry.getConsumerName()));

                // Claim message
                XClaimParams claimParams = XClaimParams.xClaimParams();
                List<StreamEntry> claimed = jedis.xclaim(
                    STREAM_KEY, GROUP_NAME, consumerName, minIdleTimeMs, claimParams, entry.getID()
                );

                for (StreamEntry msg : claimed) {
                    System.out.println("Successfully claimed message ID " + msg.getID());
                    // Message is now in our PEL; processing will handle it on the next check
                }
            }
        }
    }

    private void executeOrderJob(Map<String, String> fields) throws InterruptedException {
        // Business logic simulation
        Thread.sleep(100); 
    }
}
```

---

### 4.4 Redis Streams with Spring Data Redis (Asynchronous Container)
Using Spring Data Redis's `StreamMessageListenerContainer` to handle the asynchronous worker polling loop.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

@Configuration
public class SpringStreamConfig {

    // 1. Define the Stream Listener implementation
    public static class OrderStreamListener implements StreamListener<String, MapRecord<String, String, String>> {
        @Override
        public void onMessage(MapRecord<String, String, String> message) {
            System.out.println("Spring Worker received message ID: " + message.getId());
            System.out.println("Data: " + message.getValue());
            
            // Execute task logic here...
            
            // Spring handles acknowledgment and PEL tracking based on container configuration
        }
    }

    // 2. Container setup
    @Bean
    public Subscription orderSubscription(RedisConnectionFactory factory, OrderStreamListener listener) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .targetType(String.class) // Maps key-value fields to String
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(factory, options);

        // Subscribing to Stream via Consumer Group
        Subscription subscription = container.receive(
                Consumer.from("order:processor:group", "worker-instance-1"),
                StreamOffset.create("order:processing", ReadOffset.lastConsumed()),
                listener
        );

        container.start();
        return subscription;
    }

    @Bean
    public OrderStreamListener orderListener() {
        return new OrderStreamListener();
    }
}
```

---

## 5. INTERVIEW ANGLES

### Q: What is the exact behavior of `XREAD` vs `XREADGROUP`?
**A:** 
* **`XREAD`** acts like a traditional subscriber reading directly from a log. It takes a starting stream ID (or `$` to read only new entries) and returns any records appended after that offset. Multiple independent clients calling `XREAD` will each receive identical copies of the messages. It does not modify stream state or track consumption offsets.
* **`XREADGROUP`** is designed for worker pools. It requires a consumer group name and a consumer name. When reading with the special ID `>`, it pulls messages that have not yet been delivered to any consumer in the group, updates the group's `last_id` offset, and adds the message to both the group's and the individual consumer's **Pending Entries List (PEL)**.

### Q: Why does Redis Streams use Radix Trees (Rax) internally?
**A:** Stream IDs are timestamps coupled with a sequence number, which share identical prefix sequences (e.g. `1687841000000-...`). Storing these as independent keys in a hash table wastes substantial memory on redundant keys and internal pointers.
A **Radix Tree** compresses these common prefixes, storing shared keys within unified path structures. Inside this Radix tree, leaf nodes consolidate multiple entries into high-density **listpacks**, reducing pointer overhead to a minimum and matching modern CPU cache line sizing for rapid sequential reads.

### Q: How do you handle dead-letter / poisoned messages in Redis Streams?
**A:** When a message contains malformed data that causes a consumer crash, the consumer will fail before calling `XACK`. The message remains in the PEL. When a supervisor process runs `XCLAIM` to assign it to another worker, that worker will also crash.
To prevent this loop:
1. Use **`XPENDING`** to inspect the target stream's pending messages. This returns the delivery count for each message.
2. If a message's delivery count exceeds a threshold (e.g., 5 attempts), the worker should catch this state.
3. The worker publishes the message to a dedicated dead-letter stream (e.g., `orders:dlq`), calls `XACK` on the original stream to remove it from the active pipeline, and alerts the engineering team.

### Q: Compare the performance and architectural trade-offs of exact trimming (`MAXLEN = 1000`) vs approximate trimming (`MAXLEN ~ 1000`).
**A:** 
* **Exact Trimming (`MAXLEN = 1000`):** Forces Redis to trim the stream to exactly 1,000 entries. Since entries are packed into consolidated listpacks, exact trimming requires Redis to unpack listpacks, delete individual records, and write the remaining records back. This is an $O(N)$ CPU-intensive operation.
* **Approximate Trimming (`MAXLEN ~ 1000`):** Allows Redis to trim entries only when an entire listpack block can be discarded. This runs as an $O(1)$ operation, removing whole memory blocks at once without parsing individual entries. This is the production standard for high-throughput stream pipelines.

### Q: In a Redis HA setup (Sentinel or Cluster), are PEL and ACK states guaranteed to be safe?
**A:** No. Redis replication is asynchronous. If a primary node accepts a message, routes it to a consumer via `XREADGROUP` (modifying the group's `last_id` and updating the PEL), and crashes before those changes replicate to the replica, a failover will occur.
The new primary node will have no record of the message being dispatched or stored in the PEL. Consequently, the message may be delivered a second time. **All stream consumers must be idempotent** to guarantee safety under node failover conditions.

---

## 6. ONE-LINE RECALL CARDS

*   Redis Pub/Sub is **fire-and-forget**; disconnected clients lose published data permanently.
*   Slow Pub/Sub consumers will be **forcibly disconnected** if their socket buffer exceeds `client-output-buffer-limit`.
*   Redis Streams are durable, structured logs backed by **Radix Trees (Rax)** and compressed **listpacks**.
*   **`XREADGROUP`** with ID `>` delivers unreceived messages and adds them to the **Pending Entries List (PEL)**.
*   Consumers must call **`XACK`** to remove messages from the group and consumer PELs to free memory.
*   Stale, unacknowledged messages are reclaimed by checking **`XPENDING`** and calling **`XCLAIM`**.
*   Always use approximate trimming (**`MAXLEN ~ N`**) with `XADD`/`XTRIM` to ensure $O(1)$ memory reclamation.
*   When restarting, stream consumers should read from offset **`0-0`** first to clear pending PEL entries before reading new messages.
*   Stream metadata (PEL and ACKs) is subject to data loss during failovers due to Redis's **async replication**.

---

**Next:** [09 — Replication & HA](09-replication-ha.md) (async replication, Sentinel, failover, lost-write window).
