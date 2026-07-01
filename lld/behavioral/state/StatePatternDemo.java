package lld.behavioral.state;


// ============================================================================
// 1. State Interface
// ============================================================================
interface VendingState {
    void insertCoin(VendingMachine machine);
    void ejectCoin(VendingMachine machine);
    void dispense(VendingMachine machine);
}

// ============================================================================
// 2. Concrete State: Idle State (Waiting for Coin)
// ============================================================================
class IdleState implements VendingState {
    @Override
    public void insertCoin(VendingMachine machine) {
        System.out.println("[Idle] Coin inserted.");
        machine.setState(new HasMoneyState()); // Transition to HasMoney
    }

    @Override
    public void ejectCoin(VendingMachine machine) {
        System.out.println("[Idle] Error: No coin to eject.");
    }

    @Override
    public void dispense(VendingMachine machine) {
        System.out.println("[Idle] Error: Please insert a coin first.");
    }
}

// ============================================================================
// 3. Concrete State: Has Money State
// ============================================================================
class HasMoneyState implements VendingState {
    @Override
    public void insertCoin(VendingMachine machine) {
        System.out.println("[HasMoney] Error: Coin already inserted.");
    }

    @Override
    public void ejectCoin(VendingMachine machine) {
        System.out.println("[HasMoney] Coin ejected successfully.");
        machine.setState(new IdleState()); // Transition back to Idle
    }

    @Override
    public void dispense(VendingMachine machine) {
        System.out.println("[HasMoney] Dispensing product...");
        machine.setState(new DispensingState()); // Transition to Dispensing
        machine.dispense(); // Triggers final action
    }
}

// ============================================================================
// 4. Concrete State: Dispensing State
// ============================================================================
class DispensingState implements VendingState {
    @Override
    public void insertCoin(VendingMachine machine) {
        System.out.println("[Dispensing] Error: Please wait, dispensing in progress.");
    }

    @Override
    public void ejectCoin(VendingMachine machine) {
        System.out.println("[Dispensing] Error: Cannot eject coin, product already dispensing.");
    }

    @Override
    public void dispense(VendingMachine machine) {
        System.out.println("[Dispensing] Product delivered! Enjoy.");
        machine.setState(new IdleState()); // Transition back to Idle
    }
}

// ============================================================================
// 5. Context Class (VendingMachine)
// ============================================================================
class VendingMachine {
    private VendingState currentState;

    public VendingMachine() {
        this.currentState = new IdleState(); // Initial state
    }

    public void setState(VendingState state) {
        this.currentState = state;
    }

    // Delegate methods to the current state
    public void insertCoin() {
        currentState.insertCoin(this);
    }

    public void ejectCoin() {
        currentState.ejectCoin(this);
    }

    public void dispense() {
        currentState.dispense(this);
    }
}

// ============================================================================
// 6. Execution Demo
// ============================================================================
public class StatePatternDemo {
    public static void main(String[] args) {
        System.out.println("--- LLD State Design Pattern Demo (Vending Machine) ---\n");

        VendingMachine machine = new VendingMachine();

        // 1. Try to dispense immediately (should fail)
        System.out.print("Action: Dispense -> ");
        machine.dispense();

        // 2. Insert Coin
        System.out.print("\nAction: Insert Coin -> ");
        machine.insertCoin();

        // 3. Try to insert another coin (should fail)
        System.out.print("Action: Insert Coin again -> ");
        machine.insertCoin();

        // 4. Eject Coin
        System.out.print("\nAction: Eject Coin -> ");
        machine.ejectCoin();

        // 5. Insert Coin again and Dispense Product
        System.out.print("\nAction: Insert Coin -> ");
        machine.insertCoin();
        System.out.print("Action: Dispense -> ");
        machine.dispense();

        // 6. Machine should return to Idle State
        System.out.print("\nAction: Dispense again -> ");
        machine.dispense();
    }
}
