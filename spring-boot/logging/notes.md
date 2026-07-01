# Logging Framework — Spring Boot

## What

A logging framework captures runtime events (messages, errors, timings) from an
application and routes them to one or more destinations (console, file, log
aggregator). Spring Boot's logging stack has three layers:

| Layer | Role |
|-------|------|
| **SLF4J** | Logging *facade* — the API your code calls (`Logger`, `LoggerFactory`). Decouples code from the concrete implementation. |
| **Logback** | Default *implementation* — does the actual work: formatting, filtering, writing to appenders. |
| **Log4j2 / JUL** | Alternative implementations you can swap in by excluding `spring-boot-starter-logging`. |

High-level flow:

```
Your code → SLF4J API → Logback (implementation)
                              ↓
                      [Level filter]
                              ↓
                      [Encoder / Pattern]
                              ↓
                  ConsoleAppender / FileAppender / ...
```

### UML — Low-Level Object Model

```
«interface»
Logger (SLF4J)
  + trace(msg)
  + debug(msg)
  + info(msg)
  + warn(msg)
  + error(msg)
       ▲
       │ implements
ch.qos.logback.classic.Logger
  - name: String           ← logger name
  - level: Level           ← effective level (may be inherited)
  - parent: Logger         ← parent in the hierarchy
  - appenderList           ← where output goes
  - additive: boolean      ← propagation flag
```

`LoggerFactory.getLogger(name)` returns an existing or new `Logger` from the
**logger registry** (a `Map<String, Logger>` inside `LoggerContext`). All
loggers sharing the same context can form a hierarchy via dotted names.

---

## Why

- Without a facade (SLF4J) every library would pick its own framework, causing
  classpath conflicts and duplicate log output. SLF4J bridges them all.
- Logback's level hierarchy lets you silence noisy third-party libraries without
  touching your own code.
- Structured, levelled logs are the foundation of the three observability pillars
  (logs, metrics, traces).

---

## How

### 1 — Dependencies

Spring Boot's `spring-boot-starter` already pulls in `spring-boot-starter-logging`,
which bundles **SLF4J + Logback**. You do **not** add anything extra for basic
logging:

```xml
<!-- Already included transitively — shown for clarity -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

If you want **Log4j2** instead:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-log4j2</artifactId>
</dependency>
```

Lombok's `@Slf4j` is a compile-time shortcut — adds a static `log` field so you
skip the `LoggerFactory.getLogger(...)` boilerplate:

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

### 2 — Simple Logging in a Spring Boot Application

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    // Logger name = fully-qualified class name (conventional)
    private static final Logger log =
        LoggerFactory.getLogger(OrderService.class);

    public void placeOrder(String itemId) {
        log.debug("placeOrder called with itemId={}", itemId);  // cheap — no string concat if level is off
        log.info("Placing order for item {}", itemId);
        try {
            // ... business logic ...
        } catch (Exception e) {
            log.error("Failed to place order for item {}", itemId, e);
        }
    }
}
```

With Lombok:

```java
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrderService {
    public void placeOrder(String itemId) {
        log.info("Placing order for item {}", itemId);
    }
}
```

### 3 — Logger Name and Its Role

The **logger name** is the string key used to look up (or create) a logger in
the `LoggerContext` registry. Convention: use the **fully-qualified class name**
(`LoggerFactory.getLogger(MyClass.class)`).

Why it matters:

- It determines the logger's **position in the hierarchy** — `com.example.order`
  is a child of `com.example`, which is a child of `ROOT`.
- `application.properties` level overrides use these names as keys:
  `logging.level.com.example.order=DEBUG`.
- Log aggregators (Loki, CloudWatch) can filter/query by logger name.

### 4 — Log Levels and Priority

Five standard levels, from most severe to least:

```
ERROR > WARN > INFO > DEBUG > TRACE
```

| Level | Use it for |
|-------|------------|
| `ERROR` | Unrecoverable failures, exceptions that break a workflow |
| `WARN` | Recoverable issues, degraded behaviour, deprecation notices |
| `INFO` | Normal lifecycle events: app startup, request received, job finished |
| `DEBUG` | Detailed diagnostic info useful during development / troubleshooting |
| `TRACE` | Extremely fine-grained: loop iterations, serialisation steps |

A logger set to level `INFO` will emit `INFO`, `WARN`, and `ERROR` — anything
**equal to or more severe** than its configured level.

### 5 — Configuring Log Levels

**In `application.properties` / `application.yml`:**

```properties
# Root logger (default is INFO)
logging.level.root=WARN

