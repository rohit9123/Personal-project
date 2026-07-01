# Bean Scopes in Spring

## What

**Scope** controls how many instances of a bean Spring creates and how long each instance lives.

| Scope | Instances | Lifecycle |
|---|---|---|
| `singleton` | One per ApplicationContext | Lives as long as the context |
| `prototype` | One per injection/request | Caller is responsible for destruction |
| `request` | One per HTTP request | Destroyed when request completes |
| `session` | One per HTTP session | Destroyed when session expires |
| `application` | One per ServletContext | Lives as long as the web app |

---

## Singleton Scope (Default)

```java
@Component                        // singleton by default
// or explicitly:
@Scope("singleton")
public class AppConfig { ... }
```

- Single instance shared across the entire application.
- Created at startup (eager) unless `@Lazy`.
- Thread safety is **your responsibility** — shared state in a singleton accessed by multiple threads must be synchronized or stateless.

```java
// WRONG — mutable state in a singleton = bug under concurrency
@Component
public class Counter {
    private int count = 0;          // shared across all threads!
    public void increment() { count++; }
}

// RIGHT — make it stateless or use AtomicInteger
```

---

## Prototype Scope

```java
@Component
@Scope("prototype")
public class ReportBuilder { ... }
```

- Spring creates a **new instance** every time the bean is requested (via `@Autowired`, `getBean()`, etc.).
- Spring does **not** call `@PreDestroy` — the caller must manage cleanup.
- Use for stateful, non-thread-safe objects (builders, processors with per-request state).

### Problem: Injecting a Prototype into a Singleton

```java
@Component                        // singleton
public class OrderProcessor {

    @Autowired
    private ReportBuilder builder; // WRONG — injected once at startup; always same instance
}
```

The prototype is captured at injection time → you always get the same instance. Solutions:

**Option 1 — `ApplicationContext.getBean()`**
```java
@Component
public class OrderProcessor implements ApplicationContextAware {

    private ApplicationContext ctx;

    public void process() {
        ReportBuilder builder = ctx.getBean(ReportBuilder.class); // fresh instance each call
    }
}
```

**Option 2 — `@Lookup` method injection (Spring-managed)**
```java
@Component
public abstract class OrderProcessor {

    @Lookup
    public abstract ReportBuilder createBuilder(); // Spring overrides this; returns new instance

    public void process() {
        ReportBuilder builder = createBuilder();
    }
}
```

**Option 3 — `@Scope(proxyMode = ScopedProxyMode.TARGET_CLASS)`** (see ProxyMode section)

---

## Request Scope

```java
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST,
       proxyMode = ScopedProxyMode.TARGET_CLASS)
public class RequestContext {
    private String traceId;
    // getters/setters
}
```

- New instance per HTTP request.
- Only valid in a web `ApplicationContext`.
- `proxyMode` is required when injecting a request-scoped bean into a singleton (see below).

---

## ProxyMode

### The Problem

A singleton bean is wired once at startup. If it holds a reference to a shorter-lived bean (request/prototype), that reference goes stale after the first request.

```
Singleton (lives forever)
  ↑ injected at startup with a single instance of...
Request-scoped bean (should be fresh per request)
  → WRONG: all requests share the same instance
```

### The Fix: Scoped Proxy

```java
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class UserContext {
    private String userId;
}
```

Spring injects a **CGLIB proxy** into the singleton. Every method call on the proxy is delegated to the **real bean** for the current request/scope. The singleton holds the proxy forever; the proxy looks up the correct scoped instance on each call.

```
Singleton → holds proxy (permanent reference)
             ↓ each call
           real RequestScoped bean for THIS request
```

`ScopedProxyMode.TARGET_CLASS` — CGLIB proxy (works for any class)  
`ScopedProxyMode.INTERFACES` — JDK dynamic proxy (only if the bean implements an interface)

---

## Dynamically Initialized Beans

Sometimes you need to register beans at runtime based on configuration (e.g., create one `DataSource` bean per database entry in a config file).

### Using `BeanDefinitionRegistryPostProcessor`

```java
@Component
public class DynamicBeanRegistrar implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // Runs before bean instantiation — register new bean definitions here
        List<String> tenants = List.of("tenant-a", "tenant-b");
        for (String tenant : tenants) {
            BeanDefinitionBuilder builder =
                BeanDefinitionBuilder.genericBeanDefinition(TenantDataSource.class);
            builder.addConstructorArgValue(tenant);
            registry.registerBeanDefinition(tenant + "DataSource", builder.getBeanDefinition());
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { }
}
```

### Using `@ConditionalOnProperty` (simpler, common pattern)

```java
@Bean
@ConditionalOnProperty(name = "feature.cache.enabled", havingValue = "true")
public CacheManager cacheManager() { ... }
```

---

## Interview Angles

**Q: What is the default scope of a Spring bean?**
Singleton.

**Q: What is the difference between singleton in Spring and the Singleton design pattern?**
The design pattern enforces one instance per JVM classloader. Spring's singleton scope means one instance per `ApplicationContext` — you can have multiple contexts (e.g., in tests), each with its own instance.

**Q: When would you use prototype scope?**
For stateful, non-thread-safe objects that must not be shared: builders, report generators, objects holding per-request computation state.

**Q: What happens to `@PreDestroy` in prototype-scoped beans?**
Spring does NOT call it. You are responsible for cleanup.

**Q: Why is `proxyMode` needed for request/session scopes?**
Because the bean requesting the injection (often a singleton) is wired once at startup. Without a proxy, the singleton captures a single instance. The scoped proxy intercepts each method call and routes it to the real scoped instance for the current thread/request.

**Q: Difference between `ScopedProxyMode.TARGET_CLASS` and `ScopedProxyMode.INTERFACES`?**
`TARGET_CLASS` uses CGLIB subclassing — works on any class. `INTERFACES` uses JDK proxies — only works when the bean implements at least one interface. `TARGET_CLASS` is the safe default.

**Q: How do you inject a new prototype instance into a singleton on every method call?**
Use `@Lookup` method injection or `ApplicationContext.getBean()`. A scoped proxy with `prototype` scope also works.
