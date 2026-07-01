# 10 — Redis Cluster & Sharding

> **Why this is Topic 10:** In a high-throughput system like Zerodha, a single standalone Redis node cannot scale infinitely. When hot key traffic (like tracking millions of active user sessions, order routing configurations, or live quote streams) exceeds the physical RAM or CPU limits of a single machine, we must partition the data across multiple machines. Redis Cluster provides a share-nothing, decentralized architecture that scales horizontally to terabytes of RAM and millions of operations per second. However, distributed databases come with significant trade-offs regarding consistency, multi-key operations, and network partitioning. SDE2 interviewers will test your depth on the hash slot mechanism, CRC16 hashing, slot migration mechanics, gossip protocol overhead, the difference between `MOVED` and `ASK` redirects, hash tags, and how to write production-grade cluster configurations in Java with automated topology refresh.

---

## 1. WHAT

**Redis Cluster** is a decentralized, distributed implementation of Redis that automatically shards data across multiple master nodes. It provides high availability, horizontal scaling, and fault tolerance without requiring a central coordinator or Sentinel nodes.

```mermaid
graph TD
    Client["Cluster-Aware Client<br>(Caches Slot-to-Node Map)"]
    
    subgraph Cluster["Redis Cluster (Mesh Topology)"]
        subgraph NodeGroup1["Shard 1 (Slots 0 - 5460)"]
            Master1["Master 1 (Port 7001)"]
            Replica1["Replica 1 (Port 7004)"]
        end
        subgraph NodeGroup2["Shard 2 (Slots 5461 - 10922)"]
            Master2["Master 2 (Port 7002)"]
            Replica2["Replica 2 (Port 7005)"]
        end
        subgraph NodeGroup3["Shard 3 (Slots 10923 - 16383)"]
            Master3["Master 3 (Port 7003)"]
            Replica3["Replica 3 (Port 7006)"]
        end
    end

    Client -->|1. Direct Write/Read (Slot 3000)| Master1
    Client -.->|2. Redirect if slot mismatch| Master2
    Master1 <-->|Gossip Protocol<br>Port 17001 <-> 17002| Master2
    Master2 <-->|Gossip Protocol| Master3
    Master3 <-->|Gossip Protocol| Master1
    
    Master1 ===|Async Replication| Replica1
    Master2 ===|Async Replication| Replica2
    Master3 ===|Async Replication| Replica3
```

### Core Architecture Pillars:
1. **Hash Slots (Partitioning):** The keyspace is divided into exactly **16,384 logical partition units** called **hash slots**. Keys are mapped to these slots using a deterministic hashing algorithm.
2. **Share-Nothing Decentralization:** There is no master proxy or routing coordinator. Every node in the cluster knows which slots are assigned to every other node. If a client queries Node A for a key belonging to Node B, Node A does not proxy the query; it responds with a redirect error pointing the client to Node B.
3. **Decentralized Sentinel (High Availability):** The nodes monitor each other via a gossip protocol. If a master node fails, the remaining masters vote to promote one of the failed master's replicas to become the new master.

---

## 2. WHY (the trade-offs)

### 2.1 Sharding vs. Sentinel Replication
Sentinel-based high availability is limited by the fact that **only one master node accepts writes**. At Zerodha, during active trading hours, write throughput on user sessions, order tracking, and trade limits can choke a single core. 

| Dimension | Standalone | Sentinel (HA) | Redis Cluster (Sharding) |
| :--- | :--- | :--- | :--- |
| **Primary Focus** | Simplicity | High Availability | Horizontal Scalability & HA |
| **Max Memory Capacity** | Bounded by single instance RAM | Bounded by single instance RAM | Unlimited (Aggregate RAM across all masters) |
| **Write Scalability** | None (Single Master) | None (Single Master) | Scales linearly with number of Master nodes |
| **Decentralization** | N/A | Centralized failover controller (Sentinel) | Completely decentralized (Gossip protocol, no manager) |
| **Multi-key Operations** | Supported fully | Supported fully | Bounded to single hash slot (cross-slot operations fail) |
| **Client Complexity** | Low | Low (Requires Sentinel detection) | High (Must cache slot mapping, handle redirects) |

