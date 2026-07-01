# 06 — Redis Atomicity: Transactions & Lua Scripting

> **Why this is Topic 6:** In a fintech environment like Zerodha, operations must be correct and atomic. For example, when a user places an order, the system must check if the user has sufficient wallet balance, decrement the balance, and create the order record. If two concurrent threads try to decrement the balance, we must prevent double-spending without using heavy database row locks. This chapter explores how Redis achieves atomicity on its single execution thread, why standard transactions lack rollback capability, and why Lua scripting is the SDE2 standard for high-performance atomic operations.

---

## 1. WHAT

Redis provides two ways to group database commands and execute them atomically:

1. **Transactions (`MULTI` / `EXEC` / `WATCH`):**
   * `MULTI` starts a transaction block. Subsequent commands are queued.
   * `EXEC` executes all queued commands sequentially.
   * `WATCH` implements optimistic locking (Compare-and-Swap / CAS) by monitoring keys for changes before execution.
2. **Lua Scripting (`EVAL` / `EVALSHA`):**
   * Embeds a Lua script execution engine within the Redis server.
   * The script runs directly on the single main thread, providing guaranteed execution isolation (no other client command can interleave with the script execution).

```
                      ┌─────────────────────────────────────────┐
                      │          Redis Atomicity Engine         │
                      └─────────────────────────────────────────┘
                                           │
                ┌──────────────────────────┴──────────────────────────┐
                ▼                                                     ▼
┌───────────────────────────────┐                     ┌───────────────────────────────┐
│     1. MULTI/EXEC/WATCH       │                     │    2. LUA SCRIPTING (EVAL)    │
├───────────────────────────────┤                     ├───────────────────────────────┤
│ • Client sends commands.      │                     │ • Client sends script block.  │
│ • Commands are queued on server│                     │ • Script compiles and runs    │
│ • Executed sequentially at end│                     │   entirely on Redis CPU.      │
│ • No rollback on runtime error│                     │ • Complete isolation.         │
│ • Watch handles optimistic lock│                     │ • Can run conditional logic.  │
└───────────────────────────────┘                     └───────────────────────────────┘
```

---

## 2. WHY (the trade-offs)

### 2.1 Why Lua beats `MULTI` / `EXEC`
While `MULTI`/`EXEC` groups commands, it has significant architectural limitations:

1. **No Conditional Reads:** You cannot read a key's value *inside* a `MULTI`/`EXEC` block and use that value to decide what command to run next. All commands are queued blind. If you want to check if a balance is $\ge 10$, you must `WATCH` the key, read the key (network round-trip), start `MULTI`, write the decrement, and `EXEC`. If another client updated the key in the meantime, the transaction fails and you must retry the entire network loop.
2. **Network Round-Trips:** With `WATCH`, you pay the network penalty of multiple round-trips to complete a single atomic transaction.
3. **Lua's Solution:** A Lua script allows you to write conditional logic (`if/else`) that runs **directly inside the Redis process memory**. You send the script once. It reads the balance, checks the condition, decrements the key, and returns the result—all in **one single network round-trip** with absolute isolation.

### 2.2 Why Redis Transactions Have No Rollback
First, distinguish **two kinds of error** in a `MULTI`/`EXEC` block — they behave very differently:

1. **Compile-time (queueing) errors** — e.g. a malformed command, wrong arity, or an unknown command name. Redis detects these *at the moment the command is queued* (it returns an error instead of `QUEUED`). Since Redis 2.6.5, this **poisons the entire transaction**: when you then call `EXEC`, Redis refuses to run *any* of the queued commands and returns `EXECABORT`. So in this case it behaves like an all-or-nothing abort — **none** of the commands execute.
2. **Runtime (type) errors** — e.g. executing `INCR` on a key that actually holds a list. The command queues fine (its syntax is valid), and the error only surfaces *during* `EXEC`. Here Redis **continues to execute all other commands in the queue** and does **not** roll back. The failed command returns an error in its slot of the results array; the rest still take effect.

So "no rollback" is precisely true for **runtime errors**. **Compile-time errors abort the whole `EXEC` before anything runs.**

