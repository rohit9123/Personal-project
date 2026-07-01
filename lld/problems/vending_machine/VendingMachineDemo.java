package lld.problems.vending_machine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ============================================================================
// 1. Coin Denominations
// ============================================================================
enum Coin {
    PENNY(1), NICKEL(5), DIME(10), QUARTER(25);

    private final int value;

    Coin(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

// ============================================================================
// 2. Product and Inventory Entities
// ============================================================================
class Product {
    private final String name;
    private final int price; // in cents

    public Product(String name, int price) {
        this.name = name;
        this.price = price;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }
}

class Inventory {
    private final Map<Integer, Product> slots = new HashMap<>();
    private final Map<Integer, Integer> stock = new HashMap<>();

    public void addItem(int code, Product product, int quantity) {
        slots.put(code, product);
        stock.put(code, quantity);
    }

    public Product getProduct(int code) {
        return slots.get(code);
    }

    public boolean isAvailable(int code) {
        return slots.containsKey(code) && stock.getOrDefault(code, 0) > 0;
    }

    public void decreaseStock(int code) {
        if (slots.containsKey(code)) {
            stock.put(code, stock.get(code) - 1);
        }
    }

    public int getStockCount(int code) {
        return stock.getOrDefault(code, 0);
    }
}

// ============================================================================
// 3. State Interface
// ============================================================================
interface State {
    void insertCoin(VendingMachine machine, Coin coin);
    void pressSelectProduct(VendingMachine machine, int code);
    void pressRefund(VendingMachine machine);
    void dispense(VendingMachine machine, int code);
}

// ============================================================================
// 4. Concrete States
// ============================================================================
class IdleState implements State {
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("[IdleState] Coin inserted: " + coin.name() + " (" + coin.getValue() + "¢)");
        machine.addCoin(coin);
        machine.setState(new HasMoneyState());
    }

    @Override
    public void pressSelectProduct(VendingMachine machine, int code) {
        System.out.println("[IdleState] ERROR: Please insert coins first before selecting a product.");
    }

    @Override
    public void pressRefund(VendingMachine machine) {
        System.out.println("[IdleState] ERROR: No coins to refund. Machine is idle.");
    }

    @Override
    public void dispense(VendingMachine machine, int code) {
        System.out.println("[IdleState] ERROR: Cannot dispense. No payment made.");
    }
}

class HasMoneyState implements State {
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("[HasMoneyState] Additional coin inserted: " + coin.name() + " (" + coin.getValue() + "¢)");
        machine.addCoin(coin);
        System.out.println("  -> Current Total Balance: " + machine.getInsertedCoinsValue() + "¢");
    }

    @Override
    public void pressSelectProduct(VendingMachine machine, int code) {
        Inventory inventory = machine.getInventory();
        
        // 1. Verify existence and stock
        if (!inventory.isAvailable(code)) {
            System.out.println("[HasMoneyState] ERROR: Item with code " + code + " is out of stock!");
            // Auto refund inserted money on out-of-stock selection
            pressRefund(machine);
            return;
        }

        Product product = inventory.getProduct(code);
        int balance = machine.getInsertedCoinsValue();

        // 2. Verify payment balance
        if (balance >= product.getPrice()) {
            System.out.println("[HasMoneyState] Success! Sufficient balance for " + product.getName() + " (" + product.getPrice() + "¢).");
            machine.setState(new DispenseState());
            machine.dispense(code);
        } else {
            System.out.println("[HasMoneyState] ERROR: Insufficient balance! " + product.getName() + " costs " + product.getPrice() + "¢, but you only have " + balance + "¢.");
        }
    }

    @Override
    public void pressRefund(VendingMachine machine) {
        int refundValue = machine.getInsertedCoinsValue();
        System.out.println("[HasMoneyState] Refund processing... Returning " + refundValue + "¢ to customer.");
        machine.clearCoins();
        machine.setState(new IdleState());
    }

    @Override
    public void dispense(VendingMachine machine, int code) {
        System.out.println("[HasMoneyState] ERROR: Please make a valid product selection first.");
    }
}

class DispenseState implements State {
    @Override
    public void insertCoin(VendingMachine machine, Coin coin) {
        System.out.println("[DispenseState] ERROR: Cannot accept coins. Currently dispensing.");
    }

    @Override
    public void pressSelectProduct(VendingMachine machine, int code) {
        System.out.println("[DispenseState] ERROR: Dispense in progress. Cannot select another item.");
    }

    @Override
    public void pressRefund(VendingMachine machine) {
        System.out.println("[DispenseState] ERROR: Cannot refund during item dispensing.");
    }

