# 03 — Redis Persistence: RDB, AOF & Hybrid

> **Why this is Topic 3:** Redis is an in-memory database, meaning a server reboot or power failure wipes out all data instantly. For financial applications like Zerodha (holding user portfolios, session limits, or trade idempotency records), data loss can mean direct financial liability. Understanding how Redis backs up memory to disk, the performance impact of OS-level process `fork()` calls, Copy-on-Write (CoW) memory spikes, and how different `fsync` policies trade latency for durability is a standard SDE2 architectural interview requirement.

---

## 1. WHAT

Redis provides three distinct persistence mechanisms to write memory data to non-volatile disk:

1. **RDB (Redis Database):** Point-in-time, binary snapshots of the entire keyspace saved at configured intervals (e.g., every 5 minutes if 100 keys changed). 
2. **AOF (Append Only File):** An append-only log recording every write command received by the server. Commands are logged in the standard RESP protocol format and replayed on startup to reconstruct the state.
3. **Hybrid Persistence (Redis 4.0+):** A combination of both. When rewriting the AOF log, Redis writes an RDB snapshot to the beginning of the file, followed by new incremental write commands in AOF format.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              REDIS MEMORY                              │
└────────────────────────────────────────────────────────────────────────┘
         │                                                      │
         │ (Point-in-Time Snapshot)                             │ (Log every write)
         ▼                                                      ▼
┌────────────────────────┐                             ┌────────────────────────┐
│      RDB FILE          │                             │      AOF FILE          │
│   (dump.rdb)           │                             │   (appendonly.aof)     │
├────────────────────────┤                             ├────────────────────────┤
│ • Binary, compressed   │                             │ • RESP protocol text   │
│ • Fast to load         │                             │ • High write durability│
│ • Lossy (diff since    │                             │ • Large file size      │
│   last snapshot)       │                             │ • Slow to load         │
└────────────────────────┘                             └────────────────────────┘
         │                                                      │
         └───────────────┬──────────────────────────────────────┘
                         │ (AOF Rewrite Compacts to Hybrid)
                         ▼
           ┌───────────────────────────┐
           │        HYBRID FILE        │
           ├───────────────────────────┤
           │ [  RDB Binary Header  ]   │ ──► Fast startup recovery
           │ [ AOF Incremental Logs ]  │ ──► Zero/minimal data loss
           └───────────────────────────┘