* **The Design Rationale (for the no-rollback-on-runtime-error choice):** Redis's creator argued that runtime transaction failures are caused by developer programming bugs (e.g. type mismatch), which should be caught in testing, not operational conditions (like database lock timeouts). Eliminating rollback support keeps the Redis database engine significantly faster and simpler.

---

## 3. HOW (the internals)

### 3.1 How `MULTI`/`EXEC` works internally
When a client connection sends `MULTI`, Redis flags the client struct with `CLIENT_MULTI`:

```c
typedef struct client {
    int flags;              /* CLIENT_MULTI flag set here */
    multiState mstate;      /* Queued transaction commands list */
    // ...
} client;
```

* **Command Queueing:** While the `CLIENT_MULTI` flag is active, if the client sends a write or read command, Redis does not execute it. Instead, it validates the command (existence and arity). If valid, it appends the command to the `mstate` queue and returns `QUEUED`. If the command is unknown or malformed, Redis returns an error **and** sets a `CLIENT_DIRTY_EXEC` flag on the connection so the eventual `EXEC` will abort.
* **Execution:** When `EXEC` is called, Redis first checks the `CLIENT_DIRTY_EXEC` flag. If any command failed to queue (a compile-time error), it discards the queue and returns `EXECABORT` — **nothing runs**. Otherwise it clears the `CLIENT_MULTI` flag, loops through all queued commands in `mstate` sequentially, executes them against the database, and returns the aggregated results as an array. A command that fails *at runtime* (e.g. type mismatch) does not stop the loop — its error is placed in the results array and the remaining commands still execute.

---

### 3.2 How `WATCH` works (Optimistic Locking)
`WATCH` allows a client to monitor one or more keys. It uses a Compare-and-Swap (CAS) mechanism:

1. When a client executes `WATCH balance:100`, Redis registers this client pointer into the database's `watched_keys` dictionary:
   `watched_keys: { "balance:100" -> List of Client Pointers }`
2. If another client executes any command that modifies `"balance:100"` (like `SET`, `INCR`, or `DEL`), Redis walks the `watched_keys` list for `"balance:100"` and flags all watching clients' structs with the `CLIENT_DIRTY_CAS` bit.
3. When the original client sends `EXEC` to execute their queued transaction, Redis checks their client flags:
   * **If `CLIENT_DIRTY_CAS` is set:** Redis discards the transaction queue, frees the memory, clears the flag, and returns `NIL` (null) to indicate the transaction aborted.
   * **If `CLIENT_DIRTY_CAS` is clean:** The transaction executes atomically.

```
Client A: WATCH key ──────► Registers interest in key
Client B: SET key val ────► Fires key modification event ──► Flags Client A with DIRTY_CAS
Client A: MULTI
Client A: SET key new_val
Client A: EXEC ───────────► Checks DIRTY_CAS flag ──► Detected! ──► Transaction Aborted (Returns NIL)
```

---

### 3.3 How Lua Scripting works internally
Redis embeds a Lua interpreter into the server process. When a script runs:

* **Execution Isolation:** Because Redis is single-threaded, running a Lua script means the script occupies the main CPU thread. **No other client command can execute while the script is running.** This provides absolute transactional isolation without locks.
* **`EVAL` vs `EVALSHA`:** Sending a 2KB Lua script over the network on every query wastes network bandwidth. Instead, Redis uses a script cache:
  1. The client loads the script once using `SCRIPT LOAD`. Redis hashes the script with SHA-1, stores the script in a dictionary, and returns the hash.
  2. The client executes the script using `EVALSHA <sha1_hash> <num_keys> <keys...> <args...>`.
  3. If Redis does not find the hash in its script cache, it returns a `NOSCRIPT` error, and the client falls back to sending the full script via `EVAL`.