    @Override
    public void dispense(VendingMachine machine, int code) {
        Inventory inventory = machine.getInventory();
        Product product = inventory.getProduct(code);

        // 1. Drop item & reduce inventory
        inventory.decreaseStock(code);
        System.out.println("[DispenseState] SUCCESS: Dispensing '" + product.getName() + "'!");

        // 2. Return change
        int changeValue = machine.getInsertedCoinsValue() - product.getPrice();
        if (changeValue > 0) {
            System.out.println("[DispenseState] Returning change: " + changeValue + "¢");
        }

        // 3. Clear coins and return to Idle
        machine.clearCoins();
        machine.setState(new IdleState());
        System.out.println("[DispenseState] Machine is now IDLE.");
    }
}

// ============================================================================
// 5. Context class managing states & inventory
// ============================================================================
class VendingMachine {
    private State currentState;
    private final Inventory inventory = new Inventory();
    private final List<Coin> insertedCoins = new ArrayList<>();

    public VendingMachine() {
        this.currentState = new IdleState(); // Initial state
    }

    public void setState(State state) {
        this.currentState = state;
    }

    public State getCurrentState() {
        return currentState;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void addCoin(Coin coin) {
        insertedCoins.add(coin);
    }

    public int getInsertedCoinsValue() {
        int total = 0;
        for (Coin coin : insertedCoins) {
            total += coin.getValue();
        }
        return total;
    }

    public void clearCoins() {
        insertedCoins.clear();
    }

    // Actions
    public void insertCoin(Coin coin) {
        currentState.insertCoin(this, coin);
    }

    public void pressSelectProduct(int code) {
        currentState.pressSelectProduct(this, code);
    }

    public void pressRefund() {
        currentState.pressRefund(this);
    }

    public void dispense(int code) {
        currentState.dispense(this, code);
    }
}

// ============================================================================
// 6. Test Driver
// ============================================================================
public class VendingMachineDemo {
    public static void main(String[] args) {
        System.out.println("--- Vending Machine LLD Simulation ---");
        
        // 1. Initialize machine and inventory
        VendingMachine machine = new VendingMachine();
        Inventory inventory = machine.getInventory();
        
        inventory.addItem(101, new Product("Coke", 75), 2);   // Code 101: Coke (75¢, stock=2)
        inventory.addItem(102, new Product("Pepsi", 60), 1);  // Code 102: Pepsi (60¢, stock=1)
        inventory.addItem(103, new Product("Soda", 45), 5);   // Code 103: Soda (45¢, stock=5)

        // ==========================================
        // Test Case 1: Standard Purchase (Exact / Overpay)
        // ==========================================
        System.out.println("\n===== TEST CASE 1: Standard Purchase (Coke - 75¢) =====");
        machine.insertCoin(Coin.QUARTER); // 25
        machine.insertCoin(Coin.QUARTER); // 50
        machine.insertCoin(Coin.QUARTER); // 75
        machine.insertCoin(Coin.DIME);    // 85 (Overpay)
        
        machine.pressSelectProduct(101); // Dispenses Coke, returns 10¢ change
        
        // ==========================================
        // Test Case 2: Refund Request
        // ==========================================
        System.out.println("\n===== TEST CASE 2: Refund Request =====");
        machine.insertCoin(Coin.QUARTER); // 25
        machine.insertCoin(Coin.DIME);    // 35
        
        machine.pressRefund(); // Returns 35¢, transitions to Idle
        
        // ==========================================
        // Test Case 3: Insufficient Balance
        // ==========================================
        System.out.println("\n===== TEST CASE 3: Insufficient Balance (Pepsi - 60¢) =====");
        machine.insertCoin(Coin.QUARTER); // 25
        machine.insertCoin(Coin.NICKEL);  // 30
        
        machine.pressSelectProduct(102); // Fails (costs 60¢)
        
        // Insert remaining balance
        machine.insertCoin(Coin.QUARTER); // 55
        machine.insertCoin(Coin.DIME);    // 65 (Sufficient!)
        machine.pressSelectProduct(102); // Dispenses Pepsi, returns 5¢ change
        
        // ==========================================
        // Test Case 4: Out of Stock & Auto-Refund
        // ==========================================
        System.out.println("\n===== TEST CASE 4: Out of Stock (Pepsi - now 0 in stock) =====");
        machine.insertCoin(Coin.QUARTER); // 25
        machine.insertCoin(Coin.QUARTER); // 50
        machine.insertCoin(Coin.DIME);    // 60
        
        machine.pressSelectProduct(102); // Pepsi out of stock! Refunds 60¢, returns to Idle
        
        // ==========================================
        // Test Case 5: Press Select without Coins
        // ==========================================
        System.out.println("\n===== TEST CASE 5: Actions in Idle State =====");
        machine.pressSelectProduct(103); // Error: Please insert coins first
        machine.pressRefund(); // Error: No coins to refund
    }
}
