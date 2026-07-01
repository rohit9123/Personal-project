# Google Drive Sync Automation (Java)

A background synchronization daemon built in Java 17+ that watches the entire parent directory `/Users/rohit.kumar.4/Documents/interview-prep` and pushes additions, changes, and deletions in real-time to a specific Google Drive folder.

## Key Features
- **Recursive Monitoring**: Native macOS directory watching using Java `WatchService`.
- **Intelligent Debouncing**: Holds changes for 2 seconds to allow files to finish writing (e.g. during saves or compilation) before pushing, avoiding unnecessary Google Drive API traffic.
- **State Persistence**: Keeps a local database (`.drive-sync-state.json`) containing file metadata mapping local files to Drive IDs. This avoids duplicate uploads and checks.
- **Initial Sync check**: Scans all folders on startup to synchronize changes that occurred while the daemon was offline, ensuring no modifications are missed.
- **Safe Deletions**: Moves deleted local files/folders to the Google Drive **Trash** instead of deleting them permanently.
- **Ignore List**: Standard project files (like `.git`, `.idea`, `target`, `node_modules`, `.DS_Store`) and the automation folder itself are ignored to prevent sync loops.

---

## Setup Instructions

### 1. Configure Google Cloud Console
To communicate with Google Drive, you must register a desktop application:
1. Open the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (e.g., `Drive Sync Automation`).
3. Search for the **Google Drive API** in the API Library and click **Enable**.
4. Configure the **OAuth Consent Screen**:
   - Select User Type: **External** (or Internal if you are on a workspace).
   - Enter your email and application name (`DriveSyncApp`).
   - Click Save and continue through the steps. Under Scopes, add `.../auth/drive` (to allow creating, updating, and trashing files).
   - Under Test Users, add **your own Google account email address**. (Since the app is in "Testing" mode, only registered test users can authorize it).
5. Generate credentials:
   - Go to the **Credentials** tab on the left menu.
   - Click **Create Credentials** -> **OAuth client ID**.
   - Select Application Type: **Desktop App**. Name it `DriveSyncDesktopApp` and click **Create**.
   - Click **Download JSON** on the created credentials list.
   - Rename the downloaded file to `credentials.json`.
   - Place `credentials.json` directly inside the `drive-sync-automation` directory:
     `/Users/rohit.kumar.4/Documents/interview-prep/drive-sync-automation/credentials.json`

---

## 2. Compile & Run

Open a terminal and navigate to the project directory:
```bash
cd drive-sync-automation
```

### Build the project:
```bash
mvn clean package
```

### Launch the daemon:
```bash
mvn exec:java
```

### 3. First-Time Authentication Flow
On your very first run:
1. The terminal will print a Google OAuth authorization URL and automatically attempt to open it in your browser.
2. Select your Google account, click through the warnings (click *Advanced* -> *Go to DriveSyncApp (unsafe)* since it is a self-made testing app).
3. Grant permissions to access Google Drive.
4. The local embedded server will automatically capture the response code. The browser will show "Received verification code. You may now close this window."
5. A token file will be saved in `drive-sync-automation/tokens/StoredCredential` so that you never have to authorize the app again.

---

## Architecture & Internals

- `App.java`: Checks for `credentials.json`, bootstraps the other components, starts the daemon, and listens for the `exit` command.
- `Config.java`: Centralizes path configuration (watch path, project root, tokens directory, state file path).
- `GoogleDriveService.java`: Encapsulates Google Drive client API integration, tokens persistence, OAuth2 browser loops, folder creation, file uploads/updates, and trashing.
- `SyncEngine.java`: Manages the local mapping database (`.drive-sync-state.json`), debounces incoming write notifications, handles parent folder generation, and issues API sync calls.
- `DirectoryWatcher.java`: Integrates recursive JDK `WatchService` callbacks and runs the file lifecycle event filter (ignoring build outputs and private configuration directories).
