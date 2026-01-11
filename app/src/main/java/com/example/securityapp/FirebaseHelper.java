package com.example.securityapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FirebaseHelper {
    private static final String TAG = "FirebaseHelper";
    private static final String APP_CHECK_TOKEN_KEY = "app_check_debug_token";
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Configure Firestore settings for optimal performance
     */
    public static void configureFirestore() {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build();
            db.setFirestoreSettings(settings);
            Log.d(TAG, "Firestore configured with persistence and unlimited cache");
        } catch (Exception e) {
            Log.e(TAG, "Error configuring Firestore", e);
        }
    }

    /**
     * Store App Check debug token in SharedPreferences for future app launches
     */
    public static void saveAppCheckDebugToken(Context context, String token) {
        if (context == null || token == null || token.isEmpty()) {
            Log.w(TAG, "Cannot save null or empty App Check debug token");
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences("get_intruder", Context.MODE_PRIVATE);
            prefs.edit().putString(APP_CHECK_TOKEN_KEY, token).apply();
            Log.d(TAG, "App Check debug token saved to SharedPreferences");
        } catch (Exception e) {
            Log.e(TAG, "Error saving App Check debug token", e);
        }
    }

    /**
     * Retrieve App Check debug token from SharedPreferences
     */
    public static String getAppCheckDebugToken(Context context) {
        if (context == null) return null;

        try {
            SharedPreferences prefs = context.getSharedPreferences("get_intruder", Context.MODE_PRIVATE);
            String token = prefs.getString(APP_CHECK_TOKEN_KEY, null);

            if (token != null) {
                Log.d(TAG, "Retrieved App Check debug token from SharedPreferences");
            } else {
                Log.d(TAG, "No App Check debug token found in SharedPreferences");
            }

            return token;
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving App Check debug token", e);
            return null;
        }
    }

    /**
     * Test Firestore permissions by writing and then deleting a test document
     */
    public static void checkFirestorePermissions(Context context, FirestorePermissionCallback callback) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            if (callback != null) {
                callback.onResult(false, "User not authenticated");
            }
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String testId = UUID.randomUUID().toString();
        String userId = currentUser.getUid();

        Log.d(TAG, "🔍 Testing Firestore permissions... (attempt 1)");

        // Create a test document with timestamp and device info
        Map<String, Object> testData = new HashMap<>();
        testData.put("timestamp", System.currentTimeMillis());
        testData.put("device", android.os.Build.MODEL);
        testData.put("test_id", testId);
        testData.put("user_id", userId);

        db.collection("permission_tests")
                .document(testId)
                .set(testData)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "✅ Firestore permissions verified successfully");
                        // Successfully wrote document, now try to read it back
                        readTestDocument(db, testId, 1, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        if (callback != null) {
                            callback.onResult(false, "Write permission denied: " + e.getMessage());
                        }
                    }
                });
    }

    private static void readTestDocument(FirebaseFirestore db, String testId, int attemptNum, FirestorePermissionCallback callback) {
        if (attemptNum > MAX_RETRY_ATTEMPTS) {
            if (callback != null) {
                callback.onResult(false, "Failed to verify document after " + MAX_RETRY_ATTEMPTS + " attempts");
            }
            return;
        }

        db.collection("permission_tests")
                .document(testId)
                .get()
                .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                        if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                            // Document exists, delete it to clean up
                            cleanupTestDocument(db, testId, callback);
                        } else {
                            // Document not found yet, might need to wait for eventual consistency
                            if (attemptNum < MAX_RETRY_ATTEMPTS) {
                                Log.d(TAG, "Retrying Firestore read (attempt " + (attemptNum + 1) + ")");
                                android.os.Handler handler = new android.os.Handler();
                                handler.postDelayed(() -> readTestDocument(db, testId, attemptNum + 1, callback),
                                        1000); // Wait 1 second between retries
                            } else {
                                if (callback != null) {
                                    callback.onResult(false, "Read permission denied or document not found");
                                }
                            }
                        }
                    }
                });
    }

    private static void cleanupTestDocument(FirebaseFirestore db, String testId, FirestorePermissionCallback callback) {
        db.collection("permission_tests")
                .document(testId)
                .delete()
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "🗑️ Test document cleaned up");
                        if (callback != null) {
                            callback.onResult(true, "All permissions verified successfully");
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // Still consider test successful even if cleanup fails
                        Log.w(TAG, "Warning: Could not delete test document: " + e.getMessage());
                        if (callback != null) {
                            callback.onResult(true, "Permissions verified but cleanup failed");
                        }
                    }
                });
    }

    /**
     * Fetch cloud photos from Firebase Storage
     */
    public static void fetchCloudPhotos(Context context, CloudPhotosCallback callback) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Log.w(TAG, "User not authenticated for cloud photo fetch");
            if (callback != null) {
                callback.onResult(false, "User not authenticated", new ArrayList<>());
            }
            return;
        }

        String userId = currentUser.getUid();
        Log.d(TAG, "Fetching cloud photos for user: " + userId);

        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference storageRef = storage.getReference().child("security_photos").child(userId);

        Log.d(TAG, "Accessing storage path: security_photos/" + userId);

        storageRef.listAll()
                .addOnSuccessListener(listResult -> {
                    Log.d(TAG, "Cloud photos fetch successful. Found " + listResult.getItems().size() + " photos");
                    if (callback != null) {
                        callback.onResult(true, "Retrieved " + listResult.getItems().size() + " photos",
                                listResult.getItems());
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching cloud photos from path: security_photos/" + userId, e);
                    if (callback != null) {
                        callback.onResult(false, "Error: " + e.getMessage(), new ArrayList<>());
                    }
                });
    }

    /**
     * Download a cloud photo from Firebase Storage
     */
    public static void downloadCloudPhoto(StorageReference photoRef, File localFile, CloudPhotoDownloadCallback callback) {
        photoRef.getFile(localFile)
                .addOnSuccessListener(taskSnapshot -> {
                    if (callback != null) {
                        callback.onDownloaded(true, localFile);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error downloading cloud photo", e);
                    if (callback != null) {
                        callback.onDownloaded(false, localFile);
                    }
                });
    }

    /**
     * Get user-specific storage path for Firebase Storage
     */
    public static String getUserSpecificStoragePath(Context context) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            return "security_photos/" + currentUser.getUid();
        } else {
            // Fallback to device-specific path if no user is authenticated
            String deviceId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
            return "security_photos/device_" + deviceId;
        }
    }

    /**
     * Callback interfaces
     */
    public interface FirestorePermissionCallback {
        void onResult(boolean success, String message);
    }

    public interface CloudPhotosCallback {
        void onResult(boolean success, String message, List<StorageReference> photos);
    }

    public interface CloudPhotoDownloadCallback {
        void onDownloaded(boolean success, File downloadedFile);
    }
}
