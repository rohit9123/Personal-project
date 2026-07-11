# 15 — Redis Pipelining vs. Lua Scripting: The Deep Dive

In high-concurrency architectures (like trading platforms or payment gateways), network latency and execution atomicity are the twin bottlenecks. Redis offers two powerful tools to optimize operations: **Pipelining** and **Lua Scripting**. 

While both reduce network round-trips to **1 RTT**, they serve entirely different purposes. Here is the noob-friendly, technically precise comparison of how they work, how they differ, and when to use which.

---

## 💡 The "Noob" Analogy

Imagine you are running a restaurant kitchen and need to get ingredients from a remote pantry down the street.

### Redis Pipelining: The Delivery Boy 🚴‍♂️
* **How it works**: You write a shopping list with 5 items: *"Get Milk, Get Flour, Get Sugar, Get Eggs, Get Butter"*. You hand the entire list to a delivery boy. He walks to the pantry once, grabs all 5 items, packages them, and brings them back to you in one trip.
* **The limitation (No Decision Making)**: The delivery boy is just a carrier. If you want to say, *"Get Milk. If the Milk is expired, buy Almond Milk instead,"* the delivery boy **cannot do this**. He has to bring the expired milk back to you, you inspect it, write a new list, and send him back (paying the cost of another round-trip).

### Lua Scripting: The Trained Chef 👨‍🍳
* **How it works**: You send a trained chef to the pantry with a recipe script: *"Go check the milk. If it is fresh, take it and deduct $5 from my cash box. If it is expired, throw it away, take the almond milk, and write a warning log."*
* **The power (Local Decision Making)**: The chef goes to the pantry, checks the state of the ingredients, makes decisions **on the spot**, modifies the cash box, and returns with the final result in a single trip. No back-and-forth network trips needed for decision-making.

---

## ⚙️ How They Actually Work Under the Hood

### 1. Redis Pipelining Internals (Network Optimization)

Pipelining is a **client-side network optimization**. Redis does not have a "pipeline mode" on the server; it accepts pipelined commands natively.

```
Client Buffer                              TCP socket buffer                 Redis Main Thread
+-----------------+                        +---------------+                 +-------------------+
| SET k1 v1 \n    |                        | SET k1 v1     |                 | Read socket data  |
| INCR k2 \n      | -- Write to socket --> | INCR k2       | -- syscall ---> | Run SET k1 v1     |
| LPUSH k3 v3 \n  |                        | LPUSH k3 v3   |                 | Run INCR k2       |
+-----------------+                        +---------------+                 | Run LPUSH k3 v3   |
                                                                             +-------------------+
```

1. **Client Buffering**: The client library buffers multiple commands into a local memory buffer instead of writing them to the network socket immediately.
2. **Batch Send**: The client sends the entire buffer to the socket in one write system call (`write()`), which the OS transmits as TCP packets.
3. **Server Consumption**: Redis reads all available bytes from the socket buffer in a single network read system call (`readQueryFromClient()`).
4. **Sequential Execution**: The single main thread parses the commands and executes them one after another in memory, buffering the responses.
5. **Batch Reply**: Redis writes the stacked responses back to the client in a single write operation.

> [!WARNING]
> **No Atomicity**: Pipelining **does NOT block other clients**. While the main thread is executing your pipelined commands, commands from another client can be interleaved between your commands!

---

### 2. Lua Scripting Internals (Process-Level Atomicity)

Lua scripting is a **server-side execution engine**. Redis compiles and runs Lua scripts directly inside the Redis process memory space.

```
Client                                      Redis Engine (Single Thread)
  |                                                    |
  |--- EVALSHA <sha1> {key1} 50 ---------------------> | Complete TCP Handshake
  |                                                    | Decrypt SSL (if HTTPS proxy)
  |                                                    | [Locks Main Thread]
  |                                                    | Runs Lua interpreter
  |                                                    | - GET key1 -> 100
  |                                                    | - Compare 100 >= 50
  |                                                    | - DECRBY key1 50
  |                                                    | [Unlocks Main Thread]
  |<-- Return Success (1) ---------------------------- |
```

1. **Upload**: The client uploads a Lua script. Redis hashes it with SHA-1, stores it in a script dictionary, and compiles it.
2. **EVALSHA Call**: The client invokes the script using its SHA-1 hash via `EVALSHA`.
3. **Main Thread Blocking**: The single execution thread takes the compiled script and runs it. 
4. **Absolute Isolation**: Because Redis is single-threaded and the script runs inside the database engine, **no other client command can run until the script finishes.** It is completely atomic.
5. **Conditional Logic**: The script can read keys, perform calculations, and write updates dynamically in memory without returning to the client.

---

## 📊 Side-by-Side Comparison

