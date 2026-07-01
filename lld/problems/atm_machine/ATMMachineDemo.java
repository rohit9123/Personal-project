package lld.problems.atm_machine;

/**
 * ATM Machine -- Low-Level Design Demo
 *
 * Patterns used:
 *   1. State Pattern       -- ATM lifecycle (Idle -> CardInserted -> PinValidation -> TransactionSelection -> CashDispensing)
 *   2. Chain of Responsibility -- Cash dispensing across denominations (2000 -> 500 -> 200 -> 100)
 *
 * Run:
 *   javac -d . ATMMachineDemo.java
 *   java lld.problems.atm_machine.ATMMachineDemo
 */
public class ATMMachineDemo {

    // ─────────────────────────────────────────────
    // Domain: Account
    // ─────────────────────────────────────────────
    static class Account {
        private final String accountNumber;
        private int balance;

        Account(String accountNumber, int balance) {
            this.accountNumber = accountNumber;
            this.balance = balance;
        }

        String getAccountNumber() { return accountNumber; }
        int getBalance()          { return balance; }

        synchronized boolean debit(int amount) {
            if (amount <= 0)       { System.out.println("[Account] Invalid debit amount."); return false; }
            if (balance < amount)  { System.out.println("[Account] Insufficient funds."); return false; }
            balance -= amount;
            return true;
        }

        synchronized void credit(int amount) {
            if (amount <= 0) { System.out.println("[Account] Invalid credit amount."); return; }
            balance += amount;
        }

        @Override public String toString() { return "Account{" + accountNumber + ", balance=" + balance + "}"; }
    }

    // ─────────────────────────────────────────────
    // Domain: Card
    // ─────────────────────────────────────────────
    static class Card {
        private final String cardNumber;
        private final int pin;
        private final Account account;

        Card(String cardNumber, int pin, Account account) {
            this.cardNumber = cardNumber;
            this.pin = pin;
            this.account = account;
        }

        String getCardNumber() { return cardNumber; }
        Account getAccount()   { return account; }

        boolean validatePin(int enteredPin) {
            return this.pin == enteredPin;
        }
    }

    // ─────────────────────────────────────────────
    // Chain of Responsibility: CashDispenser
    // ─────────────────────────────────────────────
    static abstract class CashDispenser {
        protected final int denomination;
        protected int count;
        protected CashDispenser next;

        CashDispenser(int denomination, int count) {
            this.denomination = denomination;
            this.count = count;
        }

        void setNext(CashDispenser next) {
            this.next = next;
        }

        /**
         * Returns true if the full amount was dispensed, false otherwise.
         */
        boolean dispense(int amount) {
            int notesNeeded = amount / denomination;
            int notesUsed   = Math.min(notesNeeded, count);
            int dispensed   = notesUsed * denomination;
            int remainder   = amount - dispensed;

            if (notesUsed > 0) {
                count -= notesUsed;
                System.out.printf("    %d x Rs.%d%n", notesUsed, denomination);
            }

            if (remainder == 0) {
                return true;
            } else if (next != null) {
                return next.dispense(remainder);
            } else {
                System.out.println("    [ERROR] Cannot dispense remaining Rs." + remainder);
                return false;
            }
        }

        /** Dry-run check: can the chain dispense this amount without actually modifying counts? */
        boolean canDispense(int amount) {
            int notesNeeded = amount / denomination;
            int notesUsed   = Math.min(notesNeeded, count);
            int remainder   = amount - (notesUsed * denomination);

            if (remainder == 0) return true;
            if (next != null)   return next.canDispense(remainder);
            return false;
        }

        void printInventory() {
            System.out.printf("    Rs.%-6d : %d notes%n", denomination, count);
            if (next != null) next.printInventory();
        }
    }

    static class TwoThousandDispenser extends CashDispenser {
        TwoThousandDispenser(int count) { super(2000, count); }
    }
    static class FiveHundredDispenser extends CashDispenser {
        FiveHundredDispenser(int count) { super(500, count); }
    }
    static class TwoHundredDispenser extends CashDispenser {
        TwoHundredDispenser(int count) { super(200, count); }
    }
    static class HundredDispenser extends CashDispenser {
        HundredDispenser(int count) { super(100, count); }
    }

    // ─────────────────────────────────────────────
    // State Pattern: ATMState interface
    // ─────────────────────────────────────────────
    interface ATMState {
        default void insertCard(ATM atm, Card card)      { invalid("insertCard"); }
        default void enterPin(ATM atm, int pin)           { invalid("enterPin"); }
        default void selectWithdraw(ATM atm, int amount)  { invalid("selectWithdraw"); }
        default void selectBalanceInquiry(ATM atm)        { invalid("selectBalanceInquiry"); }
        default void selectDeposit(ATM atm, int amount)   { invalid("selectDeposit"); }
        default void ejectCard(ATM atm)                   { invalid("ejectCard"); }

