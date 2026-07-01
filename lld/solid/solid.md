# SOLID Principles — Complete Guide for SDE-2

## Quick Summary (TL;DR)
* **SOLID** = five design principles that make OOP code maintainable, extensible, and testable.
* Every design pattern you've studied is an application of one or more SOLID principles.
* Interviewers ask these **directly** ("Explain OCP with an example") and **indirectly** (reviewing your LLD code for violations).

| Letter | Principle | One-liner |
|--------|-----------|-----------|
| **S** | Single Responsibility | A class should have only one reason to change |
| **O** | Open/Closed | Open for extension, closed for modification |
| **L** | Liskov Substitution | Subtypes must be substitutable for their base types |
| **I** | Interface Segregation | No client should be forced to depend on methods it doesn't use |
| **D** | Dependency Inversion | Depend on abstractions, not concretions |

---

## 🤓 Noob Jargon Buster

* **"Reason to change"**: A stakeholder or requirement that could cause you to modify a class. If the CEO and the DBA both cause changes to the same class, it has two reasons to change → SRP violation.
* **"Open for extension"**: You can add new behavior (new class, new implementation) without editing existing code.
* **"Closed for modification"**: Existing, tested code stays untouched when requirements change.
* **"Substitutable"**: If code works with `Animal a`, it must also work correctly with `Dog a` or `Cat a` — no surprises, no exceptions that the parent didn't throw.
* **"Fat interface"**: An interface with too many methods, forcing implementors to write empty stubs for methods they don't need.

---

## S — Single Responsibility Principle (SRP)

> *A class should have only one reason to change.*

### Bad Example
```java
class Employee {
    void calculatePay()   { /* payroll logic */ }
    void saveToDatabase() { /* persistence logic */ }
    void generateReport() { /* PDF formatting */ }
}
```
Three reasons to change: payroll rules, DB schema, report format. Three different teams could break each other.

### Good Example
```java
class Employee {
    private String name;
    private double salary;
    // pure domain data — getters/setters only
}

class PayrollService {
    double calculatePay(Employee e) { /* payroll logic */ }
}

class EmployeeRepository {
    void save(Employee e) { /* persistence logic */ }
}

class ReportGenerator {
    void generateReport(Employee e) { /* PDF formatting */ }
}
```

### How to spot SRP violations
- Class name contains "And" or "Manager" or "Processor" (doing too much)
- Class imports from unrelated packages (e.g., `java.sql.*` + `javax.mail.*`)
- Multiple private methods that never call each other (independent responsibilities)

### From your LLD problems
- **Parking Lot**: `ParkingLot` delegates to `FeeCalculator` (pricing) and `AllocationStrategy` (spot selection) — each has one reason to change.
- **Elevator System**: `Elevator` handles movement, `DispatchStrategy` handles assignment — not one giant class.

---

## O — Open/Closed Principle (OCP)

> *Software entities should be open for extension but closed for modification.*

### Bad Example
```java
class NotificationService {
    void send(String type, String message) {
        if (type.equals("EMAIL")) {
            // send email
        } else if (type.equals("SMS")) {
            // send SMS
        } else if (type.equals("PUSH")) {
            // send push notification
        }
        // Adding Slack? Edit this method. Violates OCP.
    }
}
```

### Good Example
```java
interface NotificationChannel {
    void send(String message);
}

class EmailChannel implements NotificationChannel {
    public void send(String message) { /* send email */ }
}

class SmsChannel implements NotificationChannel {
    public void send(String message) { /* send SMS */ }
}

// Adding Slack? Just add a new class. Zero changes to existing code.
class SlackChannel implements NotificationChannel {
    public void send(String message) { /* send to Slack */ }
}

class NotificationService {
    private final List<NotificationChannel> channels;

    NotificationService(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    void notifyAll(String message) {
        channels.forEach(ch -> ch.send(message));
    }
}
```

### The pattern connection
- **Strategy Pattern** = OCP for algorithms (swap without modifying client)
- **Observer Pattern** = OCP for event handling (add listeners without modifying publisher)
- **Decorator Pattern** = OCP for adding behavior (wrap without modifying original)

### From your LLD problems
- **Parking Lot**: Adding `EVPreferredStrategy` is just a new class — `ParkingLot` stays untouched.
- **Vending Machine**: Adding `MaintenanceState` is just a new `State` implementation — zero changes to existing states.

---

## L — Liskov Substitution Principle (LSP)

