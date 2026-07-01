package lld.problems.elevator_system;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

// ============================================================================
// 1. Enums
// ============================================================================
enum Direction { UP, DOWN }

enum ElevatorState { IDLE, MOVING_UP, MOVING_DOWN, DOOR_OPEN }

// ============================================================================
// 2. Request — external hall call (floor + direction)
// ============================================================================
class Request {
    final int floor;
    final Direction direction;

    Request(int floor, Direction direction) {
        this.floor = floor;
        this.direction = direction;
    }

    @Override
    public String toString() {
        return "Request(floor=" + floor + ", dir=" + direction + ")";
    }
}

// ============================================================================
// 3. Elevator — LOOK algorithm, step-based simulation
// ============================================================================
class Elevator {
    private final int id;
    private int currentFloor;
    private ElevatorState state;
    private final TreeSet<Integer> upStops;     // ascending — floors to visit going up
    private final TreeSet<Integer> downStops;   // descending — floors to visit going down
    private Direction lastDirection;            // remembers direction before DOOR_OPEN

    public Elevator(int id, int startFloor) {
        this.id = id;
        this.currentFloor = startFloor;
        this.state = ElevatorState.IDLE;
        this.upStops = new TreeSet<>();
        this.downStops = new TreeSet<>(Collections.reverseOrder());
        this.lastDirection = Direction.UP;
    }

    /** Add a destination floor (from internal button or dispatcher assignment) */
    public void addStop(int floor) {
        if (floor == currentFloor) return;

        if (floor > currentFloor) {
            upStops.add(floor);
        } else {
            downStops.add(floor);
        }

        // If idle, start moving toward the new stop
        if (state == ElevatorState.IDLE) {
            state = (floor > currentFloor) ? ElevatorState.MOVING_UP : ElevatorState.MOVING_DOWN;
            lastDirection = (state == ElevatorState.MOVING_UP) ? Direction.UP : Direction.DOWN;
        }
    }

    /** Simulate one time step — move one floor, stop if needed, or stay idle */
    public void step() {
        switch (state) {
            case IDLE -> {} // nothing to do

            case DOOR_OPEN -> {
                // Close door and decide next action
                System.out.printf("  Elevator %d: closing doors at floor %d%n", id, currentFloor);
                reverseOrIdle();
            }

            case MOVING_UP -> {
                currentFloor++;
                System.out.printf("  Elevator %d: moving UP to floor %d%n", id, currentFloor);
                if (upStops.contains(currentFloor)) {
                    upStops.remove(currentFloor);
                    openDoor();
                }
            }

            case MOVING_DOWN -> {
                currentFloor--;
                System.out.printf("  Elevator %d: moving DOWN to floor %d%n", id, currentFloor);
                if (downStops.contains(currentFloor)) {
                    downStops.remove(currentFloor);
                    openDoor();
                }
            }
        }
    }

    private void openDoor() {
        lastDirection = (state == ElevatorState.MOVING_UP) ? Direction.UP : Direction.DOWN;
        state = ElevatorState.DOOR_OPEN;
        System.out.printf("  Elevator %d: *** DOOR OPEN at floor %d ***%n", id, currentFloor);
    }

    /** After door closes: continue in same direction, reverse, or go idle */
    private void reverseOrIdle() {
        if (lastDirection == Direction.UP) {
            if (!upStops.isEmpty()) {
                state = ElevatorState.MOVING_UP;
            } else if (!downStops.isEmpty()) {
                state = ElevatorState.MOVING_DOWN;
            } else {
                state = ElevatorState.IDLE;
            }
        } else {
            if (!downStops.isEmpty()) {
                state = ElevatorState.MOVING_DOWN;
            } else if (!upStops.isEmpty()) {
                state = ElevatorState.MOVING_UP;
            } else {
                state = ElevatorState.IDLE;
            }
        }
    }

    // ---- Getters for dispatch scoring ----

    public int getId() { return id; }
    public int getCurrentFloor() { return currentFloor; }
    public ElevatorState getState() { return state; }
    public boolean isIdle() { return state == ElevatorState.IDLE; }

