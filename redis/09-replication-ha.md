# 09 — Redis Replication & High Availability: Sentinel, Failover & Split-Brain

> **Why this is Topic 9:** In production, running a single Redis instance is a single point of failure. If the instance crashes, or the hardware fails, your application loses its cache and database state, causing downtime. To achieve high availability (HA), we replicate data from a Master to one or more Replicas. However, Redis replication is **asynchronous**, meaning there is a window of data loss during master failover. SDE2 interviews will test your understanding of how master-replica synchronization works, how Redis Sentinel detects failures and manages failovers, how to prevent "split-brain" scenarios using `min-replicas-to-write`, and how clients dynamically discover the active master.

---

## 1. WHAT

Redis achieves High Availability (HA) and read scalability through a primary-replica (master-slave) model combined with a monitoring and failover manager called **Redis Sentinel**.

```
                  ┌────────────────────┐
                  │ Client Application │
                  └─────────┬──────────┘
                            │ (Reads/Writes)
                            ▼
     ┌─────────────► ┌─────────────┐
     │ Monitoring    │   Master    │
     │               └──────┬──────┘
     │                      │
     │                      │ (Async Replication)
     │                      ▼
┌────┴──────┐        ┌─────────────┐
│ Sentinel  ├───────►│   Replica   │
└───────────┘        └─────────────┘
```

1. **Master-Replica Replication:** A master node receives all client writes and streams them to one or more read-only replicas. Replicas mirror the master's state.
2. **Redis Sentinel:** An independent, distributed service that runs alongside Redis nodes. Sentinel handles:
   * **Monitoring:** Continuously checking if master and replicas are responding.
   * **Notification:** Informing administrators or client applications about state changes via Pub/Sub.
   * **Automatic Failover:** Promoting a replica to master if the master goes offline, and reconfiguring other replicas to sync with the new master.
   * **Configuration Provider:** Acting as the source of truth for clients to discover the current master.

---

## 2. WHY (the trade-offs)

Running replicated Redis with Sentinel introduces architectural trade-offs between performance, consistency, and availability.

### 2.1 The Lost-Write Window (AP vs. CP)
Redis replication is **fundamentally asynchronous**. 
When a client sends a write to the master:
1. The master executes the write in memory.
2. The master sends an "OK" acknowledgment to the client.
3. The master propagates the write to all connected replicas.

If the master crashes *between step 2 and step 3*, the write has not reached any replica. When Sentinel detects the failure and promotes a replica to master, **that write is permanently lost**. Redis prioritizes write latency over strong consistency, aligning it with **AP** properties in the CAP theorem.

### 2.2 Replication Lag & Stale Reads
If client reads are offloaded to replicas to scale read throughput, those reads are subject to **replication lag** (network delay or replica busy processing load). Replicas can return stale data. If an application updates a user session on the master and immediately reads it from a replica, it may not see the updated data.

### 2.3 Sentinel Quorum and HA Overhead
Sentinel requires a minimum of 3 nodes to form a quorum and avoid its own split-brain scenarios. Setting up Sentinel increases infrastructure footprint, network traffic (constant ping/heartbeat checks), and connection complexity for clients.

---

## 3. HOW (the internals)

### 3.1 Master-Replica Replication Sync Steps
When a replica starts up or reconnects to a master, synchronization happens in three phases:

```
Replica                                                             Master
   │                                                                   │
   │─── 1. PSYNC <replid> <offset> ───────────────────────────────────►│
   │                                                                   │
   │◄── 2. +FULLRESYNC <new_replid> <offset> ──────────────────────────│
   │                                                                   │
   │                                           [Forks Child Process]   │
   │                                           [Runs BGSAVE to RDB]    │
   │                                           [Buffers client writes] │
   │                                           │
   │◄── 3. Sends RDB Binary File ──────────────────────────────────────│
   │                                                                   │
   │ [Flushes old data]                                                │
   │ [Loads RDB file into memory]                                      │
   │                                                                   │
   │◄── 4. Streams Replication Backlog (RESP commands) ────────────────│
   │                                                                   │
   │◄── 5. Continuous Command Propagation (RESP) ──────────────────────│
```

