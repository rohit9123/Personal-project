package com.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class SyncEngine {
    private static final Logger logger = LoggerFactory.getLogger(SyncEngine.class);
    private static final long DEBOUNCE_DELAY_MS = 2000; // 2 seconds delay to let files finish writing
    
    private final GoogleDriveService driveService;
    private final ObjectMapper mapper;
    private SyncState state;
    
    // Concurrent map to keep track of file path -> last change time (for debouncing)
    private final ConcurrentHashMap<String, Long> pendingChanges = new ConcurrentHashMap<>();
    
    // Queue of files to process after debouncing
    private final BlockingQueue<SyncTask> taskQueue = new LinkedBlockingQueue<>();
    
    // Executor for processing the task queue and debouncer
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private volatile boolean running = true;

    public static class FileState {
        public String id;
        public String md5Checksum;
        public long lastModified;
        public boolean isDirectory;

        public FileState() {}

        public FileState(String id, String md5Checksum, long lastModified, boolean isDirectory) {
            this.id = id;
            this.md5Checksum = md5Checksum;
            this.lastModified = lastModified;
            this.isDirectory = isDirectory;
        }
    }

    public static class SyncState {
        public String rootFolderId;
        public Map<String, FileState> files = new ConcurrentHashMap<>();
    }

    private static class SyncTask {
        enum Type { UPLOAD, DELETE }
        final Type type;
        final String relativePath;

        SyncTask(Type type, String relativePath) {
            this.type = type;
            this.relativePath = relativePath;
        }
    }

    public SyncEngine(GoogleDriveService driveService) {
        this.driveService = driveService;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        loadState();
        startBackgroundWorkers();
    }

    private void loadState() {
        File file = Config.STATE_FILE_PATH.toFile();
        if (file.exists()) {
            try {
                this.state = mapper.readValue(file, SyncState.class);
                logger.info("Loaded sync state. Google Drive root folder ID: {}", state.rootFolderId);
                return;
            } catch (IOException e) {
                logger.warn("Failed to parse state file. Starting with empty state.", e);
            }
        }
        
        // Start fresh
        this.state = new SyncState();
        saveState();
    }

    private synchronized void saveState() {
        try {
            // Ensure parent directory exists
            File file = Config.STATE_FILE_PATH.toFile();
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            mapper.writeValue(file, this.state);
        } catch (IOException e) {
            logger.error("Failed to save sync state file", e);
        }
    }

    /**
     * Initializes the root folder on Google Drive if not set.
     */
    public void ensureRootFolder() throws IOException {
        if (state.rootFolderId != null) {
            // Validate that the folder still exists on Drive
            try {
                String folderId = driveService.findFolderIdByName(Config.DRIVE_ROOT_FOLDER_NAME, null);
                if (folderId != null) {
                    state.rootFolderId = folderId;
                    saveState();
                    return;
                }
            } catch (IOException e) {
                logger.warn("Failed to verify root folder on Drive. Re-creating.", e);
            }
        }

        // Search or create
        String folderId = driveService.findFolderIdByName(Config.DRIVE_ROOT_FOLDER_NAME, null);
        if (folderId == null) {
            logger.info("Root folder '{}' not found on Google Drive. Creating it.", Config.DRIVE_ROOT_FOLDER_NAME);
            folderId = driveService.createFolder(Config.DRIVE_ROOT_FOLDER_NAME, null);
        } else {
            logger.info("Found existing root folder '{}' on Google Drive with ID: {}", Config.DRIVE_ROOT_FOLDER_NAME, folderId);
        }
        state.rootFolderId = folderId;
        saveState();
    }

    private void startBackgroundWorkers() {
        // 1. Debouncer Thread
        executor.submit(() -> {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, Long> entry : pendingChanges.entrySet()) {
                        String relativePath = entry.getKey();
                        long lastChange = entry.getValue();
                        
                        if (now - lastChange >= DEBOUNCE_DELAY_MS) {
                            // File has stopped changing, move to main queue
                            pendingChanges.remove(relativePath);
                            taskQueue.put(new SyncTask(SyncTask.Type.UPLOAD, relativePath));
                            logger.debug("Debounced file, queued for upload: {}", relativePath);
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in debouncer loop", e);
                }
            }
        });

        // 2. Queue Processor Thread
        executor.submit(() -> {
            while (running) {
                try {
                    SyncTask task = taskQueue.take();
                    processSyncTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error processing sync task", e);
                }
            }
        });
    }

    private void processSyncTask(SyncTask task) {
        String relPathStr = task.relativePath;
        Path localPath = Config.WATCH_DIR.resolve(relPathStr);
        logger.info("Processing sync task: {} -> {}", task.type, relPathStr);

        try {
            if (task.type == SyncTask.Type.DELETE) {
                FileState fileState = state.files.get(relPathStr);
                if (fileState != null) {
                    driveService.trashFile(fileState.id);
                    state.files.remove(relPathStr);
                    saveState();
                    logger.info("Successfully trashed and unregistered: {}", relPathStr);
                }
            } else if (task.type == SyncTask.Type.UPLOAD) {
                if (!Files.exists(localPath)) {
                    logger.warn("Skipping upload, file no longer exists locally: {}", relPathStr);
                    return;
                }

                boolean isDir = Files.isDirectory(localPath);
                if (isDir) {
                    getOrCreateDriveFolderId(Paths.get(relPathStr));
                } else {
                    syncFile(Paths.get(relPathStr));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to execute sync task for path: " + relPathStr, e);
        }
    }

    /**
     * Resolves and builds parent folder hierarchy on Drive recursively.
     */
    private String getOrCreateDriveFolderId(Path relativePath) throws IOException {
        String pathStr = relativePath.toString().replace(File.separatorChar, '/');
        
        // Clean trailing slash
        if (pathStr.endsWith("/")) {
            pathStr = pathStr.substring(0, pathStr.length() - 1);
        }
        if (pathStr.isEmpty()) {
            return state.rootFolderId;
        }

        FileState existing = state.files.get(pathStr);
        if (existing != null && existing.isDirectory) {
            return existing.id;
        }

        // Build parent folder first
        Path parentPath = relativePath.getParent();
        String parentDriveId = state.rootFolderId;
        if (parentPath != null) {
            parentDriveId = getOrCreateDriveFolderId(parentPath);
        }

        String folderName = relativePath.getFileName().toString();
        
        // Check on Drive just in case it exists but not in local state
        String driveId = driveService.findFolderIdByName(folderName, parentDriveId);
        if (driveId == null) {
            driveId = driveService.createFolder(folderName, parentDriveId);
        }

        state.files.put(pathStr, new FileState(driveId, "", 0, true));
        saveState();
        return driveId;
    }

    private void syncFile(Path relativePath) throws IOException {
        String pathStr = relativePath.toString().replace(File.separatorChar, '/');
        Path localPath = Config.WATCH_DIR.resolve(relativePath);
        
        Path parentPath = relativePath.getParent();
        String parentDriveId = state.rootFolderId;
        if (parentPath != null) {
            parentDriveId = getOrCreateDriveFolderId(parentPath);
        }

        String fileName = relativePath.getFileName().toString();
        String mimeType = getMimeType(localPath);
        String currentChecksum = getMD5Checksum(localPath);
        long lastModified = Files.getLastModifiedTime(localPath).toMillis();

        FileState cached = state.files.get(pathStr);
        if (cached == null) {
            // New file
            com.google.api.services.drive.model.File uploaded = driveService.uploadFile(
                    fileName, localPath.toFile(), mimeType, parentDriveId);
            state.files.put(pathStr, new FileState(uploaded.getId(), uploaded.getMd5Checksum(), lastModified, false));
            saveState();
            logger.info("Registered new file: {}", pathStr);
        } else {
            // Existing file: Check if we need to update
            if (!currentChecksum.equals(cached.md5Checksum) || lastModified > cached.lastModified) {
                logger.info("File modified locally. Updating on Google Drive: {}", pathStr);
                com.google.api.services.drive.model.File updated = driveService.updateFile(
                        cached.id, localPath.toFile(), mimeType);
                
                cached.md5Checksum = updated.getMd5Checksum();
                cached.lastModified = lastModified;
                state.files.put(pathStr, cached);
                saveState();
                logger.info("Successfully updated file: {}", pathStr);
            } else {
                logger.debug("File is up to date: {}", pathStr);
            }
        }
    }

    /**
     * Queues a local change event for processing.
     */
    public void queueChange(Path relativePath) {
        String relPathStr = relativePath.toString().replace(File.separatorChar, '/');
        pendingChanges.put(relPathStr, System.currentTimeMillis());
    }

    /**
     * Queues a local deletion event for processing.
     */
    public void queueDeletion(Path relativePath) {
        String relPathStr = relativePath.toString().replace(File.separatorChar, '/');
        pendingChanges.remove(relPathStr); // Cancel any pending uploads
        try {
            taskQueue.put(new SyncTask(SyncTask.Type.DELETE, relPathStr));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Walks local files and compares them with Google Drive to resolve offline changes.
     */
    public void performInitialSync(DirectoryWatcher watcher) throws IOException {
        logger.info("Starting initial sync check...");
        ensureRootFolder();

        // 1. Scan local files and upload new/changed ones
        Set<String> localFilesFound = new HashSet<>();
        
        Files.walkFileTree(Config.WATCH_DIR, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = Config.WATCH_DIR.relativize(dir);
                String relStr = relative.toString().replace(File.separatorChar, '/');
                
                if (watcher.isIgnored(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                if (!relStr.isEmpty()) {
                    localFilesFound.add(relStr);
                    getOrCreateDriveFolderId(relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = Config.WATCH_DIR.relativize(file);
                String relStr = relative.toString().replace(File.separatorChar, '/');
                
                if (!watcher.isIgnored(relative)) {
                    localFilesFound.add(relStr);
                    try {
                        syncFile(relative);
                    } catch (Exception e) {
                        logger.error("Failed to sync file during startup: " + relStr, e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // 2. Identify deleted files (things in state but no longer present locally)
        for (String cachedRelPath : state.files.keySet()) {
            if (!localFilesFound.contains(cachedRelPath)) {
                logger.info("File/folder found in local state but not locally. Trashing in Google Drive: {}", cachedRelPath);
                queueDeletion(Paths.get(cachedRelPath));
            }
        }
        
        logger.info("Initial sync complete.");
    }

    public void shutdown() {
        running = false;
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getMimeType(Path path) {
        try {
            String contentType = Files.probeContentType(path);
            if (contentType != null) {
                return contentType;
            }
        } catch (IOException e) {
            // ignore
        }
        
        // Fallback checks
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".java")) return "text/plain";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".properties")) return "text/plain";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "text/yaml";
        if (name.endsWith(".sh")) return "text/plain";
        if (name.endsWith(".txt")) return "text/plain";
        
        return "application/octet-stream";
    }

    private static String getMD5Checksum(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : md5sum) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Could not calculate checksum for: {}", path, e);
            return "";
        }
    }
}