# Your own packages
logging.level.com.example=DEBUG

# Third-party packages
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.springframework.web=INFO
```

```yaml
logging:
  level:
    root: WARN
    com.example: DEBUG
    org.hibernate.SQL: DEBUG
```

**At runtime via Spring Actuator** (no restart needed):

```bash
# Read current level
curl http://localhost:8080/actuator/loggers/com.example

# Change level
curl -X POST http://localhost:8080/actuator/loggers/com.example \
     -H 'Content-Type: application/json' \
     -d '{"configuredLevel":"DEBUG"}'
```

**In `logback-spring.xml`** (full Logback config — overrides `application.properties`):

```xml
<configuration>
    <logger name="com.example" level="DEBUG" additivity="false">
        <appender-ref ref="CONSOLE"/>
    </logger>
    <root level="WARN">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### 6 — Parent-Child Logger Hierarchy

Logback builds a tree from logger names split on `.`:

```
ROOT
 └── com
      └── com.example
           ├── com.example.order
           │    └── com.example.order.OrderService
           └── com.example.inventory
```

**Rules:**

1. **Level inheritance** — if a logger has no explicit level configured, it
   inherits from its nearest ancestor that *does* have one. `ROOT` always has a
   level (default `DEBUG` in Logback, but Spring Boot overrides it to `INFO`).

2. **Appender propagation (additivity)** — by default `additive=true`. A log
   event is passed up the hierarchy, so every ancestor's appenders also receive
   it. Setting `additivity="false"` on a logger stops propagation at that node.

**Illustration:**

```
ROOT (INFO) — ConsoleAppender
 └── com.example (DEBUG) — FileAppender, additive=true
      └── com.example.order (no level set) — inherits DEBUG from parent
```

A `DEBUG` log in `OrderService` travels:
`com.example.order` → `com.example` (FileAppender fires) → `ROOT` (ConsoleAppender fires)

Result: the DEBUG message appears in both the file AND the console.

**Advantages of the hierarchy:**

| Advantage | Detail |
|-----------|--------|
| **Inheritance** | Set `com.example=DEBUG` once — all child loggers get DEBUG without individual configuration. |
| **Selective verbosity** | Silence `org.hibernate` at WARN while keeping your own code at DEBUG. |
| **Propagation control** | `additivity=false` isolates a subtree to its own appender (e.g., audit logs to a separate file). |
| **Runtime tunability** | Change a parent's level at runtime via Actuator; all children that inherit from it are affected immediately. |

---

## Code Example

```java
// Parent logger — configured at DEBUG
Logger parentLog = LoggerFactory.getLogger("com.example");

// Child logger — no explicit level; inherits DEBUG from parent
Logger childLog  = LoggerFactory.getLogger("com.example.order");

childLog.debug("This WILL appear — inherits DEBUG from com.example");
childLog.trace("This will NOT appear — TRACE < DEBUG");
```

`application.properties`:

```properties
logging.level.com.example=DEBUG
# com.example.order inherits DEBUG automatically — no extra line needed
```

---

## Interview Angles

**Q: What is SLF4J and why does it exist?**
A: SLF4J is a logging *facade* — a stable API (just interfaces and a factory)
that your code compiles against. The actual implementation (Logback, Log4j2,
JUL) is a runtime dependency. This means you can swap implementations without
touching application code, and libraries can all use SLF4J without forcing a
specific backend on the consuming application.

**Q: What is the default logging framework in Spring Boot and why?**
A: Logback, via `spring-boot-starter-logging`. It was chosen because it is a
mature, native SLF4J implementation (no bridge needed), supports async
appenders, and has a powerful `logback-spring.xml` configuration model including
Spring profile-based `<springProfile>` blocks.

**Q: Explain logger hierarchy and level inheritance.**
A: Logback organises loggers in a tree by splitting names on `.`. `ROOT` is the
root. If a logger has no explicit level, it walks up the tree until it finds an
ancestor with one and inherits that level. This means a single
`logging.level.com.example=DEBUG` applies to every class under that package
without enumerating each one.

