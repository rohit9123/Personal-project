package lld.problems.file_system;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * In-Memory File System LLD Demo
 * 
 * Design Patterns Used:
 * - Composite Pattern: Treats files (leaves) and directories (composites) uniformly through the Entry component.
 * - Singleton Pattern: Manages global FileSystem operations.
 * 
 * Concurrency:
 * - Tree structure synchronization using ReentrantReadWriteLock to allow high throughput for parallel readers.
 * - CWD management isolated per thread using ThreadLocal.
 */
public class FileSystemDemo {

    // =========================================================================
    // 1. Composite Pattern Classes
    // =========================================================================

    /**
     * Component interface representing a general file system node.
     */
    public static abstract class Entry {
        protected String name;
        protected Directory parent;
        protected final long createdTime;
        protected long lastModifiedTime;

        public Entry(String name, Directory parent) {
            this.name = name;
            this.parent = parent;
            this.createdTime = System.currentTimeMillis();
            this.lastModifiedTime = this.createdTime;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
            this.lastModifiedTime = System.currentTimeMillis();
        }

        public Directory getParent() {
            return parent;
        }

        public void setParent(Directory parent) {
            this.parent = parent;
            this.lastModifiedTime = System.currentTimeMillis();
        }

        public abstract boolean isDirectory();
        public abstract int getSize();

        public String getAbsolutePath() {
            if (parent == null) {
                return "/";
            }
            String parentPath = parent.getAbsolutePath();
            return parentPath.equals("/") ? "/" + name : parentPath + "/" + name;
        }
    }

    /**
     * Leaf class representing a file.
     */
    public static class File extends Entry {
        private String content = "";

        public File(String name, Directory parent) {
            super(name, parent);
        }

        @Override
        public boolean isDirectory() {
            return false;
        }

        @Override
        public int getSize() {
            return content.length();
        }

        public String read() {
            return content;
        }

        public void write(String newContent) {
            this.content = newContent;
            this.lastModifiedTime = System.currentTimeMillis();
        }

        public void append(String appendContent) {
            this.content += appendContent;
            this.lastModifiedTime = System.currentTimeMillis();
        }
    }

    /**
     * Composite class representing a directory containing other entries.
     */
    public static class Directory extends Entry {
        private final Map<String, Entry> children = new TreeMap<>(); // Sorted alphabetically

        public Directory(String name, Directory parent) {
            super(name, parent);
        }

        @Override
        public boolean isDirectory() {
            return true;
        }

        @Override
        public int getSize() {
            int totalSize = 0;
            for (Entry entry : children.values()) {
                totalSize += entry.getSize();
            }
            return totalSize;
        }

        public List<Entry> getChildren() {
            return new ArrayList<>(children.values());
        }

        public Entry getChild(String name) {
            return children.get(name);
        }

        public void addEntry(Entry entry) {
            children.put(entry.getName(), entry);
            this.lastModifiedTime = System.currentTimeMillis();
        }

        public boolean removeEntry(String name) {
            Entry removed = children.remove(name);
            if (removed != null) {
                this.lastModifiedTime = System.currentTimeMillis();
                return true;
            }
            return false;
        }
    }

    // =========================================================================
    // 2. Core File System Service (Thread-Safe)
    // =========================================================================

    public static class FileSystem {
        private final Directory root = new Directory("", null);
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
        private final ThreadLocal<String> currentDirectory = ThreadLocal.withInitial(() -> "/");

        private static final FileSystem INSTANCE = new FileSystem();
        public static FileSystem getInstance() {
            return INSTANCE;
        }

        private FileSystem() {}

        /**
         * Parses and resolves path absolute mapping, processing relative '.' and '..' operators.
         */
        private List<String> parsePath(String path) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("Path cannot be empty");
            }

            String absolutePath = path;
            if (!path.startsWith("/")) {
                String cwd = currentDirectory.get();
                absolutePath = cwd.equals("/") ? "/" + path : cwd + "/" + path;
            }

