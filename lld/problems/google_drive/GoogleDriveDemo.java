import java.util.*;
import java.time.Instant;

/**
 * Google Drive LLD — Runnable Demo
 * Demonstrates: Composite (file/folder tree), Observer (sync notifications),
 *               Strategy (search), Versioning, Permission cascading.
 *
 * Compile & Run:
 *   javac GoogleDriveDemo.java && java GoogleDriveDemo
 */
public class GoogleDriveDemo {

    // ─── Permission ─────────────────────────────────────────────────────────

    enum PermissionType {
        OWNER,   // ordinal 0 — highest
        EDITOR,  // ordinal 1
        VIEWER   // ordinal 2 — lowest
    }

    // ─── User ───────────────────────────────────────────────────────────────

    static class User {
        private final String userId;
        private final String name;
        private final String email;
        private final long storageQuota;  // bytes
        private long storageUsed;

        User(String userId, String name, String email, long storageQuotaMB) {
            this.userId = userId;
            this.name = name;
            this.email = email;
            this.storageQuota = storageQuotaMB * 1024 * 1024;
            this.storageUsed = 0;
        }

        String getUserId() { return userId; }
        String getName() { return name; }
        String getEmail() { return email; }
        long getStorageUsed() { return storageUsed; }

        boolean hasQuota(long bytes) { return (storageUsed + bytes) <= storageQuota; }
        void addUsage(long bytes) { storageUsed += bytes; }
        void removeUsage(long bytes) { storageUsed = Math.max(0, storageUsed - bytes); }

        String getQuotaDisplay() {
            return String.format("%.1f KB / %.0f MB",
                storageUsed / 1024.0, storageQuota / (1024.0 * 1024.0));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof User && userId.equals(((User) o).userId);
        }

        @Override
        public int hashCode() { return userId.hashCode(); }

        @Override
        public String toString() { return name; }
    }

    // ─── FileVersion ────────────────────────────────────────────────────────

    static class FileVersion {
        private final int versionNumber;
        private final long size;
        private final String note;
        private final Instant timestamp;

        FileVersion(int versionNumber, long size, String note) {
            this.versionNumber = versionNumber;
            this.size = size;
            this.note = note;
            this.timestamp = Instant.now();
        }

        int getVersionNumber() { return versionNumber; }
        long getSize() { return size; }

        @Override
        public String toString() {
            return "v" + versionNumber + " (" + size + " bytes) — " + note;
        }
    }

    // ─── FileSystemItem — Composite Base ────────────────────────────────────

    static abstract class FileSystemItem {
        protected final String id;
        protected String name;
        protected User owner;
        protected Folder parent;
        protected final Instant createdAt;
        protected final Map<User, PermissionType> permissions = new HashMap<>();

        FileSystemItem(String id, String name, User owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.createdAt = Instant.now();
            this.permissions.put(owner, PermissionType.OWNER);
        }

        String getName() { return name; }
        User getOwner() { return owner; }
        abstract long getSize();
        abstract boolean isFolder();

        String getPath() {
            if (parent == null) return "/" + name;
            return parent.getPath() + "/" + name;
        }

        void share(User user, PermissionType perm) {
            permissions.put(user, perm);
        }

        boolean hasPermission(User user, PermissionType required) {
            if (user.equals(owner)) return true;
            PermissionType perm = permissions.get(user);
            if (perm != null) return perm.ordinal() <= required.ordinal();
            // Cascade: check parent's permission
            if (parent != null) return parent.hasPermission(user, required);
            return false;
        }

        String permissionFor(User user) {
            if (user.equals(owner)) return "OWNER";
            PermissionType perm = permissions.get(user);
            if (perm != null) return perm.name();
            if (parent != null) return parent.permissionFor(user) + " (inherited)";
            return "NONE";
        }
    }

    // ─── File — Leaf Node ───────────────────────────────────────────────────

    static class File extends FileSystemItem {
        private long size;
        private final String extension;
        private final List<FileVersion> versions = new ArrayList<>();
        private int currentVersionIndex = 0;

        File(String id, String name, User owner, long size) {
            super(id, name, owner);
            this.size = size;
            this.extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
            // Initial version
            versions.add(new FileVersion(1, size, "Initial upload"));
        }

        String getExtension() { return extension; }

        @Override
        long getSize() { return size; }

        @Override
        boolean isFolder() { return false; }