> [!NOTE]
> **Layman's Analogy: The Notebook Sync**
> Imagine two students syncing notebooks:
> * **Phase 1 (The Handshake):** Student B connects and says: *"I'm copying Notebook ID X, up to page 45, line 12."* Student A checks their circular scratchpad. If the missing lines are still there, Student A just reads the missing lines (**Partial Sync**). If they've already cleared that scratchpad, they must do a **Full Sync**.
> * **Phase 2 (Fresh Start):** Student A takes a photocopier snapshot of their entire notebook (**RDB snapshot**) and hands it to Student B. While Student B is erasing their own notebook and copying the pages, Student A keeps writing new notes on a temporary sticky pad (**Backlog Buffer**).
> * **Phase 3 (Live Updates):** Once Student B finishes copying the main notebook, Student A dictates all the notes from the sticky pad to bring Student B fully up to speed. After that, they stay in sync in real-time.

#### Phase 1: Connection & PSYNC Handshake
1. The replica establishes a connection to the master.
2. The replica sends the `PSYNC <replid> <offset>` command.
   * **Replication ID (`replid`):** A unique identifier for the master's keyspace history.
   * **Offset:** A counter representing how many bytes of write commands the replica has successfully processed.
3. The master checks if it can perform a **Partial Synchronization**:
   * If the replica's `replid` matches the master's current replication ID, **and** the requested `offset` is still present inside the master's in-memory **Replication Backlog Buffer** (`repl-backlog-size`), the master sends `+CONTINUE` and streams only the missing commands.
   * Otherwise, the master initiates a **Full Synchronization** by returning `+FULLRESYNC <master_replid> <offset>`.

#### Phase 2: Full Synchronization (RDB Transfer)
1. **Master Fork:** The master forks a child process using Copy-on-Write to write an RDB snapshot file to disk (or directly to socket in diskless mode).
2. **Write Buffering:** While the child is writing the RDB, the master stores all new client write commands in the **Replication Backlog Buffer**.
3. **RDB Transmission:** The master streams the finished RDB file over the network to the replica.
4. **Replica Loading:** The replica discards its old dataset, loads the RDB file into memory, and is blocked from serving other requests during this phase.

#### Phase 3: Replication Buffer Replay & Command Propagation
1. Once the replica completes loading the RDB, the master streams all writes accumulated in its backlog buffer.
2. After catching up, the master continuously propagates every new write command to the replica in standard RESP format.
3. Replicas send periodic heartbeats (`REPLCONF ACK <offset>`) every second to report their current offset and check network lag.

---

### 3.2 Sentinel Failover Process
Redis Sentinel is a distributed system. The process of detecting a master failure and promoting a replica is strictly sequenced:

```
[Master Fails] 
     │
     ▼
[Sentinel S1 flags SDOWN]
     │
     ▼
[S1 queries other Sentinels via Gossip Protocol]
     │
     ▼
[Quorum reached -> Master flagged ODOWN]
     │
     ▼
[Sentinels hold election (Raft-like consensus) -> Leader elected]
     │
     ▼
[Leader elects healthiest Replica -> Promotes with SLAVEOF NO ONE]
     │
     ▼
[Leader reconfigures remaining Replicas -> SLAVEOF new_master]
     │
     ▼
[Sentinels publish +switch-master channel to notify client apps]
```

#### 1. Subjective Down (SDOWN)
An individual Sentinel instance sends `PING` to the master every second. If the master fails to respond with a valid reply within the configured `down-after-milliseconds` threshold (default 30 seconds), the Sentinel flags the master locally as **SDOWN** (Subjective Down).

#### 2. Objective Down (ODOWN)
Once a Sentinel flags a master as SDOWN, it broadcasts queries to all other Sentinel nodes using the gossip protocol (`SENTINEL is-master-down-by-addr`).
If the number of Sentinels confirming that the master is unreachable reaches the configured **Quorum** size (e.g. 2 out of 3), the master is promoted to **ODOWN** (Objective Down) status.

