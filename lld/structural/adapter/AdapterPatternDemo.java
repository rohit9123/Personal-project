package lld.structural.adapter;


// ============================================================================
// 1. Target Interface (What our application code expects)
// ============================================================================
interface PaymentProcessor {
    void pay(double dollars);
}

// ============================================================================
// 2. Concrete Target (An implementation that matches our interface out-of-box)
// ============================================================================
class PayPalProcessor implements PaymentProcessor {
    @Override
    public void pay(double dollars) {
        System.out.println("[PayPal] Processing payment of $" + dollars);
    }
}

// ============================================================================
// 3. Adaptee (An incompatible third-party SDK class that we CANNOT modify)
// ============================================================================
class StripeSDK {
    // Requires billing in cents and client email
    public void makePayment(String email, double cents) {
        System.out.println("[Stripe SDK] Successfully charged " + cents + " cents to " + email);
    }
}

// ============================================================================
// 4. The Adapter (Implements Target interface, Wraps Adaptee class)
// ============================================================================
class StripeAdapter implements PaymentProcessor {
    private final StripeSDK stripeSDK; // Composition

    public StripeAdapter(StripeSDK stripeSDK) {
        this.stripeSDK = stripeSDK;
    }

    @Override
    public void pay(double dollars) {
        // Translate double dollars into double cents
        double cents = dollars * 100.0;
        
        // Provide the required email parameter (retrieved from context or hardcoded)
        String userEmail = "customer@company.com";
        
        // Delegate execution to the underlying SDK
        stripeSDK.makePayment(userEmail, cents);
    }
}

// ============================================================================
// 5. Execution Demo (The Client code)
// ============================================================================
public class AdapterPatternDemo {
    public static void main(String[] args) {
        System.out.println("--- LLD Adapter Design Pattern Demo ---\n");

        // Client expects to interact only with the PaymentProcessor interface
        
        // Scenario 1: Using PayPal (directly compatible)
        PaymentProcessor payPal = new PayPalProcessor();
        System.out.print("Action: Pay using PayPal -> ");
        payPal.pay(45.50);

        // Scenario 2: Using Stripe (needs adaptation)
        StripeSDK stripeSDK = new StripeSDK();
        PaymentProcessor stripe = new StripeAdapter(stripeSDK); // Stripe adapted to PaymentProcessor!
        System.out.print("\nAction: Pay using Stripe -> ");
        stripe.pay(45.50);
    }
}