        void uploadNewVersion(long newSize, String note) {
            owner.removeUsage(this.size);
            this.size = newSize;
            owner.addUsage(this.size);
            FileVersion version = new FileVersion(versions.size() + 1, newSize, note);
            versions.add(version);
            currentVersionIndex = versions.size() - 1;
        }

        void restoreVersion(int versionNumber) {
            if (versionNumber < 1 || versionNumber > versions.size()) {
                throw new IllegalArgumentException("Invalid version: " + versionNumber);
            }
            owner.removeUsage(this.size);
            currentVersionIndex = versionNumber - 1;
            this.size = versions.get(currentVersionIndex).getSize();
            owner.addUsage(this.size);
        }

        List<FileVersion> getVersionHistory() {
            return Collections.unmodifiableList(versions);
        }

        int getCurrentVersion() { return currentVersionIndex + 1; }

        @Override
        public String toString() {
            return name + " (" + size + " bytes, v" + getCurrentVersion() + ")";
        }
    }

    // ─── Folder — Composite Node ────────────────────────────────────────────

    static class Folder extends FileSystemItem {
        private final List<FileSystemItem> children = new ArrayList<>();

        Folder(String id, String name, User owner) {
            super(id, name, owner);
        }

        void addChild(FileSystemItem item) {
            item.parent = this;
            children.add(item);
        }

        void removeChild(String name) {
            children.removeIf(c -> c.getName().equals(name));
        }

        FileSystemItem getChild(String name) {
            return children.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst().orElse(null);
        }

        List<FileSystemItem> getChildren() {
            return Collections.unmodifiableList(children);
        }

        @Override
        long getSize() {
            return children.stream().mapToLong(FileSystemItem::getSize).sum();
        }

        @Override
        boolean isFolder() { return true; }

        // Recursive search using Strategy pattern
        List<FileSystemItem> search(SearchStrategy strategy) {
            List<FileSystemItem> results = new ArrayList<>();
            for (FileSystemItem child : children) {
                if (strategy.matches(child)) results.add(child);
                if (child instanceof Folder) {
                    results.addAll(((Folder) child).search(strategy));
                }
            }
            return results;
        }

        void printTree(String indent) {
            System.out.println(indent + "[Folder] " + name + "/");
            for (FileSystemItem child : children) {
                if (child instanceof Folder) {
                    ((Folder) child).printTree(indent + "  ");
                } else {
                    System.out.println(indent + "  " + child);
                }
            }
        }
    }

    // ─── Strategy: Search ───────────────────────────────────────────────────

    interface SearchStrategy {
        boolean matches(FileSystemItem item);
    }

    static class NameSearchStrategy implements SearchStrategy {
        private final String query;
        NameSearchStrategy(String query) { this.query = query.toLowerCase(); }

        @Override
        public boolean matches(FileSystemItem item) {
            return item.getName().toLowerCase().contains(query);
        }
    }

    static class ExtensionSearchStrategy implements SearchStrategy {
        private final String extension;
        ExtensionSearchStrategy(String ext) { this.extension = ext.toLowerCase(); }

        @Override
        public boolean matches(FileSystemItem item) {
            return item instanceof File &&
                   ((File) item).getExtension().equalsIgnoreCase(extension);
        }
    }

    static class SizeSearchStrategy implements SearchStrategy {
        private final long minSize;
        private final long maxSize;
        SizeSearchStrategy(long minSize, long maxSize) {
            this.minSize = minSize;
            this.maxSize = maxSize;
        }

        @Override
        public boolean matches(FileSystemItem item) {
            return item instanceof File &&
                   item.getSize() >= minSize && item.getSize() <= maxSize;
        }
    }

    // ─── Observer: Sync Notifications ───────────────────────────────────────

    interface SyncObserver {
        void onFileModified(File file, String action);
    }

    static class UserSyncClient implements SyncObserver {
        private final User user;
        UserSyncClient(User user) { this.user = user; }

        @Override
        public void onFileModified(File file, String action) {
            System.out.println("  [SYNC → " + user.getName() + "] " + action + ": " + file.getPath());
        }
    }

    static class SyncNotifier {
        private final List<SyncObserver> observers = new ArrayList<>();

        void subscribe(SyncObserver observer) { observers.add(observer); }
        void unsubscribe(SyncObserver observer) { observers.remove(observer); }