#### The Blocking Risk of Lua Scripts
Since scripts block the main thread, a script with an infinite loop or a slow computation will freeze the entire Redis server. 
* Redis protects itself using the **`lua-time-limit`** configuration (default: 5 seconds).
* If a script runs longer than 5 seconds, Redis does not force-kill it (as that could leave the database half-written and corrupted). Instead, it starts returning a `BUSY` error to all other incoming client queries.
* While in the `BUSY` state, Redis will only accept two commands:
  1. **`SCRIPT KILL`:** Kills the script *only* if it has performed zero write operations.
  2. **`SHUTDOWN NOSAVE`:** Terminates the entire Redis server process immediately without saving memory state to disk, preventing dirty writes from corrupting your persistence files.

---

## 4. CODE / EXAMPLES

### 4.1 Check-and-Decrement Balance Example (Preventing Double Spend)
Scenario: User wants to deduct $50 from their balance, but only if their balance is $\ge 50$.

#### Option A: Using WATCH / MULTI / EXEC (Optimistic Locking)

##### Java (Jedis Client)
```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisConnectionException;
import java.util.List;

public class BalanceWatcher {
    public boolean deductBalance(String userId, double amount) {
        String key = "user:balance:" + userId;
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            while (true) {
                jedis.watch(key); // Watch key for CAS
                
                double balance = Double.parseDouble(jedis.get(key) != null ? jedis.get(key) : "0");
                if (balance < amount) {
                    jedis.unwatch();
                    return false; // Insufficient funds
                }
                
                Transaction tx = jedis.multi();
                tx.decrBy(key, (long) amount); // Note: Jedis decrBy takes long
                List<Object> results = tx.exec();
                
                if (results != null) {
                    return true; // Transaction succeeded
                }
                // If results is null, execution failed due to WATCH trigger -> Loop and retry
            }
        }
    }
}
```

---

#### Option B: Using Lua Script (Atomic Execution - Preferred SDE2 Standard)

##### The Lua Script:
```lua
-- KEYS[1]: The balance key
-- ARGV[1]: The amount to deduct
local current_balance = redis.call('GET', KEYS[1])
if not current_balance or tonumber(current_balance) < tonumber(ARGV[1]) then
    return 0 -- Failed due to insufficient funds
else
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1 -- Success
end
```

##### Java (Spring Data Redis Implementation)
```java
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Collections;

public class SpringRedisLuaDeduct {
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> deductScript;

    public SpringRedisLuaDeduct(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        
        // Define Lua script and specify return type (corresponds to Return value 0 or 1)
        String scriptSource = 
            "local current_balance = redis.call('GET', KEYS[1])\n" +
            "if not current_balance or tonumber(current_balance) < tonumber(ARGV[1]) then\n" +
            "    return 0\n" +
            "else\n" +
            "    redis.call('DECRBY', KEYS[1], ARGV[1])\n" +
            "    return 1\n" +
            "end";
            
        this.deductScript = new DefaultRedisScript<>(scriptSource, Long.class);
    }

    public boolean deductBalance(String userId, double amount) {
        String key = "user:balance:" + userId;
        
        // Spring handles EVALSHA caching internally:
        // It tries EVALSHA first; if it returns NOSCRIPT, it automatically falls back to EVAL.
        Long result = redisTemplate.execute(
            deductScript,
            Collections.singletonList(key),
            String.valueOf((long) amount)
        );
        
        return result != null && result == 1L;
    }
}
```

---

## 5. INTERVIEW ANGLES

### Q: Why does Redis not support rollbacks when a command inside a `MULTI` block fails?
**A:** First, separate the two failure modes — the answer is different for each:
* **Compile-time errors** (malformed command, bad arity, unknown command) are caught *while queueing*. Since Redis 2.6.5, this aborts the whole transaction: `EXEC` runs **none** of the queued commands and returns `EXECABORT`. So this case *is* effectively all-or-nothing.
* **Runtime errors** (e.g. running a list command on a string key) only surface during `EXEC`. Here Redis has no rollback — the offending command returns an error in its results slot and **all other queued commands still execute**.

