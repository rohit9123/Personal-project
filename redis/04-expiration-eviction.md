# 04 — Redis Expiration & Eviction

> **Why this is Topic 4:** Redis memory is finite and expensive. Without correct management, your database will run out of RAM, leading to write failures (`OOM command not allowed`) or random data loss. For SDE2 interviews, you must clearly distinguish between **Expiration** (removing keys whose time-to-live has naturally ended) and **Eviction** (reclaiming space when physical memory limits are reached by deleting keys prematurely). Interviewers will push on the mechanics of passive vs. active expiration, replication constraints, and the internal math behind approximated LRU and LFU algorithms.

---

## 1. WHAT

Redis separates memory reclamation into two distinct workflows:

1. **Expiration (TTL):** A key is registered with a Time-To-Live (TTL). When that time passes, the key becomes dead. Redis uses **passive (lazy)** and **active (periodic)** cycles to remove expired keys from memory.
2. **Eviction (`maxmemory`):** When the database memory usage exceeds the configured `maxmemory` limit, Redis executes an eviction algorithm (like LRU, LFU, or Random) to immediately free space for incoming write commands.

```
                  ┌─────────────────────────────────────────┐
                  │            Redis Memory Limit           │
                  └─────────────────────────────────────────┘
                                       │
            ┌──────────────────────────┴──────────────────────────┐
            ▼                                                     ▼
┌───────────────────────────────┐                     ┌───────────────────────────────┐
│     1. EXPIRATION (TTL)       │                     │     2. EVICTION (maxmemory)   │
├───────────────────────────────┤                     ├───────────────────────────────┤
│ • Keys expire naturally over  │                     │ • Keys deleted prematurely to │
│   time.                       │                     │   free physical RAM space.    │
│ • Controlled by:              │                     │ • Controlled by:              │
│   - Passive (Lazy) Expiry     │                     │   - maxmemory configuration   │
│   - Active (Periodic) Expiry  │                     │   - maxmemory-policy          │
└───────────────────────────────┘                     └───────────────────────────────┘
```

---

## 2. WHY (the trade-offs)

### 2.1 Why not delete expired keys instantly?
To delete a key the exact millisecond it expires, Redis would need a dedicated timer (thread or OS signal) for every single key. 
* **The Cost:** If you have 10 million keys with TTLs, managing 10 million active timers would consume massive CPU resources, starving the single-threaded command execution loop.
* **The Trade-off:** Redis trades memory for CPU. It allows expired keys to sit in memory slightly longer, cleaning them up using cheap, scheduled sampling algorithms.

### 2.2 Why use Approximated LRU/LFU instead of standard algorithms?
A standard **Least Recently Used (LRU)** cache uses a doubly linked list of all items. When an item is read, it is moved to the head of the list.
* **The Problem:** In a multi-gigabyte cache, a linked list requires **24 bytes of pointers** (`prev`, `next` pointers) per key, leading to memory fragmentation and overhead. Moreover, mutating a list on every single read operation degrades performance.
* **The Redis Solution:** Redis uses **Approximated LRU/LFU** which stores a simple 24-bit metadata stamp on each key, avoiding linked lists entirely.

---

## 3. HOW (the internals)

### 3.1 Expiration (TTL) Internals
Every Redis database (`redisDb` struct) contains two key-value dictionaries:
* `dict`: Maps all keys to their actual data values.
* `expires`: Maps key pointers to their millisecond Unix expiration timestamps.

```c
typedef struct redisDb {
    dict *dict;    /* The keyspace map: key -> robj */
    dict *expires; /* The timeout map:  key -> timestamp */
    // ...
} redisDb;
```

#### 3.1.1 Passive (Lazy) Expiry
When a client requests a key (e.g. `GET user:123`), Redis runs a quick check:
1. It looks up the key in the `expires` dictionary.
2. If the current Unix time is greater than the expiration timestamp, Redis deletes the key (and its value) from both dictionaries and returns `NIL`.
* **Pros:** $O(1)$ CPU usage. It only cleans up what is asked for.
* **Cons:** If a key is never read again, it sits in memory forever, leaking RAM.

#### 3.1.2 Active (Periodic) Expiry
To clean up unread keys, Redis runs `activeExpireCycle()` inside its event loop (during `beforesleep` and time event ticks):

```
[activeExpireCycle Start]
           │
           ▼
┌───────────────────────────────┐
│ Sample 20 random keys with    │
│ TTL from the expires dict     │
└───────────────────────────────┘
           │
           ▼
┌───────────────────────────────┐
│ Delete all keys that have     │
│ already expired               │
└───────────────────────────────┘
           │
           ▼
   Is expired count > 5? (i.e. > 25% of sample)
         ├─── Yes ──► (Loop Again! Target is to keep expired keys < 25%)
         └─── No  ──► [Stop and Wait for next event loop tick]
```

