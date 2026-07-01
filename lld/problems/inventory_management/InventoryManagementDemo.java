package lld.problems.inventory_management;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

// ============================================================================
// 1. Enums
// ============================================================================
enum Category { ELECTRONICS, CLOTHING, GROCERY, FURNITURE }

enum AuditType { ADD, REMOVE, RESERVE, FULFILL, RELEASE, TRANSFER }

enum ReservationStatus { ACTIVE, FULFILLED, EXPIRED }

// ============================================================================
// 2. Product (Builder Pattern)
// ============================================================================
class Product {
    private final String sku;
    private final String name;
    private final double price;
    private final Category category;

    private Product(String sku, String name, double price, Category category) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.category = category;
    }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public Category getCategory() { return category; }

    static Builder builder() { return new Builder(); }

    @Override
    public String toString() {
        return String.format("Product[%s] %s $%.2f (%s)", sku, name, price, category);
    }

    static class Builder {
        private String sku, name;
        private double price;
        private Category category;

        Builder sku(String s) { this.sku = s; return this; }
        Builder name(String n) { this.name = n; return this; }
        Builder price(double p) { this.price = p; return this; }
        Builder category(Category c) { this.category = c; return this; }

        Product build() {
            Objects.requireNonNull(sku, "SKU is required");
            Objects.requireNonNull(name, "Name is required");
            if (price < 0) throw new IllegalArgumentException("Price cannot be negative");
            if (category == null) category = Category.ELECTRONICS;
            return new Product(sku, name, price, category);
        }
    }
}

// ============================================================================
// 3. StockEntry (Optimistic Locking with version)
// ============================================================================
class StockEntry {
    private final String sku;
    private int available;
    private int reserved;
    private int version;

    StockEntry(String sku, int available) {
        this.sku = sku;
        this.available = available;
        this.reserved = 0;
        this.version = 0;
    }

    public String getSku() { return sku; }
    public synchronized int getAvailable() { return available; }
    public synchronized int getReserved() { return reserved; }
    public synchronized int getVersion() { return version; }

    /** Add stock — always succeeds */
    synchronized int addStock(int qty) {
        int old = this.available;
        this.available += qty;
        this.version++;
        return old;
    }

    /** Remove stock — fails if insufficient */
    synchronized boolean removeStock(int qty) {
        if (this.available < qty) return false;
        this.available -= qty;
        this.version++;
        return true;
    }

    /**
     * Optimistic locking: reserve qty only if version matches.
     * Simulates CAS — in a real DB this would be:
     * UPDATE stock SET available=available-?, reserved=reserved+?, version=version+1
     * WHERE sku=? AND version=? AND available>=?
     */
    synchronized boolean reserveWithCAS(int qty, int expectedVersion) {
        if (this.version != expectedVersion) return false; // version mismatch — retry
        if (this.available < qty) return false;            // not enough stock
        this.available -= qty;
        this.reserved += qty;
        this.version++;
        return true;
    }

    /** Release reserved stock back to available (reservation expired/cancelled) */
    synchronized void releaseReserved(int qty) {
        this.reserved -= qty;
        this.available += qty;
        this.version++;
    }

    /** Fulfill reservation — just remove from reserved count (stock already deducted from available) */
    synchronized void fulfillReserved(int qty) {
        this.reserved -= qty;
        this.version++;
    }

    @Override
    public String toString() {
        return String.format("StockEntry[%s] available=%d reserved=%d v%d",
                sku, available, reserved, version);
    }
}

// ============================================================================
// 4. Warehouse
// ============================================================================
class Warehouse {
    private final String id;
    private final String name;
    private final String location;
    private final double shippingCostBase; // for CheapestWarehouseStrategy
    private final Map<String, StockEntry> stockBySku = new ConcurrentHashMap<>();

    Warehouse(String id, String name, String location, double shippingCostBase) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.shippingCostBase = shippingCostBase;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLocation() { return location; }
    public double getShippingCostBase() { return shippingCostBase; }

    /** Add stock for a SKU — creates StockEntry if first time */
    public int addStock(String sku, int qty) {
        StockEntry entry = stockBySku.computeIfAbsent(sku, k -> new StockEntry(k, 0));
        return entry.addStock(qty);
    }

    /** Get available quantity for a SKU */
    public int getAvailable(String sku) {
        StockEntry entry = stockBySku.get(sku);
        return entry == null ? 0 : entry.getAvailable();
    }