In standard relational databases (like Postgres), rollbacks are required to protect against operational failures (such as unique constraint violations, deadlocks, or disk-full conditions) occurring mid-transaction. Redis's creator argued that *runtime* transaction failures only stem from developer programming bugs (type mismatches) that should be caught in testing, not operational conditions — so for that case Redis deliberately omits rollback. Eliminating rollback logic keeps the database engine fast and reduces structural complexity.

### Q: Compare the performance and reliability of `WATCH`/`MULTI`/`EXEC` vs. Lua Scripting.
**A:** 
* **Performance:** Lua scripting is much faster. A `WATCH` loop requires multiple client-server round-trips: `WATCH` -> `GET` -> (decide) -> `MULTI` -> `EXEC`. If another client mutates the key, the transaction aborts, forcing a loop retry and additional network calls. A Lua script executes in a single round-trip because the conditional logic runs directly inside Redis.
* **Atomicity:** Both models guarantee isolation on the single main thread. However, a Lua script is easier to keep atomic because it does not rely on retries to succeed.
* **Blocking Risk:** A bad Lua script (e.g. one containing an infinite loop) blocks the entire Redis engine. `WATCH`/`MULTI`/`EXEC` queues are simple arrays of native commands and carry no risk of execution hang-ups.

### Q: What is the purpose of `EVALSHA` and how does it optimize Redis network usage?
**A:** Sending large Lua scripts over the network on every transaction wastes bandwidth and increases latency. 
To optimize this:
1. The client registers the script using `SCRIPT LOAD`.
2. Redis compiles the script, stores it in an internal dictionary hash table, and returns its SHA-1 hash.
3. The client calls `EVALSHA <sha1>` instead of sending the full script text.
4. If Redis receives a SHA-1 it doesn't recognize (e.g., after a server reboot where the cache was cleared), it returns a `NOSCRIPT` error. The client intercepts this error, executes `SCRIPT LOAD` to reload the script, and retries the `EVALSHA` execution.

### Q: How do you guarantee transaction/script atomicity in a Redis Cluster (Sharded environment)?
**A:** Redis Cluster distributes keys across 16,384 hash slots. Multi-key transactions (like `MGET`, `MULTI`/`EXEC` blocks, or Lua scripts modifying multiple keys) **are only allowed if all target keys map to the exact same hash slot on the same physical cluster node**. If they span different slots, Redis returns a cross-slot error: `(error) CROSSSLOT Keys in request don't hash to the same slot`.
* **The Solution:** Use **Hash Tags**. By wrapping a specific part of the key name in curly braces `{...}`, Redis will calculate the CRC16 hash slot using only the characters inside the braces.
* *Example:* `{user100}:balance` and `{user100}:profile` are guaranteed to map to the same hash slot on the same node, allowing atomic Lua scripts or transactions to operate on both keys safely.

---

## 6. ONE-LINE RECALL CARDS

*   **`MULTI`** queues commands; **`EXEC`** runs them sequentially; **`DISCARD`** flushes the queue without executing.
*   **Compile-time errors** (bad/unknown command, caught at queue time) abort the whole `EXEC` (`EXECABORT`, nothing runs) since Redis 2.6.5; **runtime errors** have **no rollback** — the failed command errors but all other queued commands still execute.
*   **`WATCH`** implements optimistic locking (CAS); it aborts `EXEC` (returning `NIL`) if monitored keys are mutated by another client.
*   **Lua scripts** run entirely on the single main thread, providing absolute isolation and conditional logic in a single RTT.
*   **`EVALSHA`** executes pre-loaded Lua scripts using their SHA-1 hash to save network transmission bandwidth.
*   If a Lua script blocks the server beyond `lua-time-limit`, Redis returns `BUSY` and only allows `SCRIPT KILL` or `SHUTDOWN NOSAVE`.
*   `SCRIPT KILL` can only terminate a blocking Lua script if the script has executed **zero** write operations.
*   Multi-key transactions and Lua scripts in Redis Cluster require **Hash Tags** (e.g. `{user123}:key`) to avoid `CROSSSLOT` errors.

---

**Next:** [07 — Distributed Locks](07-distributed-locks.md) (SET NX PX, Lua unlock, Redlock critique, fencing tokens).
