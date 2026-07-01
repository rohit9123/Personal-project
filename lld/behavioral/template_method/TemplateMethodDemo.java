package lld.behavioral.template_method;

// ============================================================================
// 0. A tiny Order value object used by the pipeline
// ============================================================================
class Order {
    final String id;
    final String symbol;
    final int quantity;
    final double limitPrice; // used only by limit orders; ignored by market orders

    Order(String id, String symbol, int quantity, double limitPrice) {
        this.id = id;
        this.symbol = symbol;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
    }
}

// ============================================================================
// 1. AbstractClass — owns the FIXED skeleton (the template method)
//    Skeleton: validate -> riskCheck -> beforeExecuteHook -> execute -> persist -> notify
// ============================================================================
abstract class OrderProcessor {

    // ---- THE TEMPLATE METHOD ----
    // 'final' so subclasses can NEVER reorder, skip, or replace the skeleton.
    public final void processOrder(Order order) {
        validate(order);
        riskCheck(order);
        beforeExecuteHook(order); // optional extension point (hook)
        execute(order);           // the genuinely varying step (abstract)
        persist(order);
        notifyUser(order);
    }

    // ---- Concrete steps: shared by ALL order types, implemented once ----
    protected void validate(Order order) {
        System.out.println("[validate]   Order " + order.id + " for " + order.quantity
                + " x " + order.symbol + " is well-formed.");
    }

    protected void riskCheck(Order order) {
        System.out.println("[riskCheck]  Margin and exposure limits OK for " + order.id + ".");
    }

    protected void persist(Order order) {
        System.out.println("[persist]    Order " + order.id + " written to the trade ledger.");
    }

    protected void notifyUser(Order order) {
        System.out.println("[notify]     Confirmation pushed to client for " + order.id + ".");
    }

    // ---- Abstract step: every concrete order type MUST implement this ----
    protected abstract void execute(Order order);

    // ---- Hook: default no-op; subclasses MAY override to plug in ----
    protected void beforeExecuteHook(Order order) {
        // default: do nothing
    }
}

// ============================================================================
// 2. ConcreteClass A — MarketOrder: fills immediately, no hook needed
// ============================================================================
class MarketOrderProcessor extends OrderProcessor {
    @Override
    protected void execute(Order order) {
        System.out.println("[execute]    MARKET fill: " + order.quantity + " x "
                + order.symbol + " at best available price.");
    }
    // Does NOT override beforeExecuteHook -> uses the empty default.
}

// ============================================================================
// 3. ConcreteClass B — LimitOrder: parks on the book + overrides the HOOK
// ============================================================================
class LimitOrderProcessor extends OrderProcessor {
    @Override
    protected void beforeExecuteHook(Order order) {
        // Opt into the extension point the base class offers.
        System.out.println("[hook]       Resting LIMIT order " + order.id
                + " on the book at " + order.limitPrice + ".");
    }

    @Override
    protected void execute(Order order) {
        System.out.println("[execute]    LIMIT fill: " + order.quantity + " x "
                + order.symbol + " once price hits " + order.limitPrice + ".");
    }
}

// ============================================================================
// 4. Execution Demo
// ============================================================================
public class TemplateMethodDemo {
    public static void main(String[] args) {
        OrderProcessor marketProcessor = new MarketOrderProcessor();
        OrderProcessor limitProcessor = new LimitOrderProcessor();

        // Same fixed skeleton runs for both; only the variable steps differ.
        System.out.println("=== Processing a MARKET order ===");
        marketProcessor.processOrder(new Order("ORD-1001", "RELIANCE", 50, 0.0));

        System.out.println("\n=== Processing a LIMIT order (note the extra hook line) ===");
        limitProcessor.processOrder(new Order("ORD-1002", "TCS", 20, 3850.0));
    }
}
