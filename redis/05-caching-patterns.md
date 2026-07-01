# 05 — Caching Patterns, Failures & Mitigations

> **Why this is Topic 5:** Designing caching layers in front of databases (like Postgres) involves complex correctness and performance trade-offs. In a financial or broker system like Zerodha, a cache synchronization bug could show users incorrect account balances, while a cache outage (avalanche or stampede) could crash the primary database under market-open traffic. SDE2 interviewers will test you on cache consistency models, race conditions in write paths, and the exact algorithms used to prevent system failures under high concurrency.

---

## 1. WHAT

A cache sits between the application server and the database to accelerate reads. 

```
                               ┌───────────────────┐
                               │    Application    │
                               └──────┬─────┬──────┘
                  1. Read Cache       │     │  3. Read DB on Miss
               ┌──────────────────────┘     └──────────────────────┐
               ▼                                                   ▼
     ┌───────────────────┐                               ┌───────────────────┐
     │    Redis Cache    │                               │   Postgres DB     │
     └───────────────────┘                               └───────────────────┘
```

When implementing this architecture, you must choose:
1. **Caching Strategy:** How data is written to and read from the cache (e.g., Cache-Aside, Write-Through, Write-Behind).
2. **Failure Mitigations:** Guardrails to prevent systemic failures (e.g., Cache Stampede, Cache Penetration, Cache Avalanche) when keys expire or missing data is queried.

---

## 2. WHY (the trade-offs)

### 2.1 Cache-Aside vs. Write-Through vs. Write-Behind

| Caching Pattern | Read Path | Write Path | Example Use Case | Pros | Cons |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Cache-Aside (Lazy)** | Read cache. On miss, read DB, write cache. | Write DB directly. Delete cache key. | **Product Catalog, User Profile:** Only cache requested data. Sellers update DB, then invalidate cache. | Simple. Cache only holds active data. Safe from cache failures. | Cache miss penalty on first read. Data inconsistency window if cache delete fails. |
| **Write-Through** | Read cache. On miss, read DB, write cache. | Write cache. Cache synchronously writes to DB. | **User Wallet Balance:** Deposits must reflect immediately in both cache + DB for instant balance read consistency. | High read consistency. Fresh cache data. | High write latency (double writes). Bloated cache with unread data. |
| **Write-Behind (Write-Back)** | Read cache. On miss, read DB, write cache. | Write cache. Cache asynchronously batches writes to DB. | **Video Views, Post Likes:** 10,000 views/sec incremented instantly in cache; background workers batch update DB every 10s. | Extremely low write latency. Batched updates reduce DB write load. | Risk of data loss if cache crashes before flushing to DB. Eventual consistency. |

---

## 3. HOW (the internals)

### 3.1 Race Conditions in Cache-Aside: Update vs. Delete
In Cache-Aside, why do we **delete (evict)** the cache key on writes instead of **updating** it?
* **The Problem with Updates:** If two concurrent transactions attempt to update the same record, network delays can cause the updates to arrive at the cache and DB in different orders.

```
Time ──►
Transaction 1: Writes to DB (Value = A) ──────────────────────────► Updates Cache (Value = A)
Transaction 2:          Writes to DB (Value = B) ──► Updates Cache (Value = B)
                                                                      │
                                                                      ▼
                                                       [CACHE HAS VALUE A (Old!)]
                                                       [DB HAS VALUE B (New!)]
```
* **The Solution (Delete):** Deleting the key forces the next read request to fetch the source-of-truth value from the database and write it to the cache, resolving the race condition.

---

### 3.2 Cache Stampede (Thundering Herd / Cache Breakdown)
* **What:** A highly popular key (e.g., the Reliance stock price tick at market open) expires. Hundreds of concurrent application threads detect the cache miss at the exact same millisecond and query the database simultaneously to rebuild the cache, knocking the database down.

```
Hot Key Expires ──► Thread 1 (Miss) ──► Query DB ──┐
                ──► Thread 2 (Miss) ──► Query DB ──┼──► [DATABASE CRASHES]
                ──► Thread 3 (Miss) ──► Query DB ──┘
```

#### Mitigations:
1. **Mutex Locking (Single Flight):** The first thread that detects the miss acquires a lock (e.g., a local mutex or Redis distributed lock). Only this thread queries the DB and rebuilds the cache. Other threads wait, retry, or return stale data until the lock is released.
2. **Probabilistic Early Expiration (XFetch Algorithm):**
   * Instead of waiting for the key to expire, threads reading the key near its expiration time can trigger an early background refresh.
   * As the key gets closer to its TTL, the probability of a read request triggering a background rewrite increases. The formula is:
     $$-\beta \times \delta \times \ln(\text{rand()}) > \text{TTL}$$
     *(Where $\beta > 0$ is a aggressiveness factor, $\delta$ is the delta computation time, and $\text{rand()}$ is a random number between 0 and 1).*
   * This ensures a hot key is refreshed by a single background thread *before* it officially dies.

