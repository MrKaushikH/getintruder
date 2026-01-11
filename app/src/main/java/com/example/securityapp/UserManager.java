package com.example.securityapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;

/**
 * Manages user-specific operations and storage
 */
public class UserManager {
    private static final String TAG = "UserManager";

    /**
     * Get user-specific photos directory with better error handling
     */
    public static File getUserPhotosDirectory(Context context) {
        File baseDir = new File(context.getFilesDir(), "security_photos");

        // Make sure base directory exists
        if (!baseDir.exists()) {
            boolean created = baseDir.mkdirs();
            Log.d(TAG, "Created base security photos directory: " + created + " at " + baseDir.getAbsolutePath());
        }

        // Try to get Firebase Auth user ID first
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            String userId = currentUser.getUid();
            // Create user-specific subdirectory using Firebase UID
            File userDir = new File(baseDir, userId);
            if (!userDir.exists()) {
                boolean created = userDir.mkdirs();
                Log.d(TAG, "✅ User-specific directory created: " + created + " at " + userDir.getAbsolutePath());
            }
            return userDir;
        } else {
            // Fallback for existing mechanism
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            String uniqueUserId = prefs.getString("unique_user_id", null);
            String userId = prefs.getString("user_id", uniqueUserId);

            if (userId != null) {
                // Create user-specific subdirectory
                File userDir = new File(baseDir, userId);
                if (!userDir.exists()) {
                    boolean created = userDir.mkdirs();
                    Log.d(TAG, "✅ User-specific directory created: " + created + " at " + userDir.getAbsolutePath());
                }
                return userDir;
            } else {
                // Cannot identify user, use base directory but ensure it exists
                Log.w(TAG, "⚠️ Could not identify user, using base security photos directory");
                if (!baseDir.exists()) {
                    baseDir.mkdirs();
                }
                return baseDir;
            }
        }
    }

    /**
     * Migrate existing photos to user-specific directory
     */
    private static void migrateExistingUserPhotos(Context context, File baseDir) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            String userName = prefs.getString("user_name", "unknown_user");
            String deviceId = android.provider.Settings.Secure.getString(
                    context.getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );

            // Generate unique ID for existing user
            String generatedId = userName.toLowerCase().replaceAll("\\s+", "_") + "_" +
                    deviceId.substring(0, Math.min(8, deviceId.length())) + "_migration";

            // Save the generated ID
            prefs.edit().putString("unique_user_id", generatedId).apply();

            // Create new user-specific directory
            File userDir = new File(baseDir, generatedId);
            if (!userDir.exists()) {
                userDir.mkdirs();
            }

            // Move existing photos to user-specific directory
            if (baseDir.exists()) {
                File[] existingPhotos = baseDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                if (existingPhotos != null && existingPhotos.length > 0) {
                    Log.d(TAG, "📦 Migrating " + existingPhotos.length + " existing photos to user-specific directory");

                    for (File photo : existingPhotos) {
                        // Only move if it's not already in a subdirectory
                        if (photo.getParent().equals(baseDir.getAbsolutePath())) {
                            File newLocation = new File(userDir, photo.getName());
                            if (photo.renameTo(newLocation)) {
                                Log.d(TAG, "✅ Migrated photo: " + photo.getName());
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "✅ Migration completed for user: " + userName + " (ID: " + generatedId + ")");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during photo migration", e);
        }
    }

    /**
     * Get current user info with username support
     */
    public static String getCurrentUserInfo(Context context) {
        // Try to get Firebase Auth user first
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            String userName = currentUser.getDisplayName() != null ? currentUser.getDisplayName() : "Unknown";
            String userEmail = currentUser.getEmail() != null ? currentUser.getEmail() : "No Email";
            String uniqueUserId = currentUser.getUid();
            long created = currentUser.getMetadata() != null ? currentUser.getMetadata().getCreationTimestamp() : 0;

            String createdText = created > 0 ?
                    new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                            .format(new java.util.Date(created)) : "Unknown";

            return "👤 Username: " + userName + "\n" +
                   "✉️ Email: " + userEmail + "\n" +
                   "🆔 ID: " + uniqueUserId.substring(0, Math.min(12, uniqueUserId.length())) + "...\n" +
                   "📅 Registered: " + createdText;
        } else {
            // Fallback to SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            String userName = prefs.getString("user_name", "Unknown");
            String uniqueUserId = prefs.getString("unique_user_id", "Not set");
            long created = prefs.getLong("user_created_timestamp", 0);

            String createdText = created > 0 ?
                    new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                            .format(new java.util.Date(created)) : "Unknown";

            return "👤 Username: " + userName + "\n" +
                   "🆔 ID: " + uniqueUserId.substring(0, Math.min(12, uniqueUserId.length())) + "...\n" +
                   "📅 Registered: " + createdText;
        }
    }

    /**
     * Get user photo statistics
     */
    public static String getUserPhotoStats(Context context) {
        File userPhotosDir = getUserPhotosDirectory(context);

        if (userPhotosDir.exists()) {
            File[] photos = userPhotosDir.listFiles((dir, name) -> name.endsWith(".jpg"));
            if (photos != null && photos.length > 0) {
                long totalSize = 0;
                File mostRecent = photos[0];

                for (File photo : photos) {
                    totalSize += photo.length();
                    if (photo.lastModified() > mostRecent.lastModified()) {
                        mostRecent = photo;
                    }
                }

                String lastCapture = new java.text.SimpleDateFormat("dd/MM/yy HH:mm",
                        java.util.Locale.getDefault()).format(new java.util.Date(mostRecent.lastModified()));

                long sizeKB = totalSize / 1024;

                return "📸 Photos: " + photos.length + " (" + sizeKB + " KB)\n" +
                        "🕒 Last: " + lastCapture + "\n" +
                        "📁 Path: " + userPhotosDir.getName().substring(0, Math.min(12, userPhotosDir.getName().length())) + "...";
            }
        }

        return "📸 Photos: 0\n🕒 Last: Never\n📁 Path: " + userPhotosDir.getName().substring(0, Math.min(12, userPhotosDir.getName().length())) + "...";
    }

    /**
     * Clean up user data (for app reset)
     */
    public static boolean cleanUserData(Context context) {
        try {
            File userPhotosDir = getUserPhotosDirectory(context);
            if (userPhotosDir.exists()) {
                File[] files = userPhotosDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                userPhotosDir.delete();
            }

            // Clear shared preferences but keep essential user info
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            String userName = prefs.getString("user_name", "");
            String uniqueUserId = prefs.getString("unique_user_id", "");

            prefs.edit().clear().apply();

            // Restore user identity
            prefs.edit()
                .putString("user_name", userName)
                .putString("unique_user_id", uniqueUserId)
                .putBoolean("first_launch", true) // This will trigger re-registration
                .apply();

            Log.d(TAG, "✅ User data cleaned successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error cleaning user data", e);
            return false;
        }
    }

    /**
     * Get user-specific Firebase storage path
     */
    public static String getFirebaseStoragePath(Context context) {
        return FirebaseHelper.getUserSpecificStoragePath(context);
    }

    /**
     * Check if current user is properly authenticated
     */
    public static boolean isUserProperlySetup(Context context) {
        // First check Firebase Auth
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();

        if (currentUser != null) {
            return true; // User is authenticated with Firebase
        }

        // Fallback to the previous method
        SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
        String uniqueUserId = prefs.getString("unique_user_id", null);
        String userName = prefs.getString("user_name", null);

        return uniqueUserId != null && !uniqueUserId.isEmpty() &&
               userName != null && !userName.isEmpty();
    }

    /**
     * Signs out current user
     */
    public static void signOut(Context context) {
        try {
            // Sign out from Firebase
            FirebaseAuth.getInstance().signOut();

            // Also sign out from Google
            try {
                com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
                        context,
                        new com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .build()
                ).signOut();
            } catch (Exception e) {
                Log.e(TAG, "Error signing out from Google", e);
            }

            // Redirect to Auth activity
            Intent intent = new Intent(context, AuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error signing out", e);
        }
    }

    /**
     * Upload a new security photo to Firebase
     */
    public static void uploadNewPhotoToFirebase(Context context, File photoFile) {
        if (photoFile == null || !photoFile.exists()) {
            Log.e(TAG, "Cannot upload non-existent photo");
            return;
        }

        // Check if auto-backup is enabled
        SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
        boolean autoBackupEnabled = prefs.getBoolean("auto_backup_enabled", true);

        if (autoBackupEnabled && NetworkHelper.isConnected()) {
            Log.d(TAG, "Starting upload for new photo: " + photoFile.getName());
            Intent backupIntent = new Intent(context, CloudBackupService.class);
            backupIntent.setAction("UPLOAD_SINGLE");
            backupIntent.putExtra("filepath", photoFile.getAbsolutePath());
            backupIntent.putExtra("automatic", true); // Mark as automatic backup

            try {
                // For Android 14+, we need to specify the foreground service type
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    backupIntent.putExtra("android.foregroundServiceType", android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(backupIntent);
                } else {
                    context.startService(backupIntent);
                }
                Log.d(TAG, "Photo upload service started successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error starting photo upload service: " + e.getMessage());
            }
        } else {
            if (!autoBackupEnabled) {
                Log.d(TAG, "Auto-backup disabled by user");
            } else {
                Log.d(TAG, "No network connection available for backup");
            }
        }
    }

    /**
     * Trigger manual backup of all photos
     */
    public static void triggerManualBackup(Context context) {
        Intent backupIntent = new Intent(context, CloudBackupService.class);
        backupIntent.setAction("UPLOAD_ALL");
        backupIntent.putExtra("automatic", false); // Mark as manual backup
        backupIntent.putExtra("force_retry", true); // Force retry even if recently attempted

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                backupIntent.putExtra("android.foregroundServiceType", android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(backupIntent);
            } else {
                context.startService(backupIntent);
            }
            Log.d(TAG, "Manual backup service started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting manual backup service: " + e.getMessage());
        }
    }

    /**
     * Save captured photo to the correct user directory
     */
    public static File savePhotoToUserDirectory(Context context, byte[] photoData, String fileName) {
        try {
            // Get user-specific directory
            File userDir = getUserPhotosDirectory(context);
            if (!userDir.exists()) {
                boolean created = userDir.mkdirs();
                Log.d(TAG, "Created user directory for photo save: " + created);
            }

            // Create file in user directory
            File photoFile = new File(userDir, fileName);

            // Write photo data to file
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(photoFile)) {
                fos.write(photoData);
                fos.flush();
            }

            Log.d(TAG, "✅ Saved photo directly to user directory: " + photoFile.getAbsolutePath());
            return photoFile;
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving photo to user directory", e);
            return null;
        }
    }
}
