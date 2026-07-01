package lld.problems.parking_lot;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ============================================================================
// 1. Enums
// ============================================================================
enum VehicleType { MOTORCYCLE, CAR, TRUCK }

enum SpotType {
    COMPACT, LARGE, HANDICAPPED;

    public boolean canFit(VehicleType vehicleType) {
        return switch (this) {
            case COMPACT -> vehicleType != VehicleType.TRUCK;
            case LARGE, HANDICAPPED -> true;
        };
    }
}

// ============================================================================
// 2. Vehicle
// ============================================================================
class Vehicle {
    private final String licensePlate;
    private final VehicleType type;

    public Vehicle(String licensePlate, VehicleType type) {
        this.licensePlate = licensePlate;
        this.type = type;
    }

    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType() { return type; }
}

// ============================================================================
// 3. ParkingSpot
// ============================================================================
class ParkingSpot {
    private final int spotNumber;
    private final SpotType type;
    private Vehicle currentVehicle;
    private boolean occupied;

    public ParkingSpot(int spotNumber, SpotType type) {
        this.spotNumber = spotNumber;
        this.type = type;
        this.occupied = false;
    }

    public boolean canFit(VehicleType vehicleType) {
        return !occupied && type.canFit(vehicleType);
    }

    public void occupy(Vehicle vehicle) {
        this.currentVehicle = vehicle;
        this.occupied = true;
    }

    public void free() {
        this.currentVehicle = null;
        this.occupied = false;
    }

    public int getSpotNumber() { return spotNumber; }
    public SpotType getType() { return type; }
    public boolean isOccupied() { return occupied; }
    public Vehicle getCurrentVehicle() { return currentVehicle; }
}

// ============================================================================
// 4. ParkingFloor
// ============================================================================
class ParkingFloor {
    private final int floorNumber;
    private final List<ParkingSpot> spots;

    public ParkingFloor(int floorNumber, List<ParkingSpot> spots) {
        this.floorNumber = floorNumber;
        this.spots = spots;
    }

    public List<ParkingSpot> getAvailableSpots(VehicleType vehicleType) {
        List<ParkingSpot> available = new ArrayList<>();
        for (ParkingSpot spot : spots) {
            if (spot.canFit(vehicleType)) {
                available.add(spot);
            }
        }
        return available;
    }

    /** Display board: count of free spots per type */
    public Map<SpotType, Integer> getDisplayStatus() {
        Map<SpotType, Integer> status = new HashMap<>();
        for (SpotType type : SpotType.values()) {
            status.put(type, 0);
        }
        for (ParkingSpot spot : spots) {
            if (!spot.isOccupied()) {
                status.merge(spot.getType(), 1, Integer::sum);
            }
        }
        return status;
    }

    public int getFloorNumber() { return floorNumber; }
    public List<ParkingSpot> getSpots() { return spots; }
}

// ============================================================================
// 5. Ticket & Receipt
// ============================================================================
class Ticket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final int floorNumber;
    private final LocalDateTime entryTime;

    public Ticket(Vehicle vehicle, ParkingSpot spot, int floorNumber, LocalDateTime entryTime) {
        this.ticketId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.vehicle = vehicle;
        this.spot = spot;
        this.floorNumber = floorNumber;
        this.entryTime = entryTime;
    }

    public String getTicketId() { return ticketId; }
    public Vehicle getVehicle() { return vehicle; }
    public ParkingSpot getSpot() { return spot; }
    public int getFloorNumber() { return floorNumber; }
    public LocalDateTime getEntryTime() { return entryTime; }

    @Override
    public String toString() {
        return String.format("Ticket[%s] Vehicle=%s Floor=%d Spot=%d(%s) Entry=%s",
                ticketId, vehicle.getLicensePlate(), floorNumber,
                spot.getSpotNumber(), spot.getType(), entryTime);
    }
}

class Receipt {
    private final Ticket ticket;
    private final LocalDateTime exitTime;
    private final int feeCents;

    public Receipt(Ticket ticket, LocalDateTime exitTime, int feeCents) {
        this.ticket = ticket;
        this.exitTime = exitTime;
        this.feeCents = feeCents;
    }

    @Override
    public String toString() {
        long hours = Duration.between(ticket.getEntryTime(), exitTime).toHours();
        return String.format("Receipt[%s] Vehicle=%s Duration=%dh Fee=$%.2f",
                ticket.getTicketId(), ticket.getVehicle().getLicensePlate(),
                Math.max(hours, 1), feeCents / 100.0);
    }
}

// ============================================================================
// 6. Allocation Strategy (Strategy Pattern)
// ============================================================================
interface AllocationStrategy {
    /** Returns a spot + floor pair, or null if no spot available */
    SpotAllocation findSpot(List<ParkingFloor> floors, VehicleType vehicleType);
}

