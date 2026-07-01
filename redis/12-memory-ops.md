# 12 — Redis Memory Model, Operations & Sizing

> **Why this is Topic 12:** Redis is a memory-bound database. Unlike disk-backed databases where RAM serves as a buffer pool, in Redis, RAM *is* the database. For senior engineers at scaling firms like Zerodha, managing Redis memory involves deep mastery of allocator mechanics (like jemalloc), external and internal fragmentation, RSS memory drift, active defragmentation, and the sizing calculations required to prevent catastrophic OOM command failures on the trading hot-path. You must know how to diagnose high fragmentation, debug slow queries via Slowlog, evaluate key memory footprints using `MEMORY USAGE`, and mathematically size instances to survive peak load and persistence fork events.

---

## 1. WHAT

Redis is an in-memory database that operates directly within the virtual memory address space of its host process. Because RAM is finite, fast, and expensive, Redis goes to great lengths to manage memory layout, track metadata overhead, and compact fragmented space.

At a high level, Redis categorizes memory into:
1. **Used Memory (`used_memory`):** The total number of bytes allocated by Redis's internal memory allocator (typically `jemalloc` on Linux) to store keys, values, and internal data structures.
2. **Resident Set Size (`used_memory_rss`):** The actual number of physical bytes mapped to RAM by the Operating System for the Redis process.
3. **Fragmentation Overhead:** The difference between `used_memory_rss` and `used_memory`. This is caused by allocator bin alignment (internal fragmentation) and unreleased memory pages (external fragmentation).
4. **Overhead Buffers:** Memory consumed by clients' query buffers, replication backlog buffers (`client-output-buffer-limit`), and copy-on-write page tables during persistence fork events.