> [!WARNING]
> **Active Expiry CPU Spike:** If millions of keys are set to expire at the exact same second (e.g., at the top of the hour), `activeExpireCycle()` will repeatedly loop because the expired percentage in its samples remains $> 25\%$. To prevent the event loop from blocking indefinitely, each cycle samples a fixed `ACTIVE_EXPIRE_CYCLE_LOOKUPS_PER_LOOP` (= **20**) keys per DB and is **time-boxed to a small fraction of CPU per cycle** (governed by the `hz` frequency and `active-expire-effort` settings — there is no fixed millisecond cap). Even so, this still triggers a noticeable latency spike for concurrent client queries. **Solution:** Jitter your TTLs (add random seconds/minutes of noise) when writing keys.

#### 3.1.3 Expiry under Replication and AOF
To guarantee master-slave consistency:
* **Replicas do not expire keys on their own.** If a client queries a replica for an expired key, the replica returns the key as if it is alive (pre-Redis 3.2) or returns `NIL` but does not delete it (Redis 3.2+).
* **The Master triggers deletions:** When the master deletes an expired key (either passively or actively), it writes a synthetic `DEL` command to its replication stream, forcing all replicas to delete the key synchronously.
* **AOF Logging:** When a key expires, Redis appends a `DEL` command to the AOF log so that database rebuilds reflect the deletion.

---

### 3.2 Eviction (`maxmemory`) Internals
If memory usage hits `maxmemory`, every incoming write command triggers `performEvictions()` before execution.

#### 3.2.1 The Sequential Eviction Loop (Check & Delete One-by-One)
Redis does **not** calculate how much memory needs to be freed (e.g. 100MB) and delete a large batch of keys at once. Instead, it runs a sequential `while` loop that checks and deletes keys **one-by-one**:

```
                  Client sends write command (e.g. SET key val)
                                    │
                                    ▼
                     Is Used Memory > maxmemory?
                                    ├─── No ───► Execute write command & exit
                                    │
                                    └─── Yes ──► [Enter Eviction Loop]
                                                       │
                                                       ▼
                                            Sample keys & select the 
                                            best candidate (LRU/LFU/TTL)
                                                       │
                                                       ▼
                                            Delete that ONE candidate key
                                                       │
                                                       ▼
                                          Is Used Memory STILL > maxmemory?
                                                       ├─── Yes ───► [Loop Again]
                                                       │
                                                       └─── No ────► Exit Loop
                                                                       │
                                                                       ▼
                                                             Execute write command
```

* **Why one-by-one?**
  Keys have unpredictable sizes in memory (e.g. a simple string could be 50 bytes, while a sorted set could be 10MB). If Redis deleted a batch of 100 keys, it might delete way too much data and empty out warm cache unnecessarily. By checking memory after *every single deletion*, Redis stops the instant memory falls below the threshold, preserving as much cached data as possible.
* **The Latency Trap:**
  Since this loop blocks the event loop, if a write triggers a large memory overflow (or if memory usage suddenly swells), deleting thousands of keys one-by-one will freeze the server for milliseconds or seconds, causing timeout cascades in client APIs.

#### 3.2.2 Eviction Policies (`maxmemory-policy`)
* **`noeviction` (Default):** Returns an OOM error `(error) OOM command not allowed when used memory > 'maxmemory'` for write commands. Reads and deletes still work.
* **`allkeys-lru`:** Evicts the least recently used keys across the entire database.
* **`volatile-lru`:** Evicts the least recently used keys *only* among keys that have an active TTL.
* **`allkeys-lfu`:** Evicts the least frequently used keys across the entire database (Redis 4.0+).
* **`volatile-lfu`:** Evicts the least frequently used keys *only* among keys with a TTL.
* **`volatile-ttl`:** Evicts the key with the shortest remaining TTL first.
* **`allkeys-random` / `volatile-random`:** Evicts random keys.

---

### 3.3 Approximated LRU Algorithm
Every `redisObject` header contains a 24-bit `lru` field:
```c
typedef struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:24;  /* Stores LRU time (seconds) OR LFU data */
    int refcount;
    void *ptr;
} robj;
```

#### How it works:
1. When a key is created or accessed, Redis writes the current Unix time (in seconds, modulo $2^{24}$) to this `lru` field.
2. When eviction is triggered, Redis does **not** scan the whole database or look at a sorted list.
3. Instead, it selects **$N$ random keys** (default $N=5$, configured by `maxmemory-samples`) and inserts them into an eviction pool (sorted by idle time).
4. Redis immediately evicts the key with the longest idle time (highest difference between current time and the `lru` timestamp) in that pool.

