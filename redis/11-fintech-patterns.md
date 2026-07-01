# 11 — Redis Fintech Patterns: Rate Limiting, Idempotency, and Leaderboards

> **Why this is Topic 11:** In a high-throughput financial system like Zerodha, correctness and latency are not negotiable. When processing hundreds of thousands of order placement requests per second, microsecond delays can cost crores, and duplicate orders can wipe out client capital. We rely on Redis for the hot path of critical fintech workflows: enforcing rate limits (to protect exchange-facing gateways), verifying transaction idempotency (to prevent double-charges on payment gateway webhooks), tracking leaderboard standings in real time (for stock-picking contests and referral programs), and detecting duplicate orders within millisecond windows. SDE2 interviewers will test your depth on the internals of these patterns, why naive implementations fail, how to write thread-safe Lua rate limiters, and how to structure Redis data structures to maintain transactional correctness.

---

## 1. WHAT

Fintech architectures demand highly performant, lock-free, and atomic building blocks. Standard relational databases (e.g., PostgreSQL) are the system of record but cannot handle hot-path operational logic at sub-millisecond latencies under high concurrency. We offload these patterns to Redis:

1. **Rate Limiting:** Enforcing throughput thresholds on APIs (e.g., maximum 50 order placements per second per API key) to protect exchange lines and downstream microservices.
2. **Transaction Idempotency & Duplicate Detection:** Protecting financial mutations (e.g., UPI pay-in processing, fund transfers) from being executed multiple times when clients retry failed network connections.
3. **Real-time Leaderboards:** Maintaining live, sorted lists of entities by score (e.g., clients ranked by daily P&L in a trading contest, or referral program winners) with $O(\log N)$ insertion and ranking times.

---

## 2. WHY (the trade-offs)

### 2.1 Rate Limiting: Sliding-Window Log vs. Token Bucket
Enforcing API limits requires keeping state. The choice of algorithm directly dictates memory usage and latency.

| Dimension | Sliding-Window Log (ZSET) | Token Bucket (Lua + Hash) |
| :--- | :--- | :--- |
| **Accuracy** | 100% accurate; tracks precise millisecond timestamps of every request. | Approximated; replenishes tokens over time mathematically. |
| **Memory Complexity** | $O(\text{Requests})$: Every request is a member in a ZSET. High memory footprint under heavy traffic. | $O(1)$ per user: Stores only a static Hash with two fields (`tokens`, `last_updated`). |
| **Burst Handling** | Hard limit; strictly enforces the maximum count within the sliding duration. | Built-in burst support up to the configured bucket capacity. |
| **Redis Network I/O** | Multiple commands (`ZREMRANGEBYSCORE`, `ZCARD`, `ZADD`) that must be wrapped in a **Lua script** to make the check-and-add atomic (a `MULTI`/`EXEC` is not sufficient — see §4.2). | Single atomic execution via a Lua script. |

> **Fintech Decision:** For low-limit, high-value operations (e.g., password reset, password validation attempts), use **Sliding-Window Log**. For high-throughput endpoints (e.g., live market-order placement), use **Token Bucket** to keep Redis memory footprint constant and flat.

---

### 2.2 Idempotency: Redis Filter vs. Database Constraints
To prevent double-charging or duplicate trade orders:
* **The Redis Filter Pattern:** Set an idempotency key in Redis using `SET key token NX PX <ttl>`. If Redis returns `OK`, proceed to run the transaction.
  * *Trade-off:* Redis is extremely fast (~1ms). However, Redis is **not 100% durable** by default (async replication and periodic `fsync` can lose writes). If the primary node crashes after setting the key but before replicating it to the replica, a retried request will bypass the Redis filter and trigger a duplicate transaction.
* **The DB Unique Constraint:** Rely on database-level unique indexes (e.g., `UNIQUE(idempotency_key)` in Postgres).
  * *Trade-off:* 100% durable and correct. However, checking this requires acquiring a database row lock, making it slow and prone to connection pool exhaustion under flash crowd events (e.g., market open at 9:15 AM).
* **The Hybrid Solution:** Use Redis as the first-line defense filter to absorb high-concurrency traffic and reject duplicate submissions early. Back it up with a hard `UNIQUE` constraint in Postgres to guarantee absolute correctness if Redis fails or fails over.

---

