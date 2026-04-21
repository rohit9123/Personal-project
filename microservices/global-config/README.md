# Global Config — Spring Cloud Config Server

Covers four interlinked topics: **`@ConfigurationProperties`**, **Profiles**, **Actuator**, and **`@RefreshScope`** — all in a proper microservices setup where config lives on a central server, not inside each service.

---

## 1. What

**Spring Cloud Config Server** — a dedicated microservice that stores and serves configuration for all other services. Each service fetches its properties from this server at startup (and on demand when refreshed). Config can be backed by a git repo or the filesystem.

**`@ConfigurationProperties`** — type-safe binding of a property prefix to a Java class. Safer and more IDE-friendly than `@Value` for groups of related properties.

**Profiles** — a named layer of config overrides. `config-client-dev.yml` is merged on top of `config-client.yml` when the `dev` profile is active. One service binary, environment-specific values injected at runtime.

**Actuator** — HTTP endpoints that expose the internal state of a running Spring Boot app. Key ones here: `/actuator/env`, `/actuator/configprops`, `/actuator/refresh`.

**`@RefreshScope`** — marks a bean as refreshable. When `POST /actuator/refresh` is called, Spring Cloud Context re-fetches config from the server and rebuilds every `@RefreshScope` bean with the new values — **no restart**.

---

## 2. Why

### The problem with per-service config files

In a microservices system with 20 services, every service has its own `application.yml` baked into its Docker image. To change a feature flag you have to: edit the file → rebuild the image → redeploy the service → wait for it to restart. Multiply that by 20 services and 3 environments.

**With a Config Server:**
```
Change config-client-prod.yml on the Config Server
  → POST /actuator/refresh to the target services
  → Services pick up the new values in-place — no rebuild, no restart
```

One place to manage config. Audit trail via git history. No secrets committed in service repos.

### @ConfigurationProperties vs @Value

| | `@Value("${app.name}")` | `@ConfigurationProperties(prefix="app")` |
|---|---|---|
| Type safety | No (always String) | Yes (bound to Java types) |
| Validation | Manual | JSR-303 annotations on the class |
| Nested objects | Not supported | Fully supported |
| Grouping | One property at a time | Entire prefix in one class |
| Refactoring | Fragile (magic string) | IDE renames the field |

Use `@Value` for a single quick property. Use `@ConfigurationProperties` for any group of related properties.

### @RefreshScope — why not just restart?

Restarting a service to change a config value causes:
- Downtime (load balancer drains in-flight requests)
- Rollout delay (K8s rolling update takes time)
- Loss of in-memory state (caches, circuit breaker counters, etc.)

`@RefreshScope` gives you zero-downtime config updates. The service keeps running; only the affected beans are rebuilt.

---

## 3. How It Works

### Startup flow

```
config-client starts
  │
  ├─ reads application.yml → finds spring.config.import: "configserver:http://localhost:8888"
  │
  ├─ GET http://localhost:8888/config-client/dev
  │     Config Server merges:
  │       config-client.yml (base)
  │     + config-client-dev.yml (profile overrides, wins on conflict)
  │     → returns combined property set as JSON
  │
  ├─ Spring Environment is populated with these properties
  │
  ├─ @ConfigurationProperties beans are created and validated
  │
  └─ App is fully started — all properties resolved, no local YAMLs needed
```

### How the Config Server resolves files

```
GET /{application}/{profile}

application = spring.application.name of the client   ("config-client")
profile     = spring.profiles.active of the client    ("dev")

Files served (merged in order, later entries win):
  1. config-client.yml          (base — always loaded)
  2. config-client-dev.yml      (profile override — loaded when profile=dev)
```

### @RefreshScope mechanics

```
POST /actuator/refresh
  │
  ├─ Spring Cloud Context fetches fresh config from Config Server
  ├─ Computes diff: which keys changed
  ├─ Invalidates @RefreshScope proxy cache (real bean instances destroyed)
  └─ Returns list of changed keys: ["feature.beta", "app.name"]

Next call to a @RefreshScope bean
  └─ proxy: "I have no real bean" → creates fresh instance
  └─ @Value fields re-injected with new values from updated Environment
```

`@ConfigurationProperties` beans are **not** refreshed by default.
Add `@RefreshScope` to them too if you want them refreshed:
```java
@RefreshScope
@ConfigurationProperties(prefix = "app")
public class AppProperties { ... }
```

### Profile loading priority (highest to lowest)

1. `--spring.profiles.active=prod` (command-line arg)
2. `SPRING_PROFILES_ACTIVE=prod` (env var)
3. `spring.profiles.active: dev` (inside application.yml)

---

## 4. Code

| File | What it is |
|---|---|
| `config-server/ConfigServerApp.java` | `@EnableConfigServer` — one annotation turns this into a config server |
| `config-server/application.yml` | `native` backend pointing to `file:../config-repo` (external folder), port 8888 |
| `config-repo/config-client.yml` | Base config for all profiles — edit freely, no server restart needed |
| `config-repo/config-client-dev.yml` | Dev overrides (beta on, debug logging, fast retry) |
| `config-repo/config-client-prod.yml` | Prod overrides (beta off, warn logging, more retries) |
| `config-client/ConfigClientApp.java` | `@ConfigurationPropertiesScan` — auto-discovers config property classes |
| `config-client/AppProperties.java` | `@ConfigurationProperties(prefix="app")` with nested `Mail` + JSR-303 validation |
| `config-client/FeatureFlags.java` | `@ConfigurationProperties(prefix="feature")` — second prefix, shows the pattern |
| `config-client/DynamicConfig.java` | `@RefreshScope` + `@Value` — re-created on `/actuator/refresh` |
| `config-client/ConfigController.java` | `GET /config` (bound values) · `GET /config/dynamic` (refresh-scope bean) |
| `config-client/application.yml` | Minimal — just app name, active profile, and Config Server URL |

