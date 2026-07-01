package lld.behavioral.strategy;


// 1. Strategy Interface
interface DriveStrategy {
    void drive();
}

// 2. Concrete Strategies
class NormalDrive implements DriveStrategy {
    @Override
    public void drive() {
        System.out.println("Driving with normal capabilities (smooth & eco-friendly).");
    }
}

class SportsDrive implements DriveStrategy {
    @Override
    public void drive() {
        System.out.println("Driving with high-performance sports capabilities!");
    }
}

// 3. Context Class
class Vehicle {
    private DriveStrategy driveStrategy; // Composition

    public Vehicle(DriveStrategy driveStrategy) {
        this.driveStrategy = driveStrategy; // Injection
    }

    public void drive() {
        driveStrategy.drive(); // Delegation
    }

    public void setDriveStrategy(DriveStrategy driveStrategy) {
        this.driveStrategy = driveStrategy; // Dynamic swap
    }
}

// 4. Execution Demo
public class StrategyPatternDemo {
    public static void main(String[] args) {
        System.out.println("--- Minimal Strategy Pattern Demo ---");

        // We create a vehicle with normal driving strategy
        Vehicle car = new Vehicle(new NormalDrive());
        car.drive();

        // Dynamically swap the driving strategy at runtime to sports drive
        System.out.println("\n[Action] Swapping to Sports Mode...");
        car.setDriveStrategy(new SportsDrive());
        car.drive();
    }
}