### 2.3 Leaderboards: Relational `ORDER BY` vs. Redis ZSET
* **Relational Database:** 
  ```sql
  SELECT user_id, score, rank() OVER (ORDER BY score DESC) FROM leaderboard;
  ```
  * *The Problem:* This query is $O(N \log N)$ or requires indexing that degrades write performance. At 1,000,000 active users, running this query frequently blocks Postgres read replicas, consumes immense CPU, and serves stale ranks.
* **Redis ZSET (Sorted Set):**
  * *The Internals:* Maintains a dual structure (a Hash Table mapping members to scores, and a Skip List ordering members).
  * *Performance:* Updating a score is $O(\log N)$. Querying the top 100 users is $O(\log N + 100)$. Ranks are updated instantly in memory with zero database scans.

---

## 3. HOW (the internals)

### 3.1 Sliding-Window Rate Limiter Internals
The sliding-window rate limiter stores every request's timestamp in a Sorted Set (ZSET).

```
              ┌─────────────────── SLIDING WINDOW (10 seconds) ───────────────────┐
              │                                                                   │
              ▼                                                                   ▼
┌─────────────┬─────────────────┬─────────────────┬─────────────────┬─────────────┬─────────────┐
│ Timestamp 1 │   Timestamp 2   │   Timestamp 3   │   Timestamp 4   │ Timestamp 5 │ Current Req │
│ (10:00:00)  │   (10:00:02)    │   (10:00:05)    │   (10:00:07)    │ (10:00:09)  │ (10:00:11)  │
└─────────────┴─────────────────┴─────────────────┴─────────────────┴─────────────┴─────────────┘
  (Deleted)
```

1. **Purge:** Redis runs `ZREMRANGEBYSCORE key -inf (now - window_size)`. This deletes all timestamps older than the current sliding window.
2. **Count:** Count the remaining requests in the window via `ZCARD key`.
3. **Evaluate & Add:** If `count < limit`, run `ZADD key now unique_uuid` and `(P)EXPIRE key window_size`; the request is allowed. Otherwise reject without adding.
4. **Atomicity:** Steps 1–3 must be **one atomic check-and-add** — wrap them in a **Lua script** (see §4.2). A `MULTI`/`EXEC` transaction is *not* sufficient here, because the natural pattern is to `ZADD` unconditionally inside `EXEC` and then `ZREM` afterward if the count was over the limit; between the `ZADD` and the `ZREM`, concurrent requests observe an inflated count and can slip past (or be wrongly rejected). Lua does the count *and* the conditional `ZADD` in a single uninterruptible step.
5. **Collision Prevention:** Because ZSET stores unique members, we cannot use the timestamp itself as the member (two requests in the same millisecond would collapse into one). We store a composite string `<timestamp>:<uuid>` or simply a random `<uuid>` as the member, with the numeric millisecond timestamp as the score.

---

### 3.2 Token-Bucket Rate Limiter in Lua
To avoid storing millions of timestamps, the Token Bucket stores the state of a bucket in a single Hash:
* `tokens`: Current available token count (float).
* `last_updated`: Epoch millisecond timestamp of the last evaluation.

#### Mathematical Replenishment
Instead of running a background cron thread to replenish tokens (which would consume CPU and require lock synchronization), the replenishment is calculated **on-demand** upon every incoming request:

$$\text{Replenished Tokens} = \min\left(\text{Max Capacity}, \text{Current Tokens} + (\text{Current Time} - \text{Last Updated}) \times \text{Fill Rate}\right)$$

```
                                  HMGET rate:limit:<user>
                                             │
                                             ▼
                               Calculate Replenishment
                        tokens = min(cap, tokens + elapsed * rate)
                                             │
                                   ┌─────────┴─────────┐
                                   │                   │
                            tokens >= requested?  tokens < requested?
                                   │                   │
                                   ▼                   ▼
                               [Allow]              [Reject]
                     tokens = tokens - requested       │
                     HMSET tokens, last_updated        │
                                   │                   │
                                   └─────────┬─────────┘
                                             │
                                             ▼
                                          Return
```

Because this calculation requires reading values, executing math, and writing back state, it is prone to **Read-Modify-Write race conditions** in multi-threaded application servers.
* **The Solution:** We run this logic inside a **Lua script**. Redis guarantees that the Lua script runs atomically and block-free relative to other operations on the main event thread, preventing concurrent clients from over-consuming tokens.

---

### 3.3 Transaction Idempotency Keys & Duplicate Detection
When a client hits a gateway to process a payout, we must protect the system against duplicate requests.