class SpotAllocation {
    final ParkingSpot spot;
    final int floorNumber;

    SpotAllocation(ParkingSpot spot, int floorNumber) {
        this.spot = spot;
        this.floorNumber = floorNumber;
    }
}

/** Scan from floor 0 upward, within each floor pick the lowest spot number */
class NearestFirstStrategy implements AllocationStrategy {
    @Override
    public SpotAllocation findSpot(List<ParkingFloor> floors, VehicleType vehicleType) {
        for (ParkingFloor floor : floors) {
            List<ParkingSpot> available = floor.getAvailableSpots(vehicleType);
            if (!available.isEmpty()) {
                return new SpotAllocation(available.get(0), floor.getFloorNumber());
            }
        }
        return null; // parking full for this vehicle type
    }
}

// ============================================================================
// 7. Fee Calculator
// ============================================================================
class FeeCalculator {
    private static final Map<VehicleType, Integer> HOURLY_RATE_CENTS = Map.of(
            VehicleType.MOTORCYCLE, 100,
            VehicleType.CAR, 200,
            VehicleType.TRUCK, 300
    );

    public static int calculate(Ticket ticket, LocalDateTime exitTime) {
        long hours = Duration.between(ticket.getEntryTime(), exitTime).toHours();
        hours = Math.max(hours, 1); // minimum 1 hour charge
        return (int) (hours * HOURLY_RATE_CENTS.get(ticket.getVehicle().getType()));
    }
}

// ============================================================================
// 8. ParkingLot — the main facade
// ============================================================================
class ParkingLot {
    private final List<ParkingFloor> floors;
    private final AllocationStrategy strategy;
    private final Map<String, Ticket> activeTickets = new HashMap<>(); // ticketId -> Ticket

    public ParkingLot(List<ParkingFloor> floors, AllocationStrategy strategy) {
        this.floors = floors;
        this.strategy = strategy;
    }

    public Ticket entry(Vehicle vehicle) {
        SpotAllocation allocation = strategy.findSpot(floors, vehicle.getType());
        if (allocation == null) {
            throw new RuntimeException("No available spot for " + vehicle.getType()
                    + " (plate: " + vehicle.getLicensePlate() + ")");
        }

        allocation.spot.occupy(vehicle);
        Ticket ticket = new Ticket(vehicle, allocation.spot, allocation.floorNumber, LocalDateTime.now());
        activeTickets.put(ticket.getTicketId(), ticket);

        System.out.println("[ENTRY] " + ticket);
        return ticket;
    }

    /** Overload for testing with a custom entry time */
    public Ticket entry(Vehicle vehicle, LocalDateTime entryTime) {
        SpotAllocation allocation = strategy.findSpot(floors, vehicle.getType());
        if (allocation == null) {
            throw new RuntimeException("No available spot for " + vehicle.getType()
                    + " (plate: " + vehicle.getLicensePlate() + ")");
        }

        allocation.spot.occupy(vehicle);
        Ticket ticket = new Ticket(vehicle, allocation.spot, allocation.floorNumber, entryTime);
        activeTickets.put(ticket.getTicketId(), ticket);

        System.out.println("[ENTRY] " + ticket);
        return ticket;
    }

    public Receipt exit(Ticket ticket, LocalDateTime exitTime) {
        if (!activeTickets.containsKey(ticket.getTicketId())) {
            throw new RuntimeException("Invalid or already used ticket: " + ticket.getTicketId());
        }

        int fee = FeeCalculator.calculate(ticket, exitTime);
        ticket.getSpot().free();
        activeTickets.remove(ticket.getTicketId());

        Receipt receipt = new Receipt(ticket, exitTime, fee);
        System.out.println("[EXIT]  " + receipt);
        return receipt;
    }

    public void displayBoard() {
        System.out.println("\n--- Parking Lot Display Board ---");
        for (ParkingFloor floor : floors) {
            Map<SpotType, Integer> status = floor.getDisplayStatus();
            System.out.printf("Floor %d: COMPACT=%d  LARGE=%d  HANDICAPPED=%d%n",
                    floor.getFloorNumber(),
                    status.get(SpotType.COMPACT),
                    status.get(SpotType.LARGE),
                    status.get(SpotType.HANDICAPPED));
        }
        System.out.println("---------------------------------");
    }
}

