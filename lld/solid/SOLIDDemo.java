package lld.solid;

import java.util.ArrayList;
import java.util.List;

// ============================================================================
// This file demonstrates all 5 SOLID principles in a single cohesive example:
// An Order Processing System
//
// Each section shows the VIOLATION first (commented), then the FIX.
// ============================================================================

// ============================================================================
// [D] Dependency Inversion — define abstractions first, everything depends on these
// [I] Interface Segregation — small, focused interfaces instead of one fat one
// ============================================================================

/** ISP: Only payment-related behavior */
interface PaymentProcessor {
    boolean charge(double amount);
    String getProviderName();
}

/** ISP: Only notification-related behavior */
interface Notifier {
    void notify(String to, String message);
}

/** ISP: Only persistence-related behavior */
interface OrderRepository {
    void save(Order order);
    Order findById(String id);
}

// ============================================================================
// [S] Single Responsibility — each class has one job
// ============================================================================

/** SRP: Pure domain entity — holds data, no business logic */
class Order {
    private final String id;
    private final String customerEmail;
    private final List<LineItem> items;
    private OrderStatus status;

    Order(String id, String customerEmail) {
        this.id = id;
        this.customerEmail = customerEmail;
        this.items = new ArrayList<>();
        this.status = OrderStatus.CREATED;
    }

    void addItem(String name, double price, int qty) {
        items.add(new LineItem(name, price, qty));
    }

    double total() {
        return items.stream().mapToDouble(LineItem::subtotal).sum();
    }

    String getId() { return id; }
    String getCustomerEmail() { return customerEmail; }
    List<LineItem> getItems() { return items; }
    OrderStatus getStatus() { return status; }
    void setStatus(OrderStatus status) { this.status = status; }
}

class LineItem {
    private final String name;
    private final double price;
    private final int quantity;

    LineItem(String name, double price, int quantity) {
        this.name = name;
        this.price = price;
        this.quantity = quantity;
    }

    double subtotal() { return price * quantity; }
    String getName() { return name; }
    double getPrice() { return price; }
    int getQuantity() { return quantity; }
}

enum OrderStatus { CREATED, PAID, FAILED, NOTIFIED }

// ============================================================================
// [O] Open/Closed — add new payment providers or notifiers without editing
//     existing code. Each is a new class implementing the interface.
// [L] Liskov Substitution — every implementation is fully substitutable.
//     No UnsupportedOperationException, no silent behavior changes.
// ============================================================================

/** OCP + LSP: New payment provider = new class, same contract */
class StripePayment implements PaymentProcessor {
    @Override
    public boolean charge(double amount) {
        // Simulate Stripe API call
        System.out.printf("  [Stripe] Charging $%.2f... SUCCESS%n", amount);
        return true;
    }

    @Override
    public String getProviderName() { return "Stripe"; }
}

class PayPalPayment implements PaymentProcessor {
    @Override
    public boolean charge(double amount) {
        System.out.printf("  [PayPal] Charging $%.2f... SUCCESS%n", amount);
        return true;
    }

    @Override
    public String getProviderName() { return "PayPal"; }
}

/** Simulates a provider that declines (for testing failure paths) */
class DeclinedPayment implements PaymentProcessor {
    @Override
    public boolean charge(double amount) {
        System.out.printf("  [Declined] Charging $%.2f... DECLINED%n", amount);
        return false;
    }

    @Override
    public String getProviderName() { return "DeclinedBank"; }
}

/** OCP + LSP: Adding Slack notification = new class, no edits anywhere */
class EmailNotifier implements Notifier {
    @Override
    public void notify(String to, String message) {
        System.out.println("  [Email] To: " + to + " -> " + message);
    }
}

class SmsNotifier implements Notifier {
    @Override
    public void notify(String to, String message) {
        System.out.println("  [SMS] To: " + to + " -> " + message);
    }
}

/** SRP: Persistence is its own class, not inside OrderService */
class InMemoryOrderRepository implements OrderRepository {
    private final List<Order> store = new ArrayList<>();

    @Override
    public void save(Order order) {
        store.add(order);
        System.out.println("  [DB] Saved order " + order.getId() + " (status=" + order.getStatus() + ")");
    }

    @Override
    public Order findById(String id) {
        return store.stream().filter(o -> o.getId().equals(id)).findFirst().orElse(null);
    }
}

// ============================================================================
// [S] SRP: OrderService only orchestrates — doesn't do payment, notification,
//          or persistence itself.
// [D] DIP: Depends on interfaces (PaymentProcessor, Notifier, OrderRepository),
//          not concrete classes. Injected via constructor.
// [O] OCP: Swap Stripe for PayPal, add Slack notifier — OrderService unchanged.
// ============================================================================
class OrderService {
    private final PaymentProcessor payment;
    private final Notifier notifier;
    private final OrderRepository repo;

