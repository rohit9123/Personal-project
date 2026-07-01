import java.util.*;
import java.util.stream.*;

/**
 * Splitwise LLD — single-file runnable demo.
 *
 * Design Patterns:
 *   - Strategy   → SplitStrategy (Equal / Exact / Percent)
 *   - Factory    → SplitStrategyFactory
 *   - Observer   → ExpenseObserver / ConsoleNotifier
 *
 * Core Algorithm:
 *   - Debt simplification via greedy min-transactions matching
 *
 * Run:  javac SplitwiseDemo.java && java SplitwiseDemo
 */
public class SplitwiseDemo {

    // ─────────────────────────── Models ───────────────────────────

    static class User {
        private final String id;
        private final String name;

        User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        String getId()   { return id; }
        String getName()  { return name; }

        @Override public String toString() { return name + "(" + id + ")"; }
    }

    static class Group {
        private final String id;
        private final String name;
        private final Map<String, User> members = new LinkedHashMap<>();

        Group(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void addMember(User user) { members.put(user.getId(), user); }

        void removeMember(String userId) { members.remove(userId); }

        Collection<User> getMembers() { return members.values(); }

        User getMember(String userId) { return members.get(userId); }

        @Override public String toString() { return name; }
    }

    static class Split {
        private final User user;
        private final double amount;

        Split(User user, double amount) {
            this.user = user;
            this.amount = amount;
        }

        User getUser()     { return user; }
        double getAmount() { return amount; }

        @Override public String toString() {
            return user.getName() + " -> $" + String.format("%.2f", amount);
        }
    }

    static class Expense {
        private final String id;
        private final String description;
        private final double amount;
        private final User paidBy;
        private final List<Split> splits;
        private final String groupId;

        Expense(String id, String description, double amount,
                User paidBy, List<Split> splits, String groupId) {
            this.id = id;
            this.description = description;
            this.amount = amount;
            this.paidBy = paidBy;
            this.splits = splits;
            this.groupId = groupId;
        }

        String getId()              { return id; }
        String getDescription()     { return description; }
        double getAmount()          { return amount; }
        User getPaidBy()            { return paidBy; }
        List<Split> getSplits()     { return splits; }
        String getGroupId()         { return groupId; }
    }

    static class Transaction {
        private final String fromUserId;
        private final String toUserId;
        private final double amount;

        Transaction(String fromUserId, String toUserId, double amount) {
            this.fromUserId = fromUserId;
            this.toUserId = toUserId;
            this.amount = amount;
        }

        @Override public String toString() {
            return fromUserId + " pays " + toUserId + " $" + String.format("%.2f", amount);
        }
    }

    // ─────────────────────── Split Strategy (Strategy Pattern) ───────────────────────

    enum SplitType { EQUAL, EXACT, PERCENTAGE }

    interface SplitStrategy {
        List<Split> split(double totalAmount, User paidBy,
                          List<User> participants, Map<String, Double> params);
        void validate(double totalAmount, List<User> participants,
                      Map<String, Double> params);
    }

    static class EqualSplitStrategy implements SplitStrategy {
        @Override
        public void validate(double totalAmount, List<User> participants,
                             Map<String, Double> params) {
            if (participants.isEmpty())
                throw new IllegalArgumentException("Need at least one participant");
        }

        @Override
        public List<Split> split(double totalAmount, User paidBy,
                                 List<User> participants, Map<String, Double> params) {
            validate(totalAmount, participants, params);
            double perPerson = totalAmount / participants.size();
            return participants.stream()
                    .map(u -> new Split(u, round(perPerson)))
                    .collect(Collectors.toList());
        }
    }

    static class ExactSplitStrategy implements SplitStrategy {
        @Override
        public void validate(double totalAmount, List<User> participants,
                             Map<String, Double> params) {
            double sum = participants.stream()
                    .mapToDouble(u -> params.getOrDefault(u.getId(), 0.0))
                    .sum();
            if (Math.abs(sum - totalAmount) > 0.01)
                throw new IllegalArgumentException(
                        "Exact amounts (" + round(sum) + ") must sum to total (" + totalAmount + ")");
        }

        @Override
        public List<Split> split(double totalAmount, User paidBy,
                                 List<User> participants, Map<String, Double> params) {
            validate(totalAmount, participants, params);
            return participants.stream()
                    .map(u -> new Split(u, params.get(u.getId())))
                    .collect(Collectors.toList());
        }
    }

    static class PercentSplitStrategy implements SplitStrategy {
        @Override
        public void validate(double totalAmount, List<User> participants,
                             Map<String, Double> params) {
            double totalPct = participants.stream()
                    .mapToDouble(u -> params.getOrDefault(u.getId(), 0.0))
                    .sum();
            if (Math.abs(totalPct - 100.0) > 0.01)
                throw new IllegalArgumentException(
                        "Percentages (" + totalPct + ") must sum to 100");
        }