        private static void invalid(String action) {
            System.out.println("  [State] Invalid action: " + action + " in current state.");
        }

        String name();
    }

    // ── Concrete States ──

    static class IdleState implements ATMState {
        @Override public String name() { return "IDLE"; }

        @Override
        public void insertCard(ATM atm, Card card) {
            System.out.println("  Card inserted: " + card.getCardNumber());
            atm.setCurrentCard(card);
            atm.setCurrentAccount(card.getAccount());
            atm.setState(new CardInsertedState());
        }
    }

    static class CardInsertedState implements ATMState {
        @Override public String name() { return "CARD_INSERTED"; }

        @Override
        public void enterPin(ATM atm, int pin) {
            System.out.println("  Reading PIN...");
            PinValidationState pinState = new PinValidationState();
            atm.setState(pinState);
            // Immediately validate the entered PIN in the new state
            pinState.enterPin(atm, pin);
        }

        @Override
        public void ejectCard(ATM atm) {
            System.out.println("  Card ejected. Returning to Idle.");
            atm.resetSession();
        }
    }

    static class PinValidationState implements ATMState {
        private static final int MAX_ATTEMPTS = 3;
        private int attempts;

        PinValidationState() {
            this.attempts = 0;
        }

        @Override public String name() { return "PIN_VALIDATION"; }

        @Override
        public void enterPin(ATM atm, int pin) {
            attempts++;
            if (atm.getCurrentCard().validatePin(pin)) {
                System.out.println("  PIN accepted. Select a transaction.");
                atm.setState(new TransactionSelectionState());
            } else if (attempts >= MAX_ATTEMPTS) {
                System.out.println("  Max PIN attempts exceeded. Ejecting card.");
                atm.resetSession();
            } else {
                System.out.println("  Wrong PIN. Attempts remaining: " + (MAX_ATTEMPTS - attempts));
            }
        }

        @Override
        public void ejectCard(ATM atm) {
            System.out.println("  Card ejected during PIN entry. Returning to Idle.");
            atm.resetSession();
        }
    }

    static class TransactionSelectionState implements ATMState {
        @Override public String name() { return "TRANSACTION_SELECTION"; }

        @Override
        public void selectWithdraw(ATM atm, int amount) {
            System.out.println("  Withdrawal requested: Rs." + amount);
            atm.setState(new CashDispensingState());
            // Delegate to the CashDispensingState
            atm.getState().selectWithdraw(atm, amount);
        }

        @Override
        public void selectBalanceInquiry(ATM atm) {
            System.out.println("  Balance: Rs." + atm.getCurrentAccount().getBalance());
            System.out.println("  (Remaining in TransactionSelection -- select another or eject.)");
        }

        @Override
        public void selectDeposit(ATM atm, int amount) {
            atm.getCurrentAccount().credit(amount);
            System.out.println("  Deposited Rs." + amount + ". New balance: Rs." + atm.getCurrentAccount().getBalance());
            System.out.println("  (Remaining in TransactionSelection -- select another or eject.)");
        }

        @Override
        public void ejectCard(ATM atm) {
            System.out.println("  Card ejected. Thank you!");
            atm.resetSession();
        }
    }

    static class CashDispensingState implements ATMState {
        @Override public String name() { return "CASH_DISPENSING"; }

        @Override
        public void selectWithdraw(ATM atm, int amount) {
            // Pre-check: can the ATM dispense this amount?
            if (!atm.getDispenserChain().canDispense(amount)) {
                System.out.println("  ATM cannot dispense Rs." + amount + " with available denominations.");
                System.out.println("  Returning to TransactionSelection.");
                atm.setState(new TransactionSelectionState());
                return;
            }

            // Debit account
            if (!atm.getCurrentAccount().debit(amount)) {
                System.out.println("  Transaction failed. Returning to TransactionSelection.");
                atm.setState(new TransactionSelectionState());
                return;
            }

            // Dispense cash
            System.out.println("  Dispensing cash:");
            atm.getDispenserChain().dispense(amount);
            atm.deductAtmBalance(amount);
            System.out.println("  Please collect your cash. Ejecting card.");
            atm.resetSession();
        }
    }

    // ─────────────────────────────────────────────
    // ATM (Context)
    // ─────────────────────────────────────────────
    static class ATM {
        private ATMState currentState;
        private Card currentCard;
        private Account currentAccount;
        private final CashDispenser dispenserChain;
        private int atmBalance;

        ATM(CashDispenser dispenserChain, int atmBalance) {
            this.dispenserChain = dispenserChain;
            this.atmBalance = atmBalance;
            this.currentState = new IdleState();
        }

