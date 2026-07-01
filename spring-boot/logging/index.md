# Logging — Topic Index

## notes.md — Core Logback Framework

| Section | Topic |
|---------|-------|
| Logging Framework | SLF4J + Logback architecture, logger names, levels, hierarchy, additivity, runtime config |
| Appenders | logback-spring.xml syntax, ConsoleAppender, FileAppender, RollingFileAppender (time-based + size+time), custom Appender |
| Async Logging | AsyncAppender (Logback) vs AsyncLogger (Log4j2), queueSize, discardingThreshold, neverBlock |
| Filters in Appenders | LevelFilter, ThresholdFilter, custom Filter, ACCEPT/DENY/NEUTRAL chain |
| Log Format and Pattern | Pattern tokens, Spring Boot default pattern, override via properties |
| Placeholders | `{}` deferred evaluation, exception as last arg, varargs form |

## structured-logging.md — Structured Logging & PII

| Section | Topic |
|---------|-------|
| Structured Logging (JSON) | logstash-logback-encoder, LogstashEncoder, profile-based switching |
| Dynamic Fields in JSON | StructuredArguments (kv, value, entries), Markers, MDC thread-scoped context |
| PII-Safe Logging | Masking vs redaction, Mask utility, PiiMaskingConverter, @JsonSerialize masking |

## mdc-distributed-logging.md — MDC & Distributed Logging

| Section | Topic |
|---------|-------|
| MDC — Mapped Diagnostic Context | What MDC is, thread-local map, set/clear in filters, %X{key} in pattern |
| MDC Problem 1 — Log Pollution | Thread-pool reuse bug, MDC.clear() in finally, why finally matters |
| MDC Problem 2 — Async MDC Loss | ThreadLocal not crossing thread boundaries, @Async drops MDC |
| MDC Problem 2 Solution — TaskDecorator | MdcTaskDecorator, getCopyOfContextMap(), setContextMap(), ThreadPoolTaskExecutor wiring |
| Correlation ID | What/why, CorrelationIdFilter, RestTemplate interceptor, X-Correlation-ID header convention |
| End-to-End Distributed Logging | TraceID vs CorrelationID, Micrometer Tracing, LogstashEncoder JSON output, full-stack wiring |
