# Spring Boot Scheduler / CronJob — 8 Production Pitfalls

---

## Pitfall 1 — CronJob with Wrong TimeZone

### Problem
Spring cron expressions fire based on the **JVM's default timezone**, which is typically UTC in containers. A job defined as `"0 0 2 * * *"` (2 AM) will fire at 2 AM UTC — which may be 7:30 AM IST or 9 PM EST depending on where users are.

### What goes wrong
```java
// Fires at 2 AM UTC — not 2 AM IST!
@Scheduled(cron = "0 0 2 * * *")
public void nightlyJob() { ... }
```

### Fix
Always specify the `zone` attribute explicitly:

```java
@Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
public void nightlyJob() { ... }
```

Or externalize both cron and zone to config:
```yaml
# application.yml
app:
  cron:
    nightly: "0 0 2 * * *"
    zone: "Asia/Kolkata"
```

```java
@Scheduled(cron = "${app.cron.nightly}", zone = "${app.cron.zone}")
public void nightlyJob() { ... }
```

### Interview Angle
**Q: What timezone does Spring use for cron by default?**
The JVM default timezone (`TimeZone.getDefault()`), which in Docker/K8s pods is typically UTC. Always pin it with `zone = "Region/City"`.

---

## Pitfall 2 — Missing `@Transactional`

### Problem
A scheduled job reads records, processes them, and writes results — but if any step fails midway, partial writes are committed because there is no transaction boundary.

### What goes wrong
```java
@Scheduled(cron = "0 0 3 * * *")
public void processOrders() {
    List<Order> pending = orderRepo.findByStatus(PENDING);
    for (Order o : pending) {
        process(o);            // may throw
        orderRepo.save(o);     // committed immediately — no rollback if next iteration fails
    }
}
```

If `process(o)` throws on item 50 of 200, items 1–49 are already committed. The DB is now in a half-processed state.

### Fix
```java
@Scheduled(cron = "0 0 3 * * *")
public void processOrders() {
    List<Order> pending = orderRepo.findByStatus(PENDING);
    for (Order o : pending) {
        orderService.processSingle(o);   // delegate to @Transactional method
    }
}

// In OrderService:
@Transactional
public void processSingle(Order o) {
    process(o);
    orderRepo.save(o);
}  // commits here; rolls back if any exception escapes
```

> Note: `@Transactional` on the `@Scheduled` method itself only works if the call goes through the Spring proxy — self-invocation bypasses the proxy, so delegate to another Spring bean.

### Interview Angle
**Q: Can you put `@Transactional` directly on the `@Scheduled` method?**
Yes, but ensure the method is on a separate Spring-managed bean so the AOP proxy wraps it. Within the same class, `this.method()` bypasses the proxy.

---

## Pitfall 3 — Out of Memory (Loading All Records at Once)

### Problem
Calling `findAll()` or fetching a huge result set into a `List` loads every row into the JVM heap simultaneously.

### What goes wrong
```java
@Scheduled(cron = "0 0 3 * * *")
public void syncProducts() {
    List<Product> all = productRepo.findAll(); // 5 million rows → OOM
    all.forEach(this::sync);
}
```

With millions of records, the heap fills up and you get `OutOfMemoryError`.

### Fix — Pagination (chunking)
```java
@Scheduled(cron = "0 0 3 * * *")
@Transactional
public void syncProducts() {
    int page = 0;
    int pageSize = 1000;
    Page<Product> chunk;

    do {
        chunk = productRepo.findAll(PageRequest.of(page++, pageSize));
        chunk.getContent().forEach(this::sync);
    } while (chunk.hasNext());
}
```

### Fix — Spring Data `Stream` / `Slice`
```java
@Transactional(readOnly = true)
public void syncProducts() {
    try (Stream<Product> stream = productRepo.streamAll()) {
        stream.forEach(this::sync);
    }
}
```

The stream cursor fetches rows lazily from the DB — heap usage stays bounded.

### Interview Angle
**Q: What is the right way to process millions of DB rows in a scheduled job?**
Paginate with `Pageable` / `Page`, use `Stream`-based queries, or use Spring Batch's `JpaPagingItemReader` which handles chunk-oriented processing natively.

---

## Pitfall 4 — First-Level Cache Bloat (Hibernate EntityManager)

### Problem
Within a single Hibernate session/transaction, every entity loaded is cached in the **first-level cache** (`EntityManager` / `Session`). When processing thousands of rows in one transaction, the cache grows without bound — even if you paginate the SQL.

### What goes wrong
```java
@Scheduled(cron = "0 0 3 * * *")
@Transactional
public void processAll() {
    int page = 0;
    Page<Order> chunk;

    do {
        chunk = orderRepo.findAll(PageRequest.of(page++, 500));
        chunk.getContent().forEach(this::process); // all 500 entities stay in L1 cache
    } while (chunk.hasNext());
    // After 10k orders: 10k entities pinned in EntityManager heap
}
```

