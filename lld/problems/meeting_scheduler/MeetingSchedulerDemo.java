package meeting_scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MeetingSchedulerDemo {

    // =========================================================================
    // Core Domain Models
    // =========================================================================

    public static class Interval {
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;

        public Interval(LocalDateTime startTime, LocalDateTime endTime) {
            if (startTime.isAfter(endTime) || startTime.isEqual(endTime)) {
                throw new IllegalArgumentException("Start time must be before end time.");
            }
            this.startTime = startTime;
            this.endTime = endTime;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public boolean overlaps(Interval other) {
            return this.startTime.isBefore(other.endTime) && other.startTime.isBefore(this.endTime);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Interval interval = (Interval) o;
            return startTime.equals(interval.startTime) && endTime.equals(interval.endTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startTime, endTime);
        }

        @Override
        public String toString() {
            return "[" + startTime.toLocalTime() + " - " + endTime.toLocalTime() + "]";
        }
    }

    public interface Lockable {
        String getLockId();
        ReentrantReadWriteLock getReadWriteLock();
    }

    public static class User implements Lockable {
        private final String id;
        private final String name;
        private final String email;
        private final List<Meeting> meetings = new ArrayList<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        public User(String id, String name, String email) {
            this.id = id;
            this.name = name;
            this.email = email;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        @Override
        public String getLockId() {
            return "user_" + id;
        }

        @Override
        public ReentrantReadWriteLock getReadWriteLock() {
            return rwLock;
        }

        public boolean isAvailable(Interval interval) {
            // Must be called with lock held
            for (Meeting m : meetings) {
                if (m.getInterval().overlaps(interval)) {
                    return false;
                }
            }
            return true;
        }

        public void addMeeting(Meeting meeting) {
            // Must be called with lock held
            meetings.add(meeting);
        }

        public List<Meeting> getSchedule() {
            rwLock.readLock().lock();
            try {
                return new ArrayList<>(meetings);
            } finally {
                rwLock.readLock().unlock();
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static class MeetingRoom implements Lockable {
        private final String id;
        private final String name;
        private final int capacity;
        private final int floor; // Location represented by floor level
        private final List<Meeting> meetings = new ArrayList<>();
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

        public MeetingRoom(String id, String name, int capacity, int floor) {
            this.id = id;
            this.name = name;
            this.capacity = capacity;
            this.floor = floor;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getCapacity() {
            return capacity;
        }

        public int getFloor() {
            return floor;
        }

        @Override
        public String getLockId() {
            return "room_" + id;
        }

        @Override
        public ReentrantReadWriteLock getReadWriteLock() {
            return rwLock;
        }

        public boolean isAvailable(Interval interval) {
            // Must be called with lock held
            for (Meeting m : meetings) {
                if (m.getInterval().overlaps(interval)) {
                    return false;
                }
            }
            return true;
        }

        public void addMeeting(Meeting meeting) {
            // Must be called with lock held
            meetings.add(meeting);
        }

        public List<Meeting> getSchedule() {
            rwLock.readLock().lock();
            try {
                return new ArrayList<>(meetings);
            } finally {
                rwLock.readLock().unlock();
            }
        }

        @Override
        public String toString() {
            return name + "(Cap: " + capacity + ", Floor: " + floor + ")";
        }
    }

    public static class Meeting {
        private final String id;
        private final String title;
        private final User organizer;
        private final List<User> participants;
        private final MeetingRoom room;
        private final Interval interval;
        private final String recurringMeetingId;

        public Meeting(String id, String title, User organizer, List<User> participants, MeetingRoom room, Interval interval) {
            this(id, title, organizer, participants, room, interval, null);
        }

        public Meeting(String id, String title, User organizer, List<User> participants, MeetingRoom room, Interval interval, String recurringMeetingId) {
            this.id = id;
            this.title = title;
            this.organizer = organizer;
            this.participants = new ArrayList<>(participants);
            this.room = room;
            this.interval = interval;
            this.recurringMeetingId = recurringMeetingId;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public User getOrganizer() { return organizer; }
        public List<User> getParticipants() { return participants; }
        public MeetingRoom getRoom() { return room; }
        public Interval getInterval() { return interval; }
        public String getRecurringMeetingId() { return recurringMeetingId; }

        @Override
        public String toString() {
            return String.format("Meeting[ID=%s, Title='%s', Room=%s, Interval=%s, Organizer=%s, Attendees=%s]",
                    id, title, room.getName(), interval, organizer.getName(), participants);
        }
    }

    public static class RecurringMeeting {
        private final String recurringMeetingId;
        private final String title;
        private final User organizer;
        private final List<User> participants;
        private final MeetingRoom room;
        private final List<Meeting> occurrences;

        public RecurringMeeting(String recurringMeetingId, String title, User organizer, List<User> participants, MeetingRoom room, List<Meeting> occurrences) {
            this.recurringMeetingId = recurringMeetingId;
            this.title = title;
            this.organizer = organizer;
            this.participants = participants;
            this.room = room;
            this.occurrences = occurrences;
        }

        public String getRecurringMeetingId() { return recurringMeetingId; }
        public String getTitle() { return title; }
        public User getOrganizer() { return organizer; }
        public List<User> getParticipants() { return participants; }
        public MeetingRoom getRoom() { return room; }
        public List<Meeting> getOccurrences() { return occurrences; }
    }

    public enum RecurrenceType {
        DAILY, WEEKLY
    }

    public static class BookingContext {
        private final int requiredCapacity;
        private final int requesterFloor;

        public BookingContext(int requiredCapacity, int requesterFloor) {
            this.requiredCapacity = requiredCapacity;
            this.requesterFloor = requesterFloor;
        }

        public int getRequiredCapacity() { return requiredCapacity; }
        public int getRequesterFloor() { return requesterFloor; }
    }

    // =========================================================================
    // Strategy Pattern for Room Booking
    // =========================================================================

    public interface RoomBookingStrategy {
        Optional<MeetingRoom> selectRoom(List<MeetingRoom> availableRooms, BookingContext context);
    }

    public static class SmallestFittingRoomStrategy implements RoomBookingStrategy {
        @Override
        public Optional<MeetingRoom> selectRoom(List<MeetingRoom> availableRooms, BookingContext context) {
            return availableRooms.stream()
                    .filter(room -> room.getCapacity() >= context.getRequiredCapacity())
                    .min(Comparator.comparingInt(MeetingRoom::getCapacity));
        }
    }

    public static class NearestRoomStrategy implements RoomBookingStrategy {
        @Override
        public Optional<MeetingRoom> selectRoom(List<MeetingRoom> availableRooms, BookingContext context) {
            return availableRooms.stream()
                    .filter(room -> room.getCapacity() >= context.getRequiredCapacity())
                    .min(Comparator.comparingInt(room -> Math.abs(room.getFloor() - context.getRequesterFloor())));
        }
    }

    public static class BookingConflictException extends Exception {
        public BookingConflictException(String message) {
            super(message);
        }
    }

    // =========================================================================
    // Main Meeting Scheduler Service
    // =========================================================================

    public static class MeetingScheduler {
        private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MeetingRoom> rooms = new ConcurrentHashMap<>();

        public void registerUser(User user) {
            users.put(user.getId(), user);
        }

        public void registerRoom(MeetingRoom room) {
            rooms.put(room.getId(), room);
        }

        public List<MeetingRoom> getAllRooms() {
            return new ArrayList<>(rooms.values());
        }

        /**
         * Helper method to acquire locks on all resources in a deadlock-free order.
         */
        private List<Lockable> lockResources(MeetingRoom room, User organizer, List<User> participants) {
            Set<Lockable> uniqueLockables = new HashSet<>();
            uniqueLockables.add(room);
            uniqueLockables.add(organizer);
            uniqueLockables.addAll(participants);

            List<Lockable> sortedLockables = new ArrayList<>(uniqueLockables);
            // Sort by getLockId() to enforce global ordering and prevent deadlocks
            sortedLockables.sort(Comparator.comparing(Lockable::getLockId));

            for (Lockable lockable : sortedLockables) {
                lockable.getReadWriteLock().writeLock().lock();
            }
            return sortedLockables;
        }

        /**
         * Helper method to release all acquired locks in reverse order.
         */
        private void unlockResources(List<Lockable> lockedResources) {
            for (int i = lockedResources.size() - 1; i >= 0; i--) {
                lockedResources.get(i).getReadWriteLock().writeLock().unlock();
            }
        }

        /**
         * Book a single meeting in a thread-safe and conflict-free manner.
         */
        public Meeting bookMeeting(String title, User organizer, List<User> participants, MeetingRoom room, Interval interval) throws BookingConflictException {
            validateBookingRequest(title, organizer, participants, room);
            Objects.requireNonNull(interval, "Meeting interval cannot be null.");
            List<Lockable> lockedResources = lockResources(room, organizer, participants);
            try {
                // Perform conflict checking under locks
                if (!room.isAvailable(interval)) {
                    throw new BookingConflictException("Room '" + room.getName() + "' is already booked for " + interval);
                }
                if (!organizer.isAvailable(interval)) {
                    throw new BookingConflictException("Organizer '" + organizer.getName() + "' has a conflict for " + interval);
                }
                for (User participant : participants) {
                    if (!participant.isAvailable(interval)) {
                        throw new BookingConflictException("Participant '" + participant.getName() + "' has a conflict for " + interval);
                    }
                }

                // All checks passed, book the meeting
                Meeting meeting = new Meeting(UUID.randomUUID().toString(), title, organizer, participants, room, interval);
                room.addMeeting(meeting);
                organizer.addMeeting(meeting);
                for (User participant : participants) {
                    participant.addMeeting(meeting);
                }
                return meeting;
            } finally {
                unlockResources(lockedResources);
            }
        }

        /**
         * Book a meeting using a room selection strategy.
         */
        public Meeting bookMeetingWithStrategy(String title, User organizer, List<User> participants, Interval interval, RoomBookingStrategy strategy, BookingContext context) throws BookingConflictException {
            List<MeetingRoom> allRooms = getAllRooms();

            // Optimistically check availability of rooms (snapshot read under read-locks)
            List<MeetingRoom> availableSnapshotRooms = new ArrayList<>();
            for (MeetingRoom r : allRooms) {
                r.getReadWriteLock().readLock().lock();
                try {
                    if (r.isAvailable(interval)) {
                        availableSnapshotRooms.add(r);
                    }
                } finally {
                    r.getReadWriteLock().readLock().unlock();
                }
            }

            // Retry cycle in case of a race condition on the selected room
            List<MeetingRoom> candidates = new ArrayList<>(availableSnapshotRooms);
            while (!candidates.isEmpty()) {
                Optional<MeetingRoom> selectedRoomOpt = strategy.selectRoom(candidates, context);
                if (!selectedRoomOpt.isPresent()) {
                    break;
                }
                MeetingRoom targetRoom = selectedRoomOpt.get();
                try {
                    return bookMeeting(title, organizer, participants, targetRoom, interval);
                } catch (BookingConflictException e) {
                    // If the conflict is about the room, retry with another room. Otherwise, it's a user conflict.
                    if (e.getMessage() != null && e.getMessage().contains("Room '" + targetRoom.getName() + "'")) {
                        candidates.remove(targetRoom);
                    } else {
                        throw e;
                    }
                }
            }
            throw new BookingConflictException("No suitable rooms available or all candidates were booked concurrently.");
        }

        /**
         * Helper method to generate intervals for recurring meetings.
         */
        public List<Interval> generateRecurringIntervals(Interval baseInterval, RecurrenceType recurrenceType, int occurrencesCount) {
            Objects.requireNonNull(baseInterval, "Base interval cannot be null.");
            Objects.requireNonNull(recurrenceType, "Recurrence type cannot be null.");
            if (occurrencesCount <= 0) {
                throw new IllegalArgumentException("Occurrences count must be greater than zero.");
            }
            List<Interval> intervals = new ArrayList<>();
            LocalDateTime currentStart = baseInterval.getStartTime();
            LocalDateTime currentEnd = baseInterval.getEndTime();
            Duration duration = Duration.between(currentStart, currentEnd);

            for (int i = 0; i < occurrencesCount; i++) {
                intervals.add(new Interval(currentStart, currentEnd));
                if (recurrenceType == RecurrenceType.DAILY) {
                    currentStart = currentStart.plusDays(1);
                } else if (recurrenceType == RecurrenceType.WEEKLY) {
                    currentStart = currentStart.plusWeeks(1);
                }
                currentEnd = currentStart.plus(duration);
            }
            return intervals;
        }

        /**
         * Book a recurring meeting in an atomic, all-or-nothing transaction.
         */
        public RecurringMeeting bookRecurringMeeting(String title, User organizer, List<User> participants, MeetingRoom room, Interval baseInterval, RecurrenceType recurrenceType, int occurrencesCount) throws BookingConflictException {
            validateBookingRequest(title, organizer, participants, room);
            List<Interval> intervals = generateRecurringIntervals(baseInterval, recurrenceType, occurrencesCount);
            List<Lockable> lockedResources = lockResources(room, organizer, participants);
            try {
                // Verify no conflicts exist across all scheduled slots
                for (Interval interval : intervals) {
                    if (!room.isAvailable(interval)) {
                        throw new BookingConflictException("Room '" + room.getName() + "' has a conflict at " + interval);
                    }
                    if (!organizer.isAvailable(interval)) {
                        throw new BookingConflictException("Organizer '" + organizer.getName() + "' has a conflict at " + interval);
                    }
                    for (User participant : participants) {
                        if (!participant.isAvailable(interval)) {
                            throw new BookingConflictException("Participant '" + participant.getName() + "' has a conflict at " + interval);
                        }
                    }
                }

                // If all slots are free, book all occurrences
                String recurringMeetingId = UUID.randomUUID().toString();
                List<Meeting> occurrences = new ArrayList<>();
                for (int i = 0; i < intervals.size(); i++) {
                    Interval interval = intervals.get(i);
                    String label = String.format("%s (Occurrence %d/%d)", title, i + 1, occurrencesCount);
                    Meeting meeting = new Meeting(UUID.randomUUID().toString(), label, organizer, participants, room, interval, recurringMeetingId);

                    room.addMeeting(meeting);
                    organizer.addMeeting(meeting);
                    for (User participant : participants) {
                        participant.addMeeting(meeting);
                    }
                    occurrences.add(meeting);
                }
                return new RecurringMeeting(recurringMeetingId, title, organizer, participants, room, occurrences);
            } finally {
                unlockResources(lockedResources);
            }
        }

        /**
         * Book a recurring meeting using a selection strategy.
         */
        public RecurringMeeting bookRecurringMeetingWithStrategy(String title, User organizer, List<User> participants, Interval baseInterval, RecurrenceType recurrenceType, int occurrencesCount, RoomBookingStrategy strategy, BookingContext context) throws BookingConflictException {
            List<Interval> intervals = generateRecurringIntervals(baseInterval, recurrenceType, occurrencesCount);
            List<MeetingRoom> allRooms = getAllRooms();

            // Find rooms available for ALL recurrence slots
            List<MeetingRoom> availableSnapshotRooms = new ArrayList<>();
            for (MeetingRoom r : allRooms) {
                r.getReadWriteLock().readLock().lock();
                try {
                    boolean availableForAll = true;
                    for (Interval interval : intervals) {
                        if (!r.isAvailable(interval)) {
                            availableForAll = false;
                            break;
                        }
                    }
                    if (availableForAll) {
                        availableSnapshotRooms.add(r);
                    }
                } finally {
                    r.getReadWriteLock().readLock().unlock();
                }
            }

            List<MeetingRoom> candidates = new ArrayList<>(availableSnapshotRooms);
            while (!candidates.isEmpty()) {
                Optional<MeetingRoom> selectedRoomOpt = strategy.selectRoom(candidates, context);
                if (!selectedRoomOpt.isPresent()) {
                    break;
                }
                MeetingRoom targetRoom = selectedRoomOpt.get();
                try {
                    return bookRecurringMeeting(title, organizer, participants, targetRoom, baseInterval, recurrenceType, occurrencesCount);
                } catch (BookingConflictException e) {
                    // If the conflict is about the room, retry with another room. Otherwise, it's a user conflict.
                    if (e.getMessage() != null && e.getMessage().contains("Room '" + targetRoom.getName() + "'")) {
                        candidates.remove(targetRoom);
                    } else {
                        throw e;
                    }
                }
            }
            throw new BookingConflictException("No suitable rooms available or all candidates were booked concurrently for the recurring slots.");
        }

        private void validateBookingRequest(String title, User organizer, List<User> participants, MeetingRoom room) throws BookingConflictException {
            if (title == null || title.trim().isEmpty()) {
                throw new IllegalArgumentException("Meeting title cannot be blank.");
            }
            Objects.requireNonNull(organizer, "Organizer cannot be null.");
            Objects.requireNonNull(participants, "Participants cannot be null.");
            Objects.requireNonNull(room, "Meeting room cannot be null.");

            Set<String> attendeeIds = new HashSet<>();
            attendeeIds.add(organizer.getId());
            for (User participant : participants) {
                Objects.requireNonNull(participant, "Participant cannot be null.");
                if (!attendeeIds.add(participant.getId())) {
                    throw new IllegalArgumentException("Organizer and participants must be unique.");
                }
            }
            if (attendeeIds.size() > room.getCapacity()) {
                throw new BookingConflictException("Room '" + room.getName() + "' does not have capacity for " + attendeeIds.size() + " attendees.");
            }
        }
    }

    // =========================================================================
    // Demonstration & Main Workflow
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== INITIALIZING MEETING SCHEDULER SYSTEM ===");
        MeetingScheduler scheduler = new MeetingScheduler();

        // 1. Create and Register Rooms
        MeetingRoom boardroom = new MeetingRoom("R1", "Boardroom", 10, 5);      // Floor 5, capacity 10
        MeetingRoom phonebooth = new MeetingRoom("R2", "Phonebooth", 2, 1);      // Floor 1, capacity 2
        MeetingRoom confroom = new MeetingRoom("R3", "Conference Room", 5, 3);   // Floor 3, capacity 5
        scheduler.registerRoom(boardroom);
        scheduler.registerRoom(phonebooth);
        scheduler.registerRoom(confroom);

        // 2. Create and Register Users
        User alice = new User("U1", "Alice", "alice@example.com");
        User bob = new User("U2", "Bob", "bob@example.com");
        User charlie = new User("U3", "Charlie", "charlie@example.com");
        User dave = new User("U4", "Dave", "dave@example.com");
        scheduler.registerUser(alice);
        scheduler.registerUser(bob);
        scheduler.registerUser(charlie);
        scheduler.registerUser(dave);

        // Define base times for bookings
        LocalDateTime baseTime = LocalDateTime.of(2026, 7, 15, 10, 0); // July 15, 2026, 10:00 AM

        // =========================================================================
        // DEMO 1: CONCURRENT RACE CONDITION SIMULATION (SAME ROOM, OVERLAPPING SLOT)
        // =========================================================================
        System.out.println("\n--- DEMO 1: Concurrent Room Booking Race (Same Room, Same Time) ---");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Interval slot10to11 = new Interval(baseTime, baseTime.plusHours(1));

        // Alice tries to book Phonebooth
        Callable<Meeting> aliceTask = () -> {
            barrier.await(); // Synchronize threads to start at the exact same moment
            return scheduler.bookMeeting("Alice's Quick Synch", alice, Collections.singletonList(bob), phonebooth, slot10to11);
        };

        // Charlie tries to book Phonebooth
        Callable<Meeting> charlieTask = () -> {
            barrier.await(); // Synchronize threads to start at the exact same moment
            return scheduler.bookMeeting("Charlie's 1-on-1", charlie, Collections.singletonList(dave), phonebooth, slot10to11);
        };

        Future<Meeting> aliceFuture = executor.submit(aliceTask);
        Future<Meeting> charlieFuture = executor.submit(charlieTask);

        try {
            Meeting meetingA = aliceFuture.get();
            System.out.println("Thread A: " + meetingA + " successfully booked!");
        } catch (ExecutionException e) {
            System.out.println("Thread A Failed: " + e.getCause().getMessage());
        }

        try {
            Meeting meetingB = charlieFuture.get();
            System.out.println("Thread B: " + meetingB + " successfully booked!");
        } catch (ExecutionException e) {
            System.out.println("Thread B Failed: " + e.getCause().getMessage());
        }

        // =========================================================================
        // DEMO 2: CONCURRENT RACE CONDITION SIMULATION (SAME USER, DIFFERENT ROOMS)
        // =========================================================================
        System.out.println("\n--- DEMO 2: Concurrent User Booking Race (Alice books Bob in R1, Charlie books Bob in R3) ---");
        CyclicBarrier barrier2 = new CyclicBarrier(2);

        Interval slot11to12 = new Interval(baseTime.plusHours(1), baseTime.plusHours(2));

        // Alice books Bob in Boardroom
        Callable<Meeting> bookBobR1 = () -> {
            barrier2.await();
            return scheduler.bookMeeting("Alice-Bob Sync", alice, Collections.singletonList(bob), boardroom, slot11to12);
        };

        // Charlie books Bob in Conference Room
        Callable<Meeting> bookBobR3 = () -> {
            barrier2.await();
            return scheduler.bookMeeting("Charlie-Bob Review", charlie, Collections.singletonList(bob), confroom, slot11to12);
        };

        Future<Meeting> futureBob1 = executor.submit(bookBobR1);
        Future<Meeting> futureBob2 = executor.submit(bookBobR3);

        try {
            Meeting m1 = futureBob1.get();
            System.out.println("Task 1: " + m1 + " successfully booked!");
        } catch (ExecutionException e) {
            System.out.println("Task 1 Failed: " + e.getCause().getMessage());
        }

        try {
            Meeting m2 = futureBob2.get();
            System.out.println("Task 2: " + m2 + " successfully booked!");
        } catch (ExecutionException e) {
            System.out.println("Task 2 Failed: " + e.getCause().getMessage());
        }

        // =========================================================================
        // DEMO 3: STRATEGY PATTERN IN ACTION
        // =========================================================================
        System.out.println("\n--- DEMO 3: Room Selection Strategies ---");
        Interval slot12to13 = new Interval(baseTime.plusHours(2), baseTime.plusHours(3));

        // Strategy A: Smallest Fitting Room for 4 people (Conference Room capacity 5 vs Boardroom capacity 10)
        BookingContext contextForFour = new BookingContext(4, 1);
        System.out.println("Booking using SmallestFittingRoomStrategy for capacity 4...");
        try {
            Meeting strategyMeeting1 = scheduler.bookMeetingWithStrategy(
                    "Team Sync (Smallest)",
                    alice,
                    Arrays.asList(bob, charlie),
                    slot12to13,
                    new SmallestFittingRoomStrategy(),
                    contextForFour
            );
            System.out.println("Success! Booked: " + strategyMeeting1);
        } catch (BookingConflictException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // Strategy B: Nearest Room Strategy (Requester on Floor 4, capacity 3. Nearest is Floor 3 - Conf Room or Floor 5 - Boardroom. Let's see)
        // Wait, slot 13 to 14.
        Interval slot13to14 = new Interval(baseTime.plusHours(3), baseTime.plusHours(4));
        BookingContext contextFloor4 = new BookingContext(3, 4);
        System.out.println("\nBooking using NearestRoomStrategy for requester on Floor 4, capacity 3...");
        try {
            Meeting strategyMeeting2 = scheduler.bookMeetingWithStrategy(
                    "Quick Meet (Nearest)",
                    dave,
                    Arrays.asList(alice, bob),
                    slot13to14,
                    new NearestRoomStrategy(),
                    contextFloor4
            );
            System.out.println("Success! Booked: " + strategyMeeting2);
        } catch (BookingConflictException e) {
            System.out.println("Failed: " + e.getMessage());
        }

        // =========================================================================
        // DEMO 4: RECURRING MEETING BOOKING
        // =========================================================================
        System.out.println("\n--- DEMO 4: Recurring Meeting Scheduling (Atomic) ---");
        Interval baseRecurringSlot = new Interval(baseTime.plusHours(5), baseTime.plusHours(6)); // 15:00 - 16:00

        System.out.println("Booking Daily Recurring Meeting for 3 days starting at " + baseRecurringSlot);
        try {
            RecurringMeeting recurringMeeting = scheduler.bookRecurringMeeting(
                    "Daily Project Standup",
                    alice,
                    Arrays.asList(bob, charlie),
                    boardroom,
                    baseRecurringSlot,
                    RecurrenceType.DAILY,
                    3
            );
            System.out.println("Success! Booked series with ID: " + recurringMeeting.getRecurringMeetingId());
            for (Meeting occurrence : recurringMeeting.getOccurrences()) {
                System.out.println(" -> " + occurrence);
            }
        } catch (BookingConflictException e) {
            System.out.println("Failed to book recurring series: " + e.getMessage());
        }

        // Check scheduler status for Alice and Room Boardroom
        System.out.println("\n--- Final Schedules Verification ---");
        System.out.println("Alice's Final Schedule:");
        for (Meeting m : alice.getSchedule()) {
            System.out.println(" - " + m.getTitle() + " at " + m.getInterval() + " in " + m.getRoom().getName());
        }

        System.out.println("\nBoardroom's Final Schedule:");
        for (Meeting m : boardroom.getSchedule()) {
            System.out.println(" - " + m.getTitle() + " at " + m.getInterval() + " with Organizer: " + m.getOrganizer());
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