> *If S is a subtype of T, then objects of type T can be replaced with objects of type S without altering the correctness of the program.*

### The Classic Violation: Square extends Rectangle
```java
class Rectangle {
    protected int width, height;

    void setWidth(int w)  { this.width = w; }
    void setHeight(int h) { this.height = h; }
    int area() { return width * height; }
}

class Square extends Rectangle {
    @Override
    void setWidth(int w)  { this.width = w; this.height = w; }
    @Override
    void setHeight(int h) { this.width = h; this.height = h; }
}
```
```java
void resize(Rectangle r) {
    r.setWidth(5);
    r.setHeight(10);
    assert r.area() == 50; // FAILS for Square! area() returns 100
}
```
`Square` breaks the contract of `Rectangle` — callers expect width and height to be independent.

### Fix: Use composition or separate abstractions
```java
interface Shape {
    int area();
}

class Rectangle implements Shape {
    private final int width, height;
    Rectangle(int w, int h) { this.width = w; this.height = h; }
    public int area() { return width * height; }
}

class Square implements Shape {
    private final int side;
    Square(int s) { this.side = s; }
    public int area() { return side * side; }
}
```

### LSP Checklist (for interviews)
A subtype must satisfy:
1. **Preconditions cannot be strengthened** — if parent accepts `null`, child can't reject it
2. **Postconditions cannot be weakened** — if parent guarantees non-null return, child must too
3. **Invariants must be preserved** — if parent guarantees `balance >= 0`, child must too
4. **No new exceptions** — child shouldn't throw exceptions the parent's contract doesn't declare

### How to spot LSP violations
- Override that throws `UnsupportedOperationException` (e.g., `Collections.unmodifiableList().add()`)
- Override that silently changes behavior (Square.setWidth modifying height)
- `instanceof` checks before calling a method — means subtypes aren't truly substitutable

---

## I — Interface Segregation Principle (ISP)

> *No client should be forced to depend on methods it does not use.*

### Bad Example
```java
interface Worker {
    void work();
    void eat();
    void sleep();
}

class Robot implements Worker {
    public void work()  { /* yes */ }
    public void eat()   { /* ??? robots don't eat */ }
    public void sleep() { /* ??? robots don't sleep */ }
}
```

### Good Example
```java
interface Workable {
    void work();
}

interface Eatable {
    void eat();
}

interface Sleepable {
    void sleep();
}

class Human implements Workable, Eatable, Sleepable {
    public void work()  { /* yes */ }
    public void eat()   { /* yes */ }
    public void sleep() { /* yes */ }
}

class Robot implements Workable {
    public void work() { /* yes, only what it needs */ }
}
```

### Real-world Java example
```java
// Fat interface (Java's own mistake)
interface List<E> extends Collection<E> {
    // 25+ methods — ArrayList implements all,
    // but Arrays.asList() returns a fixed-size list where
    // add(), remove() throw UnsupportedOperationException
    // That's ISP + LSP violation combined
}

// Better: Java 9+ introduced List.of() which returns
// an unmodifiable list — a different type entirely
```

### From your LLD problems
- **Vending Machine**: `State` interface has 4 methods. Each concrete state throws errors for invalid operations (e.g., `IdleState.dispense()` → error). This is a minor ISP tension — acceptable here because states are internal, but in a larger system you'd consider splitting.

---

## D — Dependency Inversion Principle (DIP)

> *High-level modules should not depend on low-level modules. Both should depend on abstractions.*

### Bad Example
```java
class OrderService {
    private MySQLDatabase db = new MySQLDatabase(); // concrete dependency
    private SmtpEmailSender email = new SmtpEmailSender(); // concrete dependency

    void placeOrder(Order order) {
        db.save(order);
        email.sendConfirmation(order);
    }
}
```
`OrderService` (high-level business logic) is tightly coupled to MySQL and SMTP. Switching to Postgres or SendGrid requires editing `OrderService`.

### Good Example
```java
interface OrderRepository {
    void save(Order order);
}

interface NotificationService {
    void sendConfirmation(Order order);
}

class OrderService {
    private final OrderRepository repo;
    private final NotificationService notifier;

    // Dependencies injected via constructor
    OrderService(OrderRepository repo, NotificationService notifier) {
        this.repo = repo;
        this.notifier = notifier;
    }

    void placeOrder(Order order) {
        repo.save(order);
        notifier.sendConfirmation(order);
    }
}
```
Now `OrderService` depends only on interfaces. Swap MySQL for Postgres, SMTP for SendGrid — `OrderService` doesn't change.