### 2.2 Architectural Trade-offs & Limitations
* **Cross-Slot Multi-Key Limitations:** Commands that operate on multiple keys (e.g. `MGET`, `MSET`, `SUNION`, Lua scripts, transactions) are rejected if the keys hash to different slots. Developers must group keys using *Hash Tags* to bypass this, which carries hotspots risk.
* **Write Loss Window (Consistency):** Redis Cluster uses asynchronous replication between masters and replicas. If a master accepts a write, acknowledges the client, and crashes before propagating the write to its replica, the write is lost.
* **Gossip Protocol Network Overhead:** In large clusters (e.g., 500+ nodes), the gossip traffic on the cluster bus (port + 10000) can consume significant network bandwidth.
* **Network Partition Vulnerabilities:** If the network is partitioned, a master node isolated on the minority side will continue to accept writes for a brief period (`cluster-node-timeout`) before shifting to an error state. Once the majority side elects a new master, any writes made to the old master during the partition are overwritten and permanently lost.

---

## 3. HOW (the internals)

### 3.1 The 16384 Hash Slots & Hashing
Redis Cluster does not use random sharding or consistent hashing; it uses **Hash Slots**. The keyspace is divided into 16,384 slots (numbered `0` to `16383`).

#### The Hash Slot Formula:
To determine which slot a key $K$ belongs to, Redis computes the CRC16 checksum of the key and applies a modulo of 16384. To optimize execution speed on CPU, this is implemented as a bitwise AND operation:

$$\text{Slot} = \text{CRC16}(K) \pmod{16384}$$
$$\text{Slot} = \text{CRC16}(K) \& 16383$$

> [!NOTE]
> **Why exactly 16,384 slots?**
> 1. **Gossip Protocol Packet Size:** Nodes periodically ping each other. The ping/pong packets contain the slot configuration of the sender node. A bitmap representing 16,384 slots takes up exactly **2,048 bytes (2KB)** (`16384 / 8 bits`). If Redis used 65,536 slots, the bitmap size would increase to **8,192 bytes (8KB)**. Since heartbeat packets are exchanged constantly across the mesh network, 8KB payloads would cause severe network overhead at scale.
> 2. **Granularity vs. Node Count:** A typical Redis Cluster is designed to scale up to a maximum of 1,000 master nodes. 16,384 slots provide a fine enough granularity where a 1,000-node cluster still leaves about 16 slots per node, allowing balanced distribution.
> 3. **Compression:** The slot configuration bitmap compresses extremely well when a node owns a large contiguous block of slots.

---

### 3.2 Hash Tags: Multi-Key Execution
To execute multi-key operations (like transaction blocks or Lua scripts) in a cluster, all targeted keys must reside in the **same hash slot**. Redis Cluster enforces this using **Hash Tags**.

If a key contains `{` and `}` characters, Redis hashes *only* the substring between the curly braces.

#### Hash Tag Rules:
1. The key must contain a `{` character.
2. There must be a `}` character to the right of `{`.
3. There must be one or more characters between `{` and `}`.

#### Hashing Examples:
* `{user:100}:profile` and `{user:100}:orders` $\rightarrow$ both hash **`user:100`** $\rightarrow$ Guaranteed to land on the same slot/node.
* `user:{100}:profile` $\rightarrow$ hashes **`100`**.
* `user{}{100}:profile` $\rightarrow$ hashes the entire key because the first pair `{}` is empty.
* `user{100` $\rightarrow$ hashes the entire key because there is no closing `}`.
* `user{100}profile{200}` $\rightarrow$ hashes **`100`** because only the first match pair is processed.

> [!CAUTION]
> **The Hotspot Pitfall:** Overusing hash tags to map thousands of keys (e.g. all data of a high-volume client) to the same slot destroys the benefits of horizontal scaling, creating memory hot spots and CPU bottlenecks on a single shard.

---