```
Client             API Gateway (Jedis Client)               Redis               PostgreSQL
  │                             │                             │                     │
  │─── 1. Payout (Idemp Key) ──►│                             │                     │
  │                             │─── 2. SETNX idemp:<key> ───►│                     │
  │                             │     "PENDING" PX 60000      │                     │
  │                             │◄─── 3. Returns "OK" ────────│                     │
  │                             │                                                   │
  │                             │─── 4. Begin DB Transaction ──────────────────────►│
  │                             │    - Insert ledger entry                          │
  │                             │    - Update customer balances                     │
  │                             │◄─── 5. Commit Transaction ────────────────────────│
  │                             │                                                   │
  │                             │─── 6. SET idemp:<key> ─────────────────────────────►│
  │                             │     "SUCCESS:<payload>" XX PX 86400000            │
  │◄── 7. Payout Success ───────│                                                   │
```

#### Step-by-Step State Machine
1. **Acquire:** Use `SET idempotency:key:<key> "PENDING" NX PX 60000` (e.g., 60-second execution lock).
2. **Conflict Check:** 
   * If Redis returns `nil` (key already exists), query the key:
     * If the value is `"PENDING"`, return `409 Conflict` (request is currently in flight).
     * If the value is `"SUCCESS:<payload>"`, parse the payload and return the cached response directly to the client without calling Postgres.
3. **Execution:** If `SETNX` returns `OK`, proceed to execute the database transaction.
4. **Finalize:** 
   * If the transaction succeeds, save the result: `SET idempotency:key:<key> "SUCCESS:<serialized_response>" XX PX 86400000` (24-hour retention).
   * If the transaction fails and is retryable, delete the key: `DEL idempotency:key:<key>`, letting the next attempt execute.

---

### 3.4 Tie-Breaking in Live Leaderboards (ZSET)
A basic Redis Sorted Set ranks users strictly by their score. If two users have the same score, Redis sorts them lexicographically by their member name.
* **The Fintech Problem:** In a live trading contest, if User A and User B both achieve a 25.4% ROI, the user who **reached the score first** must be ranked higher.
* **The Solution: Composite Score Encoding.**
  We encode both the actual score and the timestamp into a single double-precision floating-point number.

#### IEEE 754 Double Precision Limits
Redis ZSET scores are double-precision floats (64-bit), which possess a **53-bit mantissa**. This provides up to 15–17 decimal digits of exact integer precision (up to $9,007,199,254,740,991$).

To construct a composite score where higher score wins, but earlier timestamp wins:

$$\text{Composite Score} = \text{Base Score} + \left(1.0 - \frac{\text{Timestamp Epoch Seconds}}{10,000,000,000}\right)$$

* **Example:**
  * Base Score: `525.00` points.
  * Max expected Unix timestamp epoch seconds is $10^{10}$ (far in the future, year 2286).
  * Timestamp 1 (Earlier: `1700000000`): $1.0 - 0.1700000000 = 0.8300000000$.
    * Composite Score = $525.8300000000$.
  * Timestamp 2 (Later: `1700003600`): $1.0 - 0.1700003600 = 0.8299996400$.
    * Composite Score = $525.8299996400$.
  * Since $525.8300000000 > 525.8299996400$, the earlier participant is placed higher in the ZSET.

---

## 4. CODE / EXAMPLES

### 4.1 Token-Bucket Rate Limiter with Lua and Jedis
This Java implementation utilizes a Lua script executed atomically within Redis to enforce a memory-efficient token-bucket rate limit.