// ============================================================================
// 9. Test Driver
// ============================================================================
public class ParkingLotDemo {
    public static void main(String[] args) {
        System.out.println("--- Parking Lot LLD Simulation ---\n");

        // Build a 2-floor lot
        // Floor 0: 2 COMPACT, 1 LARGE, 1 HANDICAPPED
        // Floor 1: 2 COMPACT, 1 LARGE
        List<ParkingSpot> floor0Spots = List.of(
                new ParkingSpot(1, SpotType.HANDICAPPED),
                new ParkingSpot(2, SpotType.COMPACT),
                new ParkingSpot(3, SpotType.COMPACT),
                new ParkingSpot(4, SpotType.LARGE)
        );
        List<ParkingSpot> floor1Spots = List.of(
                new ParkingSpot(1, SpotType.COMPACT),
                new ParkingSpot(2, SpotType.COMPACT),
                new ParkingSpot(3, SpotType.LARGE)
        );

        ParkingFloor floor0 = new ParkingFloor(0, floor0Spots);
        ParkingFloor floor1 = new ParkingFloor(1, floor1Spots);
        ParkingLot lot = new ParkingLot(List.of(floor0, floor1), new NearestFirstStrategy());

        lot.displayBoard();

        // ==========================================
        // Test Case 1: Car enters, parks, exits after 3 hours
        // ==========================================
        System.out.println("\n===== TEST 1: Car parks for 3 hours =====");
        LocalDateTime now = LocalDateTime.of(2025, 6, 1, 10, 0);
        Vehicle car1 = new Vehicle("KA-01-1234", VehicleType.CAR);
        Ticket t1 = lot.entry(car1, now);

        lot.displayBoard();

        Receipt r1 = lot.exit(t1, now.plusHours(3));
        // Expected: $6.00 (3h * $2/h)

        // ==========================================
        // Test Case 2: Truck needs LARGE spot
        // ==========================================
        System.out.println("\n===== TEST 2: Truck needs a LARGE spot =====");
        Vehicle truck = new Vehicle("MH-02-9999", VehicleType.TRUCK);
        Ticket t2 = lot.entry(truck, now);
        // Should go to Floor 0, Spot 4 (LARGE) — spots 1-3 are HANDICAPPED/COMPACT which can't fit TRUCK

        lot.displayBoard();

        // ==========================================
        // Test Case 3: Fill all COMPACT spots, then try another car
        // ==========================================
        System.out.println("\n===== TEST 3: Fill compact spots =====");
        Vehicle car2 = new Vehicle("KA-03-5555", VehicleType.CAR);
        Vehicle car3 = new Vehicle("KA-04-6666", VehicleType.CAR);
        Vehicle car4 = new Vehicle("KA-05-7777", VehicleType.CAR);
        Vehicle car5 = new Vehicle("KA-06-8888", VehicleType.CAR);

        Ticket t3 = lot.entry(car2, now); // Floor 0, Spot 1 (HANDICAPPED fits car)
        Ticket t4 = lot.entry(car3, now); // Floor 0, Spot 2 (COMPACT)
        Ticket t5 = lot.entry(car4, now); // Floor 0, Spot 3 (COMPACT)
        Ticket t6 = lot.entry(car5, now); // Floor 1, Spot 1 (COMPACT)

        lot.displayBoard();

        // ==========================================
        // Test Case 4: Motorcycle can fit in COMPACT
        // ==========================================
        System.out.println("\n===== TEST 4: Motorcycle fits in compact =====");
        Vehicle bike = new Vehicle("DL-07-1111", VehicleType.MOTORCYCLE);
        Ticket t7 = lot.entry(bike, now);

        lot.displayBoard();

        // ==========================================
        // Test Case 5: Parking full for trucks — only 2 LARGE existed, 1 taken
        // ==========================================
        System.out.println("\n===== TEST 5: Second truck fills last LARGE =====");
        Vehicle truck2 = new Vehicle("TN-08-2222", VehicleType.TRUCK);
        Ticket t8 = lot.entry(truck2, now); // Floor 1, Spot 3 (last LARGE)

        lot.displayBoard();

        // Third truck should be denied
        System.out.println("\n===== TEST 6: Third truck — parking full =====");
        Vehicle truck3 = new Vehicle("AP-09-3333", VehicleType.TRUCK);
        try {
            lot.entry(truck3, now);
        } catch (RuntimeException e) {
            System.out.println("[DENIED] " + e.getMessage());
        }

        // ==========================================
        // Test Case 7: Exit frees a spot, next vehicle can park
        // ==========================================
        System.out.println("\n===== TEST 7: Truck exits, new truck can enter =====");
        lot.exit(t2, now.plusHours(2)); // Truck exits, frees Floor 0 Spot 4
        // Expected fee: $6.00 (2h * $3/h)

        Vehicle truck4 = new Vehicle("RJ-10-4444", VehicleType.TRUCK);
        Ticket t9 = lot.entry(truck4, now);

        lot.displayBoard();
    }
}