**Q: What is appender additivity?**
A: When a log event is emitted, Logback triggers the appenders of that logger,
then — because `additive=true` by default — passes the event to the parent,
which triggers its appenders, and so on up to ROOT. Setting `additivity="false"`
stops propagation at that logger, so the event is only handled by that logger's
own appenders. Use it to route specific packages to dedicated destinations
(audit file, separate alert stream) without duplicating output.

**Q: Why use `log.debug("value={}", x)` instead of `log.debug("value=" + x)`?**
A: The `{}` parameterised form defers string construction. If DEBUG is disabled,
the string concatenation never happens — no `toString()` call, no garbage. At
high throughput with DEBUG off, this avoids measurable CPU and allocation overhead.

**Q: How do you change a log level at runtime without restarting?**
A: Via the Spring Actuator `/actuator/loggers/{name}` endpoint. POST a JSON body
`{"configuredLevel":"DEBUG"}` to change the level live. The change affects all
child loggers that inherit from it. This is essential in production when you need
to temporarily crank up verbosity to diagnose an issue without a redeploy.

**Q: What is the difference between WARN and ERROR?**
A: WARN signals something unexpected that the application handled or worked
around — degraded behaviour, a retry that succeeded, a deprecated config key.
ERROR signals something that broke a workflow and requires attention — an
unhandled exception, a downstream service that is down, data that could not be
persisted. Over-use of ERROR (logging every caught exception as ERROR) causes
alert fatigue; use WARN for recoverable situations.

---

# Appenders

## What

An **Appender** is the component responsible for writing a log event to a
destination — console, file, database, HTTP endpoint, etc. It sits at the end
of the Logback pipeline:

```
Logger → [Level filter] → Encoder (pattern → bytes) → Appender → destination
```

Key points:
- A single logger can have **multiple appenders** (e.g., write to console AND a file simultaneously).
- Appenders fire in the order they are attached.
- With `additivity=true` (default) events propagate up the hierarchy, so parent
  appenders also fire. This can cause **duplicate output** if you are not careful.

---

## How

### logback-spring.xml — Full Syntax

`logback-spring.xml` lives in `src/main/resources/`. Spring Boot loads it
automatically (preferred over plain `logback.xml` because it supports
`<springProfile>` and Spring property substitution via `${}`).

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- ① Define appenders -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- ② Attach to a specific logger -->
    <logger name="com.example" level="DEBUG" additivity="false">
        <appender-ref ref="CONSOLE"/>
    </logger>

    <!-- ③ Root logger catches everything not handled above -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

</configuration>
```

Pattern conversion characters:

| Token | Output |
|-------|--------|
| `%d{HH:mm:ss.SSS}` | Timestamp |
| `%-5level` | Level, left-padded to 5 chars |
| `%thread` | Thread name |
| `%logger{36}` | Logger name, max 36 chars (abbreviated) |
| `%msg` | The log message |
| `%n` | Platform line separator |
| `%X{key}` | MDC value (e.g., `traceId`) |

### Appender defined but NOT attached to any logger

Defining an `<appender>` block in `logback-spring.xml` without an
`<appender-ref>` pointing to it means the appender is instantiated but never
receives any events — it is dead code. You must wire it to at least one
`<logger>` or `<root>`.

### additivity=true vs additivity=false with a logger + appender

```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>app.log</file>
    <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
</appender>

<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
</appender>

<!-- additivity=true (default): event goes to FILE, then propagates up to ROOT's CONSOLE -->
<logger name="com.example" level="DEBUG" additivity="true">
    <appender-ref ref="FILE"/>
</logger>

<!-- additivity=false: event goes ONLY to FILE, stops here -->
<logger name="com.example.audit" level="INFO" additivity="false">
    <appender-ref ref="FILE"/>
</logger>

<root level="INFO">
    <appender-ref ref="CONSOLE"/>
</root>
```

With `additivity=true` a DEBUG log from `com.example` hits the FILE appender
**and** the ROOT's CONSOLE appender — you see it in two places. With
`additivity=false` on `com.example.audit` the event stays in FILE only.

---

### ConsoleAppender

Writes to `System.out` (default) or `System.err`.

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.out</target>   <!-- or System.err -->
    <encoder>
        <pattern>%d{HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

Use-cases: local dev, Docker containers (logs go to stdout → `docker logs`),
Kubernetes (stdout collected by the node log agent).

---

### FileAppender

Writes all output to a **single, fixed file**. The file grows indefinitely —
no rotation, no cleanup. Only suitable for short-lived processes or demos.

```xml
<appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/app.log</file>
    <append>true</append>   <!-- false = truncate on startup -->
    <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

