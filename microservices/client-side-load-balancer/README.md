# Client-Side Load Balancer (Spring Cloud LoadBalancer)

---

## 1. What

A **client-side load balancer** lives inside the calling service.
When service A calls service B, A itself decides **which instance of B** to send the request to — it doesn't rely on an external load balancer.

Spring Cloud provides **Spring Cloud LoadBalancer** (the modern replacement for the now-deprecated Netflix Ribbon).

---

## 2. Why

In a microservices system, the service registry returns a **list** of instances for a service. Something has to pick one — that's the load balancer's job.

**Client-side** means:
- No extra network hop through a central load balancer
- The calling service has full control over the algorithm
- Works hand-in-hand with service discovery (registry gives the list, LB picks from it)

---

## 3. How It Works

```
Order Service calls inventory-service
  → Spring Cloud LB intercepts the call
  → fetches instance list from local cache (Eureka or static config)
      [instance-1: localhost:9081]
      [instance-2: localhost:9083]
  → applies load balancing algorithm to pick one
  → rewrites URL and forwards the request
```

### Built-in Algorithms

| Algorithm | Behaviour |
|-----------|-----------|
| **Round Robin** (default) | cycles 1 → 2 → 3 → 1 → 2 ... |
| **Random** | picks a random instance each time |

Custom algorithms: implement `ReactorServiceInstanceLoadBalancer` (see `CustomLoadBalancer.java`).

### Three Integration Points

| Approach | How LB is wired | Style |
|----------|-----------------|-------|
| `@LoadBalanced RestTemplate` | `@LoadBalanced` installs an interceptor on the bean | Imperative |
| `FeignClient` | LB is built-in — no annotation needed | Declarative |
| `@LoadBalanced WebClient.Builder` | Same as RestTemplate, non-blocking | Reactive |

### Scoping a custom algorithm to one service

```java
@LoadBalancerClient(name = "inventory-service", configuration = LoadBalancerConfig.class)
```

Without this, the default Round Robin applies to all services.
Use `@LoadBalancerClients(defaultConfiguration = ...)` to override the default globally.

---

## 4. Code

| File | What it is |
|------|-----------|
| `order-service/AppConfig.java` | `@LoadBalanced` RestTemplate bean + `@LoadBalancerClient` to scope Random LB to inventory |
| `order-service/LoadBalancerConfig.java` | Switches algorithm to `RandomLoadBalancer` for inventory-service |
| `order-service/CustomLoadBalancer.java` | Full custom round-robin implementation — shows the `ReactorServiceInstanceLoadBalancer` interface |
| `order-service/InventoryFeignClient.java` | Feign interface — declarative client, LB built-in |
| `order-service/RestTemplateOrderController.java` | Calls inventory via `@LoadBalanced RestTemplate` at `/order/resttemplate/{id}` |
| `order-service/FeignOrderController.java` | Calls inventory via FeignClient at `/order/feign/{id}` |
| `order-service/WebClientConfig.java` | `@LoadBalanced WebClient.Builder` bean — reactive approach (reference) |
| `order-service/application.yml` | Static instance list — no Eureka needed for this demo |
| `inventory-service/InventoryController.java` | Returns `server.port` in response — lets you see which instance was chosen |

### Run order

```bash
# Terminal 1 — inventory-service instance 1 (port 9081)
cd inventory-service && mvn spring-boot:run

# Terminal 2 — inventory-service instance 2 (port 9083)
cd inventory-service && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9083

# Terminal 3 — order-service (port 8080)
cd order-service && mvn spring-boot:run
```

### Demo — observe the load balancer choosing instances

```bash
# Via RestTemplate — hit repeatedly to see port 9081 and 9083 alternating
curl http://localhost:8080/order/resttemplate/42

# Via Feign — same load balancing, declarative client
curl http://localhost:8080/order/feign/42

# Fire several in a row to observe the algorithm
for i in $(seq 1 6); do curl -s http://localhost:8080/order/resttemplate/$i; echo; done
```

With `LoadBalancerConfig` (Random), the port in the response will not follow a predictable pattern.
Comment out `@LoadBalancerClient` in `AppConfig.java` to switch back to Round Robin (strict alternation).

---

## 5. Interview Angles

**Q: Client-side vs server-side load balancing?**

| | Client-Side | Server-Side |
|---|---|---|
| Location | Inside the calling service | External (ALB, Nginx, HAProxy) |
| Latency | Lower — no extra hop | Higher — goes through LB node |
| Visibility | Client sees all instances | Client sees one VIP |
| Failure handling | Client can retry on next instance | LB handles it |
| Example | Spring Cloud LB, Ribbon | AWS ALB, K8s Service |

---

**Q: Ribbon vs Spring Cloud LoadBalancer?**

Ribbon (Netflix) is deprecated and in maintenance mode.
Spring Cloud LoadBalancer is the official replacement since Spring Cloud 2020.
Key difference: Spring Cloud LB is non-blocking and works with RestTemplate, WebClient, and Feign.

---

**Q: How does the LB get the list of instances?**

Via `ServiceInstanceListSupplier`. By default it wraps the `DiscoveryClient` — which could be a Eureka client cache, a Consul client, or (as in this demo) a `SimpleDiscoveryClient` backed by a static YAML list. The cache refreshes every 30s with Eureka.

---

**Q: How do you handle the case where the chosen instance is down?**

Combine with **Spring Retry** or **Resilience4j Retry**. On failure, retry with a different instance. Spring Cloud LB can be configured with a `RetryAwareServiceInstanceListSupplier` that avoids recently failed instances.

---

**Q: What is zone affinity in load balancing?**

Prefer instances in the same availability zone to reduce cross-zone latency. Spring Cloud LB supports this via `ZonePreferenceServiceInstanceListSupplier`. Only relevant when your services are deployed across multiple AZs.

---

**Q: Why must `LoadBalancerConfig` NOT be annotated with `@Configuration` at class level?**

If it were picked up by component scan, it would apply globally to all services. Keeping it annotation-free and referencing it explicitly via `@LoadBalancerClient(configuration = ...)` ensures it only applies to the named service.
