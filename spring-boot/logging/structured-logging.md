# Structured Logging (JSON)

## What

**Structured logging** outputs each log event as a machine-parseable document
(typically JSON) instead of a freeform string. Every field (timestamp, level,
logger, message, MDC values, custom keys) becomes a top-level JSON key, making
log aggregators (ELK, Loki, Splunk, CloudWatch Insights) able to filter, query,
and aggregate by field without regex parsing.

---

## Why

Plain text logs require fragile regex to extract fields. Structured JSON lets
you query:

```
level:"ERROR" AND service:"order-service" AND orderId:"12345"
```

…without any parsing configuration.

---

## How

### Dependency — `logstash-logback-encoder`

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

### Swap the encoder in `logback-spring.xml`

```xml
<appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>

<root level="INFO">
    <appender-ref ref="JSON_CONSOLE"/>
</root>
```

Each log line is now a single JSON object:

```json
{
  "@timestamp": "2025-04-22T10:30:00.123Z",
  "@version": "1",
  "message": "Order 42 placed successfully",
  "logger_name": "com.example.OrderService",
  "thread_name": "http-nio-8080-exec-1",
  "level": "INFO",
  "level_value": 20000,
  "traceId": "abc123"   // ← MDC fields appear automatically
}
```

### Profile-based switching (text in dev, JSON in prod)

```xml
<springProfile name="dev">
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>   <!-- plain text -->
    </root>
</springProfile>

<springProfile name="prod">
    <root level="INFO">
        <appender-ref ref="JSON_CONSOLE"/>   <!-- JSON -->
    </root>
</springProfile>
```

---

# Dynamic Fields in JSON Logs

## What

Beyond MDC values, `logstash-logback-encoder` lets you attach **per-event
structured fields** directly at the log call site using `StructuredArguments`
and `Markers`.

---

## How

### StructuredArguments — inline key-value pairs

```java
import static net.logstash.logback.argument.StructuredArguments.*;

// kv("key", value) — adds field AND includes "key=value" in the message string
log.info("Order placed {}", kv("orderId", orderId));
// → {"message":"Order placed orderId=42","orderId":42,...}

// keyValue — same as kv, explicit form
log.info("Order placed", keyValue("orderId", orderId), keyValue("userId", userId));
// → {"message":"Order placed","orderId":42,"userId":"u1",...}

// v("key", value) — adds field but does NOT embed in message string
log.info("Order placed {}", value("orderId", orderId));

// entries(Map) — spread a map as top-level JSON fields
Map<String, Object> ctx = Map.of("orderId", 42, "region", "EU");
log.info("Order placed {}", entries(ctx));
```

### Markers — attach a structured object

```java
import net.logstash.logback.marker.Markers;

// Append a serialised POJO / map
Marker marker = Markers.append("order", orderDto);
log.info(marker, "Order created");
// → {"message":"Order created","order":{"id":42,"item":"shoes",...},...}

// appendEntries — same as entries() but as a Marker
Marker ctx = Markers.appendEntries(Map.of("orderId", 42, "region", "EU"));
log.info(ctx, "Order dispatched");
```

### MDC — thread-scoped contextual fields

```java
// Set at request entry point (e.g., a servlet filter or interceptor)
MDC.put("requestId", UUID.randomUUID().toString());
MDC.put("userId", authenticatedUser.getId());

try {
    // All log calls on this thread automatically include requestId and userId
    log.info("Processing order {}", orderId);
} finally {
    MDC.clear();   // must clear — threads are pooled, MDC persists otherwise
}
```

MDC values appear in JSON output automatically with `LogstashEncoder`.
In text patterns use `%X{requestId}`.

---

## Interview Angles — Structured Logging

**Q: What is structured logging and why is it preferred in production?**
A: Structured logging emits each log event as a machine-parseable document
(JSON) with typed fields, rather than a freeform string. Log aggregators
(Elasticsearch, Loki, Splunk) can then filter, aggregate, and alert on specific
fields without regex parsing. For example, a Kibana query for
`level:ERROR AND orderId:42` is instant and exact; the equivalent regex on
plain text logs is slow and brittle.

**Q: What is MDC and how does it work with structured logging?**
A: MDC (Mapped Diagnostic Context) is a thread-local `Map<String, String>` that
Logback reads when formatting each event. In a web application you put
`requestId`, `userId`, `traceId` into MDC in a servlet filter at the start of
the request and clear it at the end. Every log statement on that thread
automatically carries those values — no need to pass them as arguments. With
`LogstashEncoder` MDC values become top-level JSON fields, enabling
per-request log filtering in the aggregator.