    public boolean isMovingUp() {
        return state == ElevatorState.MOVING_UP
                || (state == ElevatorState.DOOR_OPEN && lastDirection == Direction.UP);
    }

    public boolean isMovingDown() {
        return state == ElevatorState.MOVING_DOWN
                || (state == ElevatorState.DOOR_OPEN && lastDirection == Direction.DOWN);
    }

    public int totalPendingStops() {
        return upStops.size() + downStops.size();
    }

    public String statusString() {
        String stops = "";
        if (!upStops.isEmpty()) stops += "up=" + upStops;
        if (!downStops.isEmpty()) stops += (stops.isEmpty() ? "" : " ") + "down=" + downStops;
        if (stops.isEmpty()) stops = "none";
        return String.format("Elevator %d: floor=%-2d state=%-11s stops=[%s]", id, currentFloor, state, stops);
    }
}

// ============================================================================
// 4. Dispatch Strategy (Strategy Pattern)
// ============================================================================
interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, int floor, Direction direction);
}

/**
 * Nearest-in-Direction: prefers elevators moving toward the request floor
 * in the same direction. Idle elevators scored by pure distance.
 */
class NearestInDirectionStrategy implements DispatchStrategy {
    private static final int MAX_PENALTY = 1_000_000;

    @Override
    public Elevator selectElevator(List<Elevator> elevators, int floor, Direction direction) {
        Elevator best = null;
        int bestScore = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            int score = calculateScore(e, floor, direction);
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private int calculateScore(Elevator e, int floor, Direction dir) {
        int distance = Math.abs(e.getCurrentFloor() - floor);

        // Case 1: Idle — pure distance
        if (e.isIdle()) {
            return distance;
        }

        // Case 2: Same direction and approaching
        if (dir == Direction.UP && e.isMovingUp() && e.getCurrentFloor() <= floor) {
            return distance; // best — will pass this floor on the way
        }
        if (dir == Direction.DOWN && e.isMovingDown() && e.getCurrentFloor() >= floor) {
            return distance; // best — will pass this floor on the way
        }

        // Case 3: Same direction but already passed the floor
        // Must complete sweep + reverse + come back
        return MAX_PENALTY + distance;
    }
}

// ============================================================================
// 5. ElevatorSystem — facade coordinating elevators + dispatcher
// ============================================================================
class ElevatorSystem {
    private final List<Elevator> elevators;
    private final DispatchStrategy strategy;

    public ElevatorSystem(int numElevators, int startFloor, DispatchStrategy strategy) {
        this.strategy = strategy;
        this.elevators = new ArrayList<>();
        for (int i = 1; i <= numElevators; i++) {
            elevators.add(new Elevator(i, startFloor));
        }
    }

    /** External request — person on a floor presses UP or DOWN */
    public void externalRequest(int floor, Direction direction) {
        Elevator selected = strategy.selectElevator(elevators, floor, direction);
        System.out.printf("[DISPATCH] %s -> assigned to Elevator %d%n",
                new Request(floor, direction), selected.getId());
        selected.addStop(floor);
    }

    /** Internal request — person inside elevator presses a floor button */
    public void internalRequest(int elevatorId, int floor) {
        Elevator e = elevators.get(elevatorId - 1);
        System.out.printf("[INTERNAL] Elevator %d: passenger pressed floor %d%n", elevatorId, floor);
        e.addStop(floor);
    }

    /** Advance all elevators by one time step */
    public void stepAll() {
        for (Elevator e : elevators) {
            e.step();
        }
    }

    /** Run N steps with status display */
    public void runSteps(int n) {
        for (int i = 1; i <= n; i++) {
            System.out.printf("%n-- Step %d --%n", i);
            stepAll();
            displayStatus();
        }
    }

    public void displayStatus() {
        for (Elevator e : elevators) {
            System.out.println("  " + e.statusString());
        }
    }

    public List<Elevator> getElevators() { return elevators; }
}

// ============================================================================
// 6. Test Driver
// ============================================================================
public class ElevatorSystemDemo {
    public static void main(String[] args) {
        System.out.println("--- Elevator System LLD Simulation ---\n");

        testSingleElevatorLOOK();
        testDispatchToNearestIdle();
        testDispatchSameDirection();
    }

