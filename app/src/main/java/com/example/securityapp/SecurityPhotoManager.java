package com.example.securityapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager class for security photos, including automatic backup scheduling
 */
public class SecurityPhotoManager {
    private static final String TAG = "SecurityPhotoManager";
    private static final long AUTO_BACKUP_DELAY_MS = 60 * 1000; // 1 minute after photo capture
    private static final long MAX_PENDING_PHOTOS = 3; // Reduced threshold to trigger backup sooner
    private static final long FILE_READINESS_DELAY = 1000; // 1 second to ensure file is ready

    private static Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingBackupTask = null;

    // Track when immediate backup was last triggered to prevent race conditions
    private static long lastImmediateBackupTime = 0;
    private static final long IMMEDIATE_BACKUP_COOLDOWN = 10000; // 10 seconds cooldown

    // Track backup failures to implement retry
    private static int backupFailureCount = 0;
    private static long lastBackupAttemptTime = 0;
    private static final int MAX_AUTO_RETRY_COUNT = 3;
    private static final long[] RETRY_DELAYS = {30000, 60000, 120000}; // 30s, 1m, 2m

    /**
     * Notify that a new security photo has been captured
     * This will schedule an automatic backup if enabled
     */
    public static void notifyNewPhoto(Context context, String photoFileName) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            boolean autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", true);

            if (!autoBackupEnabled) {
                Log.d(TAG, "Auto-backup is disabled in settings");
                return;
            }

            // If immediate backup was triggered very recently by CameraService, don't schedule another one
            // to avoid race conditions
            if (System.currentTimeMillis() - lastImmediateBackupTime < IMMEDIATE_BACKUP_COOLDOWN) {
                Log.d(TAG, "Skipping backup scheduling - immediate backup was triggered recently");
                return;
            }

            // Cancel any pending backup task
            if (pendingBackupTask != null) {
                handler.removeCallbacks(pendingBackupTask);
                Log.d(TAG, "Removed previous pending backup task");
            }

            // Check number of pending photos
            int pendingCount = countPendingPhotos(context);
            Log.d(TAG, "Pending photos for backup: " + pendingCount);

            // If enough photos are pending, trigger backup after a short delay to ensure files are ready
            if (pendingCount >= MAX_PENDING_PHOTOS) {
                Log.d(TAG, "Max pending photos reached (" + pendingCount + "), triggering backup soon");

                pendingBackupTask = () -> {
                    Log.d(TAG, "Executing backup due to max pending photos threshold");
                    triggerAutomaticBackup(context);
                };

                handler.postDelayed(pendingBackupTask, FILE_READINESS_DELAY);
                return;
            }

            // Otherwise, schedule backup with normal delay
            pendingBackupTask = () -> {
                Log.d(TAG, "Scheduled automatic backup triggered");
                triggerAutomaticBackup(context);
            };

