package com.sync;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

public class GoogleDriveService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleDriveService.class);
    
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE);
    
    private final Drive driveService;

    public GoogleDriveService() throws IOException, GeneralSecurityException {
        this.driveService = initDriveService();
    }

    private Drive initDriveService() throws IOException, GeneralSecurityException {
        final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        
        File credentialsFile = new File(Config.CREDENTIALS_FILE_PATH);
        if (!credentialsFile.exists()) {
            throw new FileNotFoundException("Google API credentials file not found at: " + Config.CREDENTIALS_FILE_PATH + 
                    ". Please download credentials.json from Google Cloud Console and place it in the drive-sync-automation directory.");
        }

        logger.info("Loading Google API client secrets from: {}", Config.CREDENTIALS_FILE_PATH);
        InputStream in = new FileInputStream(credentialsFile);
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Create data store factory for storing credentials refresh token
        File tokenDir = new File(Config.TOKENS_DIRECTORY_PATH);
        if (!tokenDir.exists()) {
            tokenDir.mkdirs();
        }
        FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(tokenDir);

        // Set up authorization code flow
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build();

        // LocalServerReceiver starts a local server to handle redirect automatically
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        return new Drive.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(Config.APPLICATION_NAME)
                .build();
    }

    /**
     * Finds a folder's Google Drive ID by name.
     */
    public String findFolderIdByName(String folderName, String parentId) throws IOException {
        String query = "name = '" + folderName + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        if (parentId != null) {
            query += " and '" + parentId + "' in parents";
        }
        
        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();
        
        List<com.google.api.services.drive.model.File> files = result.getFiles();
        if (files == null || files.isEmpty()) {
            return null;
        }
        return files.get(0).getId();
    }

    /**
     * Creates a new folder on Google Drive.
     */
    public String createFolder(String folderName, String parentId) throws IOException {
        com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
        fileMetadata.setName(folderName);
        fileMetadata.setMimeType("application/vnd.google-apps.folder");
        
        if (parentId != null) {
            fileMetadata.setParents(Collections.singletonList(parentId));
        }

        com.google.api.services.drive.model.File folder = driveService.files().create(fileMetadata)
                .setFields("id")
                .execute();
        
        logger.info("Created folder '{}' on Drive with ID: {}", folderName, folder.getId());
        return folder.getId();
    }

    /**
     * Uploads a new file to Google Drive.
     */
    public com.google.api.services.drive.model.File uploadFile(String name, File localFile, String mimeType, String parentId) throws IOException {
        com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
        fileMetadata.setName(name);
        if (parentId != null) {
            fileMetadata.setParents(Collections.singletonList(parentId));
        }

        FileContent mediaContent = new FileContent(mimeType, localFile);
        com.google.api.services.drive.model.File driveFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, md5Checksum")
                .execute();

        logger.info("Uploaded file '{}' with Drive ID: {}", name, driveFile.getId());
        return driveFile;
    }

    /**
     * Updates an existing file's contents on Google Drive.
     */
    public com.google.api.services.drive.model.File updateFile(String fileId, File localFile, String mimeType) throws IOException {
        FileContent mediaContent = new FileContent(mimeType, localFile);
        com.google.api.services.drive.model.File driveFile = driveService.files().update(fileId, null, mediaContent)
                .setFields("id, md5Checksum")
                .execute();

        logger.info("Updated file with Drive ID: {}", driveFile.getId());
        return driveFile;
    }

    /**
     * Moves a file/folder to the Google Drive Trash.
     */
    public void trashFile(String fileId) throws IOException {
        com.google.api.services.drive.model.File fileMetadata = new com.google.api.services.drive.model.File();
        fileMetadata.setTrashed(true);
        
        driveService.files().update(fileId, fileMetadata).execute();
        logger.info("Moved file/folder with ID {} to Google Drive Trash", fileId);
    }
}