    public StockEntry getStockEntry(String sku) {
        return stockBySku.get(sku);
    }

    public Map<String, StockEntry> getAllStock() {
        return Collections.unmodifiableMap(stockBySku);
    }

    @Override
    public String toString() {
        return String.format("Warehouse[%s] %s (%s)", id, name, location);
    }
}

// ============================================================================
// 5. Reservation
// ============================================================================
class Reservation {
    private final String id;
    private final String sku;
    private final String warehouseId;
    private final int quantity;
    private final Instant createdAt;
    private final Instant expiresAt;
    private volatile ReservationStatus status;

    Reservation(String sku, String warehouseId, int quantity, int durationMinutes) {
        this.id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plus(durationMinutes, ChronoUnit.MINUTES);
        this.status = ReservationStatus.ACTIVE;
    }

    // Constructor for testing with custom expiry
    Reservation(String sku, String warehouseId, int quantity, Instant expiresAt) {
        this.id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.status = ReservationStatus.ACTIVE;
    }

    public String getId() { return id; }
    public String getSku() { return sku; }
    public String getWarehouseId() { return warehouseId; }
    public int getQuantity() { return quantity; }
    public Instant getExpiresAt() { return expiresAt; }
    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    @Override
    public String toString() {
        return String.format("Reservation[%s] sku=%s warehouse=%s qty=%d status=%s expires=%s",
                id, sku, warehouseId, quantity, status, expiresAt);
    }
}

// ============================================================================
// 6. Audit Log (Append-Only)
// ============================================================================
class AuditEntry {
    private final AuditType type;
    private final String sku;
    private final String warehouseId;
    private final int oldQuantity;
    private final int newQuantity;
    private final String reason;
    private final Instant timestamp;

    AuditEntry(AuditType type, String sku, String warehouseId,
               int oldQuantity, int newQuantity, String reason) {
        this.type = type;
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.oldQuantity = oldQuantity;
        this.newQuantity = newQuantity;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("[AUDIT] %s | %s | sku=%s | warehouse=%s | %d -> %d | %s",
                timestamp, type, sku, warehouseId, oldQuantity, newQuantity, reason);
    }
}

class AuditLog {
    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    void append(AuditType type, String sku, String warehouseId,
                int oldQty, int newQty, String reason) {
        AuditEntry entry = new AuditEntry(type, sku, warehouseId, oldQty, newQty, reason);
        entries.add(entry);
    }

    List<AuditEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    void printAll() {
        System.out.println("\n--- Audit Trail ---");
        for (AuditEntry entry : entries) {
            System.out.println(entry);
        }
        System.out.println("--- End Audit Trail ---");
    }
}

// ============================================================================
// 7. Observer Pattern — Stock Change Notifications
// ============================================================================
interface StockObserver {
    void onStockChange(String sku, String warehouseId, int oldQty, int newQty);
}

class LowStockAlertObserver implements StockObserver {
    private final int threshold;

    LowStockAlertObserver(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public void onStockChange(String sku, String warehouseId, int oldQty, int newQty) {
        // Alert only when stock crosses below threshold (transition, not every update)
        if (newQty < threshold && oldQty >= threshold) {
            System.out.printf("  [LOW STOCK ALERT] %s at warehouse %s dropped to %d (threshold: %d)%n",
                    sku, warehouseId, newQty, threshold);
        }
    }
}

class StockChangeLogger implements StockObserver {
    @Override
    public void onStockChange(String sku, String warehouseId, int oldQty, int newQty) {
        System.out.printf("  [STOCK CHANGE] %s at %s: %d -> %d%n", sku, warehouseId, oldQty, newQty);
    }
}

// ============================================================================
// 8. Strategy Pattern — Warehouse Allocation
// ============================================================================
interface AllocationStrategy {
    /** Select the best warehouse to fulfill qty units of the given SKU */
    Warehouse selectWarehouse(List<Warehouse> warehouses, String sku, int qty);
}

/** Pick the first warehouse in the list that has enough stock */
class FIFOAllocationStrategy implements AllocationStrategy {
    @Override
    public Warehouse selectWarehouse(List<Warehouse> warehouses, String sku, int qty) {
        for (Warehouse w : warehouses) {
            if (w.getAvailable(sku) >= qty) return w;
        }
        return null;
    }