Problem: in production the file can grow to gigabytes. Use
`RollingFileAppender` instead.

---

### RollingFileAppender — Time-Based Rolling Policy

Rolls the log file on a calendar boundary (daily, hourly, etc.) and optionally
deletes old files.

```xml
<appender name="ROLLING_TIME" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>   <!-- active file — always this name -->

    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <!-- %d patterns determine the roll period -->
        <!-- daily: %d{yyyy-MM-dd}  hourly: %d{yyyy-MM-dd_HH} -->
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log</fileNamePattern>
        <maxHistory>30</maxHistory>          <!-- keep last 30 rolled files -->
        <totalSizeCap>1GB</totalSizeCap>     <!-- delete oldest if total exceeds 1 GB -->
    </rollingPolicy>

    <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

At midnight the active `app.log` is renamed to `app.2025-04-22.log` and a fresh
`app.log` starts. After 30 days the oldest rolled file is deleted.

---

### RollingFileAppender — Size and Time Based Rolling Policy

Adds a **per-file size cap** on top of the time-based roll. When the active file
hits `maxFileSize`, it is rolled immediately with an index suffix, without
waiting for the calendar boundary.

```xml
<appender name="ROLLING_SIZE_TIME" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>

    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <!-- %i = file index within the day (0, 1, 2 ...) -->
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
        <maxFileSize>50MB</maxFileSize>      <!-- roll within day when file hits 50 MB -->
        <maxHistory>30</maxHistory>          <!-- keep 30 days worth of files -->
        <totalSizeCap>2GB</totalSizeCap>     <!-- hard cap on total disk usage -->
    </rollingPolicy>

    <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
</appender>
```

Files produced: `app.2025-04-22.0.log`, `app.2025-04-22.1.log` (if >50 MB on same day), etc.

---

### Writing a Custom Appender

Extend `AppenderBase<ILoggingEvent>` (thread-safe, synchronized) or
`UnsynchronizedAppenderBase<ILoggingEvent>` (faster — handle synchronisation
yourself). Override `append(ILoggingEvent event)`.

```java
package com.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class AlertAppender extends AppenderBase<ILoggingEvent> {

    // Optional: configurable field set from logback-spring.xml
    private String webhookUrl;

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    protected void append(ILoggingEvent event) {
        // Called for every log event that passes the level filter
        if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
            // e.g., POST to Slack / PagerDuty
            String message = event.getFormattedMessage();
            // httpClient.post(webhookUrl, message);
            System.out.println("[ALERT] Sending to webhook: " + message);
        }
    }
}
```

Register it in `logback-spring.xml`:

```xml
<appender name="ALERT" class="com.example.logging.AlertAppender">
    <webhookUrl>https://hooks.slack.com/services/...</webhookUrl>
</appender>

<root level="ERROR">
    <appender-ref ref="ALERT"/>