---

### 3.3 Cache Penetration
* **What:** A malicious attacker (or buggy client) sends requests for keys that do **not** exist in either the cache or the database (e.g., querying for user ID `-9999`). Every request bypasses the cache and hits the database, exhausting the connection pool.

#### Mitigations:
1. **Caching Null Values:** If the DB returns "not found", write an empty/null value to the cache with a short TTL (e.g., 5 minutes). Subsequent requests hit the cache and return `NIL` immediately.
2. **Bloom Filters:**
   * A Bloom Filter is a space-efficient, probabilistic data structure used to test set membership. It tells us either:
     * *"The key is **definitely not** in the database"* (0% false negatives).
     * *"The key is **probably** in the database"* (small percentage of false positives).
   * Redis has native Bloom filter support via the **RedisBloom** module.

```
Client Request ──► [Bloom Filter] ──(No: Definitely doesn't exist) ──► Return NIL
                         │
                         ▼ (Yes: Probably exists)
                   [Check Cache] ──(Miss)──► [Query Database]
```

* **Bloom Filter Internals:**
  It allocates a bit array of size $M$, initialized to 0. It uses $K$ independent hash functions.
  * **Writing a key:** Hash the key with all $K$ functions and set the corresponding bits in the array to `1`.
  * **Reading a key:** Hash the key. If *any* of the $K$ bits are `0`, the key was **never** written. If all are `1`, the key might be present.
  * **Caveat:** You cannot delete items from a standard Bloom filter because multiple keys can share the same bits. To support deletes, you must use a **Counting Bloom Filter** (which uses counters instead of bits, consuming more memory).

---

### 3.4 Cache Avalanche
* **What:** A large chunk of cached data expires at the same time (e.g., due to a batch import where all keys were written with the exact same 24-hour TTL), or the Redis node itself crashes, causing all application traffic to hit the database at once.

#### Mitigations:
1. **TTL Jitter (Random Noise):** When setting keys, add random seconds/minutes of noise to the TTL (e.g., `base_ttl + random(0, 300)`). This spreads expiration events evenly over time.
2. **Circuit Breakers:** Implement circuit breakers (e.g., using Resilience4j or Sentinel) in the application layer. If database query latencies exceed a threshold, fail fast and return fallback data.
3. **High Availability Configuration:** Deploy Redis in Sentinel or Cluster mode with replica nodes to prevent single-point-of-failure hardware crashes.

---

## 4. CODE / EXAMPLES

### 4.1 Implementing Cache-Aside with Safe Deletes
This shows how to write updates to DB first, and then delete the cache key.

#### Java (Spring Data Redis + Spring JDBC)
```java
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class CacheAsidePattern {
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public CacheAsidePattern(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Double getUserBalance(int userId) {
        String cacheKey = "user:balance:" + userId;
        
        // 1. Read from cache
        String cachedVal = redisTemplate.opsForValue().get(cacheKey);
        if (cachedVal != null) {
            if ("NULL".equals(cachedVal)) return 0.0;
            return Double.parseDouble(cachedVal);
        }
        
        // 2. Cache Miss -> Query Database
        Double balance;
        try {
            balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE user_id = ?",
                Double.class,
                userId
            );
        } catch (Exception e) {
            // Mitigation: Cache null values for 5 mins to prevent penetration
            redisTemplate.opsForValue().set(cacheKey, "NULL", 300, TimeUnit.SECONDS);
            return 0.0;
        }
        
        if (balance == null) {
            redisTemplate.opsForValue().set(cacheKey, "NULL", 300, TimeUnit.SECONDS);
            return 0.0;
        }
        
        // 3. Write back to cache (with 1-hour TTL + 5-min random Jitter to prevent avalanche)
        long jitter = ThreadLocalRandom.current().nextLong(0, 301);
        long ttl = 3600 + jitter;
        redisTemplate.opsForValue().set(cacheKey, String.valueOf(balance), ttl, TimeUnit.SECONDS);
        
        return balance;
    }

    public void updateUserBalance(int userId, double newBalance) {
        String cacheKey = "user:balance:" + userId;
        
        // 1. Update Database First (Source of Truth)
        jdbcTemplate.update(
            "UPDATE accounts SET balance = ? WHERE user_id = ?",
            newBalance,
            userId
        );
        
        // 2. Delete Cache Key (Forces next read to fetch fresh DB value)
        redisTemplate.delete(cacheKey);
    }
}
```

---

### 4.2 Mutex Locking (Single Flight) to Prevent Cache Stampede
Using local locks to ensure only one thread queries the DB:

#### Java (ReentrantLock + ConcurrentHashMap)
```java
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class CacheStampedeMitigation {
    private final StringRedisTemplate redisTemplate;
    // Map to hold lock objects per key
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public CacheStampedeMitigation(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String getHotKeySecure(String key) {
        // 1. Read from cache
        String val = redisTemplate.opsForValue().get(key);
        if (val != null) {
            return val;
        }
        
        // 2. Get or create a lock for this specific key
        ReentrantLock keyLock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        
        // 3. Acquire lock (only one thread enters, others wait)
        boolean acquired = false;
        try {
            acquired = keyLock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) {
                // Fallback: return stale data or raise exception
                return "Stale fallback value";
            }
            
            // Double-check cache (was it updated while waiting for lock?)
            val = redisTemplate.opsForValue().get(key);
            if (val != null) {
                return val;
            }
            
            // Query DB (simulate slow query)
            String dbVal = querySlowDatabase(key);
            
            // Rebuild Cache
            redisTemplate.opsForValue().set(key, dbVal, 60, TimeUnit.SECONDS);
            return dbVal;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Fallback due to interruption";
        } finally {
            if (acquired) {
                keyLock.unlock();
            }
            // Cleanup lock map to prevent memory leak
            keyLocks.remove(key);
        }
    }

    private String querySlowDatabase(String key) {
        try {
            Thread.sleep(100); // Simulate database query delay
        } catch (InterruptedException ignored) {}
        return "database_value";
    }
}
```


---

## 5. INTERVIEW ANGLES

### Q: Why is deleting the cache key safer than updating it when writing to a database?
**A:** Updating the cache introduces race conditions due to concurrent writes. If Transaction 1 and Transaction 2 update the database in order (1 then 2), but their network packets to Redis arrive out of order (2 then 1), the cache will end up holding Transaction 1's old data, while the database holds Transaction 2's new data. Deleting the key on write avoids this. The next read operation is forced to fetch the latest committed data from the database and rebuild the cache correctly.

### Q: What is a Cache Stampede, and what are the trade-offs of using Mutex Locks vs. Probabilistic Early Expiration?
**A:** A Cache Stampede occurs when a hot key expires, causing multiple concurrent threads to miss the cache and hit the database simultaneously.
* **Mutex Locks (Single Flight):** Simple to implement. It blocks concurrent client threads while one thread updates the cache. 
  * *Trade-off:* High read latency spikes for the threads that are blocked waiting for the database query to complete.
* **Probabilistic Early Expiration (XFetch):** Requests reading a hot key near its expiration time have a probability of spawning a background thread to refresh it.
  * *Trade-off:* Zero read latency spikes (clients always get stale cache values instantly while the background thread runs). However, it requires active math configurations and slightly more memory/CPU to run the probability check on reads.

### Q: How does a Bloom Filter prevent Cache Penetration, and why is deleting items from it difficult?
**A:** A Bloom filter is placed in front of the cache. It hashes incoming keys and checks a bit array. If the bits are `0`, the key definitely does not exist in the database, allowing us to fail fast without querying the database or cache.
* **Why delete is difficult:** A Bloom filter sets bits to `1`. Since multiple keys can map to the same bit (hash collisions), clearing a bit to `0` to delete Key A might accidentally clear the bit for Key B as well, causing false negatives (incorrectly claiming Key B doesn't exist).
* **Solution:** To delete keys, you must use a **Counting Bloom Filter**, which stores an $n$-bit counter instead of a single bit at each array index. Deletes decrement the counter. This increases the memory footprint by $3-4\times$.

### Q: Explain the trade-offs of Write-Behind (Write-Back) caching. In what scenarios would you use it at a fintech broker?
**A:** Write-Behind writes changes immediately to the cache, returning success to the client, and updates the database asynchronously in batches.
* **Pros:** Low write latency (millisecond range) and database protection (collapsing multiple updates to a single row into one database write).
* **Cons:** High risk of data loss if the cache crash happens before the data is committed to the database.
* **Fintech Usage:** You **cannot** use Write-Behind for ledger balances, orders, or money transactions where durability is non-negotiable. However, it is ideal for **non-critical, high-frequency metrics** like live market feed counters, tracking active session heartbeats, or monitoring user API rate-limit buckets.

---

## 6. ONE-LINE RECALL CARDS

*   **Cache-Aside** reads from cache, falls back to DB on misses, and deletes cache keys on DB writes.
*   Deleting keys on write prevents race conditions caused by out-of-order concurrent cache updates.
*   **Write-Behind** writes to cache and updates the DB asynchronously, trading durability for high throughput.
*   **Cache Stampede** is resolved using **Single Flight locks** (blocking threads) or **XFetch** (probabilistic background refresh).
*   **Cache Penetration** (querying missing keys) is mitigated by caching null values or using **Bloom Filters**.
*   **Bloom Filters** use bit arrays and multiple hash functions, guaranteeing zero false negatives.
*   Standard Bloom filters do not support deletion; you must use **Counting Bloom Filters** which consume more RAM.
*   **Cache Avalanche** (synchronized expiration) is prevented by adding **TTL Jitter** to randomize expiration events.

---

**Next:** [06 — Atomicity & Transactions](06-atomicity-transactions-lua.md) (MULTI/EXEC/WATCH, Lua scripting, atomic balance checks).