    // DIP: Constructor injection — all dependencies are abstractions
    OrderService(PaymentProcessor payment, Notifier notifier, OrderRepository repo) {
        this.payment = payment;
        this.notifier = notifier;
        this.repo = repo;
    }

    void placeOrder(Order order) {
        System.out.println("\nPlacing order " + order.getId()
                + " (total=$" + String.format("%.2f", order.total())
                + ", provider=" + payment.getProviderName() + ")");

        // 1. Charge
        boolean charged = payment.charge(order.total());

        if (charged) {
            order.setStatus(OrderStatus.PAID);
            repo.save(order);

            // 2. Notify
            notifier.notify(order.getCustomerEmail(),
                    "Order " + order.getId() + " confirmed! Total: $"
                            + String.format("%.2f", order.total()));
            order.setStatus(OrderStatus.NOTIFIED);
        } else {
            order.setStatus(OrderStatus.FAILED);
            repo.save(order);

            notifier.notify(order.getCustomerEmail(),
                    "Order " + order.getId() + " failed — payment declined.");
        }
    }
}

// ============================================================================
// Test Driver
// ============================================================================
public class SOLIDDemo {
    public static void main(String[] args) {
        System.out.println("--- SOLID Principles Demo: Order Processing System ---");

        InMemoryOrderRepository repo = new InMemoryOrderRepository();

        // ===== Test 1: Stripe + Email (happy path) =====
        System.out.println("\n===== TEST 1: Stripe + Email =====");
        OrderService service1 = new OrderService(
                new StripePayment(),    // DIP: injected
                new EmailNotifier(),    // DIP: injected
                repo                    // DIP: injected
        );

        Order order1 = new Order("ORD-001", "alice@example.com");
        order1.addItem("Keyboard", 79.99, 1);
        order1.addItem("Mouse", 29.99, 2);
        service1.placeOrder(order1);

        assert order1.getStatus() == OrderStatus.NOTIFIED : "Order should be NOTIFIED";
        System.out.println("PASS: Order placed with Stripe + Email");

        // ===== Test 2: PayPal + SMS (swap providers — OCP in action) =====
        System.out.println("\n===== TEST 2: PayPal + SMS (OCP — zero code changes) =====");
        OrderService service2 = new OrderService(
                new PayPalPayment(),    // OCP: new provider, no edits
                new SmsNotifier(),      // OCP: new notifier, no edits
                repo
        );

        Order order2 = new Order("ORD-002", "+1-555-1234");
        order2.addItem("Monitor", 349.99, 1);
        service2.placeOrder(order2);

        assert order2.getStatus() == OrderStatus.NOTIFIED : "Order should be NOTIFIED";
        System.out.println("PASS: Swapped to PayPal + SMS without touching OrderService");

        // ===== Test 3: Payment declined (LSP — DeclinedPayment is substitutable) =====
        System.out.println("\n===== TEST 3: Payment Declined (LSP — substitutable) =====");
        OrderService service3 = new OrderService(
                new DeclinedPayment(),  // LSP: follows same contract, returns false
                new EmailNotifier(),
                repo
        );

        Order order3 = new Order("ORD-003", "bob@example.com");
        order3.addItem("GPU", 999.99, 1);
        service3.placeOrder(order3);

        assert order3.getStatus() == OrderStatus.FAILED : "Order should be FAILED";
        System.out.println("PASS: Declined payment handled correctly via LSP");

        // ===== Test 4: Multi-notifier (OCP — compose without modification) =====
        System.out.println("\n===== TEST 4: Multi-notifier via Composition =====");
        // Demonstrate OCP further: a composite notifier that sends via multiple channels
        Notifier multiNotifier = (to, msg) -> {
            new EmailNotifier().notify(to, msg);
            new SmsNotifier().notify(to, msg);
        };

        OrderService service4 = new OrderService(new StripePayment(), multiNotifier, repo);

        Order order4 = new Order("ORD-004", "carol@example.com");
        order4.addItem("Laptop", 1299.99, 1);
        service4.placeOrder(order4);

        assert order4.getStatus() == OrderStatus.NOTIFIED : "Order should be NOTIFIED";
        System.out.println("PASS: Multi-channel notification without modifying OrderService");

        // ===== Summary =====
        System.out.println("\n===== SOLID Summary =====");
        System.out.println("[S] OrderService has one job: orchestration");
        System.out.println("[O] Added PayPal, SMS, Declined, Multi-notifier — zero edits to OrderService");
        System.out.println("[L] DeclinedPayment is fully substitutable — no surprises");
        System.out.println("[I] PaymentProcessor, Notifier, OrderRepository — small focused interfaces");
        System.out.println("[D] OrderService depends on interfaces, injected via constructor");
    }
}
