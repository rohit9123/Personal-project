package com.sync;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {
    // Target directory to watch. The parent folder of the automation tool.
    public static final Path WATCH_DIR = Paths.get("/Users/rohit.kumar.4/Documents/interview-prep");
    
    // Directory where this project lives
    public static final Path PROJECT_DIR = WATCH_DIR.resolve("drive-sync-automation");
    
    // Path to credentials.json (downloaded from GCP Console)
    public static final String CREDENTIALS_FILE_PATH = PROJECT_DIR.resolve("credentials.json").toString();
    
    // Path to store OAuth user tokens
    public static final String TOKENS_DIRECTORY_PATH = PROJECT_DIR.resolve("tokens").toString();
    
    // Path to local sync state JSON database
    public static final Path STATE_FILE_PATH = PROJECT_DIR.resolve(".drive-sync-state.json");
    
    // Name of the root folder in Google Drive
    public static final String DRIVE_ROOT_FOLDER_NAME = "interview-prep-sync";

    // Application Name for Google API requests
    public static final String APPLICATION_NAME = "GoogleDriveSyncAutomation";

    public static boolean checkCredentialsExist() {
        File file = new File(CREDENTIALS_FILE_PATH);
        return file.exists() && file.isFile();
    }
}
