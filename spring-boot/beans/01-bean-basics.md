# Spring Beans — Basics, Lifecycle & ComponentScan

## What

A **Bean** is an object managed by the Spring IoC container. The container is responsible for instantiating, wiring, configuring, and destroying beans. All beans live in the `ApplicationContext`.

---

## How to Create a Bean

### 1. `@Component` (and stereotypes)

```java
@Component           // generic
@Service             // service layer (no extra behaviour — just clarity)
@Repository          // DAO layer (adds PersistenceExceptionTranslation)
@Controller          // web layer
public class PaymentService { ... }
```

Spring scans the classpath and registers these automatically.

### 2. `@Bean` inside `@Configuration`

Use this when you don't own the class (third-party) or need conditional/programmatic creation.

```java
@Configuration
public class AppConfig {

    @Bean                          // method name becomes the bean id by default
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    @Bean("customMapper")          // explicit id
    public ObjectMapper prettyMapper() { ... }
}
```

`@Configuration` marks the class so Spring proxies it (CGLIB) — repeated calls to `objectMapper()` return the **same** singleton instance.

---

## ComponentScan

`@ComponentScan` tells Spring which packages to scan for `@Component`-annotated classes.

```java
@SpringBootApplication   // already includes @ComponentScan(basePackages = current package)
public class App { ... }

// Override the default:
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.orders", "com.example.shared"})
public class App { ... }
```

**Default rule:** scans the package of the class annotated with `@SpringBootApplication` and all sub-packages. Classes outside this tree are **not** picked up automatically.

---

## Eager vs Lazy Initialization

| | Eager (default) | Lazy (`@Lazy`) |
|---|---|---|
| Created at | Container startup | First time the bean is requested |
| Startup time | Slower | Faster |
| Startup failure | Detected immediately | Detected at first use |
| Use case | Most beans | Heavy beans used infrequently |

```java
@Component
@Lazy
public class HeavyReportGenerator { ... }
```

You can also set lazy globally in `application.properties`:

```properties
spring.main.lazy-initialization=true
```

---

## Bean Lifecycle

```
Container starts
    → Instantiate (constructor)
    → Inject dependencies
    → @PostConstruct                  ← your init logic
    → Bean is ready (in use)
    → @PreDestroy                     ← your cleanup logic
    → Container shuts down
```

```java
@Component
public class CacheWarmer {

    @PostConstruct
    void init() {
        // runs after DI — safe to use injected fields here
        System.out.println("Loading cache...");
    }

    @PreDestroy
    void destroy() {
        // runs before the bean is removed from context
        System.out.println("Flushing cache...");
    }
}
```

Alternatives (for third-party beans in `@Bean`):

```java
@Bean(initMethod = "start", destroyMethod = "stop")
public DataSource dataSource() { ... }
```

---

## Interview Angles

**Q: Difference between `@Component` and `@Bean`?**
- `@Component` is class-level; Spring auto-detects it during scan. You must own/annotate the class.
- `@Bean` is method-level inside `@Configuration`; lets you create any object (including third-party) as a bean.

**Q: What happens if `@PostConstruct` throws an exception?**
The bean fails to initialize and the whole `ApplicationContext` startup fails (for eager beans).

**Q: When would you use `@Lazy`?**
For expensive resources (report generators, large caches) that aren't always needed, or to break circular dependency issues as a last resort.

**Q: What is the difference between `BeanFactory` and `ApplicationContext`?**
`BeanFactory` is the basic IoC container. `ApplicationContext` extends it with event publishing, i18n, AOP auto-proxying, and eager singleton initialization. Always use `ApplicationContext` in production.

**Q: Can a `@Lazy` bean fail silently?**
Yes — startup is fast but the error surfaces only when the bean is first injected/used, potentially in a live request rather than at boot time.