```
[Global Database Keyspace]
  │  (Pick 5 keys completely at random)
  ├─► Key A (Idle: 10s)
  ├─► Key B (Idle: 2s)
  ├─► Key C (Idle: 45s)  ◄── Oldest (Eviction Candidate)
  ├─► Key D (Idle: 1s)
  ├─► Key E (Idle: 18s)
  │
  ▼ (Evict Key C)
[Reclaimed Memory Space]
```

* **Why this works:** Increasing `maxmemory-samples` to `10` makes the eviction behavior almost identical to true mathematical LRU, without any pointer overhead.

---

### 3.4 Approximated LFU (Least Frequently Used) Algorithm
LRU fails if a key was accessed once a second ago, but was never accessed before that (it looks "new", but it is actually cold). **LFU** tracks access *frequency*.

Redis packs LFU statistics inside the same 24-bit `lru` field of the `redisObject`:
```
+------------------------------------+--------------------------+
│   Last Decayed Time (16 bits)      │  Access Counter (8 bits) │
+------------------------------------+--------------------------+
```

1. **The Access Counter (8 bits):**
   * Since 8 bits can only count up to 255, Redis uses a logarithmic counter.
   * When a key is accessed, the counter is not incremented by 1. Instead, it has a probability of incrementing based on its current value:
     $$\text{Probability} = \frac{1}{\text{counter} \times \text{lfu-log-factor} + 1}$$
   * If `lfu-log-factor = 10`, the counter hits 100 after ~1000 accesses, and tops out at 255 after 1M+ accesses.
2. **The Decaying Counter (16 bits):**
   * If a key was accessed 1 million times yesterday, but 0 times today, it should be evicted.
   * Every time a key is accessed, Redis first decays its counter based on how long it has been idle.
   * It reads `Last Decayed Time` (representing minutes since Unix epoch), calculates the time difference, and decreases the counter by 1 for every $D$ minutes (where $D$ is configured by `lfu-decay-time`).

---

## 4. CODE / EXAMPLES

### 4.1 Configuring Expiration & Eviction in `redis.conf`
```ini
# Limit Redis memory to 2 Gigabytes
maxmemory 2gb

# Eviction strategy when maxmemory is hit
# Options: noeviction, allkeys-lru, volatile-lru, allkeys-lfu, volatile-lfu, volatile-ttl, allkeys-random, volatile-random
maxmemory-policy allkeys-lru

# Number of keys sampled for approximated LRU/LFU evaluation.
# Higher = more accurate LRU (uses more CPU), Lower = faster (less accurate)
maxmemory-samples 5

# --- LFU Tuning parameters ---
# How fast the LFU counter decays. 
# Decrements the counter by 1 for every 1 minute of idle time.
lfu-decay-time 1

# Controls counter growth rate. 
# A factor of 10 means 1 million accesses are required to reach the max counter value of 255.
lfu-log-factor 10
```

### 4.2 Checking Eviction Stats
```redis
# 1. Inspect memory state
> INFO memory
# Memory
used_memory:1073741824       # Current memory usage (1 GB)
maxmemory:2147483648         # Memory limit (2 GB)
maxmemory_human:2.00G
maxmemory_policy:allkeys-lru
evicted_keys:14502           # Total keys evicted since startup

# 2. View the idle time of a key (for LRU policy)
> SET session:100 "active"
OK
# Wait 10 seconds...
> OBJECT IDLETIME session:100
(integer) 10                 # Returns idle time in seconds

# 3. View the LFU counter of a key (only when maxmemory-policy is set to LFU)
> OBJECT FREQ session:100
(integer) 18                 # Returns the logarithmic frequency counter (0-255)
```

### 4.3 Safe Expiry Jitter Implementation
To prevent the active expiry CPU spike (and downstream cache avalanche) at the top of the hour, add random jitter to your TTLs:

#### Java (Jedis Client)
```java
import redis.clients.jedis.Jedis;
import java.util.concurrent.ThreadLocalRandom;

public class RedisJitter {
    public static void setWithJitter(Jedis jedis, String key, String value, int baseTtlSeconds) {
        // Add random jitter of +/- 5% to the TTL
        int maxJitter = (int) (baseTtlSeconds * 0.05);
        int jitter = ThreadLocalRandom.current().nextInt(-maxJitter, maxJitter + 1);
        int finalTtl = baseTtlSeconds + jitter;
        
        jedis.setex(key, finalTtl, value);
    }
}
```

