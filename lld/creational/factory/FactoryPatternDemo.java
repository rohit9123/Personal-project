package lld.creational.factory;


// ============================================================================
// COMMON PRODUCTS
// ============================================================================
interface Vehicle {
    void drive();
}

class Car implements Vehicle {
    @Override
    public void drive() { System.out.println("Driving a sedan car!"); }
}

class Bike implements Vehicle {
    @Override
    public void drive() { System.out.println("Riding a sports bike!"); }
}


// ============================================================================
// 1. SIMPLE FACTORY (Idiom - Violates Open/Closed Principle)
// ============================================================================
class SimpleVehicleFactory {
    public static Vehicle createVehicle(String type) {
        if (type == null) return null;
        if (type.equalsIgnoreCase("CAR")) {
            return new Car();
        } else if (type.equalsIgnoreCase("BIKE")) {
            return new Bike();
        }
        throw new IllegalArgumentException("Unknown vehicle type: " + type);
    }
}


// ============================================================================
// 2. FACTORY METHOD PATTERN (GoF - Follows Open/Closed Principle)
// ============================================================================
abstract class VehicleFactory {
    // Abstract Factory Method
    public abstract Vehicle createVehicle();

    // Context method performing business operations on the product
    public void testDrive() {
        Vehicle vehicle = createVehicle();
        System.out.print("Test Drive Status: ");
        vehicle.drive();
    }
}

class CarFactory extends VehicleFactory {
    @Override
    public Vehicle createVehicle() {
        return new Car(); // Instantiates concrete Car
    }
}

class BikeFactory extends VehicleFactory {
    @Override
    public Vehicle createVehicle() {
        return new Bike(); // Instantiates concrete Bike
    }
}


// ============================================================================
// 3. ABSTRACT FACTORY PATTERN (GoF - Family of Related Products)
// ============================================================================

// Product Family A: Buttons
interface Button {
    void paint();
}
class WindowsButton implements Button {
    @Override
    public void paint() { System.out.println("Rendering a Windows-style Button."); }
}
class MacButton implements Button {
    @Override
    public void paint() { System.out.println("Rendering a Mac-style Button."); }
}

// Product Family B: Checkboxes
interface Checkbox {
    void paint();
}
class WindowsCheckbox implements Checkbox {
    @Override
    public void paint() { System.out.println("Rendering a Windows-style Checkbox."); }
}
class MacCheckbox implements Checkbox {
    @Override
    public void paint() { System.out.println("Rendering a Mac-style Checkbox."); }
}

// The Abstract Factory Interface
interface GUIFactory {
    Button createButton();
    Checkbox createCheckbox();
}

// Concrete Factory 1: Windows Suite
class WindowsGUIFactory implements GUIFactory {
    @Override
    public Button createButton() { return new WindowsButton(); }
    @Override
    public Checkbox createCheckbox() { return new WindowsCheckbox(); }
}

// Concrete Factory 2: Mac Suite
class MacGUIFactory implements GUIFactory {
    @Override
    public Button createButton() { return new MacButton(); }
    @Override
    public Checkbox createCheckbox() { return new MacCheckbox(); }
}


// ============================================================================
// EXECUTION DEMO
// ============================================================================
public class FactoryPatternDemo {
    public static void main(String[] args) {
        System.out.println("=== Creational Factory Patterns Demo ===\n");

        // ----------------------------------------------------
        // DEMO 1: Simple Factory
        // ----------------------------------------------------
        System.out.println("[Demo 1] Simple Factory (Helper Class):");
        Vehicle simpleCar = SimpleVehicleFactory.createVehicle("CAR");
        simpleCar.drive();
        System.out.println();

        // ----------------------------------------------------
        // DEMO 2: Factory Method (Extensible, OOP-friendly)
        // ----------------------------------------------------
        System.out.println("[Demo 2] Factory Method Pattern:");
        VehicleFactory carFactory = new CarFactory();
        carFactory.testDrive();

        VehicleFactory bikeFactory = new BikeFactory();
        bikeFactory.testDrive();
        System.out.println();

        // ----------------------------------------------------
        // DEMO 3: Abstract Factory (Families of Products)
        // ----------------------------------------------------
        System.out.println("[Demo 3] Abstract Factory Pattern:");
        // The operating system style is chosen at configuration time
        String os = "mac"; // Assume we detected macOS
        GUIFactory guiFactory;

        if (os.equalsIgnoreCase("windows")) {
            guiFactory = new WindowsGUIFactory();
        } else {
            guiFactory = new MacGUIFactory();
        }

        // Client code only works with GUIFactory, Button, and Checkbox interfaces
        Button renderBtn = guiFactory.createButton();
        Checkbox renderChk = guiFactory.createCheckbox();

        System.out.print("Client paint action: ");
        renderBtn.paint();
        System.out.print("Client paint action: ");
        renderChk.paint();
    }
}