        @Override
        public List<Split> split(double totalAmount, User paidBy,
                                 List<User> participants, Map<String, Double> params) {
            validate(totalAmount, participants, params);
            return participants.stream()
                    .map(u -> new Split(u, round(totalAmount * params.get(u.getId()) / 100.0)))
                    .collect(Collectors.toList());
        }
    }

    // ─────────────────────── Factory ───────────────────────

    static class SplitStrategyFactory {
        private static final Map<SplitType, SplitStrategy> STRATEGIES = Map.of(
                SplitType.EQUAL, new EqualSplitStrategy(),
                SplitType.EXACT, new ExactSplitStrategy(),
                SplitType.PERCENTAGE, new PercentSplitStrategy()
        );

        static SplitStrategy create(SplitType type) {
            SplitStrategy strategy = STRATEGIES.get(type);
            if (strategy == null) throw new IllegalArgumentException("Unknown split type: " + type);
            return strategy;
        }
    }

    // ─────────────────────── Observer ───────────────────────

    interface ExpenseObserver {
        void onExpenseAdded(Expense expense);
        void onSettlement(String fromUserId, String toUserId, double amount);
    }

    static class ConsoleNotifier implements ExpenseObserver {
        @Override
        public void onExpenseAdded(Expense expense) {
            System.out.println("  [NOTIFY] " + expense.getPaidBy().getName()
                    + " paid $" + String.format("%.2f", expense.getAmount())
                    + " for '" + expense.getDescription() + "'");
            for (Split s : expense.getSplits()) {
                if (!s.getUser().getId().equals(expense.getPaidBy().getId())) {
                    System.out.println("    -> " + s.getUser().getName()
                            + " owes $" + String.format("%.2f", s.getAmount()));
                }
            }
        }

        @Override
        public void onSettlement(String fromUserId, String toUserId, double amount) {
            System.out.println("  [SETTLE] " + fromUserId + " paid " + toUserId
                    + " $" + String.format("%.2f", amount));
        }
    }

    // ─────────────────────── Balance Service ───────────────────────

    static class BalanceService {
        // balances[A][B] > 0 means "B owes A that amount"
        private final Map<String, Map<String, Double>> balances = new HashMap<>();

        void recordExpense(Expense expense) {
            String payerId = expense.getPaidBy().getId();
            for (Split split : expense.getSplits()) {
                String owerId = split.getUser().getId();
                if (owerId.equals(payerId)) continue;

                addBalance(payerId, owerId, split.getAmount());
                addBalance(owerId, payerId, -split.getAmount());
            }
        }

        void settleUp(String fromUserId, String toUserId, double amount) {
            // fromUser pays toUser — reduces what fromUser owes toUser
            addBalance(toUserId, fromUserId, -amount);
            addBalance(fromUserId, toUserId, amount);
        }

        double getBalance(String userId1, String userId2) {
            return balances.getOrDefault(userId1, Collections.emptyMap())
                    .getOrDefault(userId2, 0.0);
        }

        Map<String, Double> getBalancesForUser(String userId) {
            Map<String, Double> userBalances = balances.getOrDefault(userId, Collections.emptyMap());
            // Filter out zero balances
            return userBalances.entrySet().stream()
                    .filter(e -> Math.abs(e.getValue()) > 0.01)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        List<Transaction> simplifyDebts(Collection<User> users) {
            // Step 1: Compute net balance per user
            Map<String, Double> netBalance = new HashMap<>();
            for (User u : users) {
                Map<String, Double> ub = balances.getOrDefault(u.getId(), Collections.emptyMap());
                double net = ub.values().stream().mapToDouble(Double::doubleValue).sum();
                if (Math.abs(net) > 0.01) {
                    netBalance.put(u.getId(), net);
                }
            }

            // Step 2: Greedy matching — match max debtor with max creditor
            List<String> ids = new ArrayList<>(netBalance.keySet());
            List<Transaction> result = new ArrayList<>();

            while (!ids.isEmpty()) {
                String maxDebtor = null, maxCreditor = null;
                double minVal = 0, maxVal = 0;

                for (String id : ids) {
                    double val = netBalance.get(id);
                    if (val < minVal) { minVal = val; maxDebtor = id; }
                    if (val > maxVal) { maxVal = val; maxCreditor = id; }
                }

                if (maxDebtor == null || maxCreditor == null) break;

                double amount = Math.min(-minVal, maxVal);
                result.add(new Transaction(maxDebtor, maxCreditor, round(amount)));

                netBalance.put(maxDebtor, round(minVal + amount));
                netBalance.put(maxCreditor, round(maxVal - amount));

                ids.removeIf(id -> Math.abs(netBalance.get(id)) < 0.01);
            }

            return result;
        }

        private void addBalance(String userId, String otherUserId, double amount) {
            balances.computeIfAbsent(userId, k -> new HashMap<>())
                    .merge(otherUserId, amount, Double::sum);
        }
    }

    // ─────────────────────── Expense Service (Orchestrator) ───────────────────────

    static class ExpenseService {
        private final BalanceService balanceService;
        private final List<ExpenseObserver> observers = new ArrayList<>();
        private int expenseCounter = 0;

