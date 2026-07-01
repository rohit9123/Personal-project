package lld.structural.decorator;


// ============================================================================
// 1. Component Interface
// ============================================================================
interface Pizza {
    String getDescription();
    double getCost();
}

// ============================================================================
// 2. Concrete Components (Base Pizza bases)
// ============================================================================
class Margherita implements Pizza {
    @Override
    public String getDescription() {
        return "Margherita Pizza (Tomato, Mozzarella)";
    }

    @Override
    public double getCost() {
        return 100.0;
    }
}

class Farmhouse implements Pizza {
    @Override
    public String getDescription() {
        return "Farmhouse Pizza (Veggies, Onion, Capsicum)";
    }

    @Override
    public double getCost() {
        return 150.0;
    }
}

// ============================================================================
// 3. Abstract Decorator (Implements Pizza, Has-A Pizza reference)
// ============================================================================
abstract class ToppingDecorator implements Pizza {
    protected Pizza pizza; // Composition: Reference to the wrapped Pizza object

    public ToppingDecorator(Pizza pizza) {
        this.pizza = pizza;
    }
}

// ============================================================================
// 4. Concrete Decorators (Adding cost and modifying descriptions)
// ============================================================================
class ExtraCheese extends ToppingDecorator {
    public ExtraCheese(Pizza pizza) {
        super(pizza);
    }

    @Override
    public String getDescription() {
        return pizza.getDescription() + ", Extra Cheese";
    }

    @Override
    public double getCost() {
        return pizza.getCost() + 25.0; // Base cost + Cheese cost
    }
}

class Mushroom extends ToppingDecorator {
    public Mushroom(Pizza pizza) {
        super(pizza);
    }

    @Override
    public String getDescription() {
        return pizza.getDescription() + ", Mushrooms";
    }

    @Override
    public double getCost() {
        return pizza.getCost() + 15.0; // Base cost + Mushroom cost
    }
}

class Jalapeno extends ToppingDecorator {
    public Jalapeno(Pizza pizza) {
        super(pizza);
    }

    @Override
    public String getDescription() {
        return pizza.getDescription() + ", Jalapenos";
    }

    @Override
    public double getCost() {
        return pizza.getCost() + 20.0; // Base cost + Jalapeno cost
    }
}

// ============================================================================
// 5. Execution Demo
// ============================================================================
public class DecoratorPatternDemo {
    public static void main(String[] args) {
        System.out.println("--- LLD Decorator Design Pattern Demo ---");

        // 1. Order a basic Margherita Pizza
        Pizza basePizza = new Margherita();
        System.out.println("\nOrder 1: Basic Margherita");
        System.out.println("Description: " + basePizza.getDescription());
        System.out.println("Total Cost: Rs. " + basePizza.getCost());

        // 2. Decorate the Margherita with Extra Cheese
        Pizza cheesePizza = new ExtraCheese(basePizza);
        System.out.println("\nOrder 2: Margherita with Extra Cheese");
        System.out.println("Description: " + cheesePizza.getDescription());
        System.out.println("Total Cost: Rs. " + cheesePizza.getCost());

        // 3. Chaining multiple Decorators (Margherita + Extra Cheese + Mushroom + Jalapenos)
        Pizza fullyLoadedPizza = new Jalapeno(new Mushroom(new ExtraCheese(new Margherita())));
        System.out.println("\nOrder 3: Margherita Fully Loaded");
        System.out.println("Description: " + fullyLoadedPizza.getDescription());
        System.out.println("Total Cost: Rs. " + fullyLoadedPizza.getCost());

        // 4. Order a Farmhouse Pizza with Mushroom and Jalapenos
        Pizza farmhouseWithToppings = new Jalapeno(new Mushroom(new Farmhouse()));
        System.out.println("\nOrder 4: Farmhouse with Mushroom and Jalapenos");
        System.out.println("Description: " + farmhouseWithToppings.getDescription());
        System.out.println("Total Cost: Rs. " + farmhouseWithToppings.getCost());
    }
}