#### 3. Leader Election
Once ODOWN is reached, the Sentinels must elect a single "Leader Sentinel" to orchestrate the failover.
* Sentinels vote using an epoch-based consensus algorithm (similar to Raft).
* A Sentinel nominates itself as a leader for the current epoch and requests votes from peers.
* The candidate Sentinel must receive votes from a **majority of all active Sentinels** (and at least equal to the quorum) to win the election.

#### 4. Replica Selection
The elected Leader Sentinel evaluates the available replicas of the failed master and selects the healthiest candidate using these rules:
1. **Staleness Filter:** Discard replicas that have been disconnected from the master for more than `10 * down-after-milliseconds` (considered too out-of-sync).
2. **Priority Check:** Select the replica with the lowest **replica priority** config value (set via `replica-priority` in `redis.conf`). Note: A replica-priority of `0` means the replica can *never* be promoted to master.
3. **Offset Check:** If priorities are equal, select the replica with the highest **replication offset** (meaning it has received the most data from the old master).
4. **Tie-Breaker:** If offsets are equal, select the replica with the lexicographically smallest **run ID** (an arbitrary process signature).

#### 5. Promotion & Reconfiguration
1. The Leader Sentinel sends the command `SLAVEOF NO ONE` (or `REPLICAOF NO ONE`) to the chosen replica. The replica transitions into a master.
2. The Leader Sentinel sends `SLAVEOF <new_master_ip> <new_master_port>` to the remaining replicas so they begin synchronizing from the new master.
3. Sentinels broadcast a `+switch-master` event on their internal pub/sub channels. Client libraries listening to Sentinels receive this message and automatically rebuild their connection pools to target the new master.

---

### 3.3 The Brain-Split Problem (Split-Brain)
A brain-split occurs during network partitions, dividing the Redis nodes into isolated segments.

#### Scenario:
1. We have 1 Master ($M_1$), 2 Replicas ($R_1$, $R_2$), and 3 Sentinels ($S_1$, $S_2$, $S_3$).
2. A network partition occurs. $M_1$ and Client $C_1$ are on the left side of the partition. $R_1$, $R_2$, $S_1$, $S_2$, and $S_3$ are on the right side.

```
       [Left Side]                  │                  [Right Side]
                                    │
   ┌─────────────────┐              │              ┌─────────────────┐
   │    Client C1    │              │              │  Sentinels      │
   └────────┬────────┘              │              │  [S1, S2, S3]   │
            │                       │              └────────┬────────┘
            ▼                       │                       │
   ┌─────────────────┐              │                       ▼
   │    Master M1    │              │              ┌─────────────────┐
   │ (Accepts writes)│              │              │   Replica R1    │
   └─────────────────┘              │              │ (Promoted to M2)│
                                    │              └─────────────────┘
                                    │
                            Network Partition
```

3. Sentinels ($S_1, S_2, S_3$) on the right side cannot reach $M_1$. Since they form a majority (3 out of 3), they flag $M_1$ as ODOWN, elect a leader, and promote $R_1$ to be the new master ($M_2$).
4. Client $C_1$ is still connected to $M_1$ on the left side. It continues sending write commands to $M_1$, which accepts them because it does not know it has been demoted.
5. **The partition heals.** $M_1$ reconnects to the network. The Sentinels reconfigure $M_1$ to act as a replica of the new master $M_2$.
6. **Data Loss:** $M_1$ executes `FULLRESYNC` with $M_2$. It flushes its memory, **deleting all writes accepted from Client $C_1$ during the partition**.

#### The Mitigation: `min-replicas-to-write`
To prevent an isolated master from accepting writes that will later be discarded, Redis provides two configuration parameters that act as a write circuit-breaker:

```ini
min-replicas-to-write 1
min-replicas-max-lag 10
```

* **Mechanism:** The master will refuse write commands (returning a `-NOREPLICAS` error to clients) if it is connected to fewer than `min-replicas-to-write` replicas whose lag is less than or equal to `min-replicas-max-lag` seconds.
* **During Brain-Split:** In the partition above, $M_1$ loses connection to $R_1$ and $R_2$. The master detects $0$ active replicas (which is $< 1$). $M_1$ immediately stops accepting writes. Client $C_1$ receives errors and can failover, preventing silent data loss.

---

## 4. CODE / EXAMPLES (Exclusively Java)