    @Override
    public String toString() { return "FIFOAllocationStrategy"; }
}

/** Pick the warehouse with the lowest shipping cost that has enough stock */
class CheapestWarehouseStrategy implements AllocationStrategy {
    @Override
    public Warehouse selectWarehouse(List<Warehouse> warehouses, String sku, int qty) {
        return warehouses.stream()
                .filter(w -> w.getAvailable(sku) >= qty)
                .min(Comparator.comparingDouble(Warehouse::getShippingCostBase))
                .orElse(null);
    }

    @Override
    public String toString() { return "CheapestWarehouseStrategy"; }
}

/** Pick the warehouse with the most stock (balances inventory across warehouses) */
class MostStockStrategy implements AllocationStrategy {
    @Override
    public Warehouse selectWarehouse(List<Warehouse> warehouses, String sku, int qty) {
        return warehouses.stream()
                .filter(w -> w.getAvailable(sku) >= qty)
                .max(Comparator.comparingInt(w -> w.getAvailable(sku)))
                .orElse(null);
    }

    @Override
    public String toString() { return "MostStockStrategy"; }
}

// ============================================================================
// 9. Repository Pattern — Product & Warehouse repositories
// ============================================================================
class ProductRepository {
    private final Map<String, Product> products = new ConcurrentHashMap<>();

    void save(Product product) { products.put(product.getSku(), product); }
    Product findBySku(String sku) { return products.get(sku); }
    List<Product> findAll() { return new ArrayList<>(products.values()); }

    List<Product> findByCategory(Category category) {
        return products.values().stream()
                .filter(p -> p.getCategory() == category)
                .collect(Collectors.toList());
    }

    List<Product> findByPriceRange(double minPrice, double maxPrice) {
        return products.values().stream()
                .filter(p -> p.getPrice() >= minPrice && p.getPrice() <= maxPrice)
                .collect(Collectors.toList());
    }
}

class WarehouseRepository {
    private final Map<String, Warehouse> warehouses = new ConcurrentHashMap<>();

    void save(Warehouse warehouse) { warehouses.put(warehouse.getId(), warehouse); }
    Warehouse findById(String id) { return warehouses.get(id); }
    List<Warehouse> findAll() { return new ArrayList<>(warehouses.values()); }

    /** Find warehouses that have any stock of this SKU */
    List<Warehouse> findWithStock(String sku) {
        return warehouses.values().stream()
                .filter(w -> w.getAvailable(sku) > 0)
                .collect(Collectors.toList());
    }
}

// ============================================================================
// 10. InventoryService — Main Facade
// ============================================================================
class InventoryService {
    private final ProductRepository productRepo;
    private final WarehouseRepository warehouseRepo;
    private final AuditLog auditLog;
    private final List<StockObserver> observers = new CopyOnWriteArrayList<>();
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private AllocationStrategy allocationStrategy;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    InventoryService(ProductRepository productRepo, WarehouseRepository warehouseRepo,
                     AuditLog auditLog, AllocationStrategy strategy) {
        this.productRepo = productRepo;
        this.warehouseRepo = warehouseRepo;
        this.auditLog = auditLog;
        this.allocationStrategy = strategy;
    }

    // ----- Observer management -----
    void addObserver(StockObserver observer) { observers.add(observer); }
    void removeObserver(StockObserver observer) { observers.remove(observer); }

    private void notifyObservers(String sku, String warehouseId, int oldQty, int newQty) {
        for (StockObserver obs : observers) {
            obs.onStockChange(sku, warehouseId, oldQty, newQty);
        }
    }

    // ----- Strategy management -----
    void setAllocationStrategy(AllocationStrategy strategy) {
        this.allocationStrategy = strategy;
    }

    // ----- Product operations -----
    void addProduct(Product product) {
        productRepo.save(product);
        System.out.println("[PRODUCT ADDED] " + product);
    }

    // ----- Stock operations -----
    void addStock(String warehouseId, String sku, int qty, String reason) {
        Warehouse warehouse = warehouseRepo.findById(warehouseId);
        if (warehouse == null) throw new RuntimeException("Warehouse not found: " + warehouseId);

        int oldQty = warehouse.getAvailable(sku);
        warehouse.addStock(sku, qty);
        int newQty = warehouse.getAvailable(sku);

        auditLog.append(AuditType.ADD, sku, warehouseId, oldQty, newQty, reason);
        notifyObservers(sku, warehouseId, oldQty, newQty);
        System.out.printf("[STOCK ADDED] %s: +%d at %s (now: %d)%n", sku, qty, warehouseId, newQty);
    }