            handler.postDelayed(pendingBackupTask, AUTO_BACKUP_DELAY_MS);
            Log.d(TAG, "Scheduled automatic backup in " + (AUTO_BACKUP_DELAY_MS / 1000) + " seconds");

        } catch (Exception e) {
            Log.e(TAG, "Error scheduling automatic backup", e);

            // Schedule a retry after a delay if there was an error
            scheduleBackupRetry(context);
        }
    }

    /**
     * Schedule a retry for backup if previous attempt failed
     */
    private static void scheduleBackupRetry(Context context) {
        if (backupFailureCount >= MAX_AUTO_RETRY_COUNT) {
            Log.e(TAG, "Maximum retry count reached (" + MAX_AUTO_RETRY_COUNT + "), giving up automatic retries");
            backupFailureCount = 0; // Reset for next time
            return;
        }

        // Determine delay based on failure count (with bounds check)
        long delay = backupFailureCount < RETRY_DELAYS.length ?
                     RETRY_DELAYS[backupFailureCount] :
                     RETRY_DELAYS[RETRY_DELAYS.length - 1];

        backupFailureCount++;

        Log.d(TAG, "Scheduling backup retry #" + backupFailureCount + " in " + (delay/1000) + " seconds");

        // Create a new retry task
        handler.postDelayed(() -> {
            Log.d(TAG, "Executing backup retry #" + backupFailureCount);
            triggerAutomaticBackup(context);
        }, delay);
    }

    /**
     * Count the number of photos that need to be backed up
     */
    private static int countPendingPhotos(Context context) {
        List<File> pendingPhotos = getPendingBackupPhotos(context);
        return pendingPhotos.size();
    }

    /**
     * Get list of photos that haven't been backed up yet
     */
    private static List<File> getPendingBackupPhotos(Context context) {
        List<File> pendingPhotos = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);

        try {
            File photosDir = new File(context.getFilesDir(), "security_photos");
            if (!photosDir.exists() || !photosDir.isDirectory()) {
                return pendingPhotos;
            }

            File[] photos = photosDir.listFiles((dir, name) -> name.endsWith(".jpg"));
            if (photos != null) {
                // Check each photo if it needs backup
                for (File photo : photos) {
                    String fileName = photo.getName();
                    long backupTime = prefs.getLong("backup_time_" + fileName, 0);

                    // If never backed up or backup older than file modification
                    if (backupTime == 0 || backupTime < photo.lastModified()) {
                        pendingPhotos.add(photo);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking pending photos", e);
        }

        return pendingPhotos;
    }

    /**
     * Trigger automatic backup service
     */
    public static void triggerAutomaticBackup(Context context) {
        try {
            lastBackupAttemptTime = System.currentTimeMillis();

            // Check if we have photos to backup
            List<File> pendingPhotos = getPendingBackupPhotos(context);
            if (pendingPhotos.isEmpty()) {
                Log.d(TAG, "No pending photos to backup");
                backupFailureCount = 0; // Reset failure count as there's nothing to back up
                return;
            }

            // Check if backup service is already running
            if (CloudBackupService.isRunning()) {
                Log.d(TAG, "Backup service is already running");
                return;
            }

            // Check network connectivity
            if (!NetworkHelper.isNetworkAvailable(context)) {
                Log.d(TAG, "Network not available for automatic backup - will retry later");
                scheduleBackupRetry(context);
                return;
            }

            // Check if we're on WiFi (if required)
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            boolean wifiOnlyBackup = prefs.getBoolean("wifi_only_backup", false);
            boolean autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", true);

            // Double check that auto-backup is still enabled
            if (!autoBackupEnabled) {
                Log.d(TAG, "Auto-backup has been disabled in settings, canceling backup");
                return;
            }

            if (wifiOnlyBackup && !NetworkHelper.isWifiConnection(context)) {
                Log.d(TAG, "WiFi-only setting enabled but not on WiFi, will retry later");
                scheduleBackupRetry(context);
                return;
            }

            Log.d(TAG, "Triggering automatic backup for " + pendingPhotos.size() + " photos");
            lastImmediateBackupTime = System.currentTimeMillis(); // Mark that we triggered an immediate backup

            Intent backupIntent = new Intent(context, CloudBackupService.class);
            backupIntent.setAction("UPLOAD_ALL");
            backupIntent.putExtra("automatic", true);
            backupIntent.putExtra("force_retry", backupFailureCount > 0); // Flag if this is a retry attempt

            try {
                // For Android 8+, start as foreground service
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(backupIntent);
                } else {
                    context.startService(backupIntent);
                }

                Log.d(TAG, "Automatic backup service started");
                // We'll reset failure count if the upload succeeds
            } catch (Exception e) {
                Log.e(TAG, "Error starting backup service", e);
                scheduleBackupRetry(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering automatic backup", e);
            scheduleBackupRetry(context);
        }
    }

    /**
     * Reset backup failure count (called when backup succeeds)
     */
    public static void onBackupSuccess() {
        backupFailureCount = 0;
        Log.d(TAG, "Backup completed successfully, reset failure count");
    }

    /**
     * Track backup failure (called when backup fails)
     */
    public static void onBackupFailure() {
        scheduleBackupRetry(null); // Just increment count and log, context not needed here
    }
}