```
┌────────────────────────────────────────────────────────────────────────┐
│                      Host Physical RAM (RSS Memory)                    │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     jemalloc Allocator Space                     │  │
│  │  ┌─────────────────────────┐  ┌───────────────────────────────┐  │  │
│  │  │       used_memory       │  │     External Fragmentation    │  │  │
│  │  │  ┌───────────────────┐  │  │  • Unreleased, scattered memory│  │  │
│  │  │  │ Keys & Values     │  │  │    pages.                     │  │  │
│  │  │  ├───────────────────┤  │  │  • Reclaimed by:              │  │  │
│  │  │  │ dictEntry/robj    │  │  │    - active-defrag            │  │  │
│  │  │  ├───────────────────┤  │  │    - MEMORY PURGE             │  │  │
│  │  │  │ Client Buffers    │  │  └───────────────────────────────┘  │  │
│  │  │  └───────────────────┘  │                                     │  │
│  │  └─────────────────────────┘                                     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. WHY (the trade-offs)

### 2.1 Custom Allocators (jemalloc) vs. Standard glibc malloc
The standard Linux memory allocator (`glibc`'s `ptmalloc`) is general-purpose and suffers from severe fragmentation under highly dynamic allocation and deallocation workloads. 
* **The Trade-off:** Redis defaults to **`jemalloc`** on Linux because it categorizes memory requests into fixed size-classes (bins) and manages thread-local caches (`tcache`) to avoid allocator lock contention.
* **The Cost:** While `jemalloc` is extremely fast and avoids global locks, it introduces *internal fragmentation* because small allocation sizes are rounded up to the nearest bin size class. Furthermore, `jemalloc` prioritizes reuse and may hold onto physical memory pages, causing the RSS memory to drift far higher than the memory actually containing active Redis keys.

### 2.2 Active Defragmentation (`activedefrag`) vs. CPU Latency
* **The Problem:** Over time, deleting and modifying keys leaves "holes" in physical memory pages. Standard allocators cannot compact this space automatically.
* **The Trade-off:** Redis implements `activedefrag` which scans the keyspace, copies fragmented keys to new contiguous pages, updates dictionary pointers, and releases the old pages. This process consumes CPU. If configured too aggressively, it can starve the single-threaded event loop, leading to latency spikes. Redis trades CPU cycles for long-term memory stability.

### 2.3 MEMORY USAGE Sampling Accuracy vs. Single-Thread Blocking
* **The Problem:** Determining the exact memory footprint of a Hash or Sorted Set containing 10 million elements requires traversing every node and calculating its allocated structure sizes. On a single-threaded system, this would freeze Redis.
* **The Trade-off:** The `MEMORY USAGE` command uses **approximated sampling**. Instead of traversing the entire data structure, it samples a configurable number of entries (default: 5) and projects the average size onto the entire collection. This provides sub-millisecond response times at the expense of sizing precision.

---

## 3. HOW (the internals)

### 3.1 The jemalloc Allocator & Allocation Internals
On Linux systems, Redis compiles with `jemalloc` as its default memory allocator. Jemalloc groups allocations into three categories:
1. **Small Allocations:** Sizes up to 14 KB. These are grouped into bins (e.g., 8, 16, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, etc.).
2. **Large Allocations:** Sizes from 14 KB up to 4 MB.
3. **Huge Allocations:** Sizes above 4 MB.

#### Internal vs. External Fragmentation:
* **Internal Fragmentation:** Occurs when an allocation request does not exactly match a bin size. For instance, if Redis requests 33 bytes for a data structure, jemalloc places it in the 48-byte bin. The 15 unused bytes (31% of the allocation) constitute internal fragmentation.
* **External Fragmentation:** Occurs when allocations are freed, leaving small, scattered free spaces in memory pages that cannot be merged to satisfy larger allocation requests.

### 3.2 Resident Set Size (RSS) vs. Used Memory & Fragmentation
The state of Redis memory is tracked via two critical parameters in the `INFO memory` output:
* **`used_memory`:** Memory allocated by jemalloc for data structures, keys, values, and client buffers.
* **`used_memory_rss`:** Resident Set Size; the physical memory pages mapped to RAM by the OS page table.

#### The Memory Fragmentation Ratio:
$$\text{mem\_fragmentation\_ratio} = \frac{\text{used\_memory\_rss}}{\text{used\_memory}}$$

#### Interpreting the Ratio:
* **Ratio > 1.5:** Indicates significant external fragmentation. The OS is holding physical memory pages allocated to Redis, but many slots inside those pages are empty.
* **Ratio 1.0 - 1.5:** Normal, healthy operation.
* **Ratio < 1.0:** The host is out of physical RAM, and the OS has swapped Redis memory to disk (virtual memory/swap). Since Redis is single-threaded and relies on memory speeds, swapping triggers catastrophic latency spikes (from sub-millisecond to tens of milliseconds per operation).

#### Active Defragmentation (`activedefrag`):
To reclaim fragmented space without restarting the server, Redis implements active defragmentation:
1. It iterates over the main keyspace dictionary (`dict.c`) incrementally.
2. For each key-value pair, it queries the allocator using jemalloc-specific APIs (`je_malloc_usable_size`) to determine if the allocation is fragmented.
3. If fragmentation is high, Redis allocates a new, contiguous memory slot for the value, copies the data, updates the key's value pointer in the hash table, and frees the old fragmented block.
4. This runs in the background of the event loop. The system regulates its execution speed based on configuration parameters:
   * `active-defrag-ignore-bytes`: Minimum fragmentation size before defrag starts (default: 100MB).
   * `active-defrag-threshold-lower`: Minimum fragmentation ratio before defrag starts (default: 1.5).
   * `active-defrag-cycle-min`: Minimum CPU percentage allocated to defragmentation (default: 1%).
   * `active-defrag-cycle-max`: Maximum CPU percentage allocated to defragmentation (default: 25%).

---

### 3.3 Diagnostic Commands: `MEMORY USAGE` and `MEMORY DOCTOR`

#### `MEMORY USAGE <key> [SAMPLES <count>]`
Calculates the memory footprint of a specific key in bytes:
* For string values, it calculates the size of the `redisObject` header + SDS string header + value payload + allocator rounding.
* For collection types (Lists, Hashes, Sets, ZSets), it performs a sampling pass. It reads `SAMPLES` elements (default: 5) to compute the average element size, then multiplies this by the total length of the collection.
* **Caution:** If a Hash has 10,000 small keys and 1 massive key, a low `SAMPLES` count might miss the massive key, resulting in a severe underestimation of memory usage.

#### `MEMORY DOCTOR`
An automated diagnostic tool that reports on memory pressure and configuration warnings. It analyzes:
* Peak memory usage vs. current memory usage.
* The fragmentation ratio.
* Average key size and count.
* Replication backlog and client output buffer sizes.
* High allocation spikes.

It returns actionable messages, such as advising the enablement of active defragmentation, warning of swap space activation, or identifying memory-intensive replication buffers.

---

### 3.4 Latency & Memory Troubleshooting via Slowlog
Memory allocation, memory eviction, and large key deletions block the single-threaded execution thread. To track and troubleshoot commands that degrade performance, Redis uses the **Slowlog**.

* **What it logs:** The slowlog records execution time—it does **not** include I/O operations like network transit or queue wait times. It only tracks how long the single thread was blocked executing the command.
* **Configuring the Slowlog:**
  * `slowlog-log-slower-than <microseconds>`: Threshold for logging (e.g., `10000` logs any command blocking the thread for more than 10ms).
  * `slowlog-max-len <integer>`: The maximum number of historical entries stored in the circular memory buffer.
* **Memory-related Slowlog Causes:**
  1. **Eviction Cycles:** If `maxmemory` is hit, every write command triggers one-by-one eviction checks that block the queue.
  2. **Massive Deletions (`DEL`):** Deleting a Sorted Set or Hash with millions of members blocks the thread during memory cleanup. **Remediation:** Use `UNLINK` instead of `DEL`. `UNLINK` immediately removes the key pointer from the keyspace dictionary (making it look deleted instantly to clients) and delegates the actual memory deallocation to a background thread (`bio_lazy_free`).
  3. **COW Page Table Copying:** During `fork()` for RDB/AOF writes, allocating large page tables can block the thread.

---

### 3.5 Database Memory Sizing Calculations
To sizing a Redis instance for production, you must calculate:
1. The physical overhead of Redis internal metadata wrappers.
2. The key-value payload size.
3. Allocator alignment padding.
4. Hash table array structure scaling.
5. Buffer allowances for replication and clients.
6. The Copy-on-Write (CoW) buffer required during persistence.

#### The Mathematical Formula for Key Overhead:
Every key-value pair in Redis consists of:
1. **A Hash Table Entry (`dictEntry`):** Stores pointers to the key, value, and next collision node.
   * `key` pointer (8B) + `val` pointer (8B) + `next` pointer (8B) = 24 bytes raw.
   * Jemalloc bin class alignment rounds 24 bytes up to **32 bytes**.
2. **A String Key (Simple Dynamic String - SDS):**
   * Length $L$.
   * SDS Header (for $L \le 255$ bytes, `sdshdr8` is used): `len` (1B) + `alloc` (1B) + `flags` (1B) = 3 bytes.
   * Null terminator = 1 byte.
   * Raw size = $L + 4$ bytes.
   * Jemalloc rounds this up to the nearest bin size class (e.g., if $L=15$, raw size is 19 bytes, which rounds up to **20 or 24 bytes** depending on configuration/alignment).
3. **A value wrapper object (`redisObject`):**
   * `type` (4 bits) + `encoding` (4 bits) + `lru` (24 bits) + `refcount` (4 bytes) + `ptr` pointer (8 bytes) = 16 bytes.
   * Jemalloc bin class alignment matches exactly **16 bytes**.
4. **The Value Payload (Assuming String SDS):**
   * Length $V$.
   * SDS Header (for $V \le 255$ bytes, `sdshdr8` is used): 3 bytes header + 1 byte null terminator = 4 bytes overhead.
   * Raw size = $V + 4$ bytes.
   * Jemalloc rounds this up to the nearest bin size class.

```
Total Per-Key Memory = 32B (dictEntry) + 16B (redisObject) + RoundUp(KeyLength + 4B) + RoundUp(ValLength + 4B)
```

#### Hash Table Bucket Overhead:
The main keyspace dictionary stores entries in an array of buckets. 
* The number of buckets is always a power of 2.
* If a database holds $N$ keys, the hash table size $S$ is the smallest power of 2 greater than or equal to $N$.
* Each bucket is a pointer (8 bytes).
* Hash Table Memory = $S \times 8 \text{ bytes}$.
* **Rehashing Double-Up:** During rehashing, Redis allocates a second hash table that is twice the size of the current table. Total Hash Table Memory during rehashing = $3 \times S \times 8 \text{ bytes}$.

#### Non-Key Memory Overhead:
* **Query & Output Buffers:** Allow 10-20KB per active client connection.
* **Replication Backlog Buffer:** Set by `repl-backlog-size` (typically 64MB to 512MB depending on write volume).
* **Copy-on-Write (CoW) Reservation:** During `BGSAVE` or `BGREWRITEAOF`, the OS forks the Redis process. Any page modified after the fork is copied. If your write throughput is high, up to 30-50% of your RAM must be free to prevent OOM termination of the child or parent process during persistence phases.

---

### Step-by-Step Production Sizing Example:
* **Workload:** 20 Million keys.
* **Key Format:** `session:usr:<10-digit-id>` (e.g., `session:usr:9847201856` = 22 bytes).
* **Value Format:** JSON String of average size 120 bytes.
* **Operating System:** 64-bit Linux running jemalloc.

#### Step 1: Calculate Key SDS Size
* Key length $L = 22$ bytes.
* SDS size = $22 + 4 = 26$ bytes.
* Jemalloc bin class rounds 26 up to **32 bytes**.

#### Step 2: Calculate Value SDS Size
* Value length $V = 120$ bytes.
* SDS size = $120 + 4 = 124$ bytes.
* Jemalloc bin class rounds 124 up to **128 bytes**.

#### Step 3: Calculate Memory Per Key-Value Pair
* `dictEntry` = 32 bytes
* `redisObject` = 16 bytes
* Key SDS = 32 bytes
* Value SDS = 128 bytes
* **Total per-key allocation:** $32 + 16 + 32 + 128 = 208 \text{ bytes}$.

#### Step 4: Calculate Hash Table Overhead
* We have 20 million keys.
* The nearest larger power of 2 is $2^{25} = 33,554,432$ buckets.
* Hash table memory = $33,554,432 \times 8 \text{ bytes} = 268,435,456 \text{ bytes} \approx 256 \text{ MB}$.
* During active rehashing, this spikes to $3 \times 256 \text{ MB} = 768 \text{ MB}$. We will size based on the peak rehashing state to ensure stability: **768 MB**.

#### Step 5: Sum Up Active Key Memory
* Key payload memory: $20,000,000 \times 208 \text{ bytes} = 4,160,000,000 \text{ bytes} \approx 3.87 \text{ GB}$.
* Add Peak Hash Table overhead: $3.87 \text{ GB} + 0.72 \text{ GB} = 4.59 \text{ GB}$.

#### Step 6: Factor in Fragmentation and Buffer Reserves
* **Fragmentation Safety Margin:** Apply a standard $1.2\times$ scaling factor for jemalloc external fragmentation:
  $$4.59 \text{ GB} \times 1.2 \approx 5.51 \text{ GB}$$
* **Client and Replication Buffers:** Reserve 500 MB for active trading client connection query buffers, output buffers, and replication backlog:
  $$5.51 \text{ GB} + 0.50 \text{ GB} = 6.01 \text{ GB (Target used\_memory)}$$
* **Copy-on-Write (CoW) Safety Buffer:** For a high-throughput transaction path, assume up to 30% of pages are modified during a background RDB write.
  $$6.01 \text{ GB} \times 1.3 \approx 7.81 \text{ GB}$$

#### Sizing Decision:
Deploy on an instance with at least **8 GB of RAM**, setting `maxmemory` to `6.0gb` (leaving 2 GB of physical headroom for OS, libraries, and Copy-on-Write pages).

---

## 4. CODE / EXAMPLES

The following Java examples show how to programmatically inspect Redis memory footprint, run diagnostics, and manage fragmentation using the **Jedis** and **Lettuce** clients.

### 4.1 Memory Metrics & Diagnostics using Jedis

This class connects to Redis, executes memory metrics inspection commands, parses values from `INFO memory`, and runs `MEMORY USAGE` and `MEMORY DOCTOR`.

```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import java.util.HashMap;
import java.util.Map;