        ExpenseService(BalanceService balanceService) {
            this.balanceService = balanceService;
        }

        void addObserver(ExpenseObserver observer) {
            observers.add(observer);
        }

        Expense addExpense(String description, double amount, User paidBy,
                           List<User> participants, SplitType splitType,
                           Map<String, Double> params, String groupId) {
            SplitStrategy strategy = SplitStrategyFactory.create(splitType);
            List<Split> splits = strategy.split(amount, paidBy, participants, params);

            String id = "EXP-" + (++expenseCounter);
            Expense expense = new Expense(id, description, amount, paidBy, splits, groupId);

            balanceService.recordExpense(expense);

            for (ExpenseObserver observer : observers) {
                observer.onExpenseAdded(expense);
            }

            return expense;
        }
    }

    // ─────────────────────── Utility ───────────────────────

    static double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    // ─────────────────────── Demo ───────────────────────

    public static void main(String[] args) {
        // Setup
        BalanceService balanceService = new BalanceService();
        ExpenseService expenseService = new ExpenseService(balanceService);
        expenseService.addObserver(new ConsoleNotifier());

        // Create users
        User alice = new User("u1", "Alice");
        User bob   = new User("u2", "Bob");
        User charlie = new User("u3", "Charlie");
        User diana = new User("u4", "Diana");

        // Create group
        Group trip = new Group("g1", "Goa Trip");
        trip.addMember(alice);
        trip.addMember(bob);
        trip.addMember(charlie);
        trip.addMember(diana);

        List<User> allMembers = new ArrayList<>(trip.getMembers());

        // ── Scenario 1: Equal Split ──
        System.out.println("=== Expense 1: Dinner (Equal Split) ===");
        expenseService.addExpense("Dinner at Beach Shack", 2000, alice,
                allMembers, SplitType.EQUAL, Map.of(), trip.id);

        // ── Scenario 2: Exact Split ──
        System.out.println("\n=== Expense 2: Cab (Exact Split) ===");
        expenseService.addExpense("Cab to Airport", 1500, bob, allMembers,
                SplitType.EXACT,
                Map.of("u1", 200.0, "u2", 500.0, "u3", 300.0, "u4", 500.0),
                trip.id);

        // ── Scenario 3: Percentage Split ──
        System.out.println("\n=== Expense 3: Hotel (Percentage Split) ===");
        expenseService.addExpense("Hotel Bill", 6000, charlie,
                List.of(alice, bob, charlie),
                SplitType.PERCENTAGE,
                Map.of("u1", 50.0, "u2", 30.0, "u3", 20.0),
                trip.id);

        // ── Show pairwise balances ──
        System.out.println("\n=== Pairwise Balances ===");
        for (User u : allMembers) {
            Map<String, Double> ub = balanceService.getBalancesForUser(u.getId());
            if (!ub.isEmpty()) {
                for (var entry : ub.entrySet()) {
                    double val = entry.getValue();
                    String other = entry.getKey();
                    if (val > 0) {
                        System.out.println("  " + u.getName() + " is owed $"
                                + String.format("%.2f", val) + " by " + other);
                    } else {
                        System.out.println("  " + u.getName() + " owes $"
                                + String.format("%.2f", -val) + " to " + other);
                    }
                }
            }
        }

        // ── Debt Simplification ──
        System.out.println("\n=== Simplified Debts (Min Transactions) ===");
        List<Transaction> simplified = balanceService.simplifyDebts(allMembers);
        for (Transaction t : simplified) {
            System.out.println("  " + t);
        }

        // ── Validation Demo ──
        System.out.println("\n=== Validation Demo ===");
        try {
            expenseService.addExpense("Bad Split", 1000, alice, allMembers,
                    SplitType.EXACT,
                    Map.of("u1", 100.0, "u2", 200.0, "u3", 300.0, "u4", 100.0),
                    trip.id);
        } catch (IllegalArgumentException e) {
            System.out.println("  [REJECTED] " + e.getMessage());
        }

        try {
            expenseService.addExpense("Bad Percent", 1000, alice,
                    List.of(alice, bob),
                    SplitType.PERCENTAGE,
                    Map.of("u1", 60.0, "u2", 60.0),
                    trip.id);
        } catch (IllegalArgumentException e) {
            System.out.println("  [REJECTED] " + e.getMessage());
        }

        // ── Settle Up Demo ──
        System.out.println("\n=== Settle Up Demo ===");
        if (!simplified.isEmpty()) {
            Transaction first = simplified.get(0);
            System.out.println("  Settling: " + first);
            balanceService.settleUp(first.fromUserId, first.toUserId, first.amount);
            System.out.println("  Remaining debts after settlement:");
            List<Transaction> remaining = balanceService.simplifyDebts(allMembers);
            if (remaining.isEmpty()) {
                System.out.println("    All settled!");
            } else {
                for (Transaction t : remaining) {
                    System.out.println("    " + t);
                }
            }
        }
    }
}
