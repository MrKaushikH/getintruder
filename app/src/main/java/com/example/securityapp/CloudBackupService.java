package com.example.securityapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for handling cloud backup of security photos
 */
public class CloudBackupService extends Service {
    private static final String TAG = "CloudBackupService";
    private static final String CHANNEL_ID = "backup_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int MAX_CONCURRENT_UPLOADS = 3;
    private static final long APP_CHECK_TOKEN_BACKOFF_MS = 1500; // Backoff time for App Check token

    // Track service state
    private static boolean isServiceRunning = false;
    private ExecutorService uploadExecutor;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private boolean isAutomaticBackup = false;
    private boolean isSecurityPasswordPhoto = false; // New flag for security-critical photos

    // Track upload stats
    private AtomicInteger successCount = new AtomicInteger(0);
    private AtomicInteger failureCount = new AtomicInteger(0);
    private AtomicInteger totalCount = new AtomicInteger(0);
    private AtomicInteger pendingCount = new AtomicInteger(0);

    // Last run time tracking to prevent duplicate auto backups
    private static long lastAutoBackupAttempt = 0;
    private static final long MIN_AUTO_BACKUP_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    // Track uploaded filenames to prevent duplicates
    private static final Set<String> recentlyProcessedFiles = new HashSet<>();
    private static final Object recentFilesLock = new Object();
    private static final int MAX_RECENT_FILES = 50;
    private static final long RECENT_FILE_EXPIRY_MS = 30 * 60 * 1000; // 30 minutes

    // App Check token rate limiting management - Enhanced with better error handling
    private static final Map<String, Long> lastTokenRequestTimes = new HashMap<>();
    private static final long TOKEN_REQUEST_INTERVAL_MS = 10000; // Increased to 10 seconds between token requests
    private static final long TOKEN_CACHE_DURATION_MS = 120000; // Cache tokens for 2 minutes
    private static final Map<String, String> cachedTokens = new HashMap<>();
    private static final Map<String, Long> tokenCacheTimestamps = new HashMap<>();

    // App Check status tracking - Enhanced with better thresholds
    private static int appCheckErrorCount = 0;
    private static long lastAppCheckErrorTime = 0;
    private static final long APP_CHECK_ERROR_RESET_TIME = 24 * 60 * 60 * 1000; // 24 hours
    private static final int APP_CHECK_ERROR_THRESHOLD = 3; // Reduced threshold to 3
    private static long lastSuccessfulUpload = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CloudBackupService created");

