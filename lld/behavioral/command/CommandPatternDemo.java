package lld.behavioral.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

// ============================================================================
// 0. Domain model (a simple stock-broker order)
// ============================================================================
class Order {
    final String symbol;
    final int quantity;
    double price;        // mutable: a MODIFY changes this
    final String side;   // "BUY" / "SELL"

    Order(String symbol, String side, int quantity, double price) {
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
    }

    // Snapshot copy so undo can restore the exact state it cancelled.
    Order copy() {
        return new Order(symbol, side, quantity, price);
    }

    @Override
    public String toString() {
        return side + " " + quantity + " " + symbol + " @ " + price;
    }
}

// ============================================================================
// 1. Command Interface (the request contract: execute + undo)
// ============================================================================
interface Command {
    void execute();
    void undo();
    String name();
}

// ============================================================================
// 2. Receiver (knows the real business logic: the exchange / order book)
// ============================================================================
class OrderService {
    private final Map<String, Order> book = new HashMap<>();
    private int seq = 1;

    // Returns the exchange-assigned order id.
    String placeOrder(Order order) {
        String id = "ORD-" + (seq++);
        book.put(id, order);
        System.out.println("   [Exchange] PLACED " + id + " -> " + order);
        return id;
    }

    // Re-place a previously known order under the SAME id (used by undo-of-cancel).
    void placeOrderWithId(String id, Order order) {
        book.put(id, order);
        System.out.println("   [Exchange] RE-PLACED " + id + " -> " + order);
    }

    Order cancelOrder(String id) {
        Order removed = book.remove(id);
        System.out.println("   [Exchange] CANCELLED " + id + " -> " + removed);
        return removed;
    }

    void modifyPrice(String id, double newPrice) {
        Order o = book.get(id);
        if (o == null) throw new IllegalStateException("No such order: " + id);
        System.out.println("   [Exchange] MODIFIED " + id + " price " + o.price + " -> " + newPrice);
        o.price = newPrice;
    }
}

// ============================================================================
// 3a. ConcreteCommand: PLACE (captures the assigned id so undo can cancel it)
// ============================================================================
class PlaceOrderCommand implements Command {
    private final OrderService receiver;
    private final Order order;
    private String assignedOrderId; // captured at execute() for undo

    PlaceOrderCommand(OrderService receiver, Order order) {
        this.receiver = receiver;
        this.order = order;
    }

    @Override public void execute() {
        assignedOrderId = receiver.placeOrder(order);
    }

    @Override public void undo() {
        // Undo of PLACE = CANCEL the order we created.
        receiver.cancelOrder(assignedOrderId);
    }

    @Override public String name() { return "PLACE " + order; }

    String getAssignedOrderId() { return assignedOrderId; }
}

// ============================================================================
// 3b. ConcreteCommand: CANCEL (snapshots the order so undo can re-place it)
// ============================================================================
class CancelOrderCommand implements Command {
    private final OrderService receiver;
    private final String orderId;
    private Order snapshot; // captured at execute() for undo

    CancelOrderCommand(OrderService receiver, String orderId) {
        this.receiver = receiver;
        this.orderId = orderId;
    }

    @Override public void execute() {
        Order removed = receiver.cancelOrder(orderId);
        this.snapshot = (removed == null) ? null : removed.copy();
    }

    @Override public void undo() {
        // Undo of CANCEL = re-place the snapshotted order under the same id.
        if (snapshot != null) receiver.placeOrderWithId(orderId, snapshot);
    }

    @Override public String name() { return "CANCEL " + orderId; }
}

// ============================================================================
// 3c. ConcreteCommand: MODIFY (captures old price so undo can restore it)
// ============================================================================
class ModifyOrderCommand implements Command {
    private final OrderService receiver;
    private final String orderId;
    private final double newPrice;
    private double oldPrice; // captured at execute() for undo

    // Demo-only flag to simulate a transient exchange failure for the retry section.
    private boolean failOnce;

    ModifyOrderCommand(OrderService receiver, String orderId, double newPrice) {
        this.receiver = receiver;
        this.orderId = orderId;
        this.newPrice = newPrice;
    }

    ModifyOrderCommand failingOnce() { this.failOnce = true; return this; }

    @Override public void execute() {
        if (failOnce) {
            failOnce = false; // next attempt will succeed
            throw new RuntimeException("transient exchange timeout");
        }
        // Capture the PRE-state (old price) before mutating, so undo can restore it.
        oldPrice = PriceRegistry.get(orderId);
        receiver.modifyPrice(orderId, newPrice);
        PriceRegistry.set(orderId, newPrice); // keep the registry in sync
    }

    @Override public void undo() {
        // Undo of MODIFY = restore the previous price.
        receiver.modifyPrice(orderId, oldPrice);
        PriceRegistry.set(orderId, oldPrice);
    }

    @Override public String name() { return "MODIFY " + orderId + " -> " + newPrice; }
}