```java
package com.zerodha.fintech.ratelimit;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.List;

public class TokenBucketRateLimiter {
    private final JedisPool jedisPool;
    
    // Atomic Lua script to replenish tokens and consume them in a single step
    private static final String LUA_RATE_LIMITER = 
        "local key = KEYS[1] " +
        "local capacity = tonumber(ARGV[1]) " +
        "local fill_rate = tonumber(ARGV[2]) -- tokens per millisecond\n" +
        "local now = tonumber(ARGV[3])       -- current time in epoch ms\n" +
        "local requested = tonumber(ARGV[4] or 1) " +
        
        "local data = redis.call('HMGET', key, 'tokens', 'last_updated') " +
        "local tokens = tonumber(data[1]) " +
        "local last_updated = tonumber(data[2]) " +
        
        "if not tokens then " +
        "    tokens = capacity " +
        "    last_updated = now " +
        "else " +
        "    local elapsed = now - last_updated " +
        "    if elapsed > 0 then " +
        "        local generated = elapsed * fill_rate " +
        "        tokens = math.min(capacity, tokens + generated) " +
        "        last_updated = now " +
        "    end " +
        "end " +
        
        "if tokens >= requested then " +
        "    tokens = tokens - requested " +
        "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated) " +
        "    local ttl = math.ceil(capacity / fill_rate / 1000) * 2 " +
        "    redis.call('EXPIRE', key, ttl) " +
        "    return 1 -- Allowed\n" +
        "else " +
        "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated) " +
        "    return 0 -- Rejected\n" +
        "end";

    private final String scriptSha;

    public TokenBucketRateLimiter(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        try (Jedis jedis = jedisPool.getResource()) {
            // Pre-load the script to optimize subsequent evaluations via SHA
            this.scriptSha = jedis.scriptLoad(LUA_RATE_LIMITER);
        }
    }

    /**
     * Evaluates if a request from a user is allowed.
     * @param userId The unique identifier of the user (e.g., client ID)
     * @param capacity The maximum capacity of the token bucket
     * @param tokensPerSecond The rate at which tokens are added to the bucket per second
     * @return true if the request is permitted, false if rate-limited
     */
    public boolean isAllowed(String userId, int capacity, double tokensPerSecond) {
        String key = "rate:limit:token:" + userId;
        double fillRatePerMs = tokensPerSecond / 1000.0;
        long currentTimeMs = System.currentTimeMillis();

        try (Jedis jedis = jedisPool.getResource()) {
            // Attempt to execute the script using the pre-loaded SHA hash
            Object result = jedis.evalsha(
                scriptSha, 
                Collections.singletonList(key), 
                List.of(
                    String.valueOf(capacity), 
                    String.valueOf(fillRatePerMs), 
                    String.valueOf(currentTimeMs), 
                    "1"
                )
            );
            return Long.valueOf(1).equals(result);
        }
    }
}
```

---

### 4.2 Sliding-Window Rate Limiter with Lua and Jedis
This Java implementation provides a sliding-window log rate limiter using Sorted Sets (ZSET) for absolute accuracy.

> **Why not `MULTI`/`EXEC` here?** A tempting (and common) implementation wraps `ZREMRANGEBYSCORE` → `ZCARD` → `ZADD` → `EXPIRE` in a `MULTI`/`EXEC` transaction, then reads the count back and conditionally `ZREM`s the request it just added if the limit was exceeded. This is **non-atomic with respect to the limit decision**: the `ZADD` always runs inside `EXEC`, and the "should I have added it?" check happens *after* the transaction returns. Two concurrent requests can both `ZADD` before either issues its `ZREM`, so each transiently sees an inflated count — letting requests slip past the limit (or spuriously rejecting valid ones). The correct fix is to make the **check-and-add a single atomic decision inside a Lua script** (the same discipline as the token-bucket script in §4.1): purge, count, and only `ZADD` if the count is under the limit — all on the single thread with no interleaving.

```java
package com.zerodha.fintech.ratelimit;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SlidingWindowRateLimiter {
    private final JedisPool jedisPool;

    // Atomic: purge expired entries, count, and add ONLY if under the limit.
    // The ZADD never happens unless the request is actually admitted, so no
    // concurrent request ever observes an inflated count.
    private static final String LUA_SLIDING_WINDOW =
        "local key = KEYS[1] " +
        "local now = tonumber(ARGV[1])             -- current time in epoch ms\n" +
        "local window = tonumber(ARGV[2])          -- window size in ms\n" +
        "local limit = tonumber(ARGV[3]) " +
        "local member = ARGV[4]                     -- unique member (ts:uuid)\n" +
        "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " +
        "local count = redis.call('ZCARD', key) " +
        "if count < limit then " +
        "    redis.call('ZADD', key, now, member) " +
        "    redis.call('PEXPIRE', key, window) " +
        "    return 1 -- Allowed\n" +
        "else " +
        "    return 0 -- Rejected\n" +
        "end";

    private final String scriptSha;

    public SlidingWindowRateLimiter(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        try (Jedis jedis = jedisPool.getResource()) {
            this.scriptSha = jedis.scriptLoad(LUA_SLIDING_WINDOW);
        }
    }

    /**
     * Checks rate limits using a sliding window log.
     * @param userId The unique identifier of the user
     * @param maxRequests The maximum number of requests allowed in the window
     * @param windowSizeSeconds The size of the sliding window in seconds
     * @return true if the request is allowed, false if rate-limited
     */
    public boolean isAllowed(String userId, int maxRequests, int windowSizeSeconds) {
        String key = "rate:limit:slide:" + userId;
        long nowMs = System.currentTimeMillis();
        long windowMs = windowSizeSeconds * 1000L;
        // Unique member so two requests in the same millisecond don't collapse into one
        String member = nowMs + ":" + UUID.randomUUID();

        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.evalsha(
                scriptSha,
                Collections.singletonList(key),
                List.of(
                    String.valueOf(nowMs),
                    String.valueOf(windowMs),
                    String.valueOf(maxRequests),
                    member
                )
            );
            return Long.valueOf(1).equals(result);
        }
    }
}
```