The JPA first-level cache holds a reference to every loaded entity for the duration of the session. Even with pagination, heap usage grows linearly.

### Fix — Flush and Clear the EntityManager periodically
```java
@PersistenceContext
private EntityManager em;

@Scheduled(cron = "0 0 3 * * *")
@Transactional
public void processAll() {
    int page = 0;
    Page<Order> chunk;

    do {
        chunk = orderRepo.findAll(PageRequest.of(page++, 500));
        chunk.getContent().forEach(this::process);

        em.flush();   // write pending changes to DB
        em.clear();   // evict all entities from L1 cache → GC can reclaim
    } while (chunk.hasNext());
}
```

### Interview Angle
**Q: Why does paginating a query still cause memory growth in JPA?**
Because the JPA first-level cache retains every loaded entity within the `EntityManager` session. Without `em.flush()` + `em.clear()` between chunks, all pages accumulate in heap. Flush writes dirty entities to DB; clear evicts them from the cache.

---

## Pitfall 5 — Silent Task Death (No Exception Handling)

### Problem
If an uncaught exception escapes a `@Scheduled` method (or a `ScheduledExecutorService` task), Spring/STPE silently swallows it and the task **stops firing permanently** with no error log.

### What goes wrong
```java
@Scheduled(fixedRate = 5000)
public void fetchMetrics() {
    restTemplate.getForObject(url, String.class); // throws if service is down
    // First failure → task never fires again. No log. No alert.
}
```

### Fix — Wrap in try-catch
```java
@Scheduled(fixedRate = 5000)
public void fetchMetrics() {
    try {
        restTemplate.getForObject(url, String.class);
    } catch (Exception e) {
        log.error("fetchMetrics failed, will retry next tick", e);
        // task continues to fire next tick
    }
}
```

### Fix — Global error handler on `ThreadPoolTaskScheduler`
```java
@Bean
public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(4);
    s.setErrorHandler(t -> log.error("Scheduled task threw unhandled exception", t));
    s.initialize();
    return s;
}
```

### Interview Angle
**Q: What happens if a `@Scheduled` method throws a RuntimeException?**
The exception is swallowed by the scheduler framework and the task silently stops being invoked. Always wrap the body in try-catch, or set a global `ErrorHandler` on the `TaskScheduler` bean.

---

## Pitfall 6 — Huge SQL Round Trips (N+1 / One Insert per Row)

### Problem
Processing N records with N individual `INSERT` / `UPDATE` statements causes N round trips to the DB. Each round trip has network latency overhead — 1 ms × 10,000 rows = 10 seconds of pure network wait.

### What goes wrong
```java
@Scheduled(cron = "0 0 3 * * *")
public void archiveOldOrders() {
    List<Order> old = orderRepo.findOlderThan(cutoff);
    for (Order o : old) {
        o.setStatus(ARCHIVED);
        orderRepo.save(o); // 1 UPDATE per row → N round trips
    }
}
```

### Fix — Batch Insert / Bulk Update
**Option A: JPQL bulk update (single SQL statement)**
```java
@Modifying
@Transactional
@Query("UPDATE Order o SET o.status = 'ARCHIVED' WHERE o.createdAt < :cutoff")
int archiveOlderThan(@Param("cutoff") LocalDate cutoff);
```

**Option B: JDBC batch insert**
```java
jdbcTemplate.batchUpdate(
    "INSERT INTO archive_orders (id, status) VALUES (?, ?)",
    orders,
    500,                        // batch size
    (ps, o) -> {
        ps.setLong(1, o.getId());
        ps.setString(2, o.getStatus().name());
    }
);
```

**Option C: Enable JPA batch writes (Hibernate)**
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 500
        order_inserts: true
        order_updates: true
```

With these properties, `saveAll()` accumulates SQL and sends them in batches.

### Interview Angle
**Q: How do you reduce DB round trips in a batch job?**
Use JPQL `@Modifying` bulk queries (single statement), JDBC `batchUpdate`, or enable Hibernate `jdbc.batch_size`. The goal is to send many changes per network round trip instead of one per row.

---

## Pitfall 7 — Multiple Instances Racing with `PESSIMISTIC_LOCK`

### Problem
When multiple pods run the same scheduled job, they race to process the same rows. Using `PESSIMISTIC_WRITE` lock prevents double-processing, but introduces a new problem: all pods block on the same locked rows and **only one pod does real work** while the rest wait, consuming connections.

### What goes wrong
```
Pod A: SELECT ... FOR UPDATE (acquires lock, processes 1000 rows)
Pod B: SELECT ... FOR UPDATE (BLOCKED — waits for Pod A to release)
Pod C: SELECT ... FOR UPDATE (BLOCKED — waits for Pod A to release)
```

Pods B and C hold DB connections for the entire duration of Pod A's job.

### Fix — Status-based claiming (optimistic)
Mark rows as "claimed" atomically so competing pods skip already-claimed work:

```java
@Modifying
@Transactional
@Query("UPDATE Order o SET o.status = 'PROCESSING', o.claimedBy = :podId " +
       "WHERE o.status = 'PENDING' AND o.id IN :ids")