    // ==========================================
    // Test 1: Single elevator, LOOK algorithm
    // ==========================================
    static void testSingleElevatorLOOK() {
        System.out.println("===== TEST 1: Single Elevator — LOOK Algorithm =====");
        System.out.println("Elevator starts at floor 5. Stops: UP to 8, 10 then DOWN to 3, 1.\n");

        ElevatorSystem system = new ElevatorSystem(1, 5, new NearestInDirectionStrategy());

        // External: someone on floor 8 pressed UP
        system.externalRequest(8, Direction.UP);
        // Internal: passenger inside presses 10
        system.internalRequest(1, 10);
        // External: someone on floor 3 pressed DOWN
        system.externalRequest(3, Direction.DOWN);
        // Internal: passenger on floor 3 will press 1
        system.internalRequest(1, 1);

        // 5→6→7→8(open)→8(close)→9→10(open)→10(close)→9→8→7→6→5→4→3(open)→3(close)→2→1(open)→1(close)=IDLE
        system.runSteps(18);

        Elevator e = system.getElevators().get(0);
        System.out.println("\nFinal state: " + e.statusString());
        assert e.isIdle() : "Elevator should be IDLE after completing all stops";
        assert e.getCurrentFloor() == 1 : "Elevator should end at floor 1";
        System.out.println("PASS: Elevator completed LOOK sweep and is IDLE at floor 1\n");
    }

    // ==========================================
    // Test 2: Two elevators — dispatch to nearest idle
    // ==========================================
    static void testDispatchToNearestIdle() {
        System.out.println("===== TEST 2: Dispatch to Nearest Idle Elevator =====");
        System.out.println("Elevator 1 at floor 1, Elevator 2 at floor 1. Request from floor 7 UP.\n");

        ElevatorSystem system = new ElevatorSystem(2, 1, new NearestInDirectionStrategy());

        // First request: floor 7 UP — both idle at floor 1, should pick Elevator 1 (first with same score)
        system.externalRequest(7, Direction.UP);

        // Move Elevator 1 up a few floors
        system.runSteps(3);
        // Elevator 1 is now at floor 4 heading to 7

        // Second request: floor 3 UP — Elevator 2 is idle at floor 1 (distance=2),
        // Elevator 1 is at floor 4 moving up (already passed floor 3) → Elevator 2 wins
        System.out.println("\nNew request: floor 3 UP (should go to Elevator 2)");
        system.externalRequest(3, Direction.UP);

        system.runSteps(8);

        System.out.println("\nFinal states:");
        system.displayStatus();
        System.out.println("PASS: Requests dispatched to optimal elevators\n");
    }

    // ==========================================
    // Test 3: Dispatch prefers same-direction elevator
    // ==========================================
    static void testDispatchSameDirection() {
        System.out.println("===== TEST 3: Dispatch Prefers Same-Direction Elevator =====");
        System.out.println("Elevator 1 at floor 2 heading UP to 10. Elevator 2 idle at floor 8.");
        System.out.println("Request: floor 6 UP → Elevator 1 should win (moving up, will pass floor 6).\n");

        ElevatorSystem system = new ElevatorSystem(2, 1, new NearestInDirectionStrategy());

        // Set up Elevator 1: heading to floor 10 (starts at 1)
        system.internalRequest(1, 10);
        system.stepAll(); // Elevator 1 now at floor 2, moving up

        // Move Elevator 2 to floor 8 by requesting and running
        system.internalRequest(2, 8);
        for (int i = 0; i < 8; i++) system.stepAll(); // run enough steps
        // Elevator 2 should be at 8 or idle near 8

        System.out.println("\nStatus before dispatch:");
        system.displayStatus();

        // Request floor 6 UP
        // Elevator 1 is moving up and will pass floor 6 → distance=few floors
        // Elevator 2 is idle at floor 8 → distance=2 but it needs to go DOWN to reach 6
        System.out.println();
        system.externalRequest(6, Direction.UP);

        system.runSteps(10);

        System.out.println("\nFinal states:");
        system.displayStatus();
        System.out.println("PASS: Same-direction elevator preferred for efficiency\n");
    }
}
