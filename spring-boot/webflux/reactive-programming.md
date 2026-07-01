# Spring WebFlux & Reactive Programming — A Complete Guide

---

## 1. What is Reactive Programming?

Reactive programming is a paradigm for building **non-blocking, asynchronous, event-driven** applications that handle many concurrent connections with a small number of threads. Instead of blocking a thread while waiting for I/O (database query, HTTP call, file read), the thread is released to do other work and a callback is invoked when the result is ready.

| Concept | Traditional (Servlet/MVC) | Reactive (WebFlux) |
|---------|--------------------------|---------------------|
| Threading model | 1 thread per request (blocked during I/O) | Few threads handle many requests (non-blocking I/O) |
| Concurrency with 200 threads | Max 200 concurrent requests | Thousands of concurrent requests |
| Back-pressure | None (producer can overwhelm consumer) | Built-in (consumer controls data flow) |
| Programming model | Imperative (do A, then B, then C) | Declarative pipelines (Mono/Flux chains) |

### The Core Problem WebFlux Solves

```
Traditional Servlet (Spring MVC):

  Thread-1: ──[receive request]──[query DB ... BLOCKED 50ms ...]──[return response]──
  Thread-2: ──[receive request]──[call API ... BLOCKED 200ms ...]──[return response]──
  Thread-3: ──[receive request]──[read file .. BLOCKED 30ms ....]──[return response]──
  
  Each thread is IDLE during I/O, doing nothing. With 200 threads,
  you can only handle 200 concurrent requests.

Reactive (WebFlux):

  Thread-1: ──[receive req-A]──[submit DB query, release thread]──
            ──[receive req-B]──[submit API call, release thread]──
            ──[receive req-C]──[submit file read, release thread]──
            ──[DB result ready → resume req-A → send response]──
            ──[file ready → resume req-C → send response]──
  
  1 thread handles multiple requests. I/O waits don't consume threads.
  With 4 threads (Netty default = CPU cores), handle 10,000+ concurrent connections.
```

---

## 2. Why Use WebFlux?

### When WebFlux Shines
- **High-concurrency I/O-bound services**: API gateways, BFF (Backend for Frontend), proxy services that fan out to many downstream calls
- **Streaming data**: Server-Sent Events (SSE), WebSocket feeds, real-time dashboards
- **Microservices with many downstream calls**: Calling 5 services in parallel without blocking 5 threads
- **Cloud/serverless**: Fewer threads = less memory = lower cost per container

