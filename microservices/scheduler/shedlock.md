# ShedLock — Distributed Scheduler Lock

---

## 1. What

**ShedLock** is a library that guarantees at most one instance of a Spring-scheduled task runs at a time across a cluster of JVM processes.

It does this by coordinating through a shared external store — a DB table, Redis key, or ZooKeeper node. Before a task fires, each pod attempts to atomically claim a named lock. Only the winner executes; the rest skip silently.

```
Pod A  ──── @Scheduled fires ──── claims lock ──── executes job
Pod B  ──── @Scheduled fires ──── lock taken  ──── SKIPPED
Pod C  ──── @Scheduled fires ──── lock taken  ──── SKIPPED
```

ShedLock is **not** a full job scheduler — it does not redistribute work or queue executions. Every pod still fires its own `@Scheduled` trigger; ShedLock only ensures at most one of them actually runs the body.

---

## 2. Why

### The multi-pod problem

In Kubernetes or any cloud environment you run N replicas of the same service. Every replica starts its own Spring scheduler. A `@Scheduled(cron = "0 0 2 * * *")` job fires on **all N pods simultaneously**, causing:

| Consequence | Example |
|-------------|---------|
| Duplicate DB writes | Invoice generated N times |
| Race conditions | Inventory decremented by all pods |
| External API spam | N calls to a payment gateway |
| Wasted compute | Identical report computed N times |

### Why not ShedLock for everything?

ShedLock adds latency (one extra DB round trip per execution) and a dependency on an external store. Use it only for jobs where duplicate execution is harmful. Idempotent jobs (e.g., cache warm-up) do not need it.

---

## 3. How It Works Internally

### The lock table

ShedLock maintains a single DB table:

```sql
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,   -- job name (unique per task)
    lock_until TIMESTAMP(3) NOT NULL,   -- when the lock expires (failsafe)
    locked_at  TIMESTAMP(3) NOT NULL,   -- when this pod acquired the lock
    locked_by  VARCHAR(255) NOT NULL,   -- pod hostname / UUID
    PRIMARY KEY (name)
);
```

One row per unique job name. The row persists between executions — it is updated, not deleted.

### Lock acquisition (atomic compare-and-swap)

When a task fires ShedLock executes two SQL statements — only one of them wins:

```sql
-- 1. First attempt: INSERT (succeeds only if no row exists yet)
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('reportJob', NOW() + INTERVAL '30 SECOND', NOW(), 'pod-abc');

-- 2. If INSERT fails (row exists), try to steal an *expired* lock:
UPDATE shedlock
   SET lock_until = NOW() + INTERVAL '30 SECOND',
       locked_at  = NOW(),
       locked_by  = 'pod-abc'
 WHERE name = 'reportJob'
   AND lock_until <= NOW();   -- only wins if the previous lock has expired
```

`UPDATE` returns the number of affected rows. If 1 → lock acquired. If 0 → lock held by another pod → skip.

The `WHERE lock_until <= NOW()` clause is what makes this safe: competing pods cannot steal a live lock, only an expired one.

### Lock release

After the job body completes, ShedLock updates the row:

```sql
-- Release: set lock_until to NOW() + lockAtLeastFor
-- (or NOW() if lockAtLeastFor = 0)
UPDATE shedlock
   SET lock_until = NOW() + INTERVAL '5 SECOND'   -- lockAtLeastFor
 WHERE name = 'reportJob' AND locked_by = 'pod-abc';
```

Other pods can now acquire the lock once `lock_until` passes.

### Crash safety (`lockAtMostFor`)

If the executing pod crashes after acquiring the lock but before releasing it, the row is never updated. `lockAtMostFor` is the failsafe: any pod can steal the lock once `lock_until <= NOW()`, which happens at most `lockAtMostFor` after acquisition.

```
Pod A acquires lock at T=0, lockAtMostFor=30s → lock_until = T+30s
Pod A crashes at T=10s — lock never released
Pod B fires at T=35s → lock_until <= NOW() → UPDATE wins → Pod B runs
```

Without `lockAtMostFor` a pod crash would freeze that job permanently.

### Timeline: `lockAtMostFor` vs `lockAtLeastFor`

```
lockAtMostFor = "PT30S"   (30s failsafe — what if pod crashes?)
lockAtLeastFor = "PT5S"   (5s minimum hold — prevents clock-drift double-run)

t=0   Pod A acquires lock. lock_until = now+30s
t=3   Job completes.       lock_until updated to now+5s = t+8s
t=8   lock_until passes.   Other pods can now acquire.

Without lockAtLeastFor:
t=3   Job completes.       lock_until = now (released immediately)
t=3   Pod B fires (1ms later, due to clock drift) → steals lock → runs again!
```

`lockAtLeastFor` absorbs clock skew between pods. Set it to at least `2 × max clock drift`.

---

## 4. Setup

### Step 1 — Dependency

```xml
<properties>
    <shedlock.version>5.10.2</shedlock.version>
</properties>

<dependencies>
    <!-- ShedLock Spring integration -->
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
        <version>${shedlock.version}</version>
    </dependency>

    <!-- JDBC lock provider (uses JdbcTemplate) -->
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
        <version>${shedlock.version}</version>
    </dependency>
</dependencies>
```

