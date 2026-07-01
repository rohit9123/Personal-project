# Spring Boot Scheduling — @EnableScheduling

---

## 1. What

Spring's scheduling abstraction wraps `ScheduledThreadPoolExecutor` behind annotations and a `TaskScheduler` bean, so you declare *what* runs and *when* without managing thread pools manually.

Enable it once per application:

```java
@Configuration
@EnableScheduling
public class SchedulerConfig { }
```

Then annotate any Spring-managed bean method with `@Scheduled`.

---

## 2. Fixed Rate

```java
@Scheduled(fixedRate = 5000)           // every 5 000 ms from previous *start*
public void fixedRateTask() { ... }

@Scheduled(fixedRate = 5, timeUnit = TimeUnit.SECONDS)   // same, readable form
public void fixedRateTaskTyped() { ... }

@Scheduled(fixedRateString = "${app.scheduler.rate-ms}") // from properties
public void fixedRateFromConfig() { ... }
```

**Timeline** (`rate=5s`, task takes `2s`):

```
t=0  ──[task 2s]──  t=2
t=5  ──[task 2s]──  t=7
t=10 ──[task 2s]──  t=12
```

Next start = previous *start* + rate. If the task exceeds the rate, next run fires immediately after completion (no overlap — single thread by default).

**When to use:** Metrics polling, heartbeat pings, cache warm-up ticks — where you care about a consistent wall-clock cadence.

---

## 3. Fixed Delay

```java
@Scheduled(fixedDelay = 5000)           // 5s after previous *end*
public void fixedDelayTask() { ... }

@Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
public void fixedDelayTaskTyped() { ... }

@Scheduled(fixedDelayString = "${app.scheduler.delay-ms}")
public void fixedDelayFromConfig() { ... }
```

**Timeline** (`delay=5s`, task takes `2s`):

```
t=0  ──[task 2s]──  t=2
                    |── 5s ──|
                    t=7 ──[task 2s]──  t=9
                                       |── 5s ──|
                                       t=14 ──[task 2s]──  t=16
```

Next start = previous *end* + delay. The gap between runs is always at least `delay`, regardless of how long the task takes.

**When to use:** Inbox polling, DB cleanup sweeps, any task where you must wait for the previous run to finish before starting the next (prevents double-processing).

---

## 4. One-Time Scheduling

`@Scheduled` has no built-in one-time mode. Use `TaskScheduler` directly — inject it and call `schedule(Runnable, Instant)`.

```java
@Component
public class OneTimeTask {

    private final TaskScheduler taskScheduler;

    public OneTimeTask(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    // Fires once, 10 seconds after application startup
    @PostConstruct
    public void scheduleOnce() {
        Instant fireAt = Instant.now().plusSeconds(10);
        taskScheduler.schedule(this::runOnce, fireAt);
    }

    private void runOnce() {
        System.out.println("[one-time] fired at " + Instant.now());
    }
}
```

You can also schedule from a REST endpoint or a business event handler — just inject `TaskScheduler` wherever needed.

---

## 5. What is a Cron Job

A **cron job** is a task triggered by a **cron expression** — a compact string that encodes a schedule as a combination of time fields. The name comes from the Unix `cron` daemon.

Spring supports cron expressions in `@Scheduled(cron = "...")`. Internally Spring parses the expression into a `CronTrigger` which computes the next fire time from the current time.

---

## 6. Cron Expression Syntax