---

## 5. Run the Demo

### Start both services

```bash
# Terminal 1 — Config Server (port 8888)
cd config-server && mvn spring-boot:run

# Terminal 2 — Config Client (port 8080)
cd config-client && mvn spring-boot:run
# startup log: "Located property source: [Config resource ...]"
```

### Verify the Config Server is serving config

```bash
# Raw JSON the server returns for the dev profile
curl http://localhost:8888/config-client/dev | jq .

# →  propertySources[0].source contains config-client-dev.yml values
# →  propertySources[1].source contains config-client.yml base values
```

### Check what the client loaded

```bash
curl http://localhost:8080/config
# →  {
#      "activeProfiles": ["dev"],
#      "app (@ConfigurationProperties)": {
#        "name": "Config Demo [DEV]",     ← from config-client-dev.yml
#        "maxRetries": 1,
#        "mail.host": "smtp.dev.example.com"
#      },
#      "features (@ConfigurationProperties)": {
#        "darkMode": true,
#        "beta": true
#      }
#    }
```

### Switch to prod profile

```bash
SPRING_PROFILES_ACTIVE=prod mvn spring-boot:run

curl http://localhost:8080/config
# →  "name": "Config Demo [PROD]", "maxRetries": 5, "beta": false
```

### Zero-downtime config refresh with @RefreshScope

```bash
# Step 1 — check current @RefreshScope bean values
curl http://localhost:8080/config/dynamic
# →  { "appName": "Config Demo [DEV]", "betaEnabled": true }

# Step 2 — edit config-client-dev.yml on the server side:
#   change feature.beta to false

# Step 3 — trigger refresh (client re-fetches from server)
curl -X POST http://localhost:8080/actuator/refresh
# →  ["feature.beta"]   ← list of changed keys

# Step 4 — @RefreshScope bean was re-created with new value
curl http://localhost:8080/config/dynamic
# →  { "betaEnabled": false }   ← updated, NO restart
```

### Useful Actuator endpoints

```bash
# See every property source and its values (shows Config Server as a source)
curl http://localhost:8080/actuator/env | jq .

# See all @ConfigurationProperties beans and their current bound values
curl http://localhost:8080/actuator/configprops | jq .

# Health check
curl http://localhost:8080/actuator/health
```

---

## 6. Interview Angles

**Q: What is a Spring Cloud Config Server and why do you need it?**

It is a standalone microservice that centralises configuration for all other services. Without it, config is baked into each service's Docker image — any change requires a rebuild and redeploy. With a Config Server, config lives in one place (typically a git repo), all services pull from it at startup, and any change can be pushed to running services via `/actuator/refresh` without a restart or image rebuild.

---

**Q: `@Value` vs `@ConfigurationProperties` — when do you use each?**

Use `@Value` for a single isolated property accessed in one place. Use `@ConfigurationProperties` for any group of related properties — you get type safety, IDE autocomplete, JSR-303 validation at startup, and the ability to pass the whole config group as a single constructor parameter. `@ConfigurationProperties` also supports nested objects and relaxed binding (`max-retries` → `maxRetries`) natively.

---

**Q: How does relaxed binding work?**

Spring Boot's binder accepts all of these for the same property:
- `app.max-retries` (YAML kebab-case)
- `app.maxRetries` (camelCase)
- `APP_MAX_RETRIES` (env var / SCREAMING_SNAKE_CASE)

They all bind to `int maxRetries` in the Java class. `@Value` does NOT do relaxed binding — it only accepts the exact string you write.

---

**Q: How does `@RefreshScope` work internally?**

Spring wraps every `@RefreshScope` bean in a **scoped proxy**. The real bean lives in a scope cache (keyed by bean name). When `POST /actuator/refresh` is called:
1. Spring Cloud Context re-fetches all property sources from the Config Server.
2. The scope cache is cleared — every `@RefreshScope` real instance is destroyed.
3. On the next call through the proxy, a fresh instance is created and `@Value` fields are re-injected from the updated `Environment`.

Callers hold a reference to the proxy — they transparently pick up the new instance without knowing a refresh happened.

---

**Q: What happens if the Config Server is down when a service starts?**

By default, the client fails fast — it throws an exception and refuses to start because it cannot load its config. You can set `spring.cloud.config.fail-fast=false` to fall back to local properties, or add retry with `spring.cloud.config.retry.*` to keep attempting for a configurable period. In production: run the Config Server in HA mode (multiple instances behind a load balancer) and use a git backend so the config history is durable.

---

**Q: How do you activate a profile in production?**

```bash
# Env var (most common in containers / K8s)
SPRING_PROFILES_ACTIVE=prod java -jar app.jar

# Command-line arg
java -jar app.jar --spring.profiles.active=prod
```

Both override the `spring.profiles.active` set inside `application.yml`. The Config Server then serves `config-client-prod.yml` merged with `config-client.yml`.

---

**Q: Can you refresh `@ConfigurationProperties` beans too?**

Yes. By default they are not refreshed, but you can opt in:
```java
@RefreshScope
@ConfigurationProperties(prefix = "app")
public class AppProperties { ... }
```
After `/actuator/refresh`, the entire `AppProperties` object is rebuilt from the fresh config. The trade-off: every call through the proxy pays a tiny scope-lookup cost, and the bean briefly holds stale values during the rebuild window.