        // ── State delegation ──
        void insertCard(Card card)        { log("insertCard");          currentState.insertCard(this, card); }
        void enterPin(int pin)            { log("enterPin");            currentState.enterPin(this, pin); }
        void selectWithdraw(int amount)   { log("selectWithdraw");      currentState.selectWithdraw(this, amount); }
        void selectBalanceInquiry()       { log("selectBalanceInquiry"); currentState.selectBalanceInquiry(this); }
        void selectDeposit(int amount)    { log("selectDeposit");       currentState.selectDeposit(this, amount); }
        void ejectCard()                  { log("ejectCard");           currentState.ejectCard(this); }

        // ── Getters / Setters for states ──
        ATMState getState()                { return currentState; }
        void setState(ATMState state)      { this.currentState = state; }
        Card getCurrentCard()              { return currentCard; }
        void setCurrentCard(Card card)     { this.currentCard = card; }
        Account getCurrentAccount()        { return currentAccount; }
        void setCurrentAccount(Account a)  { this.currentAccount = a; }
        CashDispenser getDispenserChain()  { return dispenserChain; }

        void deductAtmBalance(int amount)  { this.atmBalance -= amount; }

        void resetSession() {
            this.currentCard = null;
            this.currentAccount = null;
            this.currentState = new IdleState();
        }

        void printStatus() {
            System.out.println("  ATM State: " + currentState.name() + " | ATM Cash: Rs." + atmBalance);
            System.out.println("  Denomination inventory:");
            dispenserChain.printInventory();
        }

        private void log(String action) {
            System.out.println("\n>> " + action + "() [current state: " + currentState.name() + "]");
        }
    }

    // ─────────────────────────────────────────────
    // Main: Demo
    // ─────────────────────────────────────────────
    public static void main(String[] args) {
        System.out.println("=== ATM Machine Demo ===\n");

        // Setup dispenser chain: 2000 -> 500 -> 200 -> 100
        CashDispenser d2000 = new TwoThousandDispenser(5);   // 5 x 2000 = 10000
        CashDispenser d500  = new FiveHundredDispenser(10);  // 10 x 500 = 5000
        CashDispenser d200  = new TwoHundredDispenser(10);   // 10 x 200 = 2000
        CashDispenser d100  = new HundredDispenser(20);      // 20 x 100 = 2000
        d2000.setNext(d500);
        d500.setNext(d200);
        d200.setNext(d100);

        int totalAtmCash = 5 * 2000 + 10 * 500 + 10 * 200 + 20 * 100; // 19000
        ATM atm = new ATM(d2000, totalAtmCash);

        // Setup user
        Account account = new Account("ACC-1001", 50000);
        Card card = new Card("CARD-9876", 1234, account);

        // Print initial status
        System.out.println("--- Initial ATM Status ---");
        atm.printStatus();
        System.out.println("  User account: " + account);

        // ── Scenario 1: Successful withdrawal ──
        System.out.println("\n========== SCENARIO 1: Withdraw Rs.4700 ==========");
        atm.insertCard(card);
        atm.enterPin(1234);       // CardInserted -> PinValidation -> correct -> TransactionSelection
        atm.selectWithdraw(4700); // Dispense cash

        System.out.println("\n--- ATM Status After Withdrawal ---");
        atm.printStatus();
        System.out.println("  User account: " + account);

        // ── Scenario 2: Balance inquiry + deposit ──
        System.out.println("\n========== SCENARIO 2: Balance Inquiry + Deposit ==========");
        atm.insertCard(card);
        atm.enterPin(9999);       // Wrong PIN (attempt 1)
        atm.enterPin(1234);       // Correct PIN -> TransactionSelection
        atm.selectBalanceInquiry();
        atm.selectDeposit(5000);
        atm.selectBalanceInquiry();
        atm.ejectCard();

        System.out.println("\n--- Final ATM Status ---");
        atm.printStatus();
        System.out.println("  User account: " + account);

        // ── Scenario 3: Wrong PIN lockout ──
        System.out.println("\n========== SCENARIO 3: PIN Lockout ==========");
        atm.insertCard(card);
        atm.enterPin(0000);       // Attempt 1 wrong
        atm.enterPin(1111);       // Attempt 2 wrong
        atm.enterPin(2222);       // Attempt 3 wrong -> eject

        // ── Scenario 4: Invalid action in wrong state ──
        System.out.println("\n========== SCENARIO 4: Invalid Actions ==========");
        atm.selectWithdraw(1000); // Should fail -- ATM is in Idle state
        atm.enterPin(1234);       // Should fail -- ATM is in Idle state

        System.out.println("\n=== Demo Complete ===");
    }
}