public class RedisMemoryMonitor {

    private final JedisPool jedisPool;

    public RedisMemoryMonitor(String host, int port) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        this.jedisPool = new JedisPool(poolConfig, host, port);
    }

    /**
     * Parses the raw 'INFO memory' output into a key-value map.
     */
    public Map<String, String> getMemoryStats() {
        Map<String, String> stats = new HashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            String rawInfo = jedis.info("memory");
            String[] lines = rawInfo.split("\r\n");
            for (String line : lines) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    stats.put(parts[0], parts[1]);
                }
            }
        }
        return stats;
    }

    /**
     * Checks memory health and prints warnings based on fragmentation ratio and swap flags.
     */
    public void printMemoryHealthReport() {
        Map<String, String> stats = getMemoryStats();
        
        long usedMemory = Long.parseLong(stats.getOrDefault("used_memory", "0"));
        long usedMemoryRss = Long.parseLong(stats.getOrDefault("used_memory_rss", "0"));
        double fragRatio = Double.parseDouble(stats.getOrDefault("mem_fragmentation_ratio", "1.0"));
        
        System.out.printf("Used Memory: %,d bytes (%.2f MB)%n", usedMemory, usedMemory / (1024.0 * 1024.0));
        System.out.printf("Resident Set Size (RSS): %,d bytes (%.2f MB)%n", usedMemoryRss, usedMemoryRss / (1024.0 * 1024.0));
        System.out.printf("Fragmentation Ratio: %.2f%n", fragRatio);

        if (fragRatio < 1.0) {
            System.err.println("WARNING: Fragmentation ratio < 1.0! Redis is swapping to disk. Latency is compromised.");
        } else if (fragRatio > 1.5) {
            System.out.println("WARNING: Fragmentation ratio is high (> 1.5). Memory is fragmented. Enable active-defrag.");
        } else {
            System.out.println("Memory fragmentation is within healthy boundaries (1.0 - 1.5).");
        }
    }

    /**
     * Measures memory usage of a specific key.
     */
    public Long getKeyMemoryUsage(String key, int samples) {
        try (Jedis jedis = jedisPool.getResource()) {
            // Evaluates memory usage using the native MEMORY USAGE command
            return jedis.memoryUsage(key, samples);
        }
    }

    /**
     * Runs the MEMORY DOCTOR diagnostic report.
     */
    public String getMemoryDoctorReport() {
        try (Jedis jedis = jedisPool.getResource()) {
            // Executes raw 'MEMORY DOCTOR' command
            Object response = jedis.sendCommand(() -> "MEMORY".getBytes(), "DOCTOR".getBytes());
            return response != null ? response.toString() : "No report returned";
        }
    }

    public static void main(String[] args) {
        RedisMemoryMonitor monitor = new RedisMemoryMonitor("localhost", 6379);
        monitor.printMemoryHealthReport();
        
        System.out.println("\n--- Memory Doctor Report ---");
        System.out.println(monitor.getMemoryDoctorReport());
    }
}
```

---

### 4.2 Lettuce (Spring Data Redis) Advanced Memory Commands
Lettuce executes native memory commands via standard connection-driven command interfaces, bypassing the high-level templates when raw diagnostic commands like `MEMORY PURGE` (manual allocator compaction) or `SLOWLOG GET` are needed.

```java
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SpringRedisAdvancedOps {

    private final StringRedisTemplate redisTemplate;

    public SpringRedisAdvancedOps(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Manually triggers memory purging in jemalloc, returning unused dirty pages back to the OS.
     */
    public String triggerMemoryPurge() {
        return redisTemplate.execute((RedisConnection connection) -> {
            // Executing 'MEMORY PURGE'
            byte[] command = "MEMORY".getBytes();
            byte[][] args = new byte[][]{ "PURGE".getBytes() };
            Object result = connection.execute("MEMORY", args);
            return result != null ? new String((byte[]) result) : "OK";
        });
    }

    /**
     * Fetch the top N execution entries in the Redis slowlog.
     */
    public List<SlowlogEntry> getSlowLog(int count) {
        return redisTemplate.execute((RedisConnection connection) -> {
            byte[] countArg = String.valueOf(count).getBytes();
            Object response = connection.execute("SLOWLOG", new byte[][]{ "GET".getBytes(), countArg });
            
            List<SlowlogEntry> entries = new ArrayList<>();
            if (response instanceof List) {
                List<?> rawList = (List<?>) response;
                for (Object item : rawList) {
                    if (item instanceof List) {
                        List<?> inner = (List<?>) item;
                        // Slowlog entry format: [id, timestamp, durationMicroseconds, [commandArgs...]]
                        long id = (Long) inner.get(0);
                        long timestamp = (Long) inner.get(1);
                        long durationUs = (Long) inner.get(2);
                        
                        List<?> argsList = (List<?>) inner.get(3);
                        List<String> commandArgs = new ArrayList<>();
                        for (Object arg : argsList) {
                            commandArgs.add(new String((byte[]) arg));
                        }
                        
                        entries.add(new SlowlogEntry(id, timestamp, durationUs, commandArgs));
                    }
                }
            }
            return entries;
        });
    }

    public static class SlowlogEntry {
        public final long id;
        public final long timestamp;
        public final long durationMicroseconds;
        public final List<String> commandArgs;

        public SlowlogEntry(long id, long timestamp, long durationMicroseconds, List<String> commandArgs) {
            this.id = id;
            this.timestamp = timestamp;
            this.durationMicroseconds = durationMicroseconds;
            this.commandArgs = commandArgs;
        }

        @Override
        public String toString() {
            return String.format("Slowlog ID: %d | Time: %d | Duration: %d us | Command: %s", 
                id, timestamp, durationMicroseconds, String.join(" ", commandArgs));
        }
    }
}
```

---

### 4.3 Redis Configuration Tuning for Memory & Safety (`redis.conf`)
Ensure these memory settings are applied to production instances on high-traffic nodes:

```ini
# Maximum physical RAM limits
maxmemory 6gb

# Eviction strategy when maxmemory is hit (Topic 4)
maxmemory-policy noeviction

# Active Defragmentation Settings
# ----------------------------------------------------
# Enable/Disable background memory page compaction
activedefrag yes

# Start defrag only when external fragmentation is > 100MB
active-defrag-ignore-bytes 100mb

# Start defrag only when fragmentation ratio > 1.5
active-defrag-threshold-lower 1.5

# Maximum defragmentation phase throttle limit (ratio of 2.0 = 200% threshold target)
active-defrag-threshold-upper 2.0

# Minimum CPU time dedicated to active defrag
active-defrag-cycle-min 5

# Maximum CPU time dedicated to active defrag to prevent starving standard queries
active-defrag-cycle-max 25

# Maximum keys processed per active defrag iteration cycle
active-defrag-max-scan-fields 1000

# Client output buffer constraints
# ----------------------------------------------------
# Format: client-output-buffer-limit <class> <hard limit> <soft limit> <soft seconds>
# Disconnect client immediately if buffer reaches 256MB OR stays above 64MB for 60 seconds
client-output-buffer-limit normal 0 0 0
client-output-buffer-limit replica 256mb 64mb 60
client-output-buffer-limit pubsub 32mb 8mb 60

# Slowlog Troubleshooting configuration
# ----------------------------------------------------
# Log execution times slower than 10 milliseconds (10,000 microseconds)
slowlog-log-slower-than 10000

# Maintain up to 1024 historical entries in the slowlog queue
slowlog-max-len 1024
```

---

## 5. INTERVIEW ANGLES

### Q: Why does deleting 10 million keys not immediately decrease RSS memory as shown in the OS or `used_memory_rss`? How do you remediate this?
**A:** When Redis deletes keys, the `used_memory` metrics reported by Redis decrease immediately because the memory allocator (`jemalloc`) marks those memory addresses as freed within its internal tracking structure. However, `jemalloc` does not immediately return those physical pages back to the host operating system. It holds onto them in its thread-local caches (`tcache`) or marks them as dirty pages to optimize future allocation speed. As a result, the OS still sees high physical memory mapping, and `used_memory_rss` remains unchanged. 
To remediate:
1. Trigger **Active Defragmentation** (`activedefrag yes`) to reorganize pointers and release empty pages back to the OS.
2. Force a manual memory reclamation by running the command `MEMORY PURGE`. This executes an immediate allocator-level arena purge that flushes thread caches and releases unused dirty memory pages back to the kernel.

### Q: How does `activedefrag` work, and how does it avoid starving the single-threaded event loop?
**A:** Active defragmentation scans the keyspace hash tables incrementally. For each key, it checks if the value is residing in a highly fragmented memory segment. If so, it:
1. Allocates a new, contiguous memory segment from a clean page.
2. Copies the payload data from the old memory address to the new address.
3. Updates the reference pointer in the main hash table.
4. Frees the old fragmented address.

To prevent blocking the event loop:
* Active defragmentation works incrementally, scanning only a small slice of keys during each loop tick.
* The CPU consumption of the defragmentation process is dynamically adjusted between `active-defrag-cycle-min` (default: 5% CPU) and `active-defrag-cycle-max` (default: 25% CPU). If Redis client traffic increases and execution latency climbs, the cycle auto-throttles to the minimum percentage. If the server is idle, it consumes more CPU to complete defragmentation quickly.

### Q: Your Redis instance is showing a `mem_fragmentation_ratio` of 0.75 under heavy load. What is happening, what are the implications, and how do you remediate it?
**A:** A fragmentation ratio of less than 1.0 indicates that the physical RAM allocated to Redis (`used_memory_rss`) is less than the memory Redis's allocator believes it is using (`used_memory`). This is a clear indicator that the host operating system has run out of physical memory and has started swapping Redis memory pages to virtual memory on disk.
* **Implications:** This is catastrophic for Redis performance. Every request targeting swapped pages will trigger disk read operations. The single-threaded execution queue will block on synchronous disk I/O, causing latency to spike from under 1 millisecond to tens of milliseconds. This triggers timeouts across upstream microservices and API gateways.
* **Remediation:**
  1. Immediately scale up the physical RAM of the host machine or scale horizontally by sharding data across more Redis nodes.
  2. Lower the `maxmemory` setting on the instance to ensure it sits safely below the physical memory limits of the server.
  3. Turn off swap space (`swapoff -a`) on the host system to prevent swapping; it is better for a Redis node to trigger the Out-Of-Memory (OOM) killer and trigger replica failover than to degrade to swapping states.

### Q: Explain the copy-on-write (CoW) overhead during `BGSAVE` (RDB generation) or `BGREWRITEAOF`. How do Linux Transparent Huge Pages (THP) interact with CoW and what latency risks do they introduce?
**A:** When Redis forks a background worker process for persistence tasks (`BGSAVE` or `BGREWRITEAOF`), the OS uses **Copy-on-Write (CoW)**. The child process shares the exact same physical memory pages as the parent process. If a client writes to a key after the fork, the OS copies that page to a new physical address before modifying it, isolating the parent's changes from the child's point-in-time snapshot.
* **THP Interaction & Latency Risks:** By default, modern Linux systems use **Transparent Huge Pages (THP)** to group standard 4 KB memory pages into larger 2 MB pages, reducing page table lookups. If THP is enabled when a fork is active, any write to a page—even if modifying a tiny 10-byte key—forces the OS to copy the entire **2 MB** page instead of a **4 KB** page.
* This dramatically increases memory allocation overhead, consumes physical RAM at a massive rate, and blocks the event loop thread during physical page copy phases. This causes severe latency spikes during persistence updates.
* **Solution:** Transparent Huge Pages must be disabled on all hosts running production Redis instances using the command:
  `echo never > /sys/kernel/mm/transparent_hugepage/enabled`

### Q: Why is `UNLINK` preferred over `DEL` for large keys, and how does the async memory reclamation work behind the scenes?
**A:** When a user executes a `DEL` command, Redis immediately removes the key from the database's keyspace dictionary and frees the memory allocated for the key and value payload *synchronously*. If the key is a collection containing millions of items (e.g. a large Sorted Set), reclaiming the memory takes milliseconds or even seconds, completely blocking the single-threaded event loop.
* **`UNLINK`:** This performs an asynchronous delete. When called, Redis deletes the key pointer from the keyspace dictionary. To the client, the key is instantly invisible.
* Redis then checks the size of the value. If the object size is small, it is freed synchronously on the spot to avoid thread context switches. If the object is large (e.g., contains more than 64 elements/nodes), Redis registers the object on a background lazy-free queue.
* A background thread (`bio_lazy_free`) polls this queue and incrementally frees the memory blocks, ensuring that the main execution loop remains free to process incoming commands.

---

## 6. ONE-LINE RECALL CARDS

* **`used_memory`** tracks RAM allocated by jemalloc; **`used_memory_rss`** tracks actual physical memory mapped by the OS.
* A fragmentation ratio **$< 1.0$** indicates memory swapping to disk, causing severe single-threaded queue blockage.
* **`MEMORY PURGE`** manually forces jemalloc to purge empty arenas and return dirty pages to the OS.
* **`activedefrag`** copies fragmented data blocks to new, contiguous physical pages incrementally in the background.
* **`MEMORY USAGE`** samples $N$ collection nodes (default: 5) to estimate memory size without blocking the single thread.
* **`UNLINK`** makes a key instantly invisible and delegates memory reclamation of large objects to background threads (`bio_lazy_free`).
* Linux **Transparent Huge Pages (THP)** must be disabled to prevent 2MB page allocation copies during Copy-on-Write forks.
* Sizing must reserve at least **30-50% free RAM** to support Copy-on-Write page modifications during persistence forks.
* **`client-output-buffer-limit`** constraints prevent slow replicas or clients from consuming too much memory and triggering OOM.
* **Slowlog** tracks command execution time in the single thread, excluding network latency and queue wait times.

---

**Prev:** [11 — Fintech Patterns](11-fintech-patterns.md) | **Next:** [13 — Postgres + Redis System Design](13-postgres-redis-system-design.md) | **Index:** [Redis Curriculum](index.md)