        void notifyAll(File file, String action) {
            for (SyncObserver observer : observers) {
                observer.onFileModified(file, action);
            }
        }
    }

    // ─── GoogleDrive Facade ─────────────────────────────────────────────────

    static class GoogleDrive {
        private final Map<String, User> users = new HashMap<>();
        private final Map<String, Folder> userRoots = new HashMap<>();
        private final SyncNotifier notifier = new SyncNotifier();
        private int idCounter = 0;

        private String nextId() { return "item_" + (++idCounter); }

        User createUser(String id, String name, String email, long quotaMB) {
            User user = new User(id, name, email, quotaMB);
            users.put(id, user);
            Folder root = new Folder(nextId(), name + "'s Drive", user);
            userRoots.put(id, root);
            return user;
        }

        Folder getUserRoot(User user) { return userRoots.get(user.getUserId()); }

        Folder createFolder(User user, Folder parent, String name) {
            if (!parent.hasPermission(user, PermissionType.EDITOR)) {
                throw new SecurityException("No EDITOR permission on " + parent.getPath());
            }
            Folder folder = new Folder(nextId(), name, user);
            parent.addChild(folder);
            return folder;
        }

        File uploadFile(User user, Folder parent, String name, long size) {
            if (!parent.hasPermission(user, PermissionType.EDITOR)) {
                throw new SecurityException("No EDITOR permission on " + parent.getPath());
            }
            if (!user.hasQuota(size)) {
                throw new RuntimeException("Storage quota exceeded for " + user.getName());
            }
            File file = new File(nextId(), name, user, size);
            parent.addChild(file);
            user.addUsage(size);
            notifier.notifyAll(file, "UPLOADED");
            return file;
        }

        void uploadNewVersion(User user, File file, long newSize, String note) {
            if (!file.hasPermission(user, PermissionType.EDITOR)) {
                throw new SecurityException("No EDITOR permission on " + file.getPath());
            }
            file.uploadNewVersion(newSize, note);
            notifier.notifyAll(file, "VERSION_UPDATED (v" + file.getCurrentVersion() + ")");
        }

        void shareItem(FileSystemItem item, User target, PermissionType perm) {
            item.share(target, perm);
            if (item instanceof File) {
                notifier.notifyAll((File) item, "SHARED with " + target.getName() + " as " + perm);
            }
        }

        void deleteFile(User user, File file) {
            if (!file.hasPermission(user, PermissionType.OWNER)) {
                throw new SecurityException("Only OWNER can delete " + file.getPath());
            }
            if (file.parent != null) {
                file.parent.removeChild(file.getName());
            }
            user.removeUsage(file.getSize());
            notifier.notifyAll(file, "DELETED");
        }

        List<FileSystemItem> search(User user, Folder root, SearchStrategy strategy) {
            return root.search(strategy);
        }

        void subscribeSyncClient(SyncObserver observer) {
            notifier.subscribe(observer);
        }
    }

    // ─── Main Demo ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Google Drive — LLD Demo                   ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        GoogleDrive drive = new GoogleDrive();

        // ─── Setup: Create users ────────────────────────────────────────
        System.out.println("\n--- Setup: Create users ---");
        User rohit = drive.createUser("u1", "Rohit", "rohit@example.com", 100);  // 100 MB
        User priya = drive.createUser("u2", "Priya", "priya@example.com", 50);   // 50 MB
        System.out.println("  Created: " + rohit.getName() + " (100 MB quota)");
        System.out.println("  Created: " + priya.getName() + " (50 MB quota)");

        // Subscribe sync clients
        drive.subscribeSyncClient(new UserSyncClient(rohit));
        drive.subscribeSyncClient(new UserSyncClient(priya));

        // ─── Scenario 1: Build folder structure (Composite) ─────────────
        System.out.println("\n--- Scenario 1: Build folder hierarchy (Composite) ---");
        Folder root = drive.getUserRoot(rohit);
        Folder docs = drive.createFolder(rohit, root, "Documents");
        Folder photos = drive.createFolder(rohit, root, "Photos");
        Folder vacation = drive.createFolder(rohit, photos, "Vacation");

        File resume = drive.uploadFile(rohit, docs, "resume.pdf", 2048);
        File notes = drive.uploadFile(rohit, docs, "notes.txt", 512);
        File design = drive.uploadFile(rohit, docs, "design.md", 1024);
        File avatar = drive.uploadFile(rohit, photos, "avatar.png", 4096);
        File beach = drive.uploadFile(rohit, vacation, "beach.jpg", 8192);
        File sunset = drive.uploadFile(rohit, vacation, "sunset.jpg", 6144);

