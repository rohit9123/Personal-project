package com.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static java.nio.file.StandardWatchEventKinds.*;

public class DirectoryWatcher {
    private static final Logger logger = LoggerFactory.getLogger(DirectoryWatcher.class);
    
    private final WatchService watchService;
    private final Map<WatchKey, Path> keys;
    private final SyncEngine syncEngine;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public DirectoryWatcher(SyncEngine syncEngine) throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<>();
        this.syncEngine = syncEngine;
        this.executor = Executors.newSingleThreadExecutor();
        
        // Initially register root and all subdirectories
        registerAll(Config.WATCH_DIR);
    }

    private void register(Path dir) throws IOException {
        if (isIgnored(Config.WATCH_DIR.relativize(dir))) {
            return;
        }
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        keys.put(key, dir);
        logger.debug("Registered watch key for directory: {}", dir);
    }

    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = Config.WATCH_DIR.relativize(dir);
                if (isIgnored(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Determines whether a relative path should be ignored.
     */
    public boolean isIgnored(Path relPath) {
        String pathStr = relPath.toString();
        if (pathStr.isEmpty()) {
            return false;
        }
        
        // Normalize slashes
        pathStr = pathStr.replace('\\', '/');

        // Check if any segment of the path matches ignored folders
        for (Path segment : relPath) {
            String name = segment.toString();
            if (name.equals(".git") || 
                name.equals("node_modules") || 
                name.equals(".idea") || 
                name.equals(".vscode") ||
                name.equals(".remember") ||
                name.equals("drive-sync-automation") ||
                name.equals("target") ||
                name.equals(".DS_Store") ||
                name.startsWith(".")) {
                return true;
            }
        }
        
        return false;
    }

    public void start() {
        executor.submit(this::processEvents);
        logger.info("Directory watcher thread started.");
    }

    private void processEvents() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                logger.warn("WatchKey not recognized!");
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    logger.warn("Received OVERFLOW event - events might have been lost.");
                    continue;
                }

                // Context for directory entry event is the file name of entry
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);
                Path relativeChild = Config.WATCH_DIR.relativize(child);

                if (isIgnored(relativeChild)) {
                    continue;
                }

                logger.info("File Event: {} -> {}", kind.name(), relativeChild);

                if (kind == ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            // Register newly created directory and its subtree
                            registerAll(child);
                        }
                    } catch (IOException e) {
                        logger.error("Failed to recursively register newly created directory: " + child, e);
                    }
                    syncEngine.queueChange(relativeChild);
                } else if (kind == ENTRY_MODIFY) {
                    syncEngine.queueChange(relativeChild);
                } else if (kind == ENTRY_DELETE) {
                    syncEngine.queueDeletion(relativeChild);
                }
            }

            // Reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);
                logger.debug("Removed watch key for directory: {}", dir);
                if (keys.isEmpty()) {
                    logger.warn("All watched directories are inaccessible. Stopping watcher.");
                    break;
                }
            }
        }
    }

    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            logger.error("Error closing watch service", e);
        }
        executor.shutdownNow();
    }
}