| Feature | Pipelining | Lua Scripting |
| :--- | :--- | :--- |
| **Primary Purpose** | Reduce network round-trip overhead | Perform atomic read-modify-write operations |
| **Execution Location** | Commands generated by Client | Script runs inside the Redis Server |
| **Atomicity** | ❌ **No**. Other client commands can interleave. |  **Yes**. Blocks the main thread completely. |
| **Conditional Logic** | ❌ **No**. Client cannot react to data midway. |  **Yes**. Full script logic (`if/else`, loops). |
| **Round Trips (RTT)** | **1 RTT** (sends all, gets all) | **1 RTT** (triggers script, gets final response) |
| **Server CPU Load** | **Low**. Minimal parsing overhead. | **High**. Spawns Lua VM and compiles/runs logic. |
| **Thread Blocking Risk** | **Low**. Individual commands are fast. | **High**. Slow script/infinite loop freezes Redis. |
| **Error Rollback** | ❌ **No**. If command 2 fails, command 3 still runs. | ⚠️ **Partial**. Script aborts, but changes made *before* the crash persist (must handle via custom rollback logic if needed). |

---

## 🛠️ Code Examples

### 1. Pipelining Example (Batch Loading)
Use pipelining to speed up bulk writes where commands are independent.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import java.util.List;

public class RedisPipelineDemo {
    public void bulkInsertUsers() {
        try (Jedis jedis = new Jedis("localhost", 6379)) {
            Pipeline pipeline = jedis.pipelined();
            
            // Queue 10,000 writes in memory
            for (int i = 0; i < 10000; i++) {
                pipeline.hset("user:" + i, "status", "active");
                pipeline.hset("user:" + i, "role", "member");
            }
            
            // Send all commands and wait for responses (1 RTT)
            List<Object> responses = pipeline.syncAndReturnAll();
            System.out.println("Bulk insert completed. Responses count: " + responses.size());
        }
    }
}
```

### 2. Lua Scripting Example (Rate Limiter - Slide Window)
Use Lua when you must read, check, and update state atomically.

```lua
-- KEYS[1]: Rate limit key (e.g., rate:user123)
-- ARGV[1]: Max requests allowed
-- ARGV[2]: Window size in seconds
local current = redis.call('GET', KEYS[1])
if current and tonumber(current) >= tonumber(ARGV[1]) then
    return 0 -- Denied (Rate limit exceeded)
else
    if not current then
        redis.call('SET', KEYS[1], 1, 'EX', ARGV[2]) -- Create key with TTL
    else
        redis.call('INCR', KEYS[1]) -- Increment
    end
    return 1 -- Allowed
end
```

---

## ⚠️ Redis Cluster & Multi-Key Limitations

Both Pipelining and Lua scripting face constraints in a **sharded Redis Cluster**:

1. **The CROSSSLOT Restriction**:
   * If a pipeline or Lua script attempts to touch multiple keys (e.g., `keyA` and `keyB`), the operation will fail with a `CROSSSLOT` error if those keys reside in different hash slots across the cluster.
2. **The Solution (Hash Tags)**:
   * Enclose the common part of the key name in curly braces `{...}` to force them into the same hash slot on the same cluster node.
   * *Example:* `{user100}:profile` and `{user100}:settings` are guaranteed to hash to the same slot, allowing atomic pipelining or Lua scripting.

---

## 🙋‍♂️ Interview Questions

### Q: Can we use Pipelining inside a Transaction (`MULTI`/`EXEC`)?
**A:** Yes, and you *should*! While `MULTI`/`EXEC` ensures that your commands run atomically as a block, without pipelining, the client still pays the network RTT for queuing every individual command. Combining them sends the entire block in **1 RTT** and executes it **atomically**:

```java
try (Jedis jedis = new Jedis("localhost", 6379)) {
    Pipeline pipeline = jedis.pipelined();
    pipeline.multi(); // Start transaction
    pipeline.set("balance:1", "100");
    pipeline.set("balance:2", "200");
    pipeline.exec();  // Commit transaction
    pipeline.sync();  // Execute over network
}
```

### Q: Since Lua scripts run atomically, do they roll back on errors?
**A:** **No.** If a Lua script encounters an error halfway through execution (e.g., a type error), the script stops execution immediately, but **any modifications made by the script before the error was thrown remain written to the database**. Redis does not support automatic transactional rollbacks. If rollback behavior is critical, you must write manual defensive checks in your script before performing updates.

### Q: When should I choose Pipelining over Lua Scripting?
**A:** Use **Pipelining** when you have a large batch of independent writes (e.g., bulk cache loading, importing data) and you don't need the output of one command to serve as the input for the next. Use **Lua Scripting** when you need conditional logic, validation checks, or read-modify-write safety (e.g., ledger updates, rate limiting, inventory checks) where intermediate decisions are required.
