package lld.structural.proxy;

// 1. The Subject Interface
interface Image {
    void display();
}

// 2. The Real Subject (Heavy to construct / load)
class RealImage implements Image {
    private final String fileName;

    public RealImage(String fileName) {
        this.fileName = fileName;
        loadFromDisk(); // Simulation of heavy disk loading on instantiation
    }

    private void loadFromDisk() {
        System.out.println("Loading heavy image from disk: '" + fileName + "'...");
        try {
            // Simulate network or disk I/O latency
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            System.err.println("Load interrupted: " + e.getMessage());
        }
    }

    @Override
    public void display() {
        System.out.println("Displaying image: '" + fileName + "'");
    }
}

// 3. The Caching & Virtual Proxy (Controls access & delays heavy instantiation)
class ProxyImage implements Image {
    private final String fileName;
    private RealImage realImage; // Lazy-loaded reference

    public ProxyImage(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void display() {
        // Double check: Lazily load only on first display access
        if (realImage == null) {
            System.out.println("[Proxy] Real subject not initialized. Creating it now...");
            realImage = new RealImage(fileName);
        } else {
            System.out.println("[Proxy] Fetching image from cache...");
        }
        realImage.display();
    }
}

// 4. Execution Client Demo
public class ProxyPatternDemo {
    public static void main(String[] args) {
        System.out.println("--- LLD Proxy Design Pattern Demo ---");

        // Client gets a reference to the interface, backed by the Proxy
        Image image = new ProxyImage("milky_way_galaxy_8k.png");

        System.out.println("\n[Client Action] Requesting display for the 1st time:");
        image.display();

        System.out.println("\n[Client Action] Requesting display for the 2nd time (should hit cache):");
        image.display();

        System.out.println("\n[Client Action] Requesting display for the 3rd time (should hit cache):");
        image.display();
    }
}