Spring cron uses **6 fields** (unlike Unix cron's 5 — Spring adds seconds):

```
  ┌──────────── second       (0–59)
  │ ┌──────────── minute      (0–59)
  │ │ ┌──────────── hour       (0–23)
  │ │ │ ┌──────────── day-of-month  (1–31)
  │ │ │ │ ┌──────────── month       (1–12 or JAN–DEC)
  │ │ │ │ │ ┌──────────── day-of-week   (0–7 or SUN–SAT; 0 and 7 = Sunday)
  │ │ │ │ │ │
  * * * * * *
```

### Special characters

| Character | Meaning | Example |
|-----------|---------|---------|
| `*` | Every value in the field | `* * * * * *` — every second |
| `?` | No specific value (day-of-month or day-of-week only) | `0 0 9 * * ?` — 9 AM every day |
| `-` | Range | `MON-FRI` — weekdays |
| `,` | List | `1,15` — 1st and 15th |
| `/` | Step | `0/15` — every 15 units starting at 0 |
| `L` | Last | `L` in day-of-month = last day of month |
| `W` | Nearest weekday to given day | `15W` — nearest weekday to 15th |
| `#` | Nth occurrence of weekday | `2#3` — 3rd Tuesday |

> Spring uses `?` to disambiguate day-of-month vs day-of-week (only one can be specified at a time in many implementations).

---

## 7. Practical Cron Expression Samples

```
Expression              Meaning
──────────────────────────────────────────────────────────────────
0 * * * * *             Every minute (at second 0)
0 */5 * * * *           Every 5 minutes
0 0 * * * *             Every hour (top of the hour)
0 0 9 * * *             Every day at 09:00:00
0 0 9 * * MON-FRI       Weekdays at 09:00
0 0 9 1 * *             1st of every month at 09:00
0 0 9 L * *             Last day of every month at 09:00
0 0 0 1 1 *             1st January at midnight (yearly)
0 0/30 9-17 * * MON-FRI Every 30 min between 09:00–17:00, weekdays
0 0 9,18 * * *          09:00 and 18:00 every day
0 15 10 ? * 6#3         10:15 on the 3rd Saturday of every month
0 0 12 1,15 * ?         Noon on the 1st and 15th of every month
```

### In code

```java
@Scheduled(cron = "0 0 9 * * MON-FRI")
public void morningReport() {
    System.out.println("Morning report at " + Instant.now());
}

@Scheduled(cron = "0 0 0 L * *")
public void endOfMonthInvoice() {
    System.out.println("End-of-month invoice job");
}

// From application.properties: app.cron.cleanup=0 0 2 * * *
@Scheduled(cron = "${app.cron.cleanup}")
public void nightlyCleanup() {
    System.out.println("Nightly cleanup at " + Instant.now());
}
```

---

## 8. Custom Thread Pool (must-have in production)

By default Spring creates a **single-threaded** executor for `@Scheduled`. All scheduled tasks share one thread — one slow task blocks every other.

```java
@Configuration
@EnableScheduling
public class SchedulerConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(taskScheduler());
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService taskScheduler() {
        return Executors.newScheduledThreadPool(4);   // 4 concurrent scheduled tasks
    }
}
```

Or with Spring's `ThreadPoolTaskScheduler` (gives thread naming and monitoring):

```java
@Bean
public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(4);
    s.setThreadNamePrefix("sched-");
    s.setErrorHandler(t -> log.error("Scheduled task failed", t));
    s.initialize();
    return s;
}
```

---

## 9. Interview Angles

**Q: What does `@EnableScheduling` do?**
It registers a `ScheduledAnnotationBeanPostProcessor` which scans all beans for `@Scheduled` methods and registers them with a `TaskScheduler` after the context is fully initialized.

**Q: How many threads does Spring use by default for scheduling?**
One. All `@Scheduled` tasks share a single thread unless you configure a `TaskScheduler` or `ScheduledExecutorService` bean.

**Q: Difference between `fixedRate` and `fixedDelay` in Spring?**
`fixedRate` anchors the next start to the previous *start* — good for wall-clock consistent ticks. `fixedDelay` anchors to the previous *end* — good for preventing task overlap when execution time varies.

**Q: How do you run a task exactly once at startup (or after a delay)?**
Inject `TaskScheduler` and call `schedule(runnable, Instant)`. `@Scheduled` has no built-in one-time mode.

**Q: What happens if an exception is thrown from a `@Scheduled` method?**
The exception is swallowed by default and the task continues to be scheduled normally. With the default single-thread executor you won't see the stack trace unless you add error handling via `setErrorHandler` on the `TaskScheduler`.

**Q: How do you read the cron zone from config?**
`@Scheduled(cron = "${expr}", zone = "Asia/Kolkata")` — the `zone` attribute accepts a `ZoneId` string and defaults to the server timezone.

**Q: Fields in Spring cron vs Unix cron?**
Spring's extra `second` field is the leftmost.

---

## 10. Distributed Scheduling (ShedLock)

**The Problem:** In a cloud environment, you run multiple instances (pods) of the same microservice. Each instance starts its own `@Scheduled` thread. If a job runs at 2:00 AM, **all instances run it simultaneously**, duplicating work and causing DB race conditions.

**The Solution: ShedLock.**
ShedLock ensures that at most one instance runs a task at the same time. It uses an external lock (DB table, Redis, ZK).

### Implementation:

1. Add dependency: `net.javacrumbs.shedlock:shedlock-spring` + provider (e.g., JDBC).
2. Enable it:
```java
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class LockConfig {
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource);
    }
}
```

3. Annotate method:
```java
@Scheduled(cron = "0 0 2 * * *")
@SchedulerLock(name = "nightlyJob", lockAtMostFor = "5m", lockAtLeastFor = "1m")
public void nightlyJob() {
    // logic
}
```
*   `lockAtMostFor`: Failsafe. If the pod dies, lock expires after 5m so other pods can take over.
*   `lockAtLeastFor`: Prevents clock-drift issues where a pod finishes in 1s, releases lock, and another pod picks it up and runs it again.

---

## 11. Advanced Edge Cases

### A. Combining `@Scheduled` and `@Async`
*   **Default `@Scheduled`:** Execution never overlaps (single-threaded by default).
*   **With `@Async`:** The scheduler kicks off a new thread from the `@Async` thread pool every time the trigger fires. **Tasks will execute in parallel** even if the previous one is stuck. Use caution!

### B. Conditional Scheduling
To disable schedulers in test environments or local machines:
```java
@Component
@ConditionalOnProperty(value = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MyTask { ... }
```

---

## 12. Expanded Interview Angles

**Q: How do you prevent multiple instances of a microservice from running the same `@Scheduled` task?**
A: Use a distributed locking library like **ShedLock**. The pods synchronize against a central lock store (e.g., Redis, DB). Only one instance acquires the lock per time window.

**Q: What happens if you combine `@Scheduled` and `@Async`?**
A: The scheduler acts purely as a trigger, delegating execution to Spring's `@Async` thread pool. This bypasses the default sequential STPE constraint and allows tasks to run concurrently.

**Q: Why is `lockAtLeastFor` important in ShedLock?**
A: It prevents a fast task from completing and releasing the lock before other instances have evaluated their own cron triggers (due to slight clock drift). This ensures the job only runs *exactly* once.