---

### 4.3 Live Leaderboard with "First-to-Score" Tie-Breaker
This Java implementation tracks real-time scores and enforces a deterministic order for tie-breaking.

```java
package com.zerodha.fintech.leaderboard;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.Tuple;

import java.util.List;

public class FintechLeaderboard {
    private final JedisPool jedisPool;
    private final String leaderboardKey;
    
    // Constant scaling factor to represent the epoch time as a fractional component
    // 10,000,000,000.0 is used to scale down 10-digit Unix timestamps into the range of 0.0 to 1.0
    private static final double TIMESTAMP_SCALE = 10_000_000_000.0;

    public FintechLeaderboard(JedisPool jedisPool, String leaderboardKey) {
        this.jedisPool = jedisPool;
        this.leaderboardKey = leaderboardKey;
    }

    /**
     * Submits a score. Ties are broken by whoever achieved the score first.
     * @param userId The identifier of the participant
     * @param score The base score (must be integer-aligned to avoid losing precision)
     */
    public void submitScore(String userId, double score) {
        long epochSecond = System.currentTimeMillis() / 1000L;
        
        // Calculate fractional component. Earliest timestamp gets the largest fractional score.
        double timeFraction = 1.0 - (epochSecond / TIMESTAMP_SCALE);
        double compositeScore = score + timeFraction;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(leaderboardKey, compositeScore, userId);
        }
    }

    /**
     * Gets the rank of the user (0-indexed, where rank 0 is the highest score).
     * @param userId The user to fetch rank for
     * @return 0-indexed rank, or null if not found
     */
    public Long getUserRank(String userId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zrevrank(leaderboardKey, userId);
        }
    }

    /**
     * Fetches the top N users with scores and ranks.
     * @param limit Number of entries to retrieve
     * @return List of Tuples containing user IDs and composite scores
     */
    public List<Tuple> getTopTraders(int limit) {
        try (Jedis jedis = jedisPool.getResource()) {
            // Retrieve members ordered from high to low composite scores
            return jedis.zrevrangeWithScores(leaderboardKey, 0, limit - 1);
        }
    }

    /**
     * Extracts the true base score from the composite score.
     * @param compositeScore The value returned by Redis ZSET
     * @return The original integer-aligned base score
     */
    public static double extractBaseScore(double compositeScore) {
        return Math.floor(compositeScore);
    }
}
```

---

## 5. INTERVIEW ANGLES

### Q: Why is a naive ZSET sliding window rate limiter bad for memory at scale? How do you optimize it?
**A:** A ZSET sliding window rate limiter stores every request's timestamp and a UUID as members. 
* **The Cost:** If you enforce a rate limit of 5,000 requests per minute for 1,000,000 active users, and your API is run at peak capacity, your ZSETs will store up to 5 billion items in memory. Due to Redis memory overhead (dict entries, `zskiplistNode` allocations, and pointer padding), each member costs ~64 to 128 bytes, aggregating to **320 GB to 640 GB of RAM** just for rate-limiting metadata.
* **The Optimization:** Switch to the **Token Bucket** algorithm executed in a Lua script. Token Bucket holds state in a single Redis Hash (`tokens` and `last_updated`), requiring only ~100 bytes per client *regardless* of the request throughput. This reduces memory consumption to **100 MB** for 1,000,000 users, reducing memory requirements by $99.98\%$.

---

### Q: If Redis crashes and failover occurs during an idempotency key set, what are the failure modes, and how do you guarantee money correctness?
**A:** Because Redis replication is asynchronous, a write executed on the primary node (e.g., setting an idempotency key) is not guaranteed to have landed on the replica when a failover occurs.
* **Failure Scenario:**
  1. Client sends payout request. API Gateway acquires the key: `SET idemp:pay:100 PENDING NX`.
  2. Primary Redis crashes before this write is replicated to the replica.
  3. Replica is promoted to Primary.
  4. The network client retries due to a timeout. The Gateway checks the new Primary, finds no key, sets it, and processes the payout a **second time**, leading to a double-spend.