// ============================================================================
// 3d. Tiny price registry so MODIFY can capture the pre-state cleanly.
//      (In real code the Receiver would expose getPrice(id); kept separate here
//       to keep the Receiver minimal while still demonstrating undo state capture.)
// ============================================================================
class PriceRegistry {
    private static final Map<String, Double> prices = new HashMap<>();
    static void set(String id, double p) { prices.put(id, p); }
    static double get(String id) { return prices.getOrDefault(id, 0.0); }
}

// ============================================================================
// 4. Invoker (OrderBroker): triggers commands, keeps history for undo,
//    and a pending queue for transactional/deferred + retryable execution.
//    It NEVER inspects what a command actually does.
// ============================================================================
class OrderBroker {
    private final Deque<Command> history = new ArrayDeque<>();   // for undo (LIFO)
    private final Queue<Command> pending = new ArrayDeque<>();   // for queued execution

    // Execute now and record for undo.
    void submit(Command cmd) {
        System.out.println("[Broker] submit -> " + cmd.name());
        cmd.execute();
        history.push(cmd);
    }

    // Defer execution (transactional queuing / scheduling).
    void enqueue(Command cmd) {
        System.out.println("[Broker] enqueue -> " + cmd.name());
        pending.add(cmd);
    }

    // Drain the queue in order, with simple retry on failure (the retry use case).
    void processQueue() {
        System.out.println("[Broker] draining queue (" + pending.size() + " commands)...");
        while (!pending.isEmpty()) {
            Command cmd = pending.poll();
            runWithRetry(cmd, 2);
        }
    }

    private void runWithRetry(Command cmd, int maxAttempts) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                cmd.execute();
                history.push(cmd);
                return;
            } catch (RuntimeException ex) {
                System.out.println("   [Broker] attempt " + attempt + " failed for "
                        + cmd.name() + " (" + ex.getMessage() + ")");
                if (attempt == maxAttempts) {
                    System.out.println("   [Broker] DEAD-LETTER: " + cmd.name());
                }
            }
        }
    }

    void undoLast() {
        if (history.isEmpty()) {
            System.out.println("[Broker] nothing to undo");
            return;
        }
        Command cmd = history.pop();
        System.out.println("[Broker] undo -> " + cmd.name());
        cmd.undo();
    }
}

// ============================================================================
// 5. Client / Execution Demo
// ============================================================================
public class CommandPatternDemo {
    public static void main(String[] args) {
        OrderService exchange = new OrderService();   // Receiver
        OrderBroker broker = new OrderBroker();       // Invoker

        // --------------------------------------------------------------------
        // FLOW 1: execute + UNDO (the killer feature)
        // --------------------------------------------------------------------
        System.out.println("=== FLOW 1: place, modify, then UNDO the modify ===");
        Order infy = new Order("INFY", "BUY", 50, 1500.0);

        PlaceOrderCommand place = new PlaceOrderCommand(exchange, infy);
        broker.submit(place);                         // PLACED ORD-1
        String id = place.getAssignedOrderId();
        PriceRegistry.set(id, infy.price);            // track price for undo capture

        ModifyOrderCommand modify = new ModifyOrderCommand(exchange, id, 1490.0);
        broker.submit(modify);                        // price 1500 -> 1490

        broker.undoLast();                            // restores price 1490 -> 1500
        System.out.println();

        // --------------------------------------------------------------------
        // FLOW 2: transactional QUEUING (commands as deferred units of work)
        // --------------------------------------------------------------------
        System.out.println("=== FLOW 2: queue several commands, then drain in order ===");
        broker.enqueue(new PlaceOrderCommand(exchange, new Order("TCS", "BUY", 10, 3800.0)));
        broker.enqueue(new PlaceOrderCommand(exchange, new Order("WIPRO", "SELL", 100, 410.0)));
        broker.enqueue(new CancelOrderCommand(exchange, id)); // cancel the INFY order
        broker.processQueue();
        System.out.println();

        // --------------------------------------------------------------------
        // FLOW 3: RETRY of a failed command
        // --------------------------------------------------------------------
        System.out.println("=== FLOW 3: a command fails once, broker retries it ===");
        Order hdfc = new Order("HDFCBANK", "BUY", 5, 1650.0);
        PlaceOrderCommand placeHdfc = new PlaceOrderCommand(exchange, hdfc);
        broker.submit(placeHdfc);
        String hdfcId = placeHdfc.getAssignedOrderId();
        PriceRegistry.set(hdfcId, hdfc.price);

        // This MODIFY throws on its first execute(), succeeds on retry.
        broker.enqueue(new ModifyOrderCommand(exchange, hdfcId, 1660.0).failingOnce());
        broker.processQueue();
        System.out.println();

        // --------------------------------------------------------------------
        // FLOW 4: UNDO unwinds the most recent successful command (LIFO history)
        // --------------------------------------------------------------------
        System.out.println("=== FLOW 4: undo the last action again ===");
        broker.undoLast();
    }
}