    void removeStock(String warehouseId, String sku, int qty, String reason) {
        Warehouse warehouse = warehouseRepo.findById(warehouseId);
        if (warehouse == null) throw new RuntimeException("Warehouse not found: " + warehouseId);

        StockEntry entry = warehouse.getStockEntry(sku);
        if (entry == null) throw new RuntimeException("No stock entry for " + sku + " in " + warehouseId);

        int oldQty = entry.getAvailable();
        if (!entry.removeStock(qty)) {
            throw new RuntimeException("Insufficient stock for " + sku + " in " + warehouseId
                    + " (available: " + oldQty + ", requested: " + qty + ")");
        }
        int newQty = entry.getAvailable();

        auditLog.append(AuditType.REMOVE, sku, warehouseId, oldQty, newQty, reason);
        notifyObservers(sku, warehouseId, oldQty, newQty);
        System.out.printf("[STOCK REMOVED] %s: -%d at %s (now: %d)%n", sku, qty, warehouseId, newQty);
    }

    void transferStock(String fromWarehouseId, String toWarehouseId, String sku, int qty) {
        Warehouse from = warehouseRepo.findById(fromWarehouseId);
        Warehouse to = warehouseRepo.findById(toWarehouseId);
        if (from == null || to == null) throw new RuntimeException("Warehouse not found");

        StockEntry fromEntry = from.getStockEntry(sku);
        if (fromEntry == null || fromEntry.getAvailable() < qty) {
            throw new RuntimeException("Insufficient stock at source warehouse");
        }

        int oldFromQty = fromEntry.getAvailable();
        fromEntry.removeStock(qty);
        int newFromQty = fromEntry.getAvailable();

        int oldToQty = to.getAvailable(sku);
        to.addStock(sku, qty);
        int newToQty = to.getAvailable(sku);

        String reason = "Transfer from " + fromWarehouseId + " to " + toWarehouseId;
        auditLog.append(AuditType.TRANSFER, sku, fromWarehouseId, oldFromQty, newFromQty, reason);
        auditLog.append(AuditType.TRANSFER, sku, toWarehouseId, oldToQty, newToQty, reason);

        notifyObservers(sku, fromWarehouseId, oldFromQty, newFromQty);
        notifyObservers(sku, toWarehouseId, oldToQty, newToQty);

        System.out.printf("[TRANSFER] %s: %d units from %s -> %s%n", sku, qty, fromWarehouseId, toWarehouseId);
    }

    // ----- Reservation operations (with optimistic locking) -----

    Reservation reserveStock(String sku, int qty, int durationMinutes) {
        List<Warehouse> candidates = warehouseRepo.findAll();
        Warehouse selected = allocationStrategy.selectWarehouse(candidates, sku, qty);

        if (selected == null) {
            throw new RuntimeException("No warehouse has sufficient stock for " + sku + " (qty: " + qty + ")");
        }

        StockEntry entry = selected.getStockEntry(sku);
        int oldQty = entry.getAvailable();

        // Optimistic locking — retry up to 3 times on version mismatch
        boolean reserved = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            int currentVersion = entry.getVersion();
            if (entry.reserveWithCAS(qty, currentVersion)) {
                reserved = true;
                break;
            }
            // Version changed — another thread modified stock. Retry.
            System.out.println("  [CAS RETRY] Version mismatch on attempt " + (attempt + 1));
        }

        if (!reserved) {
            throw new RuntimeException("Failed to reserve stock after retries (concurrent modification)");
        }

        int newQty = entry.getAvailable();
        Reservation reservation = new Reservation(sku, selected.getId(), qty, durationMinutes);
        reservations.put(reservation.getId(), reservation);

        auditLog.append(AuditType.RESERVE, sku, selected.getId(), oldQty, newQty, "checkout reservation");
        notifyObservers(sku, selected.getId(), oldQty, newQty);

        // Schedule auto-expiry
        scheduler.schedule(() -> {
            if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                releaseReservation(reservation.getId());
            }
        }, durationMinutes, TimeUnit.MINUTES);