### DIP in Spring Boot (your daily framework)
```java
@Service
class OrderService {
    private final OrderRepository repo; // interface — Spring injects the implementation

    OrderService(OrderRepository repo) { // constructor injection = DIP in action
        this.repo = repo;
    }
}
```
Spring's entire DI container is built on DIP. Every `@Autowired` / constructor injection is this principle.

### From your LLD problems
- **Parking Lot**: `ParkingLot` depends on `AllocationStrategy` (interface), not `NearestFirstStrategy` (concrete). Injected via constructor.
- **Elevator System**: `ElevatorSystem` depends on `DispatchStrategy` (interface), not `NearestInDirectionStrategy`.

---

## How They Work Together

```
┌─────────────────────────────────────────────────────────────┐
│  You get a new requirement: "Add Slack notifications"       │
│                                                             │
│  SRP  → NotificationService is separate from OrderService   │
│  OCP  → Add SlackChannel, don't edit existing channels      │
│  LSP  → SlackChannel works wherever NotificationChannel     │
│         is expected                                         │
│  ISP  → SlackChannel only implements send(), not unrelated  │
│         methods                                             │
│  DIP  → OrderService depends on NotificationChannel         │
│         interface, not SlackChannel concrete class           │
└─────────────────────────────────────────────────────────────┘
```

---

## SOLID ↔ Design Pattern Mapping

| Principle | Patterns that embody it |
|-----------|------------------------|
| **SRP** | Facade (simplifies multi-responsibility surface), MVC |
| **OCP** | Strategy, Observer, Decorator, Factory, State |
| **LSP** | Template Method (subtypes follow parent's algorithm skeleton) |
| **ISP** | Adapter (adapts fat interface to thin one), Observer (small listener interfaces) |
| **DIP** | Factory (creates via interface), Strategy (injected), Observer (decoupled publisher/subscriber) |

---

## SDE-2 Interview Angles

### Question 1: "Give me a real example of OCP from your work"
* "In our notification system, each channel (email, SMS, push) implements a `NotificationChannel` interface. When we added Slack, we just added a new class and registered it — zero changes to the dispatch logic."
* **Tie to pattern**: "This is the Strategy Pattern applied to notification delivery."

### Question 2: "What's the difference between SRP and ISP?"
* **SRP** is about **classes** — a class should have one reason to change.
* **ISP** is about **interfaces** — an interface should not force implementors to depend on methods they don't use.
* SRP asks "does this class do too many things?" ISP asks "does this interface require too many things?"

### Question 3: "When is it OK to violate SOLID?"
* **SRP**: Early-stage prototypes — splitting everything slows you down when requirements are unclear.
* **OCP**: If a class has exactly 2 variants and will never grow, a simple if-else is clearer than a full Strategy hierarchy.
* **LSP**: `Collections.unmodifiableList()` intentionally violates LSP for safety — documented and accepted tradeoff.
* **ISP**: If all implementors genuinely use all methods, one interface is simpler than five.
* **DIP**: Don't inject `String` or `int` — DIP is for service-level dependencies, not primitives.
* **Key interview answer**: "SOLID are guidelines, not laws. Over-applying them creates unnecessary abstraction. The goal is maintainability — if a violation is simpler AND doesn't hurt extensibility, it's fine."

### Question 4: "How does Spring Boot use SOLID?"
* **SRP**: `@Controller`, `@Service`, `@Repository` — each layer has one job.
* **OCP**: Auto-configuration — add a dependency, Spring configures it. You extend behavior by adding beans, not editing framework code.
* **DIP**: The entire IoC container. `@Autowired` injects interfaces, not implementations.
* **ISP**: `CrudRepository` vs `JpaRepository` vs `PagingAndSortingRepository` — pick the interface granularity you need.

### Question 5: "Refactor this code to follow SOLID" (live coding)
* They'll give you a god class with 200 lines. Steps:
  1. **Identify responsibilities** (SRP) → extract services
  2. **Find if-else/switch on type** (OCP) → extract to Strategy/Factory
  3. **Check inheritance hierarchies** (LSP) → verify subtypes honor contracts
  4. **Look for fat interfaces** (ISP) → split
  5. **Look for `new ConcreteClass()`** in business logic (DIP) → inject via constructor
