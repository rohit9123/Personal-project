package lld.structural.composite;


import java.util.ArrayList;
import java.util.List;

// ============================================================================
// 1. Component Interface (the common contract for leaves AND containers)
//    The client only ever programs to THIS type.
// ============================================================================
interface Asset {
    double getMarketValue();          // recursive in a Portfolio, terminal in a Holding
    void print(String indent);        // pretty-print this node (and any children)
}

// ============================================================================
// 2. Leaf (a single, indivisible stock position — has NO children)
//    getMarketValue() is the recursion's base case: quantity * price, then stop.
// ============================================================================
class Holding implements Asset {
    private final String symbol;
    private final int quantity;
    private final double price;

    public Holding(String symbol, int quantity, double price) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }

    @Override
    public double getMarketValue() {
        return quantity * price; // base case — no recursion
    }

    @Override
    public void print(String indent) {
        System.out.println(indent + "- " + symbol + " x" + quantity
                + " @ " + price + "  => " + getMarketValue());
    }
}

// ============================================================================
// 3. Composite (a Portfolio that HOLDS children, each itself an Asset)
//    add()/remove() live ONLY here (the "safe" design — a leaf can't have them).
//    getMarketValue() delegates to every child and aggregates -> recursion.
// ============================================================================
class Portfolio implements Asset {
    private final String name;
    private final List<Asset> children = new ArrayList<>();

    public Portfolio(String name) {
        this.name = name;
    }

    // Child management — only meaningful on a container, so declared only here.
    public void add(Asset asset) {
        children.add(asset);
    }

    public void remove(Asset asset) {
        children.remove(asset);
    }

    @Override
    public double getMarketValue() {
        double total = 0;
        for (Asset child : children) {
            total += child.getMarketValue(); // recurse — child may be a Holding OR a Portfolio
        }
        return total;
    }

    @Override
    public void print(String indent) {
        System.out.println(indent + "+ [" + name + "]  (subtotal => " + getMarketValue() + ")");
        for (Asset child : children) {
            child.print(indent + "    "); // recurse uniformly into each child
        }
    }
}

// ============================================================================
// 4. Execution Demo — build a portfolio tree and operate on it UNIFORMLY
// ============================================================================
public class CompositePatternDemo {
    public static void main(String[] args) {
        // Leaves: single stock positions
        Asset infy = new Holding("INFY", 100, 1500.0);
        Asset tcs  = new Holding("TCS",  50,  3800.0);

        // A nested sub-portfolio (a Composite that will live inside another Composite)
        Portfolio trading = new Portfolio("Trading");
        trading.add(new Holding("RELIANCE", 20, 2900.0));
        trading.add(new Holding("HDFCBANK", 30, 1600.0));

        // Root portfolio — freely mixes leaves AND a sub-portfolio
        Portfolio account = new Portfolio("Rohit's Account");
        account.add(infy);
        account.add(tcs);
        account.add(trading); // a Portfolio added as if it were just another Asset

        // The client treats the WHOLE TREE uniformly via the Asset interface.
        account.print("");

        // One call recurses across the entire tree and aggregates.
        System.out.println("\nTotal account market value = " + account.getMarketValue());

        // Same method works on a single leaf too — no instanceof, no branching.
        Asset single = infy;
        System.out.println("Single holding value (INFY) = " + single.getMarketValue());
    }
}