#### Java (Spring Data Redis / Lettuce)
```java
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class SpringRedisJitter {
    private final StringRedisTemplate redisTemplate;

    public SpringRedisJitter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void setWithJitter(String key, String value, long baseTtlSeconds) {
        // Add random jitter of +/- 5% to the TTL
        long maxJitter = (long) (baseTtlSeconds * 0.05);
        long jitter = ThreadLocalRandom.current().nextLong(-maxJitter, maxJitter + 1);
        long finalTtl = baseTtlSeconds + jitter;
        
        redisTemplate.opsForValue().set(key, value, finalTtl, TimeUnit.SECONDS);
    }
}
```


---

## 5. INTERVIEW ANGLES

### Q: What is the difference between Passive (Lazy) Expiry and Active (Periodic) Expiry?
**A:** 
* **Passive Expiry:** When a client queries a key (e.g., `GET`), Redis checks if the key has expired. If so, it deletes it and returns `NIL`. This is $O(1)$ and cheap, but keys that are never queried will leak RAM.
* **Active Expiry:** A background cleanup task that runs inside the event loop. Per DB it samples 20 keys (`ACTIVE_EXPIRE_CYCLE_LOOKUPS_PER_LOOP`) with a TTL and deletes any that are expired. If more than 25% of the sampled keys are expired, it repeats the process (loops again). To avoid blocking the event loop, the cycle is time-boxed to a small fraction of CPU per cycle (governed by `hz` / `active-expire-effort`), not a fixed millisecond cap.

### Q: Why did Redis implement an Approximated LRU instead of a Standard LRU?
**A:** A standard LRU requires maintaining a doubly linked list of all elements in memory, sorted by access order. 
1. **Memory Overhead:** A doubly linked list requires 24 bytes of pointers (`prev`, `next`, and structure alignment) per node. For 100 million keys, this wastes 2.4GB of RAM just on list pointers.
2. **CPU & Locking Overhead:** Every `GET` or read operation forces the system to remove the node from its current list position and insert it at the head. This requires pointer modification and causes memory fragmentation and cache line misses.
Redis's **approximated LRU** uses 0 extra bytes of list overhead by storing a 24-bit timestamp inside the existing `redisObject` header, choosing the oldest candidate from a random sample of keys.

### Q: How does LFU work inside the limited 24-bit field of a `redisObject`?
**A:** Redis splits the 24-bit field into two components:
1. **16-bit Last Decayed Time:** Stores the time the key's frequency counter was last updated (resolution in minutes, modulo $2^{16}$).
2. **8-bit Access Counter:** A logarithmic counter representing access frequency.
When a key is accessed:
1. Redis reads the `Last Decayed Time` and calculates how many minutes have passed.
2. It decays the counter by decreasing it relative to the elapsed idle time (controlled by `lfu-decay-time`).
3. It conditionally increments the counter based on a probability calculation ($1 / (\text{counter} \times \text{lfu-log-factor} + 1)$), meaning the counter grows slowly and maxes out at 255.

### Q: If a master node has expired keys, but the active expiry hasn't cleaned them up yet, what happens if a client queries that key on a read-only replica?
**A:** To guarantee data consistency across the cluster, replica nodes **never** make independent decisions to delete or expire keys. 
* In Redis 3.2+, if a client queries a replica for a key that has expired on the master (but the master hasn't deleted it yet), the replica uses its local clock to check expiration and returns `NIL` (hiding the expired key from the client).
* However, the replica **does not delete the key** from its memory. It waits for the master to run its active/passive expiry cycle and send a synchronous `DEL` command down the replication stream.

---

## 6. ONE-LINE RECALL CARDS

*   **Expiration (TTL)** cleans up keys whose lifespans have ended; **Eviction** reclaims space when memory hits `maxmemory`.
*   Redis stores key TTLs in a separate `expires` dictionary mapping key pointers to millisecond timestamps.
*   **Passive expiry** checks and deletes expired keys on-access; **Active expiry** samples 20 keys periodically to clean unread keys.
*   If $>25\%$ of sampled keys are expired, **active expiry** loops again; the cycle is time-boxed to a small fraction of CPU (governed by `hz` / `active-expire-effort`), not a fixed millisecond cap.
*   **Replicas never delete expired keys autonomously**; they wait for a synthetic `DEL` command from the master.
*   **Approximated LRU** uses a 24-bit timestamp in the `redisObject` and samples $N$ random keys to evict the oldest, saving list pointers.
*   **LFU** tracks access frequency by dividing the 24-bit field into a 16-bit decay time and an 8-bit logarithmic counter.
*   `noeviction` returns write errors (`OOM`) when memory is full, whereas other policies immediately prune keys.

---

**Next:** [05 — Caching Patterns](05-caching-patterns.md) (cache-aside/write-through/write-behind, stampede, penetration/avalanche, TTL jitter).
