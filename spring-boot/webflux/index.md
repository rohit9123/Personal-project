# Spring WebFlux & Reactive Programming — Topic Index

## [reactive-programming.md](file:///Users/rohit.kumar.4/Documents/interview-prep/spring-boot/webflux/reactive-programming.md)

| Section | Topics Covered |
|---------|----------------|
| **1. What is Reactive Programming?** | Non-blocking, asynchronous, event-driven model; Threading model difference vs Traditional Servlet (1 request per thread vs Event loop); Back-pressure concept. |
| **2. Why Use WebFlux?** | High-concurrency I/O-bound microservices, streaming (SSE, WebSockets), proxy servers/BFFs; When to stay with Spring MVC (CPU-bound work, JDBC/JPA, low concurrency); Decision Matrix. |
| **3. Project Reactor — Mono & Flux** | Mono (0-1 element), Flux (0-N elements); Map vs FlatMap; "Nothing happens until you subscribe" golden rule; Error handling (`onErrorReturn`, `onErrorResume`, `retryWhen`); Backpressure management (`onBackpressureBuffer`, `onBackpressureDrop`) offloaded to boundedElastic. |
| **4. Spring WebFlux — Building Reactive APIs** | Annotated Controllers vs Functional Router Functions/Handlers; Non-blocking `WebClient` for downstream fan-out calls (`Mono.zip`). |
| **5. R2DBC — Reactive Database Access** | Non-blocking database operations, `ReactiveCrudRepository`, `@Transactional` reactive transaction boundary with lazy evaluation (`Mono.defer`). |
| **6. Reactive Spring Security** | Security filters (`SecurityWebFilterChain`, `ServerHttpSecurity`), `ReactiveSecurityContextHolder` based on Reactor Context. |
| **7. Threading Model — Netty Event Loop** | Netty event loop architecture, event loop core group size; Rules to prevent blocking event loops; Schedulers (`Schedulers.parallel`, `Schedulers.boundedElastic`). |
| **8. Testing Reactive Code** | `StepVerifier` for assertions and virtual time testing; `WebTestClient` for controller slice/integration testing. |
| **9. Common Pitfalls & Anti-Patterns** | Blocking event loop, calling `.block()`, losing ThreadLocal context (MDC/SecurityContext), side effects in `map()`, mixing MVC and WebFlux on classpath; Automatic Context Propagation setup. |
| **10. Spring MVC vs WebFlux** | Server, threading, I/O style, DB access, HTTP client, security, testing, stack traces, learning curve comparisons; WebFlux vs Spring MVC with Java 21+ Virtual Threads. |
| **11. Interview Angles** | Comprehensive list of real-world Q&As (WebFlux choices, map vs flatMap, blocking event loop, backpressure internals, ThreadLocal propagation in reactive chains, MVC/WebFlux mix, R2DBC vs JDBC, StepVerifier, and Java 21 Virtual Threads impact). |