```

---

## 2. WHY (the trade-offs)

No persistence model is perfect. Choosing the wrong strategy will either crash your server due to out-of-memory (OOM) exceptions or throttle your throughput.

### 2.1 RDB vs. AOF Comparative Analysis

| Feature | RDB (Snapshots) | AOF (Log-replay) | Hybrid (Recommended) |
| :--- | :--- | :--- | :--- |
| **Recovery Speed** | **Fast** (reads binary directly into memory). | **Slow** (replays millions of command transactions one-by-one). | **Fast** (loads binary base, replays small tail). |
| **Data Loss Risk** | **High** (loses all writes since the last snapshot was taken). | **Minimal** (loses maximum 1 second of writes with default `everysec` policy). | **Minimal** (same safety as AOF configuration). |
| **File Size** | **Compact** (compressed binary image). | **Bloated** (verbose RESP text commands). | **Semi-Compact** (binary base keeps size low). |
| **System Resource Cost** | **High CPU/Disk burst** during snapshot creation. | **Continuous disk writes** (requires SSDs to prevent I/O latency spikes). | **Burst during AOF rewrite**, low overhead otherwise. |

---

## 3. HOW (the internals)

### 3.1 RDB Snapshots: Child Processes & Copy-on-Write (CoW)
To save a snapshot without blocking clients, Redis calls `BGSAVE`. This triggers a Unix `fork()` system call, creating a child process:

```
                      ┌───────────────┐
                      │  Main Process │
                      └───────┬───────┘
                              │
                      fork()  ▼
           ┌──────────────────────────────────┐
           │ Creates child process:           │
           │ Shares physical RAM page tables │
           └────────────────┬─────────────────┘
                            │
               ┌────────────┴────────────┐
               ▼                         ▼
      ┌─────────────────┐       ┌─────────────────┐
      │  Main Process   │       │  Child Process  │
      │ (Serves Clients)│       │ (Writes dump)   │
      └────────┬────────┘       └────────┬────────┘
               │                         │
               │ (Mutates Page A)        │ (Reads Page A, B, C)
               ▼                         ▼
      ┌─────────────────┐       ┌─────────────────┐
      │ OS Copies PageA │       │ Reads original  │
      │ (Main gets A')  │       │ data of Page A  │
      └─────────────────┘       └─────────────────┘
```

#### Copy-on-Write (CoW) Mechanics:
1. **Virtual Address Space Sharing:** The `fork()` call is extremely fast because it does not copy the physical RAM contents. Instead, it copies the **page tables** (virtual memory maps) of the parent process. Both parent and child point to the same physical memory pages in RAM.
2. **Read-Only Flags:** The OS marks all memory pages for both parent and child processes as **Read-Only**.
3. **Triggering a Copy:** If a client sends a write command (e.g., `SET portfolio:123 value`), the main thread attempts to modify the memory page (say, Page A) containing that key.
4. **Page Fault Exception:** The CPU catches the write attempt on a read-only page, triggering an OS page fault.
5. **Page Duplication:** The OS kernel intercepts this fault, allocates a new physical memory page (Page A'), copies the original data of Page A into Page A', updates the main process's page table to point to Page A', marks Page A' as writable, and executes the client's write.
6. **Child Integrity:** The child process's page table still points to the original, unmodified Page A, preserving the clean point-in-time snapshot.

> [!CAUTION]
> **The CoW Memory Spike:** If your database has 10GB of data, and clients update 4GB of that data while `BGSAVE` is running, the OS must copy all modified pages. Your memory usage will spike from **10GB to 14GB**. If your system does not have enough free RAM (or configured swap space), the OS **Out-of-Memory (OOM) Killer** will instantly terminate the main Redis process, causing downtime.

---

### 3.2 AOF Internals: Write Path & Fsync Policies
Every write command is appended to an internal buffer (`server.aof_buf`) in memory first. Redis then writes this buffer to the operating system file cache using the `write()` system call. However, `write()` only guarantees that the data has left the application and entered the OS cache—it is **not** written to the physical disk yet.

To force the OS cache to flush its data to the hardware disk platter or SSD cells, Redis calls the system utility `fsync()`. You configure how often this occurs using `appendfsync`:

```
Client ──► [Redis Memory] ──► [aof_buf Buffer] ──► [OS File Cache] ──► [Physical Disk]
                                                   (write syscall)     (fsync syscall)
                                                                       ▲
                                                                       │ (Managed by appendfsync)
                                                                       ├─► always (High latency)
                                                                       ├─► everysec (1s window)
                                                                       └─► no (OS choice - 30s)
```

1. **`appendfsync always`:** Calls `fsync()` after every single write operation.
   * *Safety:* Maximum durability (0 data loss).
   * *Performance:* Throttled by disk I/O write speed (typically 100-1000 operations/sec on standard SSDs, vs. 100,000+ operations/sec in memory). High disk wear.
2. **`appendfsync everysec` (Default & Recommended):** A dedicated background thread calls `fsync()` once every second.
   * *Safety:* Maximum data loss window is bounded to **1–2 seconds** of transactions.
   * *Performance:* Almost as fast as no persistence, as the system calls run asynchronously.
3. **`appendfsync no`:** Redis does not call `fsync()`. It relies on the operating system to flush the cache (usually every 30 seconds on Linux).
   * *Safety:* High data loss risk (up to 30 seconds of writes lost on power failure).
   * *Performance:* Maximum performance.

---

### 3.3 AOF Rewrite (`BGREWRITEAOF`)
Because the AOF records every write, it grows indefinitely. If a key `counter` is incremented 1,000,000 times, the AOF will contain 1,000,000 lines of `INCR` statements, but the actual database only holds one value.

To compact the log, Redis runs `BGREWRITEAOF`:
1. **Child Fork:** Redis forks a child process.
2. **Read Memory state:** The child process reads the current database keys from memory and writes the minimum command sequence required to recreate them directly to a *new temporary AOF file*. (It does **not** read or parse the old, bloated AOF file).
3. **Accumulate New Changes:** While the child is writing, the main process continues serving clients and appends all incoming new write commands to the old AOF *and* buffers them in an **AOF Rewrite Buffer**.
4. **Merge & Atomic Rename:** Once the child completes the rewrite, the main thread appends the rewrite buffer contents to the new AOF file, and atomically replaces the old AOF file with the new one.

```
[Main Process] ──(Incoming Writes)──► [AOF Buffer] ──► [Old AOF File]
       │
       ├─► (Fork child process)
       │
       ▼ (While child writes...)
[AOF Rewrite Buffer]
       │
       │ (Merge at end of child write)
       ▼
[New Compact AOF File] ◄── (Child writes current state directly from memory)
```

---

### 3.4 Hybrid Persistence (`aof-use-rdb-preamble`)
Starting in Redis 4.0, you can enable Hybrid Persistence. 
When `BGREWRITEAOF` runs, the child process writes the database state in **binary RDB format** to the beginning of the AOF file. Any new client writes occurring during the rewrite are appended to the end of the file in **RESP text command format**.

*   **Resulting File Structure:** `[ RDB Header Binary Data ... ][ AOF RESP Text Commands ... ]`
*   **Intuitive Logic:** 
    *   **The Child Process** handles the base snapshot (the frozen old state).
    *   **The Main Thread** handles incoming real-time client updates, logging them to a temporary memory buffer.
    *   At the end of the rewrite, the main thread **merges** the snapshot file with the memory-buffered AOF text commands.
*   **Startup Recovery Flow ("Rebuilding the DB"):** 
    1. Redis parses the binary RDB preamble header first, loading the massive base state into RAM at hardware speeds (fast loading).
    2. Redis then reads the remaining AOF text command tail and replays them sequentially to preserve the absolute latest transactions (preserving 100% of the state).

---

## 4. CODE / EXAMPLES

### 4.1 Configuring Persistence in `redis.conf`
Here is a production-grade configuration setup for robust persistence:

```ini
# --- RDB Snapshots ---
# Save the database to disk:
# - After 3600 seconds (1 hr) if at least 1 key changed
# - After 300 seconds (5 min) if at least 100 keys changed
# - After 60 seconds if at least 10,000 keys changed
save 3600 1
save 300 100
save 60 10000

# Compress RDB files using LZF compression (saves disk, uses slightly more CPU)
rdbcompression yes

# Filename and directory for RDB dumps
dbfilename dump.rdb
dir /var/lib/redis

# --- AOF Configurations ---
# Enable the Append Only File
appendonly yes

# AOF file name
appendfilename "appendonly.aof"

# Fsync policy (always, everysec, no)
appendfsync everysec

# Performance optimization:
# If you experience severe disk latency spikes during RDB snapshots or AOF rewrites, 
# setting this to 'yes' stops the main thread from calling fsync() while a fork child 
# is writing, preventing disk starvation blocks. Trade-off: potential 30s data loss.
no-appendfsync-on-rewrite yes

# Auto-trigger AOF rewrite when file size doubles (100% growth) AND is at least 64MB
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb

# --- Hybrid Persistence ---
# Enable the hybrid RDB-AOF preamble structure (Redis 4.0+)
aof-use-rdb-preamble yes
```

### 4.2 Inspecting a RESP-formatted AOF File
If you disable the hybrid preamble and inspect a raw AOF file, you will see raw RESP (Redis Serialization Protocol) blocks:

```redis
# 1. Add some keys
> SET user:100 "Alice"
OK

# 2. View the end of appendonly.aof file on disk
$ tail -n 10 /var/lib/redis/appendonly.aof
*3
$3
SET
$8
user:100
$5
Alice
```
* RESP Protocol Breakdown:
  * `*3` means an array of 3 arguments.
  * `$3` means the next argument string has a length of 3 bytes.
  * `SET` is the string.
  * `$8` indicates 8 bytes for `user:100`.
  * `$5` indicates 5 bytes for `Alice`.

### 4.3 Triggering Manual Backups and Verification
```redis
# 1. Force a synchronous block-and-save (blocks main thread - NEVER run in production!)
> SAVE
OK

# 2. Force an asynchronous background snapshot (non-blocking)
> BGSAVE
Background saving started

# 3. Force an asynchronous AOF rewrite compaction
> BGREWRITEAOF
Background append only file rewriting started

# 4. Check the status of ongoing persistence forks
> INFO persistence
# Persistence
loading:0
rdb_changes_since_last_save:0
rdb_bgsave_in_progress:0             # 1 if active fork is running
rdb_last_bgsave_status:ok
aof_enabled:1
aof_rewrite_in_progress:0            # 1 if active AOF rewrite is running
aof_last_rewrite_status:ok
```

---

## 5. INTERVIEW ANGLES

### Q: What is Copy-on-Write (CoW), and how can a `BGSAVE` trigger an Out-of-Memory (OOM) error?
**A:** When `BGSAVE` runs, Redis calls `fork()` to create a child process. The child process shares the physical memory pages of the parent process, mapping its virtual page tables to the same RAM locations. To prevent data modifications, the OS marks these pages as read-only.
If the parent process receives write commands while the child is saving, the OS intercepts the write attempt, duplicates the target memory page, updates the parent's pointer to the new copy, and writes the change there. The child's pointer remains on the original page.
**The OOM risk:** If client writes modify a large fraction of the database memory during the snapshot window, the OS duplicates those pages. In a write-heavy database of 20GB, if 50% of the keys are updated during a snapshot, memory usage spikes by another 10GB. If the system does not have enough headroom, the OS OOM killer will terminate the Redis server.

### Q: What is the setting `no-appendfsync-on-rewrite` and why would you set it to `yes`?
**A:** During an AOF rewrite (`BGREWRITEAOF`) or RDB snapshot (`BGSAVE`), the child process performs heavy sequential disk writes. If the disk is saturated or suffers from I/O bottlenecks, system queue depths fill up.
If `appendfsync everysec` is active, the background AOF thread tries to call `fsync()`. If the disk queue is blocked by the child process's writes, the `fsync()` system call blocks. If the block lasts longer than 2 seconds, the main Redis thread (which executes user commands) will stall to prevent the AOF buffer from overflowing.
Setting `no-appendfsync-on-rewrite yes` tells Redis: *"Do not call fsync() while a background child is executing writes."* This keeps the main thread unblocked during disk bottlenecks. 
**Trade-off:** If the server crashes during the rewrite, you could lose up to 30 seconds of write data (relying on default OS flushes) instead of the typical 1-2 seconds.

### Q: What happens if the server crashes while writing an AOF file? How do you recover?
**A:** If the power cuts mid-write, the AOF file can end with a corrupted or half-written RESP command. Upon restarting, Redis will read the corrupted file, encounter a parse error, and fail to boot to prevent starting in an inconsistent state.
**Recovery Procedure:**
1. Backup the corrupted `.aof` file.
2. Run the tool `redis-check-aof --fix <filename>` provided by the Redis package.
3. This tool scans the file, finds the last clean RESP marker, and truncates the remaining corrupted bytes at the tail.
4. Restart Redis. (Note: The half-written transaction at the very end of the file is lost).

### Q: Why doesn't Redis parse the old AOF file to do a Rewrite?
**A:** Reading and parsing a massive, bloated AOF file (e.g., 50GB) from disk is incredibly slow, memory-intensive, and disk-bound. 
Instead, the child process spawned by `BGREWRITEAOF` simply walks the **in-memory database keys** (which represents the absolute latest collapsed state of the system). It writes the current memory values directly to disk. For example, if a list contains 10 elements, it just writes one single `RPUSH` command with 10 values, completely bypassing the history of modifications.

---

## 6. ONE-LINE RECALL CARDS

*   **RDB** creates compact, binary snapshots of memory; it is fast to load but prone to data loss on sudden crashes.
*   **AOF** logs every write command in RESP text format; it is slow to replay but provides high durability.
*   **`BGSAVE`** forks a child process using **Copy-on-Write (CoW)** to write snapshots without blocking client commands.
*   **Copy-on-Write** duplicates RAM pages *only* when the main thread writes to them, causing temporary memory spikes.
*   **`appendfsync everysec`** uses a background thread to flush the OS write cache to physical disk every second, limiting data loss to 1-2s.
*   `no-appendfsync-on-rewrite yes` avoids main thread stalls by disabling `fsync` during active child snapshot operations.
*   **AOF Rewrite (`BGREWRITEAOF`)** builds a clean log by writing the current in-memory database state, rather than reading the old AOF.
*   **Hybrid Persistence** writes a fast-loading binary RDB base followed by a durable incremental AOF text log inside the same file.

---

**Next:** [04 — Expiration & Eviction](04-expiration-eviction.md) (TTL, lazy vs active expiry, maxmemory policies, approximated LRU/LFU).
