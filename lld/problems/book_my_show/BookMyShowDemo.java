import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * BookMyShow LLD — Runnable Demo
 * Demonstrates: Strategy (Pricing), Observer (Notifications), ReentrantLock (Concurrency)
 *
 * Compile & Run:
 *   javac BookMyShowDemo.java && java BookMyShowDemo
 */
public class BookMyShowDemo {

    // ─── Enums ───────────────────────────────────────────────────────────────

    enum SeatCategory { GOLD, SILVER, PLATINUM }
    enum SeatStatus   { AVAILABLE, HELD, BOOKED }
    enum BookingStatus { CONFIRMED, CANCELLED, PENDING_PAYMENT, EXPIRED }

    // ─── Domain Objects ──────────────────────────────────────────────────────

    static class Seat {
        private final String seatId;
        private final int row, col;
        private final SeatCategory category;

        Seat(String seatId, int row, int col, SeatCategory category) {
            this.seatId = seatId; this.row = row; this.col = col; this.category = category;
        }

        String getSeatId() { return seatId; }
        SeatCategory getCategory() { return category; }

        @Override public String toString() { return seatId + "(" + category + ")"; }
    }

    static class Booking {
        private final String bookingId;
        private final Show show;
        private final List<String> seatIds;
        private final String userId;
        private final int totalAmount;
        private BookingStatus status;

        Booking(String bookingId, Show show, List<String> seatIds, String userId,
                int totalAmount, BookingStatus status) {
            this.bookingId = bookingId; this.show = show; this.seatIds = seatIds;
            this.userId = userId; this.totalAmount = totalAmount; this.status = status;
        }

        String getBookingId() { return bookingId; }
        Show getShow() { return show; }
        List<String> getSeatIds() { return seatIds; }
        String getUserId() { return userId; }
        int getTotalAmount() { return totalAmount; }
        BookingStatus getStatus() { return status; }
        void setStatus(BookingStatus s) { this.status = s; }
    }

    // ─── Show (Contains the critical section) ────────────────────────────────

    static class Show {
        private final String showId;
        private final String movieTitle;
        private final Map<String, SeatStatus> seatStatusMap = new ConcurrentHashMap<>();
        private final Map<String, Seat> seatMap = new ConcurrentHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();

        Show(String showId, String movieTitle, List<Seat> seats) {
            this.showId = showId;
            this.movieTitle = movieTitle;
            for (Seat seat : seats) {
                seatMap.put(seat.getSeatId(), seat);
                seatStatusMap.put(seat.getSeatId(), SeatStatus.AVAILABLE);
            }
        }

        String getShowId() { return showId; }
        String getMovieTitle() { return movieTitle; }
        Seat getSeat(String seatId) { return seatMap.get(seatId); }

        /**
         * Thread-safe seat hold — the critical section.
         * Acquires a per-show lock, checks ALL seats are AVAILABLE, then marks them HELD atomically.
         */
        List<String> holdSeats(List<String> seatIds, String userId) {
            lock.lock();
            try {
                // Validate all seats are available (all-or-nothing)
                for (String seatId : seatIds) {
                    SeatStatus status = seatStatusMap.get(seatId);
                    if (status != SeatStatus.AVAILABLE) {
                        throw new RuntimeException("Seat " + seatId + " is " + status
                            + " — cannot hold. User: " + userId);
                    }
                }
                // Mark all as HELD
                for (String seatId : seatIds) {
                    seatStatusMap.put(seatId, SeatStatus.HELD);
                }
                return seatIds;
            } finally {
                lock.unlock();
            }
        }

        void confirmBooking(List<String> seatIds) {
            lock.lock();
            try {
                for (String seatId : seatIds) {
                    seatStatusMap.put(seatId, SeatStatus.BOOKED);
                }
            } finally {
                lock.unlock();
            }
        }

        void releaseSeats(List<String> seatIds) {
            lock.lock();
            try {
                for (String seatId : seatIds) {
                    seatStatusMap.put(seatId, SeatStatus.AVAILABLE);
                }
            } finally {
                lock.unlock();
            }
        }