### 3.3 MOVED vs. ASK Redirects
In a decentralized cluster, clients are responsible for routing commands to the correct node. When a client sends a query to Node A for a key that maps to a slot owned by Node B, Node A rejects the query and sends a redirect response.

#### 1. `MOVED` Redirect
A `MOVED` redirect is returned when the slot is **permanently** owned by another node.
* **Error Response:** `-MOVED 3999 127.0.0.1:7002` (Indicates Slot 3999 is owned by node at 127.0.0.1:7002).
* **Client Action:** The client updates its local slot-to-node routing cache (mapping Slot 3999 to Node 7002) and retries the command. All future queries for Slot 3999 will go directly to Node 7002.

#### 2. `ASK` Redirect
An `ASK` redirect is returned **temporarily** when a slot is undergoing migration (resharding) from a source node to a destination node, and the requested key has already been migrated.
* **Error Response:** `-ASK 3999 127.0.0.1:7002`
* **Client Action:** 
  1. The client must connect to the target node (`127.0.0.1:7002`).
  2. The client must execute an **`ASKING`** command first.
  3. The client then executes the original query on the target node.
  4. **CRITICAL:** The client **does NOT** update its local routing cache. Future queries for Slot 3999 are still routed to the source node.

```
Client                      Source (Node A)                 Destination (Node B)
  │                                │                                  │
  │─── 1. GET {user:100}:profile ──>│                                  │
  │    (Key migrated to B)         │                                  │
  │◄── 2. -ASK 3999 B-IP:B-PORT ───│                                  │
  │                                                                   │
  │─── 3. ASKING ────────────────────────────────────────────────────>│
  │◄── 4. OK ────────────────────────────────────────────────────────>│
  │                                                                   │
  │─── 5. GET {user:100}:profile ────────────────────────────────────>│
  │◄── 6. "Alice" (Data response) ───────────────────────────────────│
```

> [!NOTE]
> **Why is the `ASKING` command required?**
> The destination node does not yet officially own Slot 3999 (migration is incomplete). If a client sends a query for Slot 3999 to the destination node without first sending `ASKING`, the destination node will reject it with a `-MOVED` error pointing back to the source node, creating an infinite redirect loop. The `ASKING` command sets a one-shot flag on the connection to force the node to process the query for this specific slot.

---

### 3.4 Slot Migration Mechanics (Resharding)
When adding or removing nodes, hash slots must be moved between instances. This process is online and does not block client traffic.

#### Migration Sequence for Slot $S$ from Source Node A to Target Node B:
1. **Set States:** 
   * On Target Node B: Run `CLUSTER SETSLOT <S> IMPORTING <Node-A-ID>` (Sets the node to accept `ASKING` requests for slot $S$).
   * On Source Node A: Run `CLUSTER SETSLOT <S> MIGRATING <Node-B-ID>` (Instructs the node to return `-ASK` redirects for keys it no longer holds).
2. **Move Keys:** 
   * The migration controller requests keys from Node A: `CLUSTER GETKEYSINSLOT <S> <batch_count>`.
   * For each key, Node A executes the atomic, blocking `MIGRATE` command:
     `MIGRATE <B-host> <B-port> "" <db-index> <timeout> KEYS <key1> <key2>...`
   * *Under the hood:* `MIGRATE` connects to B, serializes the key-value payload, transmits it, verifies it, deletes the key from A's memory, and returns `OK`.
3. **Finalize Ownership:**
   * Once Slot $S$ is empty on Node A, run `CLUSTER SETSLOT <S> NODE <Node-B-ID>` on all master nodes (or let it propagate via Gossip) to update slot ownership cluster-wide.

---

### 3.5 Gossip Protocol & Failover
Every node opens two ports:
1. **Client Port (e.g., 6379):** Used for client commands.
2. **Cluster Bus Port (e.g., 16379 - Client Port + 10000):** A binary protocol port used for node-to-node communication.

#### The Gossip Cycle:
* Nodes ping a random selection of other nodes every second.
* If a node does not receive a response within half of `cluster-node-timeout`, it actively pings the target.