</root>
```

Logback calls `setWebhookUrl(...)` via reflection (JavaBean convention) during
configuration, then calls `start()` and later `append()` for each event.

---

## Interview Angles — Appenders

**Q: What is an Appender and how does it relate to a Logger?**
A: A Logger decides *whether* to emit an event (level check + filter). An
Appender decides *where* the event goes. One logger can have many appenders;
one appender can be shared by many loggers via `<appender-ref>`. Logback's
pipeline: Logger → filter → encoder → appender → destination.

**Q: What is the risk of additivity=true when you attach an appender to a
non-root logger?**
A: Duplicate output. If `com.example` has a `ConsoleAppender` and `additivity=true`,
every event also propagates to ROOT which typically has its own `ConsoleAppender`.
The same log line appears twice. Fix: set `additivity="false"` on the logger
or remove the appender from ROOT.

**Q: When would you use FileAppender vs RollingFileAppender?**
A: FileAppender only for short-lived scripts/tests where file size is bounded.
Production services always use `RollingFileAppender` — without rotation logs
fill the disk, which kills the JVM. `TimeBasedRollingPolicy` is the minimum;
`SizeAndTimeBasedRollingPolicy` adds a safety net for high-throughput services
that could exceed disk limits within a single day.

**Q: Explain TimeBasedRollingPolicy vs SizeAndTimeBasedRollingPolicy.**
A: `TimeBasedRollingPolicy` rolls only on a calendar boundary (e.g., midnight).
A high-throughput service could write 100 GB in one day before the roll happens.
`SizeAndTimeBasedRollingPolicy` adds `maxFileSize` — if the active file hits
that size mid-day it rolls immediately, producing a new file with an incremented
`%i` index. It gives both time-based archiving and a per-file size bound.

**Q: What is `totalSizeCap` and why does it matter?**
A: `totalSizeCap` limits the total disk space used by all rolled files managed
by the policy. When the cap is exceeded Logback deletes the oldest rolled files.
Without it `maxHistory` only limits the *count* of files — if each file is 50 MB
and you keep 100 of them you still use 5 GB. `totalSizeCap` is the absolute disk
safety valve.

**Q: How do you write a custom Appender in Logback?**
A: Extend `AppenderBase<ILoggingEvent>` and override `append(ILoggingEvent)`.
Logback calls `append` for every event after level-filtering. Configurable
properties are set via JavaBean setters called during XML parsing. Wire it in
`logback-spring.xml` using the fully-qualified class name in `class=`. Common
use-cases: push ERRORs to Slack/PagerDuty, write structured JSON to Kafka,
publish metrics to a monitoring system.

**Q: Why use `logback-spring.xml` instead of `logback.xml`?**
A: `logback-spring.xml` is processed by Spring Boot's logging initialiser, which
adds two features: `<springProfile name="dev">` blocks that activate per Spring
profile, and `${spring.application.name}` property substitution from
`application.properties`. Plain `logback.xml` is loaded by Logback directly
before Spring context starts, so neither feature is available.

---

# Async Logging

## What

Synchronous appenders write to their destination (disk, socket) on the
**application thread** — the thread that called `log.info(...)` blocks until the
I/O completes. Async logging offloads that I/O to a background thread via a
queue, so the application thread returns almost immediately.

Two distinct mechanisms exist depending on the framework:

| Mechanism | Framework | How it works |
|-----------|-----------|--------------|
| **AsyncAppender** | Logback | Wraps *any* existing appender; puts events in a `BlockingQueue`; one background thread drains it |
| **AsyncLogger** | Log4j2 | Logger-level async via LMAX Disruptor ring buffer; much higher throughput |

Spring Boot ships Logback by default → use **AsyncAppender**.
Switch to Log4j2 to get **AsyncLogger** with Disruptor.

---

## Why

Synchronous file writes are ~1–10 ms each. At 1000 log calls/sec that is
1–10 s of thread time *per second* wasted on I/O. Async logging reduces
application-thread latency to microseconds and prevents logging from becoming
a throughput bottleneck.

---

## How

### AsyncAppender (Logback)

```xml
<!-- 1. Define the real (delegate) appender -->
<appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
    </encoder>
</appender>

<!-- 2. Wrap it in AsyncAppender -->
<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>512</queueSize>          <!-- default 256; tune to burst size -->
    <discardingThreshold>0</discardingThreshold>  <!-- 0 = never discard; default 20% = discards TRACE/DEBUG/INFO when queue 80% full -->
    <neverBlock>false</neverBlock>      <!-- false = block when queue full; true = drop silently -->
    <includeCallerData>false</includeCallerData>  <!-- true = expensive stack walk for %caller token -->
    <appender-ref ref="FILE"/>          <!-- delegate to the real appender -->
</appender>

<!-- 3. Wire to root -->
<root level="INFO">
    <appender-ref ref="ASYNC_FILE"/>
</root>
```

Key knobs:

| Property | Default | Effect |
|----------|---------|--------|
| `queueSize` | 256 | Queue capacity. Increase for bursty traffic. |
| `discardingThreshold` | 20 | % full at which TRACE/DEBUG/INFO are *silently dropped*. Set `0` to never discard. |
| `neverBlock` | false | If `true`, drops events instead of blocking the caller when the queue is full. |
| `includeCallerData` | false | Compute `%caller` / line numbers — expensive; leave false unless you need it. |

**Behaviour when queue fills up:**
- `neverBlock=false` (default): application thread **blocks** until space is available — latency spikes but no data loss.
- `neverBlock=true`: event **dropped** silently — no latency spike, but logs are lost.

### AsyncLogger (Log4j2) — for reference

```xml
<!-- log4j2.xml -->
<AsyncLogger name="com.example" level="DEBUG" additivity="false">
    <AppenderRef ref="RollingFile"/>