### When to Stick with Spring MVC
- **CPU-bound workloads**: Image processing, encryption, heavy computation — blocking a thread is fine because the CPU is actually doing work
- **JDBC/JPA**: Traditional relational DB drivers are blocking. Using them in WebFlux defeats the purpose (you'd block the event loop). Use R2DBC instead.
- **Simple CRUD APIs**: If you handle <1,000 concurrent connections, Spring MVC is simpler and easier to debug
- **Team familiarity**: Reactive code has a steep learning curve. Debugging stack traces is harder.

### The Decision Matrix

```
                        Low Concurrency          High Concurrency
                      (< 1K concurrent)        (> 10K concurrent)
                   ┌─────────────────────┬─────────────────────┐
  CPU-bound        │   Spring MVC        │   Spring MVC        │
                   │   (blocking is fine) │   (scale horizontally)│
                   ├─────────────────────┼─────────────────────┤
  I/O-bound        │   Spring MVC        │   Spring WebFlux    │
                   │   (simpler)          │   (non-blocking wins)│
                   └─────────────────────┴─────────────────────┘
```

---

## 3. Project Reactor — Mono & Flux

Spring WebFlux is built on **Project Reactor**, which provides two core reactive types:

| Type | What it represents | Analogy |
|------|-------------------|---------|
| `Mono<T>` | 0 or 1 element (async) | `CompletableFuture<T>` but with back-pressure |
| `Flux<T>` | 0 to N elements (async stream) | `Stream<T>` but asynchronous and push-based |

### 3.1 Mono — Single Async Value

```java
// Creating Monos
Mono<String> just     = Mono.just("hello");           // Immediate value
Mono<String> empty    = Mono.empty();                  // No value
Mono<String> error    = Mono.error(new RuntimeException("boom"));
Mono<String> deferred = Mono.fromCallable(() -> fetchFromDB());  // Lazy
Mono<String> future   = Mono.fromFuture(completableFuture);      // Bridge

// Transforming
Mono<Integer> length = Mono.just("hello")
    .map(String::length)                    // Transform: "hello" → 5
    .filter(len -> len > 3)                 // Filter: keep if > 3
    .defaultIfEmpty(0);                     // Fallback if empty

// Chaining async calls (flatMap = async map)
Mono<Order> order = userService.findById(userId)           // Mono<User>
    .flatMap(user -> orderService.findLatest(user.getId()))  // Mono<Order>
    .switchIfEmpty(Mono.error(new NotFoundException()));
```

**Key rule**: `map` for synchronous transforms, `flatMap` for async transforms (returns another Mono/Flux).

### 3.2 Flux — Async Stream of Elements

```java
// Creating Fluxes
Flux<Integer> range     = Flux.range(1, 10);                   // 1 to 10
Flux<String>  fromList  = Flux.fromIterable(List.of("a","b")); // From collection
Flux<Long>    interval  = Flux.interval(Duration.ofSeconds(1)); // Every 1 sec
Flux<String>  generate  = Flux.generate(sink -> sink.next("tick")); // Programmatic

// Transforming
Flux<String> names = Flux.just("alice", "bob", "charlie")
    .filter(name -> name.length() > 3)       // Keep: "alice", "charlie"
    .map(String::toUpperCase)                 // "ALICE", "CHARLIE"
    .sort()                                   // "ALICE", "CHARLIE"
    .take(1);                                 // "ALICE"

// Combining
Flux<String> merged = Flux.merge(flux1, flux2);           // Interleave
Flux<String> concat = Flux.concat(flux1, flux2);          // Sequential
Flux<Tuple2<A,B>> zipped = Flux.zip(fluxA, fluxB);       // Pair-wise
```

### 3.3 The Golden Rule: Nothing Happens Until You Subscribe

```java
// This does NOTHING — no HTTP call is made
Mono<User> user = webClient.get()
    .uri("/users/1")
    .retrieve()
    .bodyToMono(User.class)
    .map(u -> { System.out.println("Got user"); return u; });

// Only when you subscribe does the pipeline execute
user.subscribe(u -> System.out.println(u.getName()));

// In WebFlux controllers, the framework subscribes for you
// when you return Mono/Flux from a handler method
```

### 3.4 Error Handling

```java
Mono<User> user = userService.findById(id)
    .onErrorReturn(new User("fallback"))                     // Static fallback
    .onErrorResume(e -> cacheService.getCachedUser(id))      // Async fallback
    .onErrorMap(e -> new ServiceException("User fetch failed", e))  // Wrap
    .doOnError(e -> log.error("Failed to fetch user", e))    // Side-effect (logging)
    .retry(3)                                                 // Retry up to 3 times
    .retryWhen(Retry.backoff(3, Duration.ofMillis(500)));    // Retry with backoff
```

### 3.5 Back-Pressure

Back-pressure is how a slow consumer tells a fast producer to slow down:

```
Producer (Database)          Consumer (HTTP Client)
    ──[item 1]──►
    ──[item 2]──►
    ──[item 3]──►            "I can only handle 2 at a time!"
    ◄── request(2) ──        (back-pressure signal)
    ──[item 4]──►
    ──[item 5]──►
    ◄── request(2) ──
```

```java
Flux.range(1, 1_000_000)
    // Pick ONE strategy. Here: buffer up to 256 items, and drop the
    // overflow (logging each dropped item) once the buffer is full.
    .onBackpressureBuffer(256, item -> log.warn("Dropped overflow: {}", item))
    .publishOn(Schedulers.boundedElastic(), 1) // Publish to a slow worker pool with prefetch of 1
    .subscribe(item -> {
        try {
            Thread.sleep(100);        // Simulated slow consumer (safe on elastic scheduler)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
```

---

## 4. Spring WebFlux — Building Reactive APIs

### 4.1 Annotated Controllers (Familiar Style)

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // Return Mono<T> instead of T
    @GetMapping("/{id}")
    public Mono<ResponseEntity<User>> getUser(@PathVariable String id) {
        return userService.findById(id)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // Return Flux<T> instead of List<T>
    @GetMapping
    public Flux<User> getAllUsers() {
        return userService.findAll();
    }

    // Streaming response (Server-Sent Events)
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<User> streamUsers() {
        return userService.findAll()
                .delayElements(Duration.ofSeconds(1));  // Emit one per second
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<User> createUser(@RequestBody Mono<User> userMono) {
        return userMono.flatMap(userService::save);
    }
}
```

### 4.2 Functional Endpoints (Router Functions)

An alternative to annotated controllers — more functional, no reflection:

```java
@Configuration
public class UserRouter {

    @Bean
    public RouterFunction<ServerResponse> userRoutes(UserHandler handler) {
        return RouterFunctions.route()
                .path("/api/users", builder -> builder
                    .GET("/{id}", handler::getUser)
                    .GET("", handler::getAllUsers)
                    .POST("", handler::createUser)
                )
                .build();
    }
}

@Component
@RequiredArgsConstructor
public class UserHandler {

    private final UserService userService;

    public Mono<ServerResponse> getUser(ServerRequest request) {
        String id = request.pathVariable("id");
        return userService.findById(id)
                .flatMap(user -> ServerResponse.ok().bodyValue(user))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getAllUsers(ServerRequest request) {
        return ServerResponse.ok().body(userService.findAll(), User.class);
    }

    public Mono<ServerResponse> createUser(ServerRequest request) {
        return request.bodyToMono(User.class)
                .flatMap(userService::save)
                .flatMap(user -> ServerResponse.created(URI.create("/api/users/" + user.getId()))
                        .bodyValue(user));
    }
}
```

**When to use which?**
- **Annotated controllers**: Team is familiar with Spring MVC, easier to read, IDE support is better
- **Router functions**: Functional style, no reflection, better for lightweight microservices, composable routes

### 4.3 WebClient — Non-Blocking HTTP Client

`WebClient` replaces `RestTemplate` in reactive applications:

```java
@Service
public class OrderService {

    private final WebClient webClient;

    public OrderService(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://order-service")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // Simple GET
    public Mono<Order> getOrder(String id) {
        return webClient.get()
                .uri("/orders/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError,
                    response -> Mono.error(new NotFoundException("Order not found")))
                .bodyToMono(Order.class);
    }

    // POST with body
    public Mono<Order> createOrder(Order order) {
        return webClient.post()
                .uri("/orders")
                .bodyValue(order)
                .retrieve()
                .bodyToMono(Order.class);
    }

    // Parallel calls — fan-out to multiple services
    public Mono<OrderDetails> getOrderDetails(String orderId) {
        Mono<Order> orderMono = getOrder(orderId);
        Mono<User> userMono = userService.getUser(orderId);
        Mono<List<Item>> itemsMono = itemService.getItems(orderId);

        return Mono.zip(orderMono, userMono, itemsMono)
                .map(tuple -> new OrderDetails(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }
}
```

**Key advantage**: `Mono.zip()` executes all three calls **concurrently** without blocking three threads. In Spring MVC, you'd need `CompletableFuture` + custom executor pool.

---

## 5. R2DBC — Reactive Database Access

Traditional JDBC is blocking — calling `resultSet.next()` blocks the thread. R2DBC is the reactive alternative:

```java
// Dependencies: spring-boot-starter-data-r2dbc + r2dbc-postgresql

@Table("users")
public record User(
    @Id Long id,
    String username,
    String email,
    int balance,
    LocalDateTime createdAt
) {
    public User withBalance(int newBalance) {
        return new User(id, username, email, newBalance, createdAt);
    }
}

public interface UserRepository extends ReactiveCrudRepository<User, Long> {
    
    Flux<User> findByUsername(String username);
    
    @Query("SELECT * FROM users WHERE email = :email")
    Mono<User> findByEmail(String email);
}

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Mono<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public Flux<User> findAll() {
        return userRepository.findAll();
    }

    public Mono<User> save(User user) {
        return userRepository.save(user);
    }

    // Reactive transaction
    @Transactional
    public Mono<Void> transferCredits(Long fromId, Long toId, int amount) {
        return userRepository.findById(fromId)
                .flatMap(from -> {
                    if (from.balance() < amount) {
                        return Mono.error(new IllegalArgumentException("Insufficient balance"));
                    }
                    return userRepository.save(from.withBalance(from.balance() - amount));
                })
                .then(Mono.defer(() -> userRepository.findById(toId)))
                .flatMap(to -> userRepository.save(to.withBalance(to.balance() + amount)))
                .then();
    }
}
```

### R2DBC vs JDBC

| Aspect | JDBC | R2DBC |
|--------|------|-------|
| Blocking | Yes — thread blocked per query | No — non-blocking I/O |
| API | `ResultSet`, `PreparedStatement` | `Publisher<Row>`, reactive streams |
| ORM | JPA/Hibernate (full ORM) | Spring Data R2DBC (lightweight, no lazy loading) |
| Transactions | `@Transactional` (ThreadLocal-based) | `@Transactional` (Reactor Context-based) |
| Maturity | 25+ years, battle-tested | Newer, fewer drivers, some gaps |
| Connection Pool | HikariCP | r2dbc-pool |

**Important**: R2DBC does NOT support JPA/Hibernate. No lazy loading, no entity relationships, no JPQL. You write repository methods or raw SQL queries. For complex relational models, JDBC is still the pragmatic choice.

---

## 6. Reactive Spring Security

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                    .pathMatchers("/auth/**").permitAll()
                    .pathMatchers("/admin/**").hasRole("ADMIN")
                    .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
```

**Key differences from Servlet Security**:
- `SecurityFilterChain` → `SecurityWebFilterChain`
- `HttpSecurity` → `ServerHttpSecurity`
- `HttpServletRequest` → `ServerWebExchange`
- `authorizeHttpRequests` → `authorizeExchange`
- `SecurityContextHolder` (ThreadLocal) → `ReactiveSecurityContextHolder` (Reactor Context)
- `UserDetailsService` → `ReactiveUserDetailsService`

---

## 7. Threading Model — Netty Event Loop

WebFlux runs on **Netty** (not Tomcat) by default. Netty uses an event-loop model:

```
                    ┌──────────────────────────────┐
                    │   Netty Event Loop Group      │
                    │   (4 threads = CPU cores)     │
                    │                              │
   Request A ──►   │  Thread-1: ──[read A]──       │
   Request B ──►   │            ──[read B]──       │
   Request C ──►   │            ──[DB callback A]──│
   Request D ──►   │  Thread-2: ──[read C]──       │
                    │            ──[read D]──       │
                    │            ──[API callback B]─│
                    │  Thread-3: idle               │
                    │  Thread-4: idle               │
                    └──────────────────────────────┘
```

**Critical rule: NEVER BLOCK the event loop thread.**

```java
// BAD — blocks the event loop for 50ms → all other requests on this thread stall
@GetMapping("/bad")
public Mono<String> bad() {
    return Mono.fromCallable(() -> {
        Thread.sleep(50);                    // BLOCKING!
        return jdbcTemplate.queryForObject(  // BLOCKING JDBC!
            "SELECT name FROM users WHERE id = 1", String.class);
    });
}

// GOOD — offload blocking work to a bounded elastic scheduler
@GetMapping("/good")
public Mono<String> good() {
    return Mono.fromCallable(() -> 
            jdbcTemplate.queryForObject("SELECT name FROM users WHERE id = 1", String.class))
        .subscribeOn(Schedulers.boundedElastic());  // Run on separate thread pool
}

// BEST — use non-blocking R2DBC
@GetMapping("/best")
public Mono<String> best() {
    return r2dbcTemplate.getDatabaseClient()
        .sql("SELECT name FROM users WHERE id = 1")
        .map(row -> row.get("name", String.class))
        .one();  // Fully non-blocking
}
```

### Schedulers

| Scheduler | Thread Pool | Use For |
|-----------|-------------|---------|
| `Schedulers.parallel()` | Fixed (CPU cores) | CPU-bound computation |
| `Schedulers.boundedElastic()` | Elastic, max 10x cores | Blocking I/O (legacy JDBC, file I/O) |
| `Schedulers.single()` | 1 thread | Sequential tasks |
| `Schedulers.immediate()` | Current thread | Testing, no hop |

---

## 8. Testing Reactive Code

### 8.1 StepVerifier — The Reactor Test Tool

```java
@Test
void testFindById() {
    Mono<User> user = userService.findById("1");

    StepVerifier.create(user)
            .assertNext(u -> {
                assertEquals("alice", u.getUsername());
                assertEquals("alice@test.com", u.getEmail());
            })
            .verifyComplete();  // Verify the Mono completes successfully
}

@Test
void testFindAll() {
    Flux<User> users = userService.findAll();

    StepVerifier.create(users)
            .expectNextCount(3)
            .verifyComplete();
}

@Test
void testError() {
    Mono<User> user = userService.findById("nonexistent");

    StepVerifier.create(user)
            .verifyError(NotFoundException.class);
}

@Test
void testWithVirtualTime() {
    // Test time-based operators without waiting
    StepVerifier.withVirtualTime(() -> 
            Flux.interval(Duration.ofHours(1)).take(3))
        .thenAwait(Duration.ofHours(3))
        .expectNextCount(3)
        .verifyComplete();
}
```

### 8.2 WebTestClient — Test WebFlux Endpoints

```java
@WebFluxTest(UserController.class)
class UserControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private UserService userService;

    @Test
    void getUser_found() {
        when(userService.findById("1")).thenReturn(Mono.just(new User("1", "alice")));

        webTestClient.get()
                .uri("/api/users/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(User.class)
                .value(user -> assertEquals("alice", user.getUsername()));
    }

    @Test
    void getUser_notFound() {
        when(userService.findById("999")).thenReturn(Mono.empty());

        webTestClient.get()
                .uri("/api/users/999")
                .exchange()
                .expectStatus().isNotFound();
    }
}
```

---

## 9. Common Pitfalls & Anti-Patterns

| Pitfall | Problem | Fix |
|---------|---------|-----|
| **Blocking the event loop** | `Thread.sleep()`, JDBC, `synchronized` on event loop thread freezes all requests on that thread | Use `subscribeOn(Schedulers.boundedElastic())` for blocking calls, or switch to R2DBC |
| **Calling `.block()` in reactive chain** | Defeats the purpose; deadlocks if called on event loop thread | Never `.block()` inside a reactive pipeline. Use `.flatMap()` instead |
| **Not subscribing** | Pipeline is assembled but never executed | Return `Mono`/`Flux` from controller (framework subscribes), or call `.subscribe()` explicitly |
| **Losing context (MDC/SecurityContext)** | ThreadLocal doesn't work across thread hops in reactive chains | Use Reactor Context (`deferContextual`), or `Hooks.onEachOperator(contextPropagation)` with Micrometer Context Propagation |
| **Side effects in `map()`** | `map()` is for transformation, not I/O. Putting HTTP calls in `map()` makes them blocking | Use `flatMap()` for any async/side-effectful operation |
| **Ignoring errors** | Unhandled errors terminate the stream silently | Always add `onErrorResume` / `onErrorReturn` / `doOnError` |
| **Mixing MVC and WebFlux** | Both on the classpath causes confusing auto-configuration | Choose one. Remove `spring-boot-starter-web` if using WebFlux |
| **Using `Flux.toStream()`** | Converts reactive to blocking iterator — blocks the thread | Only use in tests or non-reactive code paths |

### 9.1 Context Propagation (MDC & Spring Security)

Because reactive pipelines hop across threads, standard `ThreadLocal` variables (like log correlation IDs in MDC or security credentials in `SecurityContextHolder`) are lost.

**The Fix in Spring Boot 3.x:**

1. Add the Micrometer Context Propagation dependency:
   ```xml
   <dependency>
       <groupId>io.micrometer</groupId>
       <artifactId>context-propagation</artifactId>
       <version>1.1.0</version>
   </dependency>
   ```
2. Enable automatic propagation in your application bootstrap (e.g., in a main method or `@PostConstruct` block):
   ```java
   @Configuration
   public class ContextPropagationConfig {
       @PostConstruct
       public void init() {
           Hooks.enableAutomaticContextPropagation();
       }
   }
   ```
3. Read Reactor `Context` values manually when needed using `deferContextual`:
   ```java
   public Mono<String> getGreeting() {
       return Mono.deferContextual(ctx -> {
           String transactionId = ctx.getOrDefault("txId", "unknown");
           return Mono.just("Hello, transaction: " + transactionId);
       });
   }
   ```

---

## 10. Spring MVC vs WebFlux — Complete Comparison

| Aspect | Spring MVC | Spring WebFlux |
|--------|-----------|----------------|
| Server | Tomcat, Jetty, Undertow | Netty (default), Tomcat, Jetty |
| Threading | 1 thread per request | Event loop (few threads) |
| Blocking I/O | Natural (JDBC, RestTemplate) | Forbidden on event loop |
| Non-blocking I/O | Awkward (requires @Async) | Natural (Mono/Flux) |
| Database | JPA/Hibernate, JDBC | R2DBC, MongoDB Reactive |
| HTTP client | RestTemplate, RestClient | WebClient |
| Security | SecurityFilterChain (servlet) | SecurityWebFilterChain (reactive) |
| Testing | MockMvc | WebTestClient |
| Back-pressure | None | Built-in (Reactive Streams) |
| Stack traces | Clear, linear | Complex, hard to read |
| Learning curve | Low | High |
| Ecosystem maturity | Excellent | Growing (some gaps) |
| Best for | CRUD APIs, JPA apps, low-medium concurrency | High-concurrency I/O, streaming, gateways |

### 10.1 Spring WebFlux vs Spring MVC with Virtual Threads (Java 21+)

With Java 21+ Virtual Threads (`spring.threads.virtual.enabled=true`), Spring MVC can handle tens of thousands of concurrent I/O-bound requests using lightweight virtual threads instead of blocking OS threads, making it a highly compelling alternative to WebFlux.

| Aspect | Spring MVC + Virtual Threads | Spring WebFlux |
|--------|------------------------------|----------------|
| **Programming Model** | Imperative, synchronous, simple | Reactive, asynchronous, declarative |
| **I/O Style** | Blocking (JVM yields execution automatically) | Non-blocking (event loop/callbacks) |
| **Database Access** | Standard JPA/Hibernate, JDBC | R2DBC, Reactive MongoDB |
| **Complexity** | Very Low (standard Java code) | High (requires Project Reactor learning curve) |
| **Debugging** | Traditional stack traces, debuggers work | Harder to debug, async context jumps |
| **Best For** | 90% of business applications, CRUD, JPA | Real-time streams, SSE, high-scale API Gateways |

---

## 11. Interview Angles

### Q1: "When would you choose WebFlux over Spring MVC?"

**Answer**: "WebFlux shines in high-concurrency I/O-bound scenarios — API gateways, BFF services that fan out to 5-10 downstream calls, or real-time streaming endpoints (SSE, WebSockets). The non-blocking model lets a few Netty threads handle thousands of concurrent connections, whereas Spring MVC would need thousands of threads (each blocked on I/O). However, for CPU-bound work or traditional CRUD with JPA, Spring MVC is simpler and faster to develop. The decision comes down to: if your bottleneck is thread exhaustion from I/O waits, go reactive. If it's CPU, stay with MVC."

### Q2: "Explain the difference between `map` and `flatMap` in Reactor."

**Answer**: "`map` is a synchronous 1:1 transformation — it takes a value and returns a transformed value (`T → R`). `flatMap` is for asynchronous transformations — it takes a value and returns a Publisher (`T → Mono<R>` or `T → Flux<R>`), then flattens the result into the main stream. Use `map` for simple transforms like `String::toUpperCase`. Use `flatMap` when the transformation involves I/O (database call, HTTP request) that returns a Mono or Flux. Using `map` for async operations would give you `Mono<Mono<T>>` — nested publishers — which is wrong."

### Q3: "What happens if you block the Netty event loop thread?"

**Answer**: "Netty uses a fixed-size event loop group (default = CPU core count). Each event loop thread handles thousands of connections. If you block one thread with `Thread.sleep()`, JDBC, or a `synchronized` block, ALL connections assigned to that thread stall — not just the one making the blocking call. With 4 event loop threads, blocking one means 25% of your server's capacity is frozen. In production, this causes cascading timeouts. The fix is to offload blocking work to `Schedulers.boundedElastic()` using `subscribeOn()`, or better yet, use non-blocking alternatives (R2DBC instead of JDBC, WebClient instead of RestTemplate)."

### Q4: "How does back-pressure work in Reactive Streams?"

**Answer**: "Back-pressure is defined in the Reactive Streams spec (implemented by Project Reactor). The `Subscriber` signals demand to the `Publisher` via `request(n)` — 'give me n items.' The Publisher must not emit more than requested. In practice: if a database query returns 1 million rows but the HTTP client can only write 100 at a time, the Flux from R2DBC will pause fetching from the database until the client consumes the current batch. Strategies for when the producer is faster: `onBackpressureBuffer(maxSize)` buffers up to a limit, `onBackpressureDrop()` discards excess items, and `onBackpressureLatest()` keeps only the most recent item."

### Q5: "How do you handle ThreadLocal (MDC, SecurityContext) in reactive code?"

**Answer**: "ThreadLocal doesn't work in reactive applications because a single request hops across multiple threads (event loop → scheduler → event loop). Spring Security uses `ReactiveSecurityContextHolder`, which stores the context in Reactor's `Context` (propagated through the reactive chain, not via ThreadLocal). For MDC (logging), use Micrometer's Context Propagation library — it hooks into Reactor's `Context` and populates MDC before each operator executes. In Spring Boot 3.x, you enable this with `Hooks.enableAutomaticContextPropagation()`. Without it, your log correlation IDs will be missing or wrong."

### Q6: "Can you mix WebFlux and Spring MVC in the same application?"

**Answer**: "Technically no — if both `spring-boot-starter-web` and `spring-boot-starter-webflux` are on the classpath, Spring Boot defaults to Spring MVC (servlet mode). You'd be running on Tomcat, not Netty, and the reactive benefits are lost. However, you *can* use `WebClient` (from WebFlux) inside a Spring MVC application — it's a non-blocking HTTP client that works independently of the server runtime. This is actually a common migration path: start by replacing `RestTemplate` with `WebClient` in an MVC app, then gradually move endpoints to reactive when needed."

### Q7: "R2DBC vs JDBC — when to use which?"

**Answer**: "Use JDBC (with JPA/Hibernate) when you have complex relational models with entity relationships, lazy loading, L2 cache, and JPQL queries — JDBC's ecosystem is 25 years mature. Use R2DBC when you're building a fully reactive stack and your queries are simple (key lookups, flat results, manual joins). R2DBC doesn't support JPA — no lazy loading, no entity graphs, no `@OneToMany`. It's essentially a reactive JDBC with Spring Data's repository pattern. My rule: if the service is I/O-heavy with simple data access (API gateway, event processor), R2DBC. If it's a domain-heavy service with complex queries, JDBC + MVC."

### Q8: "How do you test reactive code?"

**Answer**: "Project Reactor provides `StepVerifier` — you pass it a Mono/Flux and assert on each emitted element, errors, and completion. `StepVerifier.create(mono).assertNext(user -> assertEquals(...)).verifyComplete()`. For time-based operators (`Flux.interval`, `delay`), use `StepVerifier.withVirtualTime()` to simulate time passing without actually waiting. For endpoint testing, `WebTestClient` replaces `MockMvc` — it's a non-blocking test client that speaks reactive. `@WebFluxTest` slices the context like `@WebMvcTest`. For integration tests, use `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `WebTestClient` autowired."

### Q9: "With the introduction of Virtual Threads (Project Loom) in Java 21, is Spring WebFlux still relevant?"

**Answer**: "Yes, WebFlux is still relevant, but its target use cases have narrowed. Virtual Threads solve the thread-exhaustion bottleneck for traditional blocking applications (Spring MVC + JPA/JDBC) by making blocking cheap, rendering WebFlux unnecessary for standard CRUD APIs. However, WebFlux remains the superior choice for:
1. **Event-Driven Streaming**: Applications relying on long-lived connections such as Server-Sent Events (SSE), WebSockets, or RSocket.
2. **End-to-End Backpressure**: Applications where you need explicit load control and backpressure signals propagated from the consumer all the way to the publisher (e.g., down to R2DBC or Kafka).
3. **Complex Reactive Workflows**: Systems that leverage rich Reactor operators like `zip`, `buffer`, `window`, `timeout`, or advanced `retryWhen` policies to coordinate concurrent tasks.
4. **Ultra-Low Memory Footprint**: Netty event loops require far less memory overhead under high concurrency than virtual threads (which still have metadata and call stacks), making WebFlux ideal for memory-constrained container gateways."
