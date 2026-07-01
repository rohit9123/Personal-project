# Dependency Injection in Spring

## What

**Dependency Injection (DI)** is the mechanism by which the Spring container supplies an object's dependencies instead of the object creating them itself. It implements the Inversion of Control (IoC) principle: the container controls object creation, not the object.

**Why DI matters:**
- Without it, classes create their own dependencies (`new X()`) → tight coupling, untestable code.
- With DI, dependencies are injected → easy to swap implementations, easy to mock in tests.

---

## Three Types of Injection

### 1. Field Injection

```java
@Component
public class OrderService {

    @Autowired
    private InventoryService inventoryService;   // injected directly into the field
}
```

**Advantages:**
- Least boilerplate — no constructor or setter needed.

**Disadvantages:**
- Cannot use `final` → dependency is mutable.
- Hidden dependencies — can't tell what a class needs without reading its fields.
- Hard to test — must use a Spring context or reflection to inject mocks.
- Encourages violating SRP (easy to add 10 `@Autowired` fields).

> **Avoid in production code.** Spring itself discourages it.

---

### 2. Setter Injection

```java
@Component
public class OrderService {

    private InventoryService inventoryService;

    @Autowired
    public void setInventoryService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }
}
```

**Advantages:**
- Optional dependencies — if no bean exists, the field stays `null` (use `@Autowired(required = false)`).
- Can be re-injected after construction (useful for testing with setters).

**Disadvantages:**
- Cannot use `final` → dependency is mutable.
- Object is partially constructed between instantiation and injection.
- Not immediately obvious which dependencies are mandatory.

> **Use for optional dependencies only.**

---

### 3. Constructor Injection (Recommended)

```java
@Component
public class OrderService {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    // Spring auto-detects single-constructor — @Autowired optional in Spring 4.3+
    public OrderService(InventoryService inventoryService, PaymentService paymentService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
    }
}
```

**Advantages:**
- Dependencies are `final` → immutable, thread-safe.
- Object is fully initialized at construction — no partially wired state.
- Dependencies are explicit — visible in the constructor signature.
- Easy to test — pass mocks directly via `new OrderService(mockInv, mockPay)`.
- Circular dependency fails **fast** at startup (vs. infinite loop at runtime).

**Disadvantages:**
- More boilerplate for many dependencies (signal to refactor if this happens).

> **Prefer always.** Use Lombok's `@RequiredArgsConstructor` to eliminate boilerplate.

---

## Circular Dependency Problem

Occurs when A depends on B and B depends on A (directly or transitively).

```
OrderService → PaymentService → OrderService  ← circular!
```

With constructor injection Spring throws `BeanCurrentlyInCreationException` at startup.

### Solutions

**Option 1 — Refactor (best):** Extract the shared logic into a third bean (C) that both A and B can depend on.

**Option 2 — `@Lazy` on one side:**
```java
@Component
public class OrderService {

    private final PaymentService paymentService;

    public OrderService(@Lazy PaymentService paymentService) {
        this.paymentService = paymentService;
    }
}
```
Injects a CGLIB proxy; the real bean is created on first call. Defers the cycle — use only as a last resort.

**Option 3 — Setter injection on one side:** Breaks the constructor cycle because setters run after construction. Not recommended for the same reasons setter injection is weak.

**Option 4 — `@PostConstruct` + manual lookup:** Resolves after both beans are created. Ugly — avoid.

---

## Unsatisfied Dependency Problem

Spring throws `NoSuchBeanDefinitionException` when it can't find a bean to inject.

### Common Causes & Fixes

| Cause | Fix |
|---|---|
| Class not annotated with `@Component` | Add the annotation |
| Class is outside `@ComponentScan` base package | Move the class or extend the scan |
| Multiple beans of the same type | Use `@Qualifier("beanName")` or `@Primary` |
| Profile mismatch | Check `@Profile` and active profiles |
| Interface injected but no implementation bean | Ensure the implementation is a bean |

```java
// Multiple implementations — disambiguate with @Qualifier
@Autowired
@Qualifier("fastPaymentService")
private PaymentService paymentService;

// Or mark one as the default:
@Primary
@Component
public class FastPaymentService implements PaymentService { ... }
```

---

## Interview Angles

**Q: Why prefer constructor injection over field injection?**
Immutability (`final`), testability without Spring, fail-fast at startup, and explicit dependencies.

**Q: How does Spring resolve which bean to inject when multiple exist?**
1. By type — if exactly one bean matches.
2. By `@Primary` — if one is marked as the default.
3. By `@Qualifier` or field/parameter name — as a tiebreaker.

**Q: What is `@Autowired(required = false)`?**
The dependency is optional. If no matching bean exists, Spring injects `null` instead of throwing.

**Q: What exception does a circular dependency throw?**
`BeanCurrentlyInCreationException` (with constructor injection). With field/setter injection it may succeed or cause a `StackOverflowError` depending on the cycle.

**Q: What is the difference between `@Primary` and `@Qualifier`?**
`@Primary` is a default-candidate marker on the bean definition. `@Qualifier` is a consumer-side selector. `@Qualifier` wins when both are present.