</AsyncLogger>
```

Uses LMAX Disruptor — a lock-free ring buffer. Benchmarks show 6–68× higher
throughput vs synchronous. Works at the **Logger** level, not the appender level.
Requires `log4j-core` + `disruptor` on the classpath.

---

## Interview Angles — Async Logging

**Q: What problem does AsyncAppender solve?**
A: It decouples the application thread from I/O. Synchronous appenders write to
disk on the calling thread; if the disk is slow or the log file is on a network
share, every log call adds latency to the request path. AsyncAppender puts the
event in a queue and returns immediately; a background thread does the actual
write. This trades a small amount of memory (the queue) and the risk of losing
in-flight events on crash for lower p99 request latency.

**Q: What happens to logs if the application crashes with an AsyncAppender?**
A: Events still in the queue are lost. Logback's shutdown hook flushes the queue
on a graceful JVM exit, but a hard kill (`kill -9`) or OOM crash loses whatever
is queued. If zero-loss is required use a synchronous appender or ensure the
queue is drained before shutdown (set `discardingThreshold=0` and keep
`neverBlock=false` so the queue must be drained before the app exits).

**Q: What is `discardingThreshold` and when would you change it?**
A: When the queue reaches `(100 - discardingThreshold)%` full, Logback starts
silently dropping TRACE, DEBUG, and INFO events to protect the queue from
overflowing and blocking the application. The default is 20 (drop below-WARN
when 80% full). Set it to `0` to never discard — the queue can fill to 100%,
after which the application thread blocks (or drops, if `neverBlock=true`). Set
to `0` when log completeness matters more than latency; leave at default for
latency-sensitive services.

**Q: AsyncAppender vs AsyncLogger — which is better?**
A: AsyncLogger (Log4j2 + Disruptor) wins on raw throughput — it is lock-free and
operates at the logger level, batching events more efficiently. AsyncAppender is
simpler to add to an existing Logback setup (just wrap any appender). For most
Spring Boot services AsyncAppender is sufficient; switch to Log4j2 AsyncLogger
only when profiling shows logging is a measurable bottleneck.

---

# Filters in Appenders

## What

A **Filter** sits between the logger's level check and the appender's `append()`
call. It lets an appender accept or reject individual log events based on
criteria beyond the logger's effective level.

Logback filter chain decisions:

| Decision | Meaning |
|----------|---------|
| `ACCEPT` | Let the event through; stop evaluating further filters |
| `DENY` | Drop the event; stop evaluating further filters |
| `NEUTRAL` | Pass to the next filter in the chain; if no more filters, ACCEPT |

---

## How

### LevelFilter — exact level match

Accept only `ERROR`; drop everything else:

```xml
<appender name="ERROR_ONLY" class="ch.qos.logback.core.ConsoleAppender">
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
        <level>ERROR</level>
        <onMatch>ACCEPT</onMatch>
        <onMismatch>DENY</onMismatch>
    </filter>
    <encoder>
        <pattern>%d %-5level %logger - %msg%n</pattern>
    </encoder>
</appender>
```

### ThresholdFilter — minimum level

Accept `WARN` and above; drop DEBUG/INFO:

```xml
<appender name="WARN_UP" class="ch.qos.logback.core.ConsoleAppender">
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>WARN</level>   <!-- NEUTRAL below threshold → DENY; ACCEPT at/above -->
    </filter>
    <encoder>
        <pattern>%d %-5level %logger - %msg%n</pattern>
    </encoder>
</appender>
```

### Multiple Filters (chained)

Filters evaluate in order. First non-NEUTRAL decision wins:

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <!-- 1. Drop TRACE entirely -->
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
        <level>TRACE</level>
        <onMatch>DENY</onMismatch>
        <onMismatch>NEUTRAL</onMismatch>
    </filter>
    <!-- 2. Accept everything else -->
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>DEBUG</level>
    </filter>
    <encoder>...</encoder>
</appender>
```

### Custom Filter

Extend `Filter<ILoggingEvent>`:

```java
public class SensitivePackageFilter extends Filter<ILoggingEvent> {
    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event.getLoggerName().startsWith("com.example.payment")) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
```