        System.out.println("\n  File tree:");
        root.printTree("  ");
        System.out.println("\n  Total size of root: " + root.getSize() + " bytes");
        System.out.println("  Total size of Photos/: " + photos.getSize() + " bytes");
        System.out.println("  Rohit's storage: " + rohit.getQuotaDisplay());

        // ─── Scenario 2: File versioning ────────────────────────────────
        System.out.println("\n--- Scenario 2: File versioning ---");
        System.out.println("  resume.pdf current: v" + resume.getCurrentVersion() +
                           " (" + resume.getSize() + " bytes)");
        drive.uploadNewVersion(rohit, resume, 2560, "Updated work experience");
        drive.uploadNewVersion(rohit, resume, 3072, "Added new project section");
        System.out.println("  resume.pdf current: v" + resume.getCurrentVersion() +
                           " (" + resume.getSize() + " bytes)");

        System.out.println("\n  Version history:");
        for (FileVersion v : resume.getVersionHistory()) {
            System.out.println("    " + v);
        }

        System.out.println("\n  Restoring to v1...");
        resume.restoreVersion(1);
        System.out.println("  resume.pdf now: v" + resume.getCurrentVersion() +
                           " (" + resume.getSize() + " bytes)");

        // Restore back to latest
        resume.restoreVersion(3);

        // ─── Scenario 3: Sharing & Permissions ──────────────────────────
        System.out.println("\n--- Scenario 3: Sharing & Permissions ---");
        drive.shareItem(docs, priya, PermissionType.EDITOR);
        System.out.println("  Shared 'Documents/' with Priya as EDITOR");

        System.out.println("  Priya's permission on Documents/: " + docs.permissionFor(priya));
        System.out.println("  Priya's permission on resume.pdf: " + resume.permissionFor(priya));
        System.out.println("  Priya's permission on Photos/:    " + photos.permissionFor(priya));

        // Priya can edit files inside Documents/ (inherited permission)
        System.out.println("\n  Priya uploads a file to Documents/ (has inherited EDITOR):");
        File meeting = drive.uploadFile(priya, docs, "meeting-notes.txt", 256);
        System.out.println("  ✓ Priya created " + meeting.getPath());

        // Priya can't access Photos/ (no permission)
        System.out.print("  Priya tries to upload to Photos/ (no permission): ");
        try {
            drive.uploadFile(priya, photos, "hack.txt", 100);
        } catch (SecurityException e) {
            System.out.println("DENIED — " + e.getMessage());
        }

        // ─── Scenario 4: Search with strategies ────────────────────────
        System.out.println("\n--- Scenario 4: Search with pluggable strategies ---");

        System.out.println("  Search by name 'notes':");
        List<FileSystemItem> results = drive.search(rohit, root, new NameSearchStrategy("notes"));
        for (FileSystemItem item : results) {
            System.out.println("    → " + item.getPath());
        }

        System.out.println("  Search by extension 'jpg':");
        results = drive.search(rohit, root, new ExtensionSearchStrategy("jpg"));
        for (FileSystemItem item : results) {
            System.out.println("    → " + item.getPath());
        }

        System.out.println("  Search by size 1024-4096 bytes:");
        results = drive.search(rohit, root, new SizeSearchStrategy(1024, 4096));
        for (FileSystemItem item : results) {
            System.out.println("    → " + item.getPath() + " (" + item.getSize() + " bytes)");
        }

        // ─── Scenario 5: Delete a file (Observer fires) ────────────────
        System.out.println("\n--- Scenario 5: Delete file (Observer notification) ---");
        System.out.println("  Deleting sunset.jpg...");
        drive.deleteFile(rohit, sunset);
        System.out.println("  Photos/ size after delete: " + photos.getSize() + " bytes");
        System.out.println("  Rohit's storage: " + rohit.getQuotaDisplay());

        // ─── Scenario 6: Composite — recursive size after changes ───────
        System.out.println("\n--- Scenario 6: Composite — final tree & sizes ---");
        root.printTree("  ");
        System.out.println("\n  Root total size: " + root.getSize() + " bytes");

        System.out.println("\n✓ Demo complete.");
    }
}