### 4.1 Redis Sentinel Connection Config using Jedis
`JedisSentinelPool` queries the Sentinels to locate the current master and subscribes to failover events.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.JedisPoolConfig;
import java.util.HashSet;
import java.util.Set;

public class JedisSentinelService {
    private final JedisSentinelPool sentinelPool;

    public JedisSentinelService() {
        String masterName = "mymaster";
        
        // Define Sentinel node addresses
        Set<String> sentinels = new HashSet<>();
        sentinels.add("192.168.1.10:26379");
        sentinels.add("192.168.1.11:26379");
        sentinels.add("192.168.1.12:26379");

        // Pool configuration details
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(32);
        poolConfig.setMinIdle(8);
        poolConfig.setTestOnBorrow(true);

        // Instantiating the pool. It will query sentinels automatically.
        this.sentinelPool = new JedisSentinelPool(
            masterName, 
            sentinels, 
            poolConfig, 
            6000,          // Connection timeout (ms)
            "strongPassword" // Redis password
        );
    }

    public void setSessionData(String sessionId, String value) {
        // JedisSentinelPool.getResource() returns a client pointing to the active master
        try (Jedis jedis = sentinelPool.getResource()) {
            jedis.setex("session:" + sessionId, 3600, value);
        }
    }

    public String getSessionData(String sessionId) {
        try (Jedis jedis = sentinelPool.getResource()) {
            return jedis.get("session:" + sessionId);
        }
    }