```xml
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <filter class="com.example.logging.SensitivePackageFilter"/>
    <encoder>...</encoder>
</appender>
```

---

## Interview Angles — Filters

**Q: What is the difference between a logger level and an appender filter?**
A: The logger level is a coarse gate: events below the effective level are
discarded *before* any appender sees them. An appender filter is a fine-grained
gate *inside* the appender: it can accept or deny events that already passed the
logger level. Use logger levels for broad control (silence third-party DEBUG);
use filters for targeted routing (only ERROR events go to PagerDuty appender,
only audit events go to the audit file appender).

**Q: Difference between LevelFilter and ThresholdFilter?**
A: `LevelFilter` matches one *exact* level — you specify `onMatch` and
`onMismatch` decisions, giving full control. `ThresholdFilter` is a minimum-level
gate: it returns NEUTRAL (pass) for events at or above the threshold and DENY for
events below — simpler, but no per-level routing.

---

# Log Format and Pattern

## What

A **pattern** is the format string used by Logback's `PatternLayoutEncoder` to
convert a log event into a human-readable string.

---

## How

### Common Pattern Tokens

| Token | Output | Notes |
|-------|--------|-------|
| `%d{HH:mm:ss.SSS}` | Timestamp | `%d` alone uses ISO-8601 |
| `%p` / `%-5level` | Log level | `%-5` left-aligns in 5-char field |
| `%t` / `%thread` | Thread name | |
| `%logger{36}` | Logger name | Abbreviated to 36 chars max |
| `%msg` / `%m` | Message | |
| `%n` | OS line separator | Always end patterns with `%n` |
| `%ex` | Stack trace | Appended automatically with `%msg%n` |
| `%X{key}` | MDC value | e.g., `%X{traceId}` |
| `%clr(%d){faint}` | Coloured output (console) | Spring Boot default uses `%clr` |
| `%relative` | Ms since JVM start | Lightweight alternative to `%d` |

### Spring Boot Default Pattern

```
%clr(%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}){faint} %clr(%5p) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n%wEx
```

Override in `application.properties`:

```properties
logging.pattern.console=%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n
logging.pattern.file=%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n
```

Or directly in `logback-spring.xml`:

```xml
<property name="LOG_PATTERN"
          value="%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} [%X{traceId}] - %msg%n"/>

<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>${LOG_PATTERN}</pattern>
    </encoder>
</appender>
```

---

# Placeholders in Log Statements

## What

SLF4J supports **parameterised logging** using `{}` placeholders. The message
template and arguments are kept separate; Logback only interpolates them if the
log event will actually be emitted.

## Why

String concatenation is evaluated *before* calling the logger — even if the
level is disabled:

```java
// BAD — toString() + concat always runs, even when DEBUG is off
log.debug("Processing order: " + order.toString());

// GOOD — toString() deferred; never called if DEBUG is disabled
log.debug("Processing order: {}", order);
```

## How

```java
// Single placeholder
log.info("Order {} placed successfully", orderId);

// Multiple placeholders
log.info("Order {} placed by user {} at {}", orderId, userId, Instant.now());

// Exception — always the LAST argument, no placeholder for it
// Logback recognises a Throwable final arg and appends the full stack trace
log.error("Failed to process order {}", orderId, ex);

// Array/varargs (more than 2 params are automatically varargs)
log.debug("Step {}/{} — item={} qty={}", step, total, item, qty);
```

**Rule:** the exception (`Throwable`) must be the **last** argument and must
**not** have a corresponding `{}`. Logback detects it by type and prints the
full stack trace after the message.

---

## Interview Angles — Patterns and Placeholders

**Q: Why use `{}` placeholders instead of string concatenation in log calls?**
A: `log.debug("val={}", x)` defers the `toString()` and string construction to
inside Logback's pipeline, where the level check has already happened. If DEBUG
is off, the string is never built — saving CPU and reducing garbage, which matters
at high call rates. Concatenation (`"val=" + x`) runs unconditionally on the
caller's thread regardless of level.

**Q: How do you log an exception with SLF4J?**
A: Pass the `Throwable` as the **last** argument with no corresponding `{}`
placeholder: `log.error("Failed for id {}", id, ex)`. Logback recognises the
trailing Throwable by type and appends the full stack trace. Adding a placeholder
for the exception would print only its `toString()` and suppress the trace.