* **Guarantees:**
  1. **Database Constraint:** The primary system of record (PostgreSQL) must enforce a hard database-level `UNIQUE` index constraint on the transaction hash or idempotency key. Any duplicate write will throw a database violation, causing a rollback.
  2. **Wait for Replication:** Use the Redis `WAIT` command (`WAIT 1 100`) after setting the key to block the gateway until the write is acknowledged by at least one replica, minimizing the loss window to near-zero.
  3. **Read-Before-Write DB validation:** Do not trust Redis as a single source of truth for idempotency; verify the transaction state in the relational ledger table during the DB transaction block.

---

### Q: How do you implement a tie-breaker in a Redis-based live leaderboard where users with the same score must be ranked by who reached it first?
**A:** You use **composite score encoding**. Since Redis ZSET scores are double-precision floats, we can add a decimal fraction representing time.
* **The Formula:**
  $$\text{Composite Score} = \text{Base Score} + \left(1.0 - \frac{\text{Timestamp Epoch Seconds}}{10,000,000,000}\right)$$
* **Why it works:** The Unix timestamp is a monotonically increasing value. Dividing it by $10^{10}$ scales it to a fraction between $0$ and $1$. Subtracting it from $1.0$ makes it monotonically *decreasing*. Thus, the earlier the score was submitted (smaller timestamp), the larger the added fraction, giving the earlier user a higher ranking.
* **Precision Limit:** Double-precision floats have 53 bits of mantissa, which limits safe integer precision to $9 \times 10^{15}$. If base scores are integers up to $10^{9}$, we have $10^6$ units of space left for the fractional timestamp. In this case, we must use a relative timestamp (e.g., seconds elapsed since the start of the tournament, rather than since the 1970 Unix epoch) to avoid precision truncation.

---

### Q: Under high concurrency, what happens if two identical requests check a transaction idempotency key at the exact same microsecond?
**A:** Redis command execution is strictly single-threaded. Even if two client requests hit the Redis network socket at the exact same microsecond, the OS kernel multiplexing layer and the Redis event loop serializes them. 
One request will always be processed first.
* If you use `SET key value NX PX`, the first request will succeed and return `OK`. The second request will instantly fail to set the key and return `nil`.
* If you use a naive `GET` followed by a `SET`, the two requests can execute interleavingly: Both do `GET` -> both find the key missing -> both do `SET` and proceed to execute the transaction, creating a race condition.
* **Rule:** Always use atomic actions (`SET NX PX` or Lua scripts) to guarantee exclusion under microsecond concurrency.

---

### Q: When executing a Lua rate limiter script, does it block all other commands on Redis? How do you prevent it from causing latency spikes?
**A:** Yes. Because Redis runs commands on a single main thread, any execution—including Lua scripts—is fully blocking.
To prevent latency spikes and watchdog timeouts:
1. **Minimize Logic:** Keep the Lua script minimal. Avoid complex loops, regex matching, or $O(N)$ scanning. Only execute simple arithmetic and hash mutations.
2. **Cluster Compatibility:** In a sharded Redis Cluster, a Lua script must only access keys that reside on the **same sharding slot**. If you try to query keys in different slots, Redis will reject the script with a `CROSSSLOT Keys in request don't hash to the same slot` error. Ensure all keys in the script map to the same node by using **Hash Tags** (e.g., `{user:123}:tokens` and `{user:123}:last_updated`).

---

## 6. ONE-LINE RECALL CARDS

* **Sliding-window log** uses ZSET with timestamps as scores but consumes memory proportional to the request count.
* **Token-bucket in Lua** stores state in a Redis Hash, keeping the memory footprint flat at $O(1)$ per client.
* **Atomic `SET NX PX`** acts as a fast distributed idempotency gate, but database-level constraints must back it up.
* **Redis ZSET** uses a Skip List and Hash Table to maintain sorted order and ranks in $O(\log N)$ time.
* **Composite score encoding** (`base_score - timestamp`) implements a "first-to-score" tie-breaker within ZSET float limits.
* **Lua scripts** run atomically, preventing read-modify-write race conditions between rate limit checks and updates.
* **Dynamic TTLs** must be set via `EXPIRE` inside rate limiters to prevent stale keys from leaking memory.
* **Idempotency keys** must store the transaction status and result payload, avoiding reprocessing of completed payments.

---

**Next:** [12 — Memory Model & Ops](12-memory-ops.md) (fragmentation, jemalloc, latency monitoring, slowlog).