        System.out.printf("[RESERVED] %s: %d units at %s for %d min -> %s%n",
                sku, qty, selected.getId(), durationMinutes, reservation.getId());
        return reservation;
    }

    // Reserve with a specific expiry instant (for testing short expiry)
    Reservation reserveStockWithExpiry(String sku, int qty, Instant expiresAt) {
        List<Warehouse> candidates = warehouseRepo.findAll();
        Warehouse selected = allocationStrategy.selectWarehouse(candidates, sku, qty);

        if (selected == null) {
            throw new RuntimeException("No warehouse has sufficient stock for " + sku);
        }

        StockEntry entry = selected.getStockEntry(sku);
        int oldQty = entry.getAvailable();
        int currentVersion = entry.getVersion();

        if (!entry.reserveWithCAS(qty, currentVersion)) {
            throw new RuntimeException("Failed to reserve stock (concurrent modification)");
        }

        int newQty = entry.getAvailable();
        Reservation reservation = new Reservation(sku, selected.getId(), qty, expiresAt);
        reservations.put(reservation.getId(), reservation);

        auditLog.append(AuditType.RESERVE, sku, selected.getId(), oldQty, newQty, "checkout reservation");
        notifyObservers(sku, selected.getId(), oldQty, newQty);

        // Schedule auto-expiry
        long delayMs = Math.max(0, expiresAt.toEpochMilli() - Instant.now().toEpochMilli());
        scheduler.schedule(() -> {
            if (reservation.getStatus() == ReservationStatus.ACTIVE) {
                releaseReservation(reservation.getId());
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        System.out.printf("[RESERVED] %s: %d units at %s -> %s (expires: %s)%n",
                sku, qty, selected.getId(), reservation.getId(), expiresAt);
        return reservation;
    }

    void fulfillReservation(String reservationId) {
        Reservation r = reservations.get(reservationId);
        if (r == null) throw new RuntimeException("Reservation not found: " + reservationId);
        if (r.getStatus() != ReservationStatus.ACTIVE) {
            throw new RuntimeException("Reservation " + reservationId + " is " + r.getStatus());
        }
        if (r.isExpired()) {
            releaseReservation(reservationId);
            throw new RuntimeException("Reservation " + reservationId + " has expired");
        }

        Warehouse warehouse = warehouseRepo.findById(r.getWarehouseId());
        StockEntry entry = warehouse.getStockEntry(r.getSku());
        entry.fulfillReserved(r.getQuantity());
        r.setStatus(ReservationStatus.FULFILLED);

        auditLog.append(AuditType.FULFILL, r.getSku(), r.getWarehouseId(),
                r.getQuantity(), 0, "order confirmed #" + reservationId);

        System.out.printf("[FULFILLED] Reservation %s — %d units of %s sold from %s%n",
                reservationId, r.getQuantity(), r.getSku(), r.getWarehouseId());
    }

    void releaseReservation(String reservationId) {
        Reservation r = reservations.get(reservationId);
        if (r == null || r.getStatus() != ReservationStatus.ACTIVE) return; // idempotent

        Warehouse warehouse = warehouseRepo.findById(r.getWarehouseId());
        StockEntry entry = warehouse.getStockEntry(r.getSku());
        int oldQty = entry.getAvailable();
        entry.releaseReserved(r.getQuantity());
        int newQty = entry.getAvailable();
        r.setStatus(ReservationStatus.EXPIRED);

        auditLog.append(AuditType.RELEASE, r.getSku(), r.getWarehouseId(),
                oldQty, newQty, "reservation expired #" + reservationId);
        notifyObservers(r.getSku(), r.getWarehouseId(), oldQty, newQty);

        System.out.printf("[RELEASED] Reservation %s — %d units of %s returned to %s (now: %d)%n",
                reservationId, r.getQuantity(), r.getSku(), r.getWarehouseId(), newQty);
    }

    // ----- Search operations -----
    List<Product> searchByCategory(Category category) {
        return productRepo.findByCategory(category);
    }

    List<Product> searchByPriceRange(double minPrice, double maxPrice) {
        return productRepo.findByPriceRange(minPrice, maxPrice);
    }

    /** Find products available (in stock) at any warehouse */
    List<Product> searchAvailableProducts() {
        return productRepo.findAll().stream()
                .filter(p -> warehouseRepo.findWithStock(p.getSku()).size() > 0)
                .collect(Collectors.toList());
    }

    // ----- Diagnostics -----
    void printWarehouseStatus() {
        System.out.println("\n--- Warehouse Inventory Status ---");
        for (Warehouse w : warehouseRepo.findAll()) {
            System.out.println(w);
            for (Map.Entry<String, StockEntry> e : w.getAllStock().entrySet()) {
                System.out.println("    " + e.getValue());
            }
        }
        System.out.println("----------------------------------");
    }

    AuditLog getAuditLog() { return auditLog; }
    void shutdown() { scheduler.shutdownNow(); }
}

// ============================================================================
// 11. Test Driver
// ============================================================================
public class InventoryManagementDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Inventory Management System — LLD Simulation ===\n");

        // -----------------------------------------------
        // Setup: repositories, audit log, service
        // -----------------------------------------------
        ProductRepository productRepo = new ProductRepository();
        WarehouseRepository warehouseRepo = new WarehouseRepository();
        AuditLog auditLog = new AuditLog();

        Warehouse mumbai = new Warehouse("WH-MUM", "Mumbai Warehouse", "Mumbai", 50.0);
        Warehouse delhi = new Warehouse("WH-DEL", "Delhi Warehouse", "Delhi", 80.0);
        Warehouse bangalore = new Warehouse("WH-BLR", "Bangalore Warehouse", "Bangalore", 30.0);
        warehouseRepo.save(mumbai);
        warehouseRepo.save(delhi);
        warehouseRepo.save(bangalore);

        InventoryService service = new InventoryService(
                productRepo, warehouseRepo, auditLog, new FIFOAllocationStrategy());

        // Register observers
        service.addObserver(new LowStockAlertObserver(10));
        service.addObserver(new StockChangeLogger());

        // ===================================================
        // TEST 1: Add products using Builder pattern
        // ===================================================
        System.out.println("===== TEST 1: Add Products (Builder Pattern) =====");
        Product laptop = Product.builder()
                .sku("LAPTOP-001").name("MacBook Pro 16\"").price(2499.99)
                .category(Category.ELECTRONICS).build();
        Product phone = Product.builder()
                .sku("PHONE-001").name("iPhone 15 Pro").price(999.99)
                .category(Category.ELECTRONICS).build();
        Product tshirt = Product.builder()
                .sku("SHIRT-001").name("Cotton T-Shirt").price(29.99)
                .category(Category.CLOTHING).build();

        service.addProduct(laptop);
        service.addProduct(phone);
        service.addProduct(tshirt);

        // Validate builder — missing SKU should throw
        try {
            Product.builder().name("Invalid").price(10).build();
        } catch (NullPointerException e) {
            System.out.println("[BUILDER VALIDATION] " + e.getMessage());
        }

        // ===================================================
        // TEST 2: Add stock to warehouses
        // ===================================================
        System.out.println("\n===== TEST 2: Add Stock to Warehouses =====");
        service.addStock("WH-MUM", "LAPTOP-001", 50, "initial shipment");
        service.addStock("WH-DEL", "LAPTOP-001", 30, "initial shipment");
        service.addStock("WH-BLR", "LAPTOP-001", 20, "initial shipment");
        service.addStock("WH-MUM", "PHONE-001", 100, "initial shipment");
        service.addStock("WH-BLR", "SHIRT-001", 500, "initial shipment");

        service.printWarehouseStatus();

        // ===================================================
        // TEST 3: Reserve stock for checkout (with auto-expiry)
        // ===================================================
        System.out.println("\n===== TEST 3: Reserve Stock for Checkout =====");
        Reservation res1 = service.reserveStock("LAPTOP-001", 5, 15);
        System.out.println("Reservation created: " + res1);

        Reservation res2 = service.reserveStock("PHONE-001", 3, 10);
        System.out.println("Reservation created: " + res2);

        service.printWarehouseStatus();

        // ===================================================
        // TEST 4: Fulfill an order (convert reservation to sale)
        // ===================================================
        System.out.println("\n===== TEST 4: Fulfill Order =====");
        service.fulfillReservation(res1.getId());
        // res1 is now FULFILLED — stock is permanently deducted

        service.printWarehouseStatus();

        // ===================================================
        // TEST 5: Reservation expiry (short TTL for demo)
        // ===================================================
        System.out.println("\n===== TEST 5: Reservation Expiry =====");
        // Create a reservation that expires in 2 seconds
        Reservation shortRes = service.reserveStockWithExpiry(
                "LAPTOP-001", 2, Instant.now().plusSeconds(2));
        System.out.println("Short-lived reservation: " + shortRes);
        System.out.println("Waiting 3 seconds for expiry...");
        Thread.sleep(3000);
        // The scheduler should have released it by now
        System.out.println("Reservation status after expiry: " + shortRes.getStatus());

        service.printWarehouseStatus();

        // ===================================================
        // TEST 6: Low-stock alert (Observer Pattern)
        // ===================================================
        System.out.println("\n===== TEST 6: Low Stock Alert =====");
        // BLR has 20 laptops. Remove 12 to trigger alert (threshold = 10)
        service.removeStock("WH-BLR", "LAPTOP-001", 12, "damaged goods write-off");
        // Available should now be 8, which is below threshold 10

        // ===================================================
        // TEST 7: Transfer stock between warehouses
        // ===================================================
        System.out.println("\n===== TEST 7: Transfer Stock =====");
        service.transferStock("WH-MUM", "WH-BLR", "LAPTOP-001", 15);
        service.printWarehouseStatus();

        // ===================================================
        // TEST 8: Concurrent stock updates (thread-safety)
        // ===================================================
        System.out.println("\n===== TEST 8: Concurrent Stock Updates =====");
        // Add 100 phones to Mumbai for the concurrency test
        service.addStock("WH-MUM", "PHONE-001", 100, "concurrency test stock");
        int initialStock = mumbai.getAvailable("PHONE-001");
        System.out.println("Initial PHONE-001 stock at Mumbai: " + initialStock);

        // 20 threads each try to reserve 5 phones = 100 total needed
        // We have 200 phones at Mumbai (100 original + 100 added)
        // minus the 3 reserved in TEST 3 = 197 available
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(20);
        List<String> successes = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 20; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    Reservation r = service.reserveStock("PHONE-001", 5, 30);
                    successes.add("Thread-" + threadNum + " -> " + r.getId());
                } catch (Exception e) {
                    failures.add("Thread-" + threadNum + " -> " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("\nConcurrency results:");
        System.out.println("  Successful reservations: " + successes.size());
        System.out.println("  Failed reservations: " + failures.size());
        System.out.println("  Remaining PHONE-001 at Mumbai: " + mumbai.getAvailable("PHONE-001"));

        // ===================================================
        // TEST 9: Strategy Pattern — switch allocation strategy
        // ===================================================
        System.out.println("\n===== TEST 9: Allocation Strategy Switch =====");
        // Add some laptops to all warehouses for a fair comparison
        service.addStock("WH-MUM", "LAPTOP-001", 10, "strategy test");
        service.addStock("WH-DEL", "LAPTOP-001", 10, "strategy test");
        service.addStock("WH-BLR", "LAPTOP-001", 10, "strategy test");

        System.out.println("Using CheapestWarehouseStrategy (BLR has lowest shipping cost: 30):");
        service.setAllocationStrategy(new CheapestWarehouseStrategy());
        Reservation cheapRes = service.reserveStock("LAPTOP-001", 2, 15);
        System.out.println("  Allocated from: " + cheapRes.getWarehouseId());

        System.out.println("Using MostStockStrategy:");
        service.setAllocationStrategy(new MostStockStrategy());
        Reservation balancedRes = service.reserveStock("LAPTOP-001", 2, 15);
        System.out.println("  Allocated from: " + balancedRes.getWarehouseId());

        // ===================================================
        // TEST 10: Search / Filter products
        // ===================================================
        System.out.println("\n===== TEST 10: Search & Filter =====");
        System.out.println("Electronics:");
        service.searchByCategory(Category.ELECTRONICS)
                .forEach(p -> System.out.println("  " + p));

        System.out.println("Price range $20 - $50:");
        service.searchByPriceRange(20, 50)
                .forEach(p -> System.out.println("  " + p));

        System.out.println("Products in stock:");
        service.searchAvailableProducts()
                .forEach(p -> System.out.println("  " + p));

        // ===================================================
        // Print full audit trail
        // ===================================================
        auditLog.printAll();

        // Cleanup
        service.shutdown();

        System.out.println("\n=== Simulation Complete ===");
    }
}