int claimOrders(@Param("ids") List<Long> ids, @Param("podId") String podId);
```

Each pod claims a disjoint batch — no blocking.

### Fix — Distributed lock (ShedLock)
If only one pod should run the job at all:
```java
@Scheduled(cron = "0 0 3 * * *")
@SchedulerLock(name = "nightlyJob", lockAtMostFor = "10m", lockAtLeastFor = "1m")
public void nightlyJob() { ... }
```

ShedLock uses an optimistic DB row to elect one leader; other pods skip entirely without blocking.

### Interview Angle
**Q: What is the problem with PESSIMISTIC_LOCK when multiple service instances run the same batch job?**
All instances try to acquire the same rows and block each other, holding DB connections for the lock duration. Only one instance does work. Use status-based claiming or a distributed lock (ShedLock) instead.

---

## Pitfall 8 — Blocking Issue with `PESSIMISTIC_LOCK` (Connection Pool Exhaustion)

### Problem
Pessimistic locks require holding a DB connection for the entire lock duration. If many threads (or pods) queue up waiting for the same lock, each holds an open connection from the pool. The pool exhausts, new requests time out, and the entire application becomes unresponsive — even requests unrelated to the scheduled job.

### What goes wrong
```
Connection pool size: 10
Scheduled job holds lock for 60s
9 other threads each grab a connection and block on SELECT ... FOR UPDATE

→ Pool exhausted: every incoming HTTP request fails with "Connection pool timeout"
```

### Fix — `SKIP LOCKED` (PostgreSQL / MySQL 8+)
Instead of blocking, skip rows locked by another transaction:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // -2 = SKIP_LOCKED
@Query("SELECT o FROM Order o WHERE o.status = 'PENDING'")
List<Order> findPendingWithSkipLocked();
```

Or via native query:
```sql
SELECT * FROM orders WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED LIMIT 100
```

Each pod grabs only rows that are not yet locked — no blocking, no connection starvation.

### Fix — Short lock windows
Keep the work done under the lock minimal. Fetch IDs under lock, release, then process:

```java
@Transactional
public List<Long> claimBatch(int batchSize) {
    List<Long> ids = fetchAndLockIds(batchSize);  // short transaction — lock held briefly
    return ids;
}

// Then process ids outside any lock transaction
public void processBatch(List<Long> ids) {
    ids.forEach(this::process);
}
```

### Fix — Decouple with a queue
Instead of having pods compete on the same DB table, push work items to a queue (Kafka / SQS). Pods consume partitions/messages independently — zero locking required.

### Interview Angle
**Q: How can a PESSIMISTIC_LOCK in a scheduled job take down an entire application?**
Each waiting thread holds a DB connection. If more threads are waiting than pool slots available, incoming HTTP requests also can't get connections — the whole app stalls. Fix with `SKIP LOCKED` (so pods take non-contended rows), shorter lock windows, or moving to a queue-based model.

**Q: What does `SKIP LOCKED` do?**
It tells the DB to skip rows that are currently locked by another transaction rather than blocking. Competing consumers each get a disjoint subset of rows — ideal for multi-pod batch processing.

---

## Quick Reference Table

| # | Pitfall | Root Cause | Fix |
|---|---------|-----------|-----|
| 1 | Wrong fire time | No timezone set | `zone = "Region/City"` on `@Scheduled` |
| 2 | Partial writes | No transaction | `@Transactional` on processing method |
| 3 | OOM on load | `findAll()` on huge table | Pagination / `Stream` query |
| 4 | L1 cache bloat | EntityManager retains all loaded entities | `em.flush()` + `em.clear()` per chunk |
| 5 | Silent task death | Uncaught exception swallowed | try-catch inside task or global `ErrorHandler` |
| 6 | Slow batch (N+1) | One SQL per row | Bulk JPQL / JDBC `batchUpdate` / Hibernate batch |
| 7 | Multiple pods race | Pessimistic lock — only one pod works | Status claiming / ShedLock |
| 8 | Connection pool exhaustion | Threads block on lock, hold connections | `SKIP LOCKED` / short lock windows / queue |
