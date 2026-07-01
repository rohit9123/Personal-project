package lld.creational.singleton;


// ============================================================================
// 1. Double-Checked Locking (DCL) - Classic Interview Choice
// ============================================================================
class DCLSingleton {
    // volatile ensures memory visibility and prevents instruction reordering
    private static volatile DCLSingleton instance;

    private DCLSingleton() {}

    public static DCLSingleton getInstance() {
        if (instance == null) { // 1st check (no locking for performance)
            synchronized (DCLSingleton.class) { // Lock block
                if (instance == null) { // 2nd check (under lock to prevent duplicate creation)
                    instance = new DCLSingleton();
                }
            }
        }
        return instance;
    }
}

// ============================================================================
// 2. Bill Pugh Singleton - The Best Java-Specific Solution
// ============================================================================
class BillPughSingleton {
    private BillPughSingleton() {}

    // Static inner class loaded ONLY when getInstance() is called
    private static class Holder {
        private static final BillPughSingleton INSTANCE = new BillPughSingleton();
    }

    public static BillPughSingleton getInstance() {
        return Holder.INSTANCE; // Thread-safety is guaranteed by ClassLoader
    }
}

// ============================================================================
// 3. Execution Demo
// ============================================================================
public class SingletonImplementation {
    public static void main(String[] args) {
        System.out.println("--- Minimal Singleton Pattern Demo ---");

        // Verify that DCL Singleton returns the exact same instance
        DCLSingleton dcl1 = DCLSingleton.getInstance();
        DCLSingleton dcl2 = DCLSingleton.getInstance();
        System.out.println("DCL Instances match: " + (dcl1 == dcl2) + " (Hash: " + dcl1.hashCode() + ")");

        // Verify that Bill Pugh Singleton returns the exact same instance
        BillPughSingleton bp1 = BillPughSingleton.getInstance();
        BillPughSingleton bp2 = BillPughSingleton.getInstance();
        System.out.println("Bill Pugh Instances match: " + (bp1 == bp2) + " (Hash: " + bp1.hashCode() + ")");
    }
}
