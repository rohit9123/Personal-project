package lld.behavioral.observer;


import java.util.ArrayList;
import java.util.List;

// ============================================================================
// 1. Observer Interface (The Subscriber contract)
// ============================================================================
interface Observer {
    void update(String videoTitle);
}

// ============================================================================
// 2. Concrete Observer (A real User subscribing to a channel)
// ============================================================================
class Subscriber implements Observer {
    private final String name;

    public Subscriber(String name) {
        this.name = name;
    }

    @Override
    public void update(String videoTitle) {
        System.out.println("Notification to " + name + ": New video uploaded -> \"" + videoTitle + "\"");
    }
}

// ============================================================================
// 3. Subject (The YouTube Channel that notifies subscribers)
// ============================================================================
class Channel {
    private final List<Observer> subscribers = new ArrayList<>();
    
    // Subscribe
    public void subscribe(Observer subscriber) {
        subscribers.add(subscriber);
    }

    // Unsubscribe
    public void unsubscribe(Observer subscriber) {
        subscribers.remove(subscriber);
    }

    // Upload video and notify everyone
    public void uploadVideo(String videoTitle) {
        System.out.println("\n[Channel] Uploaded video: " + videoTitle);
        for (Observer subscriber : subscribers) {
            subscriber.update(videoTitle); // Notify
        }
    }
}

// ============================================================================
// 4. Execution Demo
// ============================================================================
public class ObserverPatternDemo {
    public static void main(String[] args) {
        Channel myChannel = new Channel();

        // Create subscribers
        Subscriber Alice = new Subscriber("Alice");
        Subscriber Bob = new Subscriber("Bob");

        // Alice and Bob subscribe
        myChannel.subscribe(Alice);
        myChannel.subscribe(Bob);

        // Upload a video -> Both Alice and Bob get notified
        myChannel.uploadVideo("Design Patterns for Beginners");

        // Bob unsubscribes
        myChannel.unsubscribe(Bob);

        // Upload another video -> Only Alice gets notified
        myChannel.uploadVideo("How to switch jobs in 2026");
    }
}