        void printSeatMap() {
            System.out.println("\n  Seat Map for: " + movieTitle + " [" + showId + "]");
            seatStatusMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("    %-6s %-10s %s%n",
                    e.getKey(), seatMap.get(e.getKey()).getCategory(), e.getValue()));
        }
    }

    // ─── Strategy Pattern: Pricing ───────────────────────────────────────────

    interface PricingStrategy {
        int calculatePrice(Seat seat, Show show);
    }

    static class FlatPricingStrategy implements PricingStrategy {
        private final Map<SeatCategory, Integer> priceMap;

        FlatPricingStrategy(Map<SeatCategory, Integer> priceMap) {
            this.priceMap = priceMap;
        }

        @Override
        public int calculatePrice(Seat seat, Show show) {
            return priceMap.getOrDefault(seat.getCategory(), 0);
        }
    }

    static class SurgePricingStrategy implements PricingStrategy {
        private final PricingStrategy base;
        private final double multiplier;

        SurgePricingStrategy(PricingStrategy base, double multiplier) {
            this.base = base; this.multiplier = multiplier;
        }

        @Override
        public int calculatePrice(Seat seat, Show show) {
            return (int) (base.calculatePrice(seat, show) * multiplier);
        }
    }

    // ─── Observer Pattern: Notifications ─────────────────────────────────────

    interface BookingObserver {
        void onBookingConfirmed(Booking booking);
        void onBookingCancelled(Booking booking);
    }

    static class EmailNotifier implements BookingObserver {
        @Override
        public void onBookingConfirmed(Booking booking) {
            System.out.println("  [EMAIL] ✓ Booking " + booking.getBookingId()
                + " confirmed for " + booking.getUserId()
                + " | Seats: " + booking.getSeatIds()
                + " | Amount: ₹" + booking.getTotalAmount());
        }

        @Override
        public void onBookingCancelled(Booking booking) {
            System.out.println("  [EMAIL] ✗ Booking " + booking.getBookingId()
                + " cancelled for " + booking.getUserId());
        }
    }

    static class SMSNotifier implements BookingObserver {
        @Override
        public void onBookingConfirmed(Booking booking) {
            System.out.println("  [SMS]   ✓ Ticket booked: " + booking.getSeatIds()
                + " for " + booking.getShow().getMovieTitle());
        }

        @Override
        public void onBookingCancelled(Booking booking) {
            System.out.println("  [SMS]   ✗ Booking cancelled: " + booking.getBookingId());
        }
    }

    // ─── BookingService (Orchestrator) ───────────────────────────────────────

    static class BookingService {
        private final Map<String, Show> shows = new ConcurrentHashMap<>();
        private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
        private PricingStrategy pricingStrategy;
        private final List<BookingObserver> observers = new ArrayList<>();
        
        // Use a daemon thread pool so that scheduled tasks don't prevent JVM shutdown
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        private final Map<String, ScheduledFuture<?>> activeHolds = new ConcurrentHashMap<>();

        void addShow(Show show) { shows.put(show.getShowId(), show); }
        void setPricingStrategy(PricingStrategy strategy) { this.pricingStrategy = strategy; }
        void registerObserver(BookingObserver observer) { observers.add(observer); }

        /**
         * Convenience method for instant booking (hold + immediate confirm)
         */
        Booking bookTickets(String showId, List<String> seatIds, String userId) {
            Booking booking = holdSeats(showId, seatIds, userId, 60);
            return confirmBooking(booking.getBookingId());
        }

        /**
         * Hold seats with a custom timeout (in seconds)
         */
        Booking holdSeats(String showId, List<String> seatIds, String userId, long delaySeconds) {
            Show show = shows.get(showId);
            if (show == null) throw new RuntimeException("Show not found: " + showId);

            // 1. Hold seats atomically in the show
            show.holdSeats(seatIds, userId);

            // 2. Calculate price
            int total = seatIds.stream()
                .map(show::getSeat)
                .mapToInt(seat -> pricingStrategy.calculatePrice(seat, show))
                .sum();

            // 3. Create booking in PENDING_PAYMENT
            String bookingId = "BKG-" + UUID.randomUUID().toString().substring(0, 8);
            Booking booking = new Booking(bookingId, show, seatIds, userId, total, BookingStatus.PENDING_PAYMENT);
            bookings.put(bookingId, booking);

            // 4. Schedule release
            ScheduledFuture<?> releaseTask = scheduler.schedule(() -> {
                show.lock.lock();
                try {
                    Booking currentBooking = bookings.get(bookingId);
                    if (currentBooking != null && currentBooking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                        System.out.println("  [System] ⏱ Hold expired for Booking " + bookingId + ". Releasing seats: " + seatIds);
                        currentBooking.setStatus(BookingStatus.EXPIRED);
                        show.releaseSeats(seatIds);
                    }
                } finally {
                    show.lock.unlock();
                }
            }, delaySeconds, TimeUnit.SECONDS);

            activeHolds.put(bookingId, releaseTask);
            return booking;
        }

        /**
         * Confirm payment and finalize booking (HELD -> BOOKED)
         */
        Booking confirmBooking(String bookingId) {
            Booking booking = bookings.get(bookingId);
            if (booking == null) throw new RuntimeException("Booking not found: " + bookingId);

            Show show = booking.getShow();
            show.lock.lock();
            try {
                if (booking.getStatus() == BookingStatus.EXPIRED) {
                    throw new RuntimeException("Cannot confirm: Booking " + bookingId + " has expired!");
                }
                if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
                    throw new RuntimeException("Cannot confirm: Booking " + bookingId + " is in invalid status: " + booking.getStatus());
                }

                // 1. Confirm seats
                show.confirmBooking(booking.getSeatIds());

                // 2. Update booking status
                booking.setStatus(BookingStatus.CONFIRMED);

                // 3. Cancel scheduled expiry task
                ScheduledFuture<?> task = activeHolds.remove(bookingId);
                if (task != null) {
                    task.cancel(false);
                }

                // 4. Notify observers
                observers.forEach(o -> o.onBookingConfirmed(booking));

                return booking;
            } finally {
                show.lock.unlock();
            }
        }

        void cancelBooking(String bookingId) {
            Booking booking = bookings.get(bookingId);
            if (booking == null) throw new RuntimeException("Booking not found: " + bookingId);

            booking.setStatus(BookingStatus.CANCELLED);
            booking.getShow().releaseSeats(booking.getSeatIds());
            observers.forEach(o -> o.onBookingCancelled(booking));
        }
    }

    // ─── Main Demo ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       BookMyShow — LLD Demo                     ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // --- Setup: Create seats for a screen ---
        List<Seat> seats = new ArrayList<>();
        seats.add(new Seat("A1", 1, 1, SeatCategory.GOLD));
        seats.add(new Seat("A2", 1, 2, SeatCategory.GOLD));
        seats.add(new Seat("A3", 1, 3, SeatCategory.GOLD));
        seats.add(new Seat("B1", 2, 1, SeatCategory.SILVER));
        seats.add(new Seat("B2", 2, 2, SeatCategory.SILVER));
        seats.add(new Seat("B3", 2, 3, SeatCategory.SILVER));
        seats.add(new Seat("C1", 3, 1, SeatCategory.PLATINUM));
        seats.add(new Seat("C2", 3, 2, SeatCategory.PLATINUM));

        // --- Setup: Create a show ---
        Show show = new Show("SHOW-001", "Avengers: Endgame", seats);

        // --- Setup: Booking service with flat pricing ---
        BookingService service = new BookingService();
        service.addShow(show);
        service.setPricingStrategy(new FlatPricingStrategy(Map.of(
            SeatCategory.GOLD, 350,
            SeatCategory.SILVER, 200,
            SeatCategory.PLATINUM, 500
        )));
        service.registerObserver(new EmailNotifier());
        service.registerObserver(new SMSNotifier());

        // ─── Scenario 1: Normal booking ─────────────────────────────────
        System.out.println("\n--- Scenario 1: Rohit books Gold seats A1, A2 ---");
        Booking b1 = service.bookTickets("SHOW-001", List.of("A1", "A2"), "user_rohit");
        show.printSeatMap();

        // ─── Scenario 2: Another user tries the same seats (CONFLICT) ───
        System.out.println("\n--- Scenario 2: Priya tries to book A1 (already booked) ---");
        try {
            service.bookTickets("SHOW-001", List.of("A1", "B1"), "user_priya");
        } catch (RuntimeException e) {
            System.out.println("  ✗ EXPECTED ERROR: " + e.getMessage());
        }

        // ─── Scenario 3: Priya books different seats ────────────────────
        System.out.println("\n--- Scenario 3: Priya books Silver seats B1, B2 ---");
        Booking b2 = service.bookTickets("SHOW-001", List.of("B1", "B2"), "user_priya");

        // ─── Scenario 4: Cancel Rohit's booking ─────────────────────────
        System.out.println("\n--- Scenario 4: Rohit cancels booking ---");
        service.cancelBooking(b1.getBookingId());
        show.printSeatMap();

        // ─── Scenario 5: Surge pricing kicks in ─────────────────────────
        System.out.println("\n--- Scenario 5: Surge pricing (1.5x) — Amit books A1, A2 (now released) ---");
        service.setPricingStrategy(new SurgePricingStrategy(
            new FlatPricingStrategy(Map.of(
                SeatCategory.GOLD, 350,
                SeatCategory.SILVER, 200,
                SeatCategory.PLATINUM, 500
            )), 1.5));
        Booking b3 = service.bookTickets("SHOW-001", List.of("A1", "A2"), "user_amit");

        // ─── Scenario 6: Concurrent booking stress test ─────────────────
        System.out.println("\n--- Scenario 6: 5 threads race for seat C1 (only 1 wins) ---");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final String userId = "racer_" + i;
            results.add(executor.submit(() -> {
                latch.countDown();
                latch.await(); // All threads start simultaneously
                try {
                    Booking b = service.bookTickets("SHOW-001", List.of("C1"), userId);
                    return userId + " WON — Booking: " + b.getBookingId();
                } catch (RuntimeException e) {
                    return userId + " LOST — " + e.getMessage();
                }
            }));
        }

        for (Future<String> f : results) {
            System.out.println("  " + f.get());
        }
        executor.shutdown();

        // ─── Scenario 7: Seat Hold Expiration & Late Confirm ─────────────
        System.out.println("\n--- Scenario 7: Seat Hold Expiration & Late Confirm ---");
        System.out.println("  Holding seat C2 for 2 seconds for user_neha...");
        Booking b4 = service.holdSeats("SHOW-001", List.of("C2"), "user_neha", 2);
        show.printSeatMap();

        System.out.println("\n  Wait 3 seconds for hold to expire...");
        Thread.sleep(3000);
        show.printSeatMap();

        System.out.println("\n  Trying to confirm expired booking (should fail)...");
        try {
            service.confirmBooking(b4.getBookingId());
        } catch (RuntimeException e) {
            System.out.println("  ✗ EXPECTED ERROR: " + e.getMessage());
        }

        // ─── Final seat map ─────────────────────────────────────────────
        show.printSeatMap();

        System.out.println("\n✓ Demo complete.");
    }
}
