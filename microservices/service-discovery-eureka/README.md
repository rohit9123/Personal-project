# Service Discovery with Eureka

---

## 1. What

Service Discovery is the mechanism by which microservices **find each other's network location (host + port)** at runtime — without hardcoding URLs.

**Eureka** is Netflix's Service Discovery server, integrated into the Spring Cloud ecosystem.

Two parts:
- **Eureka Server** — the central registry (knows where every service lives)
- **Eureka Client** — every microservice that registers itself and queries the registry

---

## 2. Why

In a microservices setup, services scale up/down dynamically. IPs and ports change constantly.
Hardcoding `http://order-service:8081` breaks the moment an instance moves or you run 3 copies of it.

Service Discovery solves:
- Dynamic registration when a service starts
- Automatic deregistration when a service dies
- Lookup by logical name, not IP

---

## 3. How It Works

### Registration Flow
```
Microservice starts
  → sends POST /eureka/apps/{appName} to Eureka Server
  → Eureka stores: appName, instanceId, IP, port, status=UP
  → Service sends heartbeat every 30s (default)
  → If no heartbeat received in 90s → Eureka marks DOWN → evicts it
```

### Discovery Flow
```
Service A wants to call Service B
  → asks Eureka: "give me instances of service-b"
  → Eureka returns list of live instances
  → Service A picks one (load balancer decides which)
  → calls it directly  ← Eureka is NOT in the call path
```

> Eureka is **not** a proxy. It only helps with lookup. Actual HTTP calls are peer-to-peer.

### Client-Side Cache
Eureka clients cache the registry locally and refresh every 30s.
Even if Eureka Server goes down briefly, services can still discover each other from cache.

### Self-Preservation Mode
If Eureka stops receiving heartbeats from many services at once, it assumes a **network partition** (not mass failure) and stops evicting instances.
This prevents wiping the entire registry during a brief network glitch.

---

## 4. Code

See Java files in this folder:

| File | What it is |
|------|-----------|
| `EurekaServerApp.java` | The Eureka registry server |
| `eureka-server.yml` | Server config |
| `OrderServiceApp.java` | A microservice that registers with Eureka |
| `InventoryServiceApp.java` | Another microservice (the one being called) |
| `AppConfig.java` | Configures @LoadBalanced RestTemplate |
| `OrderController.java` | Calls inventory-service by logical name |
| `eureka-client.yml` | Client config (shared pattern for any service) |

---

## 5. Interview Angles

**Q: Client-side vs Server-side service discovery?**

| | Client-Side (Eureka) | Server-Side (K8s, AWS ALB) |
|---|---|---|
| Who queries registry | The calling service | Infra / load balancer |
| Coupling | Needs discovery library | Client stays simple |
| Example | Spring Cloud + Eureka | K8s Services, AWS ELB |

---

**Q: What happens if Eureka Server goes down?**

Clients use their local cache (refreshed every 30s) to keep calling each other.
New registrations fail until server recovers.
For HA: run Eureka in a cluster with peer-to-peer replication. (mainly 3)

---

**Q: What is self-preservation mode?**

If heartbeat loss exceeds 85% of expected beats in a time window, Eureka stops evicting instances — it suspects a network problem, not that services actually died.

---

**Q: Heartbeat vs Health Check?**

- **Heartbeat** — proves the instance process is alive (every 30s)
- **Health check** — integrates with Spring Actuator `/health` to report actual app health

Set `eureka.client.healthcheck.enabled=true` to use Actuator health instead of just heartbeat.

---

**Q: Eureka vs Consul vs Zookeeper?**

| | Eureka | Consul | Zookeeper |
|---|---|---|---|
| CAP | AP | CP | CP |
| Health checks | Heartbeat | HTTP/TCP/Script | None built-in |
| Best for | Spring Cloud apps | Multi-platform | Distributed coordination |