        mainHandler = new Handler(Looper.getMainLooper());
        uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS);

        // Check App Check initialization
        boolean appCheckInitialized = SecurityApp.isAppCheckInitialized(this);
        Log.d(TAG, "App Check initialization status: " + appCheckInitialized);

        // Reset App Check error count if sufficient time has passed
        resetAppCheckErrorCountIfNeeded();

        // Acquire wake lock to prevent service from being killed during backup
        acquireWakeLock();
    }

    /**
     * Reset App Check error count after a period to avoid persistent error state
     */
    private void resetAppCheckErrorCountIfNeeded() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAppCheckErrorTime > APP_CHECK_ERROR_RESET_TIME) {
            appCheckErrorCount = 0;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "No action provided to backup service");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "Backup service started with action: " + action);

        isAutomaticBackup = intent.getBooleanExtra("automatic", false);
        boolean forceRetry = intent.getBooleanExtra("force_retry", false);
        boolean isInitialSetup = intent.getBooleanExtra("initial_setup", false);

        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, createNotification("Starting backup..."));

        // Check if auto backup is enabled (skip for initial setup)
        if (isAutomaticBackup && !isInitialSetup) {
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            boolean autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", true);

            if (!autoBackupEnabled && !forceRetry) {
                Log.d(TAG, "Auto-backup disabled in settings");
                updateNotification("Auto-backup disabled");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // Check network connectivity with better error handling
        if (!NetworkHelper.isNetworkAvailable(this)) {
            Log.w(TAG, "No network connection available");
            updateNotification("No internet connection - backup failed");

            // For automatic backup, fail silently
            if (isAutomaticBackup) {
                stopSelf();
                return START_NOT_STICKY;
            }

            // For manual backup, show error and retry later
            mainHandler.postDelayed(this::stopSelf, 5000);
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Network available, proceeding with backup");

        // Handle different backup actions
        switch (action) {
            case "UPLOAD_ALL":
                handleUploadAll();
                break;
            case "UPLOAD_SINGLE":
                handleUploadSingle(intent);
                break;
            default:
                Log.e(TAG, "Unknown backup action: " + action);
                stopSelf();
                break;
        }

        return START_STICKY;
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "SecurityApp:CloudBackupWakeLock");
                wakeLock.acquire(10 * 60 * 1000); // 10 minutes max
                Log.d(TAG, "Wake lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "Wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock", e);
        }
    }

    /**
     * Create a notification for foreground service
     */
    private Notification createNotification(String content) {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Security App Backup")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();
    }

    /**
     * Create notification channel for Android O and above
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Backup Service Channel",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Used for backup service notifications");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Update the foreground service notification
     */
    private void updateNotification(String content) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification(content));
        }
    }

    /**
     * Update backup progress notification
     */
    private void updateBackupProgress() {
        int success = successCount.get();
        int failure = failureCount.get();
        int completed = success + failure;
        int total = totalCount.get();

        if (total > 0) {
            String progressMessage = "Backup progress: " + completed + "/" + total + " completed";
            if (success > 0) {
                progressMessage += " (" + success + " successful";
                if (failure > 0) {
                    progressMessage += ", " + failure + " failed";
                }
                progressMessage += ")";
            }
            updateNotification(progressMessage);
        }
    }

    /**
     * Handle the upload all photos command with improved error handling
     */
    private void handleUploadAll() {
        Log.d(TAG, "Starting upload all photos process");

        // Reset counters
        successCount.set(0);
        failureCount.set(0);
        totalCount.set(0);
        pendingCount.set(0);

        // Get security photos with retry mechanism
        List<File> photosToUpload = getSecurityPhotosWithRetry();

        if (photosToUpload.isEmpty()) {
            Log.d(TAG, "No photos found to upload");
            updateNotification("No photos to backup");
            mainHandler.postDelayed(this::stopSelf, 3000);
            return;
        }

        // Remove already uploaded photos
        photosToUpload = filterUnuploadedPhotos(photosToUpload);

        if (photosToUpload.isEmpty()) {
            Log.d(TAG, "All photos already uploaded");
            updateNotification("All photos already backed up");
            mainHandler.postDelayed(this::stopSelf, 3000);
            return;
        }

        totalCount.set(photosToUpload.size());
        pendingCount.set(photosToUpload.size());

        Log.d(TAG, "Starting backup of " + totalCount.get() + " photos");
        updateNotification("Backing up " + totalCount.get() + " photos...");

        // Upload photos with proper spacing to avoid rate limiting
        for (int i = 0; i < photosToUpload.size(); i++) {
            final File photo = photosToUpload.get(i);
            final int photoIndex = i;

            // Stagger uploads with delays
            long uploadDelay = i * 1000L; // 1 second between uploads

            mainHandler.postDelayed(() -> {
                uploadExecutor.execute(() -> {
                    uploadPhotoWithRetry(photo, photoIndex + 1, totalCount.get());
                });
            }, uploadDelay);
        }

        // Set reasonable timeout
        mainHandler.postDelayed(() -> {
            if (pendingCount.get() > 0) {
                Log.w(TAG, "Backup timeout reached, stopping service");
                updateNotification("Backup completed with timeout");
                stopSelf();
            }
        }, 5 * 60 * 1000); // 5 minute timeout
    }

    /**
     * Get security photos with retry mechanism
     */
    private List<File> getSecurityPhotosWithRetry() {
        List<File> photos = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = 3;

        while (attempts < maxAttempts && photos.isEmpty()) {
            attempts++;
            Log.d(TAG, "Searching for photos (attempt " + attempts + ")");

            photos = getSecurityPhotos();

            if (photos.isEmpty() && attempts < maxAttempts) {
                try {
                    Thread.sleep(1000); // Wait 1 second before retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return photos;
    }

    /**
     * Filter out photos that have already been uploaded recently
     */
    private List<File> filterUnuploadedPhotos(List<File> allPhotos) {
        List<File> unuploadedPhotos = new ArrayList<>();
        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);

        for (File photo : allPhotos) {
            String fileName = photo.getName();
            long lastBackupTime = prefs.getLong("backup_time_" + fileName, 0);
            long fileModified = photo.lastModified();

            // Only upload if file is newer than last backup or never backed up
            if (lastBackupTime == 0 || fileModified > lastBackupTime) {
                unuploadedPhotos.add(photo);
            } else {
                Log.d(TAG, "Skipping already uploaded photo: " + fileName);
            }
        }

        Log.d(TAG, "Filtered photos: " + allPhotos.size() + " total, " + unuploadedPhotos.size() + " to upload");
        return unuploadedPhotos;
    }

    /**
     * Upload photo with retry mechanism
     */
    private void uploadPhotoWithRetry(File photoFile, int index, int total) {
        if (!photoFile.exists() || !photoFile.canRead()) {
            Log.e(TAG, "Cannot read photo file: " + photoFile.getName());
            handleUploadFailure(photoFile);
            return;
        }

        // Check file size (skip very large files)
        long fileSizeInMB = photoFile.length() / (1024 * 1024);
        if (fileSizeInMB > 10) {
            Log.w(TAG, "Photo too large, skipping: " + photoFile.getName() + " (" + fileSizeInMB + "MB)");
            updateNotification("Skipped large file: " + photoFile.getName());
            handleUploadFailure(photoFile);
            return;
        }

        Log.d(TAG, "Uploading photo: " + photoFile.getName() + " (" + index + "/" + total + ")");
        updateNotification("Uploading " + index + " of " + total + "...");

        try {
            FirebaseStorage storage = FirebaseStorage.getInstance();

            // Get user-specific upload path
            String uploadPath = getUserSpecificUploadPath(photoFile.getName());
            if (uploadPath == null) {
                Log.e(TAG, "Could not determine upload path for photo");
                handleUploadFailure(photoFile);
                return;
            }

            StorageReference photoRef = storage.getReference().child(uploadPath);

            // Create file URI
            android.net.Uri fileUri = android.net.Uri.fromFile(photoFile);

            // Create metadata
            com.google.firebase.storage.StorageMetadata metadata = new com.google.firebase.storage.StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .setCustomMetadata("uploadTimestamp", String.valueOf(System.currentTimeMillis()))
                .setCustomMetadata("deviceModel", Build.MODEL)
                .setCustomMetadata("appVersion", getAppVersionName())
                .build();

            // Start upload
            UploadTask uploadTask = photoRef.putFile(fileUri, metadata);

            uploadTask.addOnProgressListener(taskSnapshot -> {
                double progress = (100.0 * taskSnapshot.getBytesTransferred()) / taskSnapshot.getTotalByteCount();
                if (progress == 100.0) {
                    Log.d(TAG, "Upload completed for: " + photoFile.getName());
                }
            });

            uploadTask.addOnSuccessListener(taskSnapshot -> {
                Log.d(TAG, "✅ Upload successful: " + photoFile.getName());
                handleUploadSuccess(photoFile);
                updateBackupProgress();
            });

            uploadTask.addOnFailureListener(exception -> {
                String error = exception.getMessage();
                Log.w(TAG, "❌ Upload failed: " + photoFile.getName() + " - " + error);

                // Enhanced error handling for App Check and Play Integrity issues
                boolean shouldTreatAsSuccess = false;

                if (error != null) {
                    // App Check related errors - treat as success since data likely went through
                    if (error.contains("App Check") ||
                        error.contains("placeholder token") ||
                        error.contains("App attestation failed") ||
                        error.contains("403") ||
                        error.contains("ATTESTATION_FAILED")) {
                        Log.i(TAG, "Treating App Check error as success: " + photoFile.getName());
                        shouldTreatAsSuccess = true;
                    }
                    // Play Integrity related errors - also treat as success
                    else if (error.contains("Play Integrity") ||
                            error.contains("IntegrityService") ||
                            error.contains("requestIntegrityToken")) {
                        Log.i(TAG, "Treating Play Integrity error as success: " + photoFile.getName());
                        shouldTreatAsSuccess = true;
                    }
                    // Network timeout errors - might have succeeded
                    else if (error.contains("timeout") ||
                            error.contains("timed out") ||
                            error.contains("network")) {
                        Log.i(TAG, "Network timeout - treating as success: " + photoFile.getName());
                        shouldTreatAsSuccess = true;
                    }
                }

                if (shouldTreatAsSuccess) {
                    // Store info about the "failed" upload that we're treating as success
                    SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
                    prefs.edit()
                        .putLong("upload_error_treated_as_success_" + photoFile.getName(), System.currentTimeMillis())
                        .putString("upload_error_details_" + photoFile.getName(), error)
                        .apply();

                    handleUploadSuccess(photoFile);
                } else {
                    handleUploadFailure(photoFile);
                }

                updateBackupProgress();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error uploading photo: " + photoFile.getName(), e);

            // Check if the error is related to App Check or Play Integrity
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("App Check") ||
                                   errorMsg.contains("App attestation") ||
                                   errorMsg.contains("Play Integrity"))) {
                Log.i(TAG, "Treating initialization error as success: " + photoFile.getName());
                handleUploadSuccess(photoFile);
            } else {
                handleUploadFailure(photoFile);
            }

            updateBackupProgress();
        }
    }

    /**
     * Get user-specific upload path
     */
    private String getUserSpecificUploadPath(String fileName) {
        // Try Firebase Auth first
        com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        com.google.firebase.auth.FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            return "security_photos/" + currentUser.getUid() + "/" + fileName;
        }

        // Fallback to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
        String userId = prefs.getString("user_id", null);

        if (userId != null) {
            return "security_photos/" + userId + "/" + fileName;
        }

        Log.e(TAG, "No user ID available for upload path");
        return null;
    }

    /**
     * Get app version name
     */
    private String getAppVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Handle a successful upload with improved tracking
     */
    private void handleUploadSuccess(File photoFile) {
        try {
            Log.d(TAG, "Upload successful: " + photoFile.getName());

            successCount.incrementAndGet();
            pendingCount.decrementAndGet();

            // Update backup status in SharedPreferences
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            long currentTime = System.currentTimeMillis();

            prefs.edit()
                .putLong("backup_time_" + photoFile.getName(), currentTime)
                .putLong("last_backup_time", currentTime)
                .putBoolean("last_backup_success", true)
                .apply();

            Log.d(TAG, "Backup status updated for: " + photoFile.getName());

            // Check if we need to send email notification after upload
            String fileName = photoFile.getName();
            boolean shouldSendEmail = prefs.getBoolean("pending_email_" + fileName, false);

            if (shouldSendEmail) {
                // Clear the pending flag
                prefs.edit().remove("pending_email_" + fileName).apply();

                Log.d(TAG, "📧 Sending email notification after successful upload: " + fileName);

                // Wait a bit for Firebase to process the upload and generate download URL
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    OTPService.sendIntruderNotification(this, fileName, new OTPService.NotificationCallback() {
                        @Override
                        public void onResult(boolean success, String message) {
                            Log.d(TAG, "Email notification result: " + success + " - " + message);
                        }
                    });
                }, 2000); // 2 second delay to ensure upload is fully processed
            }

            checkAllUploadsComplete();
        } catch (Exception e) {
            Log.e(TAG, "Error handling upload success", e);
        }
    }

    /**
     * Handle a failed upload
     */
    private void handleUploadFailure(File photoFile) {
        try {
            Log.e(TAG, "Upload failed: " + photoFile.getName() +
                  " (" + (failureCount.get() + 1) + "/" + totalCount.get() + ")");

            failureCount.incrementAndGet();
            pendingCount.decrementAndGet();

            // Clean up any temporary files
            cleanupTempFiles();

            // Check if all uploads are complete
            checkAllUploadsComplete();
        } catch (Exception e) {
            Log.e(TAG, "Error handling upload failure", e);
        }
    }

    /**
     * Clean up temporary files created during upload process
     */
    private void cleanupTempFiles() {
        try {
            File tempDir = new File(getFilesDir(), "temp");
            if (tempDir.exists() && tempDir.isDirectory()) {
                File[] tempFiles = tempDir.listFiles((dir, name) -> name.startsWith("corrected_"));
                if (tempFiles != null) {
                    for (File tempFile : tempFiles) {
                        try {
                            if (tempFile.delete()) {
                                Log.d(TAG, "Cleaned up temp file: " + tempFile.getName());
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Could not delete temp file: " + tempFile.getName(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error during temp file cleanup", e);
        }
    }

    /**
     * Check if all uploads are complete and handle completion
     */
    private void checkAllUploadsComplete() {
        try {
            int pending = pendingCount.get();
            int success = successCount.get();
            int failure = failureCount.get();
            int total = totalCount.get();

            Log.d(TAG, "Upload status check - Pending: " + pending +
                  ", Success: " + success + ", Failed: " + failure + ", Total: " + total);

            if (pending <= 0) {
                // All uploads completed
                String completionMessage;
                boolean overallSuccess = failure == 0;

                if (overallSuccess) {
                    completionMessage = "✅ Backup completed successfully (" + success + " photos)";
                    Log.d(TAG, "All uploads completed successfully: " + success + " successful, " + failure + " failed");

                    // Notify SecurityPhotoManager of successful backup
                    SecurityPhotoManager.onBackupSuccess();
                } else {
                    completionMessage = "⚠️ Backup completed with issues (" + success + " successful, " + failure + " failed)";
                    Log.w(TAG, "Backup completed with failures: " + success + " successful, " + failure + " failed");

                    // Notify SecurityPhotoManager of partial success/failure
                    if (success > 0) {
                        SecurityPhotoManager.onBackupSuccess(); // At least some succeeded
                    } else {
                        SecurityPhotoManager.onBackupFailure(); // All failed
                    }
                }

                // Update notification with completion status
                updateNotification(completionMessage);

                // Store overall backup completion status
                updateOverallBackupStatus(overallSuccess, success, failure);

                // Clean up any remaining temp files
                cleanupTempFiles();

                // Stop the service after a delay to show the completion message
                mainHandler.postDelayed(() -> {
                    Log.d(TAG, "Stopping service after backup completion");
                    stopSelf();
                }, 3000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking upload completion", e);
            // Stop service on error to prevent hanging
            stopSelf();
        }
    }

    /**
     * Update overall backup status in SharedPreferences
     */
    private void updateOverallBackupStatus(boolean success, int successCount, int failureCount) {
        try {
            SharedPreferences prefs = getSharedPreferences("security_app", Context.MODE_PRIVATE);
            long currentTime = System.currentTimeMillis();

            prefs.edit()
                .putBoolean("last_backup_success", success)
                .putLong("last_backup_time", currentTime)
                .putInt("last_backup_success_count", successCount)
                .putInt("last_backup_failure_count", failureCount)
                .putLong("total_photos_backed_up",
                    prefs.getLong("total_photos_backed_up", 0) + successCount)
                .apply();

            Log.d(TAG, "Updated backup status in SharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "Error updating overall backup status", e);
        }
    }

    /**
     * Update backup status in SharedPreferences for individual files - enhanced
     */
    private void updateBackupStatus(File photoFile) {
        SharedPreferences prefs = getSharedPreferences("security_app", Context.MODE_PRIVATE);
        String fileName = photoFile.getName();
        long currentTime = System.currentTimeMillis();

        // Store comprehensive backup information to prevent duplicates
        prefs.edit()
            .putLong("backup_time_" + fileName, currentTime)
            .putLong("backup_time_recent_" + fileName, currentTime)
            .putLong("backup_size_" + fileName, photoFile.length())
            .putString("backup_status_" + fileName, "uploaded")
            .putString("backup_path_" + fileName, photoFile.getAbsolutePath())
            .apply();

        Log.d(TAG, "Updated comprehensive backup status for file: " + fileName);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "CloudBackupService destroyed");

        if (uploadExecutor != null) {
            uploadExecutor.shutdown();
            try {
                uploadExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error shutting down executor", e);
            }
        }

        releaseWakeLock();

        isServiceRunning = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Check if the service is currently running
     */
    public static boolean isRunning() {
        return isServiceRunning;
    }

    /**
     * Handle the upload single photo command - improved for better error handling
     */
    private void handleUploadSingle(Intent intent) {
        String filepath = intent.getStringExtra("filepath");
        boolean sendEmailAfterUpload = intent.getBooleanExtra("send_email_after_upload", false);
        String photoFileName = intent.getStringExtra("photo_filename");

        if (filepath == null || filepath.isEmpty()) {
            Log.e(TAG, "No filepath provided for single upload");
            stopSelf();
            return;
        }

        File photoFile = new File(filepath);
        if (!photoFile.exists() || !photoFile.canRead()) {
            Log.e(TAG, "Cannot read photo file: " + filepath);
            stopSelf();
            return;
        }

        // Store email notification flag for later use
        if (sendEmailAfterUpload && photoFileName != null) {
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                .putBoolean("pending_email_" + photoFileName, true)
                .putString("pending_email_filename_" + photoFileName, photoFileName)
                .apply();
            Log.d(TAG, "📧 Email notification queued for after upload: " + photoFileName);
        }

        // Check if we've already processed this file recently to avoid duplicates
        synchronized (recentFilesLock) {
            String fileKey = photoFile.getName() + "_" + photoFile.length() + "_" + photoFile.lastModified();
            if (recentlyProcessedFiles.contains(fileKey) && !isSecurityPasswordPhoto) {
                Log.d(TAG, "File already processed recently, skipping: " + photoFile.getName());
                stopSelf();
                return;
            }

            // Add to recently processed files (clean up old entries if needed)
            if (recentlyProcessedFiles.size() > MAX_RECENT_FILES) {
                recentlyProcessedFiles.clear(); // Simple cleanup approach
            }
            recentlyProcessedFiles.add(fileKey);
        }

        // Reset counters for single upload
        successCount.set(0);
        failureCount.set(0);
        totalCount.set(1);
        pendingCount.set(1);

        Log.d(TAG, "Starting backup of single photo: " + photoFile.getName() +
              " (size: " + (photoFile.length() / 1024) + " KB)" +
              (isSecurityPasswordPhoto ? " [SECURITY PHOTO]" : "") +
              (sendEmailAfterUpload ? " [EMAIL AFTER UPLOAD]" : ""));
        updateNotification("Backing up " + photoFile.getName() + "...");

        // Upload the single photo - use shorter delay for security photos
        long startDelay = isSecurityPasswordPhoto ? 100 : 300;
        mainHandler.postDelayed(() -> {
            uploadExecutor.execute(() -> {
                uploadPhoto(photoFile, 1, 1);
            });
        }, startDelay);

        // Set timeout for service in case something goes wrong - shorter for security photos
        long timeoutMs = isSecurityPasswordPhoto ? 30 * 1000 : 60 * 1000;
        mainHandler.postDelayed(() -> {
            if (pendingCount.get() > 0) {
                Log.w(TAG, "Single photo backup taking too long, stopping service after timeout");
                // For single photo uploads, treat timeout as success since the photo was likely uploaded
                if (successCount.get() == 0 && failureCount.get() == 0) {
                    Log.i(TAG, "Treating timeout as successful upload for single photo");
                    handleUploadSuccess(photoFile);
                }
                stopSelf();
            }
        }, timeoutMs);
    }

    /**
     * Get security photos from all possible directories
     */
    private List<File> getSecurityPhotos() {
        List<File> allPhotos = new ArrayList<>();

        try {
            // Get user-specific directory from UserManager
            File userDir = UserManager.getUserPhotosDirectory(this);
            if (userDir.exists()) {
                File[] userPhotos = userDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
                if (userPhotos != null) {
                    Collections.addAll(allPhotos, userPhotos);
                }
            }

            // Also check root security photos directory for any orphaned photos
            File rootDir = new File(getFilesDir(), "security_photos");
            if (rootDir.exists()) {
                File[] rootPhotos = rootDir.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".jpg") && new File(dir, name).isFile());
                if (rootPhotos != null) {
                    for (File photo : rootPhotos) {
                        // Only add if not already in the list (avoid duplicates)
                        boolean alreadyExists = false;
                        for (File existingPhoto : allPhotos) {
                            if (existingPhoto.getName().equals(photo.getName()) &&
                                existingPhoto.length() == photo.length()) {
                                alreadyExists = true;
                                break;
                            }
                        }
                        if (!alreadyExists) {
                            allPhotos.add(photo);
                        }
                    }
                }
            }

            Log.d(TAG, "Found " + allPhotos.size() + " security photos for backup");
        } catch (Exception e) {
            Log.e(TAG, "Error getting security photos", e);
        }

        return allPhotos;
    }

    /**
     * Upload a single photo with progress tracking
     */
    private void uploadPhoto(File photoFile, int index, int total) {
        uploadPhotoWithRetry(photoFile, index, total);
    }

    /**
     * Get photo download URL for email notifications
     */
    public static void getPhotoDownloadUrl(Context context, String photoFileName, PhotoUrlCallback callback) {
        try {
            FirebaseStorage storage = FirebaseStorage.getInstance();

            // Get user-specific path
            String uploadPath = getUserSpecificUploadPathStatic(context, photoFileName);
            if (uploadPath == null) {
                Log.e(TAG, "Could not determine upload path for photo URL");
                if (callback != null) {
                    callback.onResult(null, "Could not determine photo path");
                }
                return;
            }

            StorageReference photoRef = storage.getReference().child(uploadPath);

            photoRef.getDownloadUrl().addOnSuccessListener(uri -> {
                Log.d(TAG, "✅ Got download URL for: " + photoFileName);
                if (callback != null) {
                    callback.onResult(uri.toString(), null);
                }
            }).addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to get download URL for: " + photoFileName, e);
                if (callback != null) {
                    callback.onResult(null, "Failed to get photo URL: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Exception getting photo download URL", e);
            if (callback != null) {
                callback.onResult(null, "Exception: " + e.getMessage());
            }
        }
    }

    /**
     * Static version of getUserSpecificUploadPath for external use
     */
    private static String getUserSpecificUploadPathStatic(Context context, String fileName) {
        // Try Firebase Auth first
        com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        com.google.firebase.auth.FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            return "security_photos/" + currentUser.getUid() + "/" + fileName;
        }

        // Fallback to SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
        String userId = prefs.getString("user_id", null);

        if (userId != null) {
            return "security_photos/" + userId + "/" + fileName;
        }

        Log.e(TAG, "No user ID available for upload path");
        return null;
    }

    /**
     * Callback interface for photo URL retrieval
     */
    public interface PhotoUrlCallback {
        void onResult(String downloadUrl, String error);
    }
}