            String[] parts = absolutePath.split("/");
            Stack<String> resolvedParts = new Stack<>();
            for (String part : parts) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (!resolvedParts.isEmpty()) {
                        resolvedParts.pop();
                    }
                } else {
                    resolvedParts.push(part);
                }
            }
            return new ArrayList<>(resolvedParts);
        }

        public String getCWD() {
            return currentDirectory.get();
        }

        public void cd(String path) {
            rwLock.readLock().lock();
            try {
                List<String> parts = parsePath(path);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null || !child.isDirectory()) {
                        throw new IllegalArgumentException("Directory not found: " + path);
                    }
                    current = (Directory) child;
                }
                currentDirectory.set(current.getAbsolutePath());
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public void mkdir(String path) {
            rwLock.writeLock().lock();
            try {
                List<String> parts = parsePath(path);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null) {
                        Directory newDir = new Directory(part, current);
                        current.addEntry(newDir);
                        current = newDir;
                    } else if (child.isDirectory()) {
                        current = (Directory) child;
                    } else {
                        throw new IllegalArgumentException("mkdir failed: " + part + " already exists and is a file.");
                    }
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public void createFile(String path) {
            rwLock.writeLock().lock();
            try {
                List<String> parts = parsePath(path);
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Cannot create file at root '/'");
                }
                String fileName = parts.remove(parts.size() - 1);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null || !child.isDirectory()) {
                        throw new IllegalArgumentException("Directory path does not exist: " + part);
                    }
                    current = (Directory) child;
                }

                if (current.getChild(fileName) != null) {
                    throw new IllegalArgumentException("Entry already exists: " + fileName);
                }
                current.addEntry(new File(fileName, current));
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public void writeFile(String path, String content) {
            rwLock.writeLock().lock();
            try {
                List<String> parts = parsePath(path);
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Invalid file path");
                }
                Directory current = root;
                for (int i = 0; i < parts.size() - 1; i++) {
                    Entry child = current.getChild(parts.get(i));
                    if (child == null || !child.isDirectory()) {
                        throw new IllegalArgumentException("Directory path does not exist: " + parts.get(i));
                    }
                    current = (Directory) child;
                }
                String fileName = parts.get(parts.size() - 1);
                Entry entry = current.getChild(fileName);
                if (entry == null) {
                    File file = new File(fileName, current);
                    file.write(content);
                    current.addEntry(file);
                } else if (entry.isDirectory()) {
                    throw new IllegalArgumentException("Path resolves to a directory: " + path);
                } else {
                    ((File) entry).write(content);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public String readFile(String path) {
            rwLock.readLock().lock();
            try {
                List<String> parts = parsePath(path);
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Invalid file path");
                }
                Directory current = root;
                for (int i = 0; i < parts.size() - 1; i++) {
                    Entry child = current.getChild(parts.get(i));
                    if (child == null || !child.isDirectory()) {
                        throw new IllegalArgumentException("Directory path does not exist: " + parts.get(i));
                    }
                    current = (Directory) child;
                }
                Entry entry = current.getChild(parts.get(parts.size() - 1));
                if (entry == null || entry.isDirectory()) {
                    throw new IllegalArgumentException("File not found or is a directory: " + path);
                }
                return ((File) entry).read();
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public void delete(String path) {
            rwLock.writeLock().lock();
            try {
                List<String> parts = parsePath(path);
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("Cannot delete root");
                }
                String targetName = parts.remove(parts.size() - 1);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null || !child.isDirectory()) {
                        throw new IllegalArgumentException("Directory path does not exist: " + part);
                    }
                    current = (Directory) child;
                }
                boolean removed = current.removeEntry(targetName);
                if (!removed) {
                    throw new IllegalArgumentException("File or directory not found: " + targetName);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        public List<String> ls(String path) {
            rwLock.readLock().lock();
            try {
                List<String> parts = parsePath(path);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null) {
                        throw new IllegalArgumentException("Path not found: " + path);
                    }
                    if (child.isDirectory()) {
                        current = (Directory) child;
                    } else {
                        return Collections.singletonList(child.getName());
                    }
                }
                List<String> result = new ArrayList<>();
                for (Entry entry : current.getChildren()) {
                    result.add(entry.getName() + (entry.isDirectory() ? "/" : ""));
                }
                return result;
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public int getSize(String path) {
            rwLock.readLock().lock();
            try {
                List<String> parts = parsePath(path);
                Directory current = root;
                for (String part : parts) {
                    Entry child = current.getChild(part);
                    if (child == null) {
                        throw new IllegalArgumentException("Path not found: " + path);
                    }
                    if (child.isDirectory()) {
                        current = (Directory) child;
                    } else {
                        return child.getSize();
                    }
                }
                return current.getSize();
            } finally {
                rwLock.readLock().unlock();
            }
        }

        public List<String> find(String pattern) {
            rwLock.readLock().lock();
            try {
                List<String> matches = new ArrayList<>();
                searchRecursively(root, pattern, matches);
                return matches;
            } finally {
                rwLock.readLock().unlock();
            }
        }

        private void searchRecursively(Directory dir, String pattern, List<String> matches) {
            for (Entry entry : dir.getChildren()) {
                if (entry.getName().contains(pattern)) {
                    matches.add(entry.getAbsolutePath());
                }
                if (entry.isDirectory()) {
                    searchRecursively((Directory) entry, pattern, matches);
                }
            }
        }
    }

    // =========================================================================
    // 3. Demo Driver
    // =========================================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== IN-MEMORY FILE SYSTEM LLD DEMONSTRATION ===");
        FileSystem fs = FileSystem.getInstance();

        // 1. Basic Directory Creation
        System.out.println("\n--- Step 1: Creating Directories ---");
        fs.mkdir("/a/b/c");
        fs.mkdir("/a/d");
        System.out.println("Current Directory (CWD): " + fs.getCWD());
        System.out.println("Listing of root /: " + fs.ls("/"));
        System.out.println("Listing of /a: " + fs.ls("/a"));

        // 2. File Creation and Writing
        System.out.println("\n--- Step 2: Creating and Writing Files ---");
        fs.createFile("/a/b/file1.txt");
        fs.writeFile("/a/b/file1.txt", "Hello World of LLD!");
        
        fs.createFile("/a/d/file2.txt");
        fs.writeFile("/a/d/file2.txt", "In-Memory FileSystem design details.");

        System.out.println("file1 content: " + fs.readFile("/a/b/file1.txt"));
        System.out.println("file2 content: " + fs.readFile("/a/d/file2.txt"));

        // 3. Size Calculations (Composite Pattern validation)
        System.out.println("\n--- Step 3: Size Calculations (Composite Pattern) ---");
        System.out.println("Size of file1: " + fs.getSize("/a/b/file1.txt") + " bytes");
        System.out.println("Size of file2: " + fs.getSize("/a/d/file2.txt") + " bytes");
        System.out.println("Total size of directory /a (recursively computed): " + fs.getSize("/a") + " bytes");

        // 4. Directory Navigation & Relative Paths
        System.out.println("\n--- Step 4: Directory Navigation & Relative Paths ---");
        fs.cd("/a/b");
        System.out.println("CWD after cd('/a/b'): " + fs.getCWD());
        System.out.println("Listing current directory '.': " + fs.ls("."));
        
        fs.createFile("relative_file.txt");
        fs.writeFile("relative_file.txt", "Created using a relative path.");
        System.out.println("Listing current directory after creation: " + fs.ls("."));
        System.out.println("Size of directory /a after new addition: " + fs.getSize("/a") + " bytes");
        
        fs.cd("..");
        System.out.println("CWD after cd('..'): " + fs.getCWD());
        System.out.println("Listing current directory: " + fs.ls("."));

        // 5. Search / Find
        System.out.println("\n--- Step 5: Searching entries containing 'file' ---");
        List<String> searchResults = fs.find("file");
        for (String match : searchResults) {
            System.out.println("  Found: " + match);
        }

        // 6. Deletion
        System.out.println("\n--- Step 6: Deleting Files & Directories ---");
        System.out.println("Deleting /a/d/file2.txt...");
        fs.delete("/a/d/file2.txt");
        System.out.println("Listing of /a/d: " + fs.ls("/a/d"));
        System.out.println("Total size of directory /a after deletion: " + fs.getSize("/a") + " bytes");

        // 7. Concurrent Reads and Writes
        System.out.println("\n--- Step 7: Concurrent Stress Testing ---");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);

        // Writer task: Add dynamic directories and write files
        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    String dirPath = "/concurrent/dir_" + i;
                    String filePath = dirPath + "/log.txt";
                    fs.mkdir(dirPath);
                    fs.writeFile(filePath, "Thread log content " + i);
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        // Reader task: Reads a static file
        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    fs.readFile("/a/b/file1.txt");
                    Thread.sleep(15);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        // Reader task: Lists directory structure & search
        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    fs.ls("/a");
                    fs.find("log");
                    Thread.sleep(12);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        // Writer task: Creates and deletes temporary files
        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    String tempPath = "/a/b/temp_" + i + ".txt";
                    fs.createFile(tempPath);
                    fs.writeFile(tempPath, "temp content");
                    fs.delete(tempPath);
                    Thread.sleep(18);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        });

        latch.await();
        executor.shutdown();

        System.out.println("Listing of /concurrent: " + fs.ls("/concurrent"));
        System.out.println("Concurrent stress test completed successfully!");
    }
}