**Q: What is the difference between StructuredArguments.kv() and Markers.append()?**
A: `kv("key", value)` is a log-call argument — it embeds the key-value in the
message string *and* adds it as a JSON field. It is concise and good for simple
scalar values. `Markers.append("key", obj)` attaches a structured object (POJO,
map, collection) as a nested JSON field without touching the message string. Use
Markers when you need to log a full object graph; use `kv` for individual fields.

---

# PII-Safe Logging

## What

**PII (Personally Identifiable Information)** includes names, emails, phone
numbers, national IDs, credit card numbers, passwords, IP addresses, and
tokens. Logging raw PII violates GDPR/CCPA, creates audit findings, and
expands breach surface.

PII-safe logging means:
1. **Never** logging raw sensitive values.
2. **Masking** partial values when they are needed for debugging.
3. **Redacting** values that should never appear at all.

---

## How

### 1 — Discipline at the call site (simplest)

```java
// Never log raw password or full card
log.info("Login attempt for user {}", user.getEmail());  // BAD if email is PII
log.info("Login attempt for userId {}", user.getId());   // GOOD — use opaque ID
log.info("Card ending {}", card.getLast4());             // GOOD — truncated
```

### 2 — Masking utility method

```java
public class Mask {
    public static String email(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String card(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}

// Usage
log.info("Payment for user {}", Mask.email(user.getEmail()));
// → "Payment for user j***@example.com"
```

### 3 — Custom Logback `MessageConverter` (regex-based, defence-in-depth)

Intercepts the formatted message string and redacts patterns matching known PII
shapes. Acts as a safety net when a developer accidentally logs raw PII.

```java
package com.example.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

public class PiiMaskingConverter extends MessageConverter {

    // credit card: 13–19 consecutive digits
    private static final Pattern CC = Pattern.compile("\\b\\d{13,19}\\b");
    // simple email pattern
    private static final Pattern EMAIL =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        msg = CC.matcher(msg).replaceAll("****-REDACTED-****");
        msg = EMAIL.matcher(msg).replaceAll("***@***.***");
        return msg;
    }
}
```

Register in `logback-spring.xml`:

```xml
<conversionRule conversionWord="maskedMsg"
                converterClass="com.example.logging.PiiMaskingConverter"/>

<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <!-- replace %msg with %maskedMsg -->
        <pattern>%d %-5level %logger - %maskedMsg%n</pattern>
    </encoder>
</appender>
```

### 4 — `@JsonSerialize` masking for structured JSON logs

When using `LogstashEncoder`, annotate DTO fields:

```java
public class UserDto {
    private String userId;

    @JsonSerialize(using = EmailMaskSerializer.class)
    private String email;
}

public class EmailMaskSerializer extends JsonSerializer<String> {
    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider p)
            throws IOException {
        gen.writeString(Mask.email(value));
    }
}
```

JSON output: `{"userId":"u1","email":"j***@example.com"}`

---

## Interview Angles — PII-Safe Logging

**Q: What is PII and why must it be kept out of logs?**
A: PII is any data that identifies a natural person: name, email, phone, national
ID, IP address, payment data. Logs are often stored in plaintext, replicated to
multiple systems, and retained for months. A breach or accidental exposure of
log files immediately constitutes a data-protection incident under GDPR/CCPA.
Logs also tend to be over-shared (developers, analytics teams, vendors) making
them a wide blast radius. The rule: treat log files as semi-public and never
write anything you would not put on a notice board.

**Q: What is the difference between masking and redaction?**
A: Masking partially conceals a value, preserving enough for debugging:
`j***@example.com` lets an engineer confirm which user triggered an error
without exposing the full email. Redaction removes the value entirely, replacing
it with a fixed token like `[REDACTED]`. Use masking when partial values aid
diagnosis; use redaction when no part of the value should appear (passwords,
tokens, SSNs).

**Q: Why is a regex `MessageConverter` a "defence-in-depth" control and not a
primary control?**
A: Regex matching is a last line of defence — it adds latency to every log
call, can produce false positives (matching non-PII digit strings), and cannot
catch all PII forms (names, free-text addresses). The primary control is
discipline at the call site: never pass raw sensitive objects to log methods.
The converter catches accidents when developers forget, but it should not be the
only safeguard.
