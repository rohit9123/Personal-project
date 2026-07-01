package com.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("==================================================");
        logger.info("   Starting Google Drive Sync Automation (Java)   ");
        logger.info("==================================================");
        logger.info("Watching directory: {}", Config.WATCH_DIR);
        logger.info("Project directory: {}", Config.PROJECT_DIR);

        // 1. Verify credentials.json exists
        if (!Config.checkCredentialsExist()) {
            logger.error("Error: Google API Credentials file not found!");
            System.err.println("\n[SETUP REQUIRED]");
            System.err.println("To run this automation, you must configure a Google Cloud Project:");
            System.err.println("1. Visit the Google Cloud Console (https://console.cloud.google.com/).");
            System.err.println("2. Enable the 'Google Drive API' for your project.");
            System.err.println("3. Go to 'APIs & Services > Credentials' and click 'Create Credentials > OAuth client ID'.");
            System.err.println("4. Select Application Type: 'Desktop App'. Name it e.g. 'DriveSyncApp'.");
            System.err.println("5. Download the JSON credentials file and rename it to 'credentials.json'.");
            System.err.println("6. Place 'credentials.json' in this folder:");
            System.err.println("   " + Config.CREDENTIALS_FILE_PATH);
            System.err.println();
            System.exit(1);
        }

        GoogleDriveService driveService = null;
        SyncEngine syncEngine = null;
        DirectoryWatcher watcher = null;

        try {
            // 2. Initialize Drive Service (this might open a browser window for first-time auth)
            logger.info("Initializing Google Drive service...");
            driveService = new GoogleDriveService();

            // 3. Initialize Sync Engine
            syncEngine = new SyncEngine(driveService);

            // 4. Initialize Directory Watcher
            watcher = new DirectoryWatcher(syncEngine);

            // 5. Run initial sync to upload any modifications that occurred while offline
            syncEngine.performInitialSync(watcher);

            // 6. Start watching for changes
            watcher.start();
            logger.info("Real-time folder monitoring is active.");
            logger.info("Press Ctrl+C or type 'exit' and press Enter to stop the sync daemon.");

            // Register shutdown hook for graceful termination
            final GoogleDriveService ds = driveService;
            final SyncEngine se = syncEngine;
            final DirectoryWatcher dw = watcher;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown signal received. Cleaning up...");
                if (dw != null) dw.stop();
                if (se != null) se.shutdown();
                logger.info("Google Drive Sync Automation stopped. Goodbye!");
            }));

            // Keep the application running and listen to stdin for manual exit command
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("exit".equalsIgnoreCase(line.trim())) {
                        logger.info("Exit command received.");
                        break;
                    }
                }
            }

            // Normal shutdown
            System.exit(0);

        } catch (Exception e) {
            logger.error("An error occurred during sync automation execution", e);
            if (watcher != null) watcher.stop();
            if (syncEngine != null) syncEngine.shutdown();
            System.exit(1);
        }
    }
}