    public void shutdown() {
        if (sentinelPool != null) {
            sentinelPool.close();
        }
    }
}
```

---

### 4.2 Spring Data Redis Configuration with Sentinel & Lettuce (Read-Replica Preferred)
In production microservices, we configure Lettuce (Spring's default Redis driver) to write to the Master and offload reads to Replicas.

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.lettuce.core.ReadFrom;

@Configuration
public class SpringRedisSentinelConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // 1. Configure Sentinel settings
        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
            .master("mymaster")
            .sentinel("192.168.1.10", 26379)
            .sentinel("192.168.1.11", 26379)
            .sentinel("192.168.1.12", 26379);
        
        sentinelConfig.setPassword("strongPassword");

        // 2. Read Preference: Read from replica where possible, fallback to master
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .build();
        
        // 3. Return Lettuce connection factory
        return new LettuceConnectionFactory(sentinelConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

> [!TIP]
> **Read Preference Modes in Lettuce:**
> * `ReadFrom.MASTER`: Reads only from the master (strictly consistent, highest latency/load on master).
> * `ReadFrom.REPLICA`: Reads only from replica nodes (can read stale data, scale read operations).
> * `ReadFrom.REPLICA_PREFERRED` (Recommended): Reads from replicas, falls back to the master if all replicas are unreachable.

---

## 5. INTERVIEW ANGLES

### Q: Walk through the exact "lost-write window" during a Redis Sentinel failover. Why is Redis replication fundamentally AP rather than CP?
**A:** The lost-write window consists of two distinct segments:
1. **Replication Lag Window:** Redis replication is asynchronous. Master acknowledges the write to the client immediately and fires the replication payload down the TCP socket to the replica. If the master dies before the packet hits the replica, the write is lost.
2. **Failover Detection Window:** When the master dies, Sentinels do not instantly promote a replica. They wait for `down-after-milliseconds` (e.g. 10s or 30s) to confirm SDOWN, gossip to confirm ODOWN, elect a leader, and execute promotion. During this detection window, clients who still have an open connection to the old master will continue sending writes to it. When the old master recovers, it gets demoted to a replica and its memory is completely overwritten during `FULLRESYNC`, wiping out those writes.
Because write operations do not block waiting for consensus ACKs from a majority of nodes, Redis replication prioritizes write speed and availability over absolute consistency, classifying it as an AP database.

### Q: Explain the difference between Full Sync and Partial Sync in master-replica replication. What happens under heavy write load when the replication backlog buffer size (`repl-backlog-size`) is too small?
**A:** 
* **Partial Sync:** Occurs when a replica disconnects briefly. The replica reconnects and requests its last processed offset. If the master still has the writes that occurred during the disconnect inside its memory backlog buffer (`repl-backlog-size`), it streams just those missing RESP commands.
* **Full Sync:** Occurs when a replica connects for the first time, or if the disconnect period was so long that the replica's offset fell out of the master's backlog buffer. The master must run `BGSAVE` to create an RDB, stream the RDB file to the replica, and have the replica block while reloading the file.
**Under heavy write load:** If `repl-backlog-size` is set too small (e.g. 1MB on a system writing 10MB/sec), the backlog will overwrite its older buffer entries within 100 milliseconds. If a replica experiences a brief network blip lasting 200 milliseconds, it will reconnect and request its offset. Since the offset has been overwritten, the master is forced to fall back to a **Full Sync**. 
The Full Sync triggers disk I/O (BGSAVE) and streams a massive file, saturating the network. This network/disk load causes the replica to lag further, creating a vicious cycle of repeated Full Syncs that can stall the master and crash the cluster. The solution is to scale up `repl-backlog-size` to buffer several minutes of write traffic.

### Q: What is a Split-Brain scenario, and how do `min-replicas-to-write` and `min-replicas-max-lag` prevent it? What are the operational trade-offs?
**A:** A split-brain occurs when a network partition cuts off the master and a few client applications from the rest of the Sentinel cluster and replicas. The majority side of the partition elects a new master, while the old master continues accepting writes from the isolated client applications. When the partition heals, the old master is demoted to replica and all writes it accepted during the partition are overwritten and lost.
The settings `min-replicas-to-write` and `min-replicas-max-lag` prevent this by disabling writes on the old master. If the old master cannot communicate with at least $N$ replicas with a lag of less than $M$ seconds, it begins rejecting client writes.
**Operational Trade-off:** By enabling this protection, you trade write-availability for consistency. If network congestion or a replica restart causes replica lags to exceed the threshold, the master will reject writes for all client applications, even when no network partition exists.

### Q: How does Sentinel elect a Leader Sentinel, and then how does that Leader decide which Replica to promote?
**A:** 
* **Leader Sentinel Election:** Sentinels use an epoch-based voting mechanism. When a master is detected as ODOWN, a Sentinel increments its configuration epoch and requests votes from peer Sentinels. The first Sentinel to receive votes from a majority of all configured Sentinels is elected leader.
* **Replica Selection:** The leader Sentinel selects the promotion candidate using a strict hierarchy:
  1. Filters out "unhealthy" replicas (disconnected from Sentinel, or disconnected from the old master for too long).
  2. Selects the replica with the lowest configured `replica-priority` (non-zero).
  3. If priorities are equal, selects the replica with the highest `repl-offset` (most up-to-date data).
  4. If offsets are equal, selects the replica with the lexicographically smallest `runid` as a tie-breaker.

---

## 6. ONE-LINE RECALL CARDS

*   Redis replication is **asynchronous**, meaning clients receive write confirmations before the data is copied to replicas.
*   **PSYNC** allows replicas to reconnect and resume replication at a specific offset without performing a full data transfer.
*   A **Full Resync** forks a master `BGSAVE` process to generate and transfer an RDB binary file, blocking the replica's main thread during load.
*   **Subjective Down (SDOWN)** is a local failure flag set by a single Sentinel; **Objective Down (ODOWN)** is a cluster-wide consensus reached when quorum is met.
*   Sentinel uses a **Raft-like epoch voting consensus** to elect a single leader Sentinel to coordinate the failover steps.
*   Replica promotion chooses the healthier node by prioritizing **lowest replica-priority**, then **highest offset**, then **lexicographically smallest run ID**.
*   **Split-Brain** occurs when network partitions isolate a master, allowing it to accept writes that are later discarded when the partition heals.
*   **`min-replicas-to-write`** and **`min-replicas-max-lag`** safeguard data by blocking master writes if connected replica count drops below threshold.
*   Java clients discover master failovers dynamically by subscribing to the Sentinel's **`+switch-master`** Pub/Sub channel.

---

**Next:** [10 — Redis Cluster & Sharding](10-cluster-sharding.md) (16384 hash slots, CRC16, MOVED/ASK, hash tags, multi-key limits).