### Step 2 — Create the lock table

```sql
-- src/main/resources/schema.sql
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

### Step 3 — Configure `LockProvider` + enable

```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")  // global failsafe: 10 min
public class LockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()   // use DB server clock — avoids pod clock drift
                .build()
        );
    }
}
```

`usingDbTime()` is critical in multi-region setups where pod clocks can differ by seconds.

### Step 4 — Annotate each job

```java
@Component
public class ReportJob {

    private static final Logger log = LoggerFactory.getLogger(ReportJob.class);

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(
        name          = "generateReport",   // must be unique across all jobs
        lockAtMostFor = "PT55S",            // < fixedDelay — ensures cleanup before next run
        lockAtLeastFor = "PT5S"             // absorbs clock skew
    )
    public void generateReport() {
        LockAssert.assertLocked();  // throws if ShedLock wiring is broken
        log.info("Generating report...");
        // ... actual work
    }
}
```

---

## 5. Annotation Reference

| Attribute | Type | Description |
|-----------|------|-------------|
| `name` | String | Unique lock name. Row key in the `shedlock` table. |
| `lockAtMostFor` | ISO-8601 duration | **Failsafe TTL.** Lock expires after this, even if pod crashes. |
| `lockAtLeastFor` | ISO-8601 duration | **Minimum hold.** Prevents re-acquisition due to clock drift. |

### `@EnableSchedulerLock.defaultLockAtMostFor`

Sets the global `lockAtMostFor` for any `@SchedulerLock` that does not specify its own. Always override per-job to match each job's expected duration.

### Rule of thumb

```
lockAtMostFor  > max expected job duration   (gives pod time to finish before failsafe fires)
lockAtLeastFor ≥ 2 × max clock skew between pods  (typically 5s–30s)
lockAtMostFor  < fixedDelay                  (lock clears before next trigger, no self-blocking)
```

---

## 6. Lock Providers

| Provider | Dependency artifact | Notes |
|----------|---------------------|-------|
| JDBC (any RDBMS) | `shedlock-provider-jdbc-template` | Works with any DB that has a `shedlock` table |
| Redis | `shedlock-provider-redis-spring` | Good if DB is not available |
| MongoDB | `shedlock-provider-mongo` | Uses a `shedlock` collection |
| ZooKeeper | `shedlock-provider-zookeeper-curator` | Strong consistency, complex setup |
| In-memory | `shedlock-provider-inmemory` | Tests only — no cross-JVM coordination |

All providers implement the same `LockProvider` interface — swapping is a one-line config change.

---

## 7. LockAssert

```java
LockAssert.assertLocked();
```

Throws `IllegalStateException` if the calling thread does not hold a ShedLock lock. Add this at the start of every `@SchedulerLock` method body to catch misconfiguration early (e.g., forgetting `@EnableSchedulerLock`, calling the method directly in tests).

In unit tests, stub it:
```java
LockAssert.TestOnly.makeAllAssertsPass(true);
```

---

## 8. Interview Angles

**Q: What problem does ShedLock solve?**
In a clustered service, every pod fires its own `@Scheduled` trigger simultaneously. ShedLock elects one winner per job per trigger window using a shared lock store, preventing duplicate execution.

**Q: How does ShedLock acquire a lock atomically?**
It attempts an INSERT for the job row. If the row exists (lock held), it tries an UPDATE with the condition `lock_until <= NOW()`. Only the pod whose UPDATE affects 1 row acquires the lock. This is a DB-level compare-and-swap — no application-level mutex needed.

**Q: What is `lockAtMostFor` for?**
Crash safety. If the pod holding the lock dies before releasing it, the row is never updated and the lock would be held forever. `lockAtMostFor` caps the lock TTL: once `lock_until` passes, any other pod can claim it.

**Q: What is `lockAtLeastFor` for?**
Clock-drift protection. Without it, a fast-running job could release the lock while another pod with a slightly ahead clock is already trying to acquire it, causing a double-run within the same trigger window. `lockAtLeastFor` keeps the lock held long enough to absorb skew.

**Q: Why use `usingDbTime()`?**
Pod clocks may drift by seconds in multi-AZ or multi-region deployments. `usingDbTime()` uses the DB server's clock for `locked_at` and `lock_until` comparisons, so all pods operate on a single authoritative clock.

**Q: Does ShedLock guarantee exactly-once execution?**
No — it guarantees **at-most-once** per trigger window. If all pods crash during execution, the job may not run at all for that window. For exactly-once semantics you need idempotent jobs + a persistent job log.

**Q: What happens if `lockAtMostFor` is less than the job's actual run time?**
The lock expires while the job is still running. Another pod acquires the lock and also runs the job — the guarantee breaks. Always set `lockAtMostFor` higher than the worst-case job duration.

**Q: ShedLock vs database-level `PESSIMISTIC_LOCK` for preventing duplicate batch processing?**
`PESSIMISTIC_LOCK` makes competing pods block on locked rows — connection pool exhaustion risk. ShedLock skips competing pods entirely; they return immediately without holding a connection. ShedLock is the right tool for "should only one pod run this?" Pessimistic locking is for "should only one pod process this specific row?"
