package cab_booking;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class CabBookingDemo {

    // --- Value Objects & Enums ---

    public static class Location {
        private final double x;
        private final double y;

        public Location(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public double getY() { return y; }

        public double distanceTo(Location other) {
            double dx = this.x - other.x;
            double dy = this.y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }

        @Override
        public String toString() {
            return String.format("(%.2f, %.2f)", x, y);
        }
    }

    public static enum DriverStatus {
        OFFLINE,
        AVAILABLE,
        BUSY
    }

    public static enum TripStatus {
        REQUESTED,
        ASSIGNED,
        STARTED,
        COMPLETED,
        CANCELLED
    }

    // --- Domain Models ---

    public static class Rider {
        private final String id;
        private final String name;
        private final double rating;

        public Rider(String id, String name, double rating) {
            this.id = id;
            this.name = name;
            this.rating = rating;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public double getRating() { return rating; }
    }

    public static class Driver {
        private final String id;
        private final String name;
        private final double rating;
        private final AtomicReference<Location> location;
        private final AtomicReference<DriverStatus> status;

        public Driver(String id, String name, double rating, Location location) {
            this.id = id;
            this.name = name;
            this.rating = rating;
            this.location = new AtomicReference<>(location);
            this.status = new AtomicReference<>(DriverStatus.AVAILABLE);
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public double getRating() { return rating; }

        public Location getLocation() {
            return location.get();
        }

        public void updateLocation(Location newLocation) {
            location.set(newLocation);
        }

        public DriverStatus getStatus() {
            return status.get();
        }

        public boolean reserve() {
            return status.compareAndSet(DriverStatus.AVAILABLE, DriverStatus.BUSY);
        }

        public void release() {
            status.compareAndSet(DriverStatus.BUSY, DriverStatus.AVAILABLE);
        }

        public void setOffline() {
            while (true) {
                DriverStatus current = status.get();
                if (current == DriverStatus.BUSY) {
                    throw new IllegalStateException("Driver is currently on a trip and cannot go offline.");
                }
                if (status.compareAndSet(current, DriverStatus.OFFLINE)) {
                    break;
                }
            }
        }

        public void setAvailable() {
            status.set(DriverStatus.AVAILABLE);
        }
    }

    // --- Strategies ---

    public static interface PricingStrategy {
        double calculateFare(Location pickup, Location destination);
    }

    public static class DefaultPricingStrategy implements PricingStrategy {
        private static final double BASE_FARE = 50.0;
        private static final double PER_KM_RATE = 12.0;

        @Override
        public double calculateFare(Location pickup, Location destination) {
            double distance = pickup.distanceTo(destination);
            return BASE_FARE + (distance * PER_KM_RATE);
        }
    }

    public static class SurgePricingStrategy implements PricingStrategy {
        private static final double BASE_FARE = 50.0;
        private static final double PER_KM_RATE = 12.0;
        private final double multiplier;

        public SurgePricingStrategy(double multiplier) {
            this.multiplier = multiplier;
        }

        @Override
        public double calculateFare(Location pickup, Location destination) {
            double distance = pickup.distanceTo(destination);
            return (BASE_FARE + (distance * PER_KM_RATE)) * multiplier;
        }
    }

    public static interface MatchingStrategy {
        Driver matchDriver(Rider rider, Location pickup, List<Driver> availableDrivers);
    }

    public static class NearestDriverMatchingStrategy implements MatchingStrategy {
        @Override
        public Driver matchDriver(Rider rider, Location pickup, List<Driver> availableDrivers) {
            Driver nearestDriver = null;
            double minDistance = Double.MAX_VALUE;

            for (Driver driver : availableDrivers) {
                double dist = driver.getLocation().distanceTo(pickup);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearestDriver = driver;
                }
            }
            return nearestDriver;
        }
    }

    // --- Trip Object ---

    public static class Trip {
        private final String tripId;
        private final Rider rider;
        private final Driver driver;
        private final Location pickup;
        private final Location destination;
        private final double fare;
        private final AtomicReference<TripStatus> status;

        public Trip(String tripId, Rider rider, Driver driver, Location pickup, Location destination, double fare) {
            this.tripId = tripId;
            this.rider = rider;
            this.driver = driver;
            this.pickup = pickup;
            this.destination = destination;
            this.fare = fare;
            this.status = new AtomicReference<>(TripStatus.REQUESTED);
        }

        public String getTripId() { return tripId; }
        public Rider getRider() { return rider; }
        public Driver getDriver() { return driver; }
        public Location getPickup() { return pickup; }
        public Location getDestination() { return destination; }
        public double getFare() { return fare; }
        public TripStatus getStatus() { return status.get(); }

        public boolean assign() {
            return status.compareAndSet(TripStatus.REQUESTED, TripStatus.ASSIGNED);
        }

        public boolean start() {
            return status.compareAndSet(TripStatus.ASSIGNED, TripStatus.STARTED);
        }

        public boolean complete() {
            if (status.compareAndSet(TripStatus.STARTED, TripStatus.COMPLETED)) {
                driver.release();
                return true;
            }
            return false;
        }

        public boolean cancel() {
            while (true) {
                TripStatus current = status.get();
                if (current != TripStatus.REQUESTED && current != TripStatus.ASSIGNED) {
                    return false;
                }
                if (status.compareAndSet(current, TripStatus.CANCELLED)) {
                    driver.release();
                    return true;
                }
            }
        }
    }

    // --- Cab Booking Service ---

    public static class CabBookingService {
        private final ConcurrentHashMap<String, Rider> riders = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Driver> drivers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Trip> trips = new ConcurrentHashMap<>();
        private final AtomicInteger tripCounter = new AtomicInteger(0);

        private final AtomicReference<PricingStrategy> pricingStrategy = new AtomicReference<>(new DefaultPricingStrategy());
        private final AtomicReference<MatchingStrategy> matchingStrategy = new AtomicReference<>(new NearestDriverMatchingStrategy());

        private static final CabBookingService INSTANCE = new CabBookingService();
        public static CabBookingService getInstance() { return INSTANCE; }

        private CabBookingService() {}

        public void registerRider(Rider rider) {
            riders.put(rider.getId(), rider);
        }

        public void registerDriver(Driver driver) {
            drivers.put(driver.getId(), driver);
        }

        public Rider getRider(String riderId) {
            return riders.get(riderId);
        }

        public Driver getDriver(String driverId) {
            return drivers.get(driverId);
        }

        public Trip getTrip(String tripId) {
            return trips.get(tripId);
        }

        public void setPricingStrategy(PricingStrategy strategy) {
            this.pricingStrategy.set(strategy);
        }

        public void setMatchingStrategy(MatchingStrategy strategy) {
            this.matchingStrategy.set(strategy);
        }

        public Trip bookCab(String riderId, Location pickup, Location destination) {
            Rider rider = riders.get(riderId);
            if (rider == null) {
                throw new IllegalArgumentException("Rider not found: " + riderId);
            }

            Driver assignedDriver = null;
            while (true) {
                List<Driver> availableDrivers = drivers.values().stream()
                    .filter(d -> d.getStatus() == DriverStatus.AVAILABLE)
                    .collect(Collectors.toList());

                if (availableDrivers.isEmpty()) {
                    return null;
                }

                Driver candidate = matchingStrategy.get().matchDriver(rider, pickup, availableDrivers);
                if (candidate == null) {
                    return null;
                }

                if (candidate.reserve()) {
                    assignedDriver = candidate;
                    break;
                }
            }

            double fare = pricingStrategy.get().calculateFare(pickup, destination);
            String tripId = "TRIP-" + tripCounter.incrementAndGet();
            Trip trip = new Trip(tripId, rider, assignedDriver, pickup, destination, fare);
            
            trip.assign();
            trips.put(tripId, trip);

            return trip;
        }

        public boolean startTrip(String tripId) {
            Trip trip = trips.get(tripId);
            if (trip == null) return false;
            return trip.start();
        }

        public boolean completeTrip(String tripId) {
            Trip trip = trips.get(tripId);
            if (trip == null) return false;
            return trip.complete();
        }

        public boolean cancelTrip(String tripId) {
            Trip trip = trips.get(tripId);
            if (trip == null) return false;
            return trip.cancel();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        CabBookingService service = CabBookingService.getInstance();

        // Register riders
        service.registerRider(new Rider("R1", "Alice", 4.8));
        service.registerRider(new Rider("R2", "Bob", 4.9));
        service.registerRider(new Rider("R3", "Charlie", 4.7));
        service.registerRider(new Rider("R4", "David", 4.5));
        service.registerRider(new Rider("R5", "Eve", 4.6));

        // Register drivers
        service.registerDriver(new Driver("D1", "Driver_One", 4.9, new Location(0.0, 0.0)));
        service.registerDriver(new Driver("D2", "Driver_Two", 4.8, new Location(1.0, 1.0)));
        service.registerDriver(new Driver("D3", "Driver_Three", 4.7, new Location(5.0, 5.0)));

        System.out.println("=== INITIAL STATS ===");
        System.out.println("Driver 1 location: " + service.getDriver("D1").getLocation() + ", Status: " + service.getDriver("D1").getStatus());
        System.out.println("Driver 2 location: " + service.getDriver("D2").getLocation() + ", Status: " + service.getDriver("D2").getStatus());
        System.out.println("Driver 3 location: " + service.getDriver("D3").getLocation() + ", Status: " + service.getDriver("D3").getStatus());

        // Single booking test
        System.out.println("\n=== SINGLE BOOKING TEST ===");
        Location pickup = new Location(0.1, 0.1);
        Location destination = new Location(10.0, 10.0);
        Trip trip = service.bookCab("R1", pickup, destination);
        if (trip != null) {
            System.out.println("Trip successfully booked! Trip ID: " + trip.getTripId() +
                    ", Driver Assigned: " + trip.getDriver().getName() +
                    ", Fare: $" + String.format("%.2f", trip.getFare()) +
                    ", Trip Status: " + trip.getStatus());
            
            System.out.println("Starting Trip...");
            service.startTrip(trip.getTripId());
            System.out.println("Trip Status: " + trip.getStatus() + ", Driver Status: " + trip.getDriver().getStatus());

            System.out.println("Completing Trip...");
            service.completeTrip(trip.getTripId());
            System.out.println("Trip Status: " + trip.getStatus() + ", Driver Status: " + trip.getDriver().getStatus());
        } else {
            System.out.println("Failed to book cab.");
        }

        // Dynamic surge pricing test
        System.out.println("\n=== SURGE PRICING TEST ===");
        service.setPricingStrategy(new SurgePricingStrategy(2.5)); // 2.5x surge pricing
        Trip surgeTrip = service.bookCab("R2", new Location(0.2, 0.2), new Location(5.0, 5.0));
        if (surgeTrip != null) {
            System.out.println("Surge Trip Fare: $" + String.format("%.2f", surgeTrip.getFare()) + " (with 2.5x surge)");
            service.cancelTrip(surgeTrip.getTripId());
            System.out.println("Surge Trip Cancelled. Driver Status: " + surgeTrip.getDriver().getStatus());
        }

        // Reset pricing strategy to default
        service.setPricingStrategy(new DefaultPricingStrategy());

        // Concurrency test: Multi-threaded booking requests
        System.out.println("\n=== CONCURRENT BOOKINGS TEST ===");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        // Position all three drivers and make them available
        service.getDriver("D1").updateLocation(new Location(0.0, 0.0));
        service.getDriver("D1").setAvailable();
        service.getDriver("D2").updateLocation(new Location(1.0, 1.0));
        service.getDriver("D2").setAvailable();
        service.getDriver("D3").updateLocation(new Location(2.0, 2.0));
        service.getDriver("D3").setAvailable();

        String[] riders = {"R1", "R2", "R3", "R4", "R5"};
        Location riderPickup = new Location(0.5, 0.5);

        for (String riderId : riders) {
            executor.submit(() -> {
                try {
                    Thread.sleep((long) (Math.random() * 100)); // random slight offset
                    System.out.println("[Thread-" + Thread.currentThread().getId() + "] Rider " + riderId + " attempting to book a cab...");
                    Trip t = service.bookCab(riderId, riderPickup, new Location(10.0, 10.0));
                    if (t != null) {
                        System.out.println("[Thread-" + Thread.currentThread().getId() + "] SUCCESS: Rider " + riderId + 
                                " booked " + t.getDriver().getName() + " on Trip " + t.getTripId());
                        Thread.sleep(150);
                        service.startTrip(t.getTripId());
                        Thread.sleep(150);
                        service.completeTrip(t.getTripId());
                        System.out.println("[Thread-" + Thread.currentThread().getId() + "] TRIP COMPLETED: Trip " + t.getTripId());
                    } else {
                        System.out.println("[Thread-" + Thread.currentThread().getId() + "] FAILED: No driver available for Rider " + riderId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        executor.shutdownNow();

        System.out.println("\n=== FINAL STATS ===");
        System.out.println("Driver 1 Status: " + service.getDriver("D1").getStatus());
        System.out.println("Driver 2 Status: " + service.getDriver("D2").getStatus());
        System.out.println("Driver 3 Status: " + service.getDriver("D3").getStatus());
    }
}