#### Failover Flow:
1. **`PFAIL` (Possible Fail):** If a master node is unreachable for longer than `cluster-node-timeout` milliseconds, the detecting node flags it as `PFAIL`.
2. **`FAIL`:** When a master receives gossip messages confirming that a **majority of masters** agree the target is in `PFAIL` state, it marks the target as `FAIL` and broadcasts a `FAIL` message to the cluster.
3. **Replica Election:** The replicas of the failed master detect the `FAIL` state. They calculate a start delay based on their replication offset:
   $$\text{Start Delay} = 500\text{ms} + \text{random}(0, 500)\text{ms} + (\text{replica\_rank} \times 1000\text{ms})$$
   * *Rank 0* is the replica with the highest replication offset (most up-to-date). It starts its election immediately. 
   * The replica increments the cluster epoch (`currentEpoch`) and requests votes from the active masters.
   * If a majority of masters vote yes, the replica is promoted, takes over the slot mapping, and broadcasts a cluster-wide update.

---

## 4. CODE / EXAMPLES (Exclusively Java)

### 4.1 Jedis Cluster Configuration & Hash Tag Routing
`JedisCluster` internally caches the slot-to-node mapping and automatically handles `MOVED` and `ASK` redirects.

```java
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.exceptions.JedisClusterOperationException;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JedisClusterService {

    private JedisCluster jedisCluster;

    public void init() {
        Set<HostAndPort> jedisClusterNodes = new HashSet<>();
        // Seed nodes (Jedis discovers the remaining nodes automatically)
        jedisClusterNodes.add(new HostAndPort("127.0.0.1", 7001));
        jedisClusterNodes.add(new HostAndPort("127.0.0.1", 7002));
        jedisClusterNodes.add(new HostAndPort("127.0.0.1", 7003));

        // Configure connection pooling properties
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxIdle(32);
        poolConfig.setMinIdle(8);
        poolConfig.setTestOnBorrow(true);

        // Initialize JedisCluster
        // Params: nodes, connectionTimeout (2s), socketTimeout (3s), maxAttempts (5 redirects), password, poolConfig
        this.jedisCluster = new JedisCluster(
            jedisClusterNodes, 
            2000, 
            3000, 
            5, 
            "cluster_secure_password", 
            poolConfig
        );
    }

    public void runOperations() {
        // 1. Single Key Operations work seamlessly
        jedisCluster.set("user:1001:email", "alice@zerodha.com");
        System.out.println("Email: " + jedisCluster.get("user:1001:email"));

        // 2. Multi-Key Operations without Hash Tags will FAIL
        try {
            // These keys hash to different slots
            jedisCluster.mget("user:1001:email", "user:1002:email");
        } catch (JedisClusterOperationException e) {
            System.err.println("Cross-slot error caught successfully: " + e.getMessage());
        }

        // 3. Multi-Key Operations WITH Hash Tags will SUCCEED
        // Both keys are forced to hash based on the string 'user:1001'
        jedisCluster.set("{user:1001}:email", "alice@zerodha.com");
        jedisCluster.set("{user:1001}:role", "trader");

        // Since both keys map to the exact same slot, MGET succeeds
        List<String> values = jedisCluster.mget("{user:1001}:email", "{user:1001}:role");
        System.out.println("MGET Results: " + values);
    }

    public void shutdown() {
        if (jedisCluster != null) {
            try {
                jedisCluster.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
```

---

### 4.2 Spring Data Redis (Lettuce) Configuration with Production Topology Refresh
In a production cloud environment, Redis nodes can be replaced, failovers occur, and slots migrate. By default, Spring Data Redis's underlying Lettuce client caches the node topology. If a failover occurs, Lettuce will continue sending traffic to the dead master node unless **Topology Refreshing** is configured.

```java
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
public class RedisClusterConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 1. Configure the seed nodes
        List<String> nodes = Arrays.asList(
            "127.0.0.1:7001",
            "127.0.0.1:7002",
            "127.0.0.1:7003"
        );

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(nodes);
        clusterConfig.setPassword("cluster_secure_password");
        clusterConfig.setMaxRedirects(5); // Retries for MOVED/ASK redirects

        // 2. Configure Cluster Topology Refresh Options (CRITICAL for production HA)
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
            // Enable adaptive refresh (triggers topology update on MOVED, ASK, connection drops)
            .enableAdaptiveRefreshTrigger(
                ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT,
                ClusterTopologyRefreshOptions.RefreshTrigger.ASK_REDIRECT,
                ClusterTopologyRefreshOptions.RefreshTrigger.PERSISTENT_RECONNECTS
            )
            .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(30)) // Max once every 30s to prevent spam
            // Enable periodic polling refresh
            .enablePeriodicRefresh(Duration.ofMinutes(10)) // Polls the cluster configuration map every 10 mins
            .build();

        // 3. Configure Socket Options
        SocketOptions socketOptions = SocketOptions.builder()
            .connectTimeout(Duration.ofSeconds(2)) // Fail fast if connection fails
            .keepAlive(true)
            .build();

        // 4. Configure Cluster Client Options
        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
            .topologyRefreshOptions(topologyRefreshOptions)
            .socketOptions(socketOptions)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS) // Do not queue commands when disconnected
            .build();

        // 5. Combine configurations into Lettuce Connection Factory
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(3)) // Write/Read timeout
            .clientOptions(clientOptions)
            .build();

        return new LettuceConnectionFactory(clusterConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

---

## 5. INTERVIEW ANGLES

### Q: Why did Redis choose exactly 16,384 slots? Explain the Gossip protocol payload size math.
**A:** The limit is set due to a trade-off between network overhead and cluster scalability:
1. **Gossip Packet Overhead:** Nodes exchange heartbeat packets containing the slot layout they own. This configuration is stored as a raw bitmap. A bitmap of 16,384 slots takes exactly 2KB of data (`16384 / 8 bits = 2048 bytes`). If the cluster used 65,536 slots, the configuration bitmap would grow to 8KB. Given that gossip packets are sent constantly by every node to multiple peers, the 8KB header size would flood the network link in large clusters.
2. **Granularity vs. Node Count:** Redis Cluster scales up to a maximum recommended size of 1,000 master nodes. 16,384 slots are sufficient to allow 1,000 masters to hold an average of 16 slots each, making slots easy to move and balance. 65,536 slots would offer no performance or partitioning advantage at this scale.
3. **Compression Efficiency:** CONTIGUOUS slots are compressed efficiently in cluster message formats. A smaller number of slots (16,384) remains highly compact compared to a large, sparse 65,536 slot configuration.

---

### Q: What is the difference between `MOVED` and `ASK` redirect errors? How does a cluster-aware client react to each, and why?
**A:** They represent different states of slot ownership:
* **`MOVED`** represents a **permanent** change of slot ownership. The slot has been migrated, and the target node is now the official owner. 
  * *Client Reaction:* The client updates its local routing cache mapping that slot to the target node's IP/Port, and then retries the command. Subsequent operations for that slot bypass the redirecting node.
* **`ASK`** represents a **temporary** state during slot migration. The slot is migrating, and the target key has already moved to the destination node, but the migration of the slot itself is still in progress.
  * *Client Reaction:* The client establishes a temporary connection to the destination node, sends an `ASKING` command, and then sends the actual query. The client **does NOT** update its local routing cache. Subsequent requests for other keys in that slot are still sent to the source node.
  * *Why `ASKING`?* Because the destination node does not yet own the slot. If a client queries the destination without `ASKING`, the destination will reject it with `MOVED` pointing back to the source, causing an infinite loop.

---

### Q: How do you execute atomic multi-key transactions or Lua scripts in Redis Cluster, and what are the operational risks?
**A:** In Redis Cluster, multi-key operations (transactions, MGET/MSET, Lua scripts) are only supported if all keys map to the **exact same hash slot**.
* **The Solution:** Use **Hash Tags**. By wrapping a common identifier inside curly braces, e.g., `{user:1001}:profile` and `{user:1001}:metadata`, Redis is forced to hash only the string inside `{}` (`user:1001`). Both keys are guaranteed to land on the same master node, permitting atomic execution.
* **The Operational Risks:** Hash tags can lead to **Hot Spotting**. If thousands of heavy keys share the same tag, they will stack on a single master. This leads to unbalanced memory distribution and CPU bottlenecks on one node, while other nodes in the cluster remain underutilized.

---

### Q: What happens if a network partition splits a Redis Cluster? Under what conditions does the cluster stop accepting writes, and how does the cluster resolve partitions?
**A:** 
1. **The Partition Behavior:** Suppose we have a cluster of 3 masters ($M1, M2, M3$) each with one replica. A network partition occurs, isolating $M1$ and its replica $R1$ from the rest of the cluster ($M2, M3, R2, R3$).
2. **Minority Side ($M1$):** $M1$ is isolated. It can no longer communicate with the majority of masters. If clients send writes to $M1$, it will accept them initially. However, after `cluster-node-timeout` passes, $M1$ realizes it cannot reach the majority, realizes it is isolated, and starts returning error messages (`CLUSTERDOWN`) to clients, rejecting all writes.
3. **Majority Side ($M2, M3$):** The majority side realizes $M1$ is down. Since $M2$ and $M3$ form a majority of the cluster's masters (2 out of 3), they can vote. They elect and promote replica $R1$ (which is on the majority side, or if not, whichever replica of $M1$ is reachable) to become the new master for $M1$'s slots.
4. **Reconciliation:** When the network heals, $M1$ re-joins the cluster. It detects that its epoch is older than the newly promoted master's epoch. $M1$ demotes itself to a replica and starts syncing from the new master, discarding any writes it accepted during the partition window (these writes are permanently lost).
5. **Mitigation:** To prevent accepting writes that are doomed to be lost, configure:
   * `min-replicas-to-write 1` (requires at least 1 replica to be connected to the master for writes to succeed).
   * `min-replicas-max-lag 10` (the replica must have synced within the last 10 seconds).

---

### Q: Why is configuring Lettuce Connection Factory topology refresh properties critical in Spring Data Redis?
**A:** By default, Lettuce caches the cluster partition layout. If a failover occurs (e.g. Master A dies, Replica A is promoted to Master A' with a new IP/Port, or a new slot map is generated):
1. **The Problem:** Lettuce will continue sending queries to Master A's old IP. The socket connection will time out, or requests will fail, causing downtime.
2. **The Fix:** We must enable **Adaptive Topology Refresh**. This configures Lettuce to listen for events like `MOVED_REDIRECT`, `ASK_REDIRECT`, or connection drops (`PERSISTENT_RECONNECTS`). When these occur, Lettuce queries the remaining nodes (`CLUSTER SHARDS` or `CLUSTER NODES`) to refresh its internal slot-to-node routing table dynamically.
3. **Periodic Refresh:** We also configure a **Periodic Refresh** (e.g., every 10 minutes) as a fallback mechanism to discover newly added shards or nodes during routine scaling operations.

---

## 6. ONE-LINE RECALL CARDS

* **Redis Cluster** partitions the keyspace into **16,384** logical hash slots distributed across master nodes.
* The slot for a key is calculated using **`CRC16(key) & 16383`**.
* **Hash Tags** (e.g., `{user:100}:key`) force keys to map to the same slot to allow multi-key operations/transactions.
* **`MOVED` redirects** indicate a permanent slot ownership change; the client updates its routing cache.
* **`ASK` redirects** indicate a temporary slot migration; the client sends `ASKING` and does NOT update its cache.
* The **Gossip Protocol** runs on the cluster bus port (client port + 10000) to maintain state and detect node failures.
* A master is marked **`FAIL`** only when a majority of master nodes agree it is in a `PFAIL` (possible fail) state.
* Promoted replicas rank based on **replication offset** to minimize data loss and avoid split-brain elections.
* Network partitions cause the minority side to enter **`CLUSTERDOWN`** state after `cluster-node-timeout` expires.
* **Lettuce adaptive topology refresh** is mandatory in production to update cached routing tables during node failovers.

---

**Next:** [11 — Fintech Patterns](11-fintech-patterns.md) (rate limiting, idempotency, session stores, leaderboards).
