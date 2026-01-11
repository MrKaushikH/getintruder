package com.example.securityapp;

import static com.example.securityapp.UserManager.getUserPhotosDirectory;

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_ENABLE_ADMIN = 1;
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_SMS_PERMISSION = 101;
    private static final int REQUEST_LOCATION_PERMISSION = 102;
    private static final int REQUEST_WIFI_STATE_PERMISSION = 103;
    private static final int REQUEST_MULTIPLE_PERMISSIONS = 104;

    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private TextView statusText;
    private Button enableButton;
    private Button viewPhotosButton;
    private TextView photosCountText;
    private TextView lastCaptureText;
    private Button cloudBackupButton;
    private ImageButton settingsButton;

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private boolean firestorePermissionsChecked = false;

    private boolean isGoingToBackground = false;
    private boolean initializationInProgress = false;
    private static boolean usernameDialogShown = false;
    private boolean isVisible = false;
    private long lastVisibilityChangeTime = 0;

    // Add these variables for cloud photo functionality
    private boolean isShowingCloudPhotos = false;
    private List<com.google.firebase.storage.StorageReference> cloudPhotoRefs = new ArrayList<>();
    private File cloudPhotosDir;

    // Add flag to track if user has seen backup explanation
    private boolean hasSeenBackupExplanation = false;

    // Add cloud photo count tracking
    private int currentCloudPhotoCount = 0;

    // Add photo cache to prevent repeated searches
    private static final long PHOTO_CACHE_DURATION = 10000;
    private static List<File> cachedPhotosList = new ArrayList<>();
    private static long lastPhotoSearchTime = 0;

    // Handler for real-time stats update
    private Handler statsHandler = new Handler();
    private Runnable statsUpdater = new Runnable() {
        @Override
        public void run() {
            updateStats();
            statsHandler.postDelayed(this, 3000);
        }
    };

    // Add missing variable for dialog tracking
    private AlertDialog currentPhotosListDialog = null;

    private BroadcastReceiver photoCaptureReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            // Add resource loading safety check
            ensureResourcesAvailable();

            initializeFirebase();
            initializationInProgress = true;

            // Check authentication with fallback support
            if (!checkAuthenticationStatus()) {
                Log.d(TAG, "User not authenticated, redirecting to AuthActivity");
                Intent intent = new Intent(this, AuthActivity.class);
                startActivity(intent);
                finish();
                return;
            }

            finishInitialization();
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);

            // Handle resource loading errors gracefully
            if (e.getMessage() != null && (e.getMessage().contains("resource") ||
                                         e.getMessage().contains("APK") ||
                                         e.getMessage().contains("asset"))) {
                Log.w(TAG, "Resource loading error detected, attempting recovery");

                // Try to reinitialize resources
                try {
                    Thread.sleep(1000); // Brief pause
                    finishInitialization();
                } catch (Exception retryException) {
                    Log.e(TAG, "Resource recovery failed", retryException);
                    // Show error to user but don't crash
                    runOnUiThread(() -> {
                        Toast.makeText(this, "App initialization error. Please restart the app.", Toast.LENGTH_LONG).show();
                    });
                }
            }
        } finally {
            initializationInProgress = false;
        }

        // Register visibility change listener
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    checkVisibilityChange();
                }
            });
    }

    /**
     * Ensure app resources are properly loaded
     */
    private void ensureResourcesAvailable() {
        try {
            // Try to access a resource to ensure they're loaded
            getResources().getString(android.R.string.ok);

            // Try to access app-specific resources
            try {
                getResources().getDrawable(R.drawable.ic_launcher_foreground);
            } catch (Exception e) {
                Log.w(TAG, "App-specific resources not fully loaded yet: " + e.getMessage());
                // This is non-critical, continue anyway
            }

            Log.d(TAG, "Resources verification completed");
        } catch (Exception e) {
            Log.w(TAG, "Resource verification failed", e);
            // Don't throw - let the app continue with degraded functionality
        }
    }

    /**
     * Initialize Firebase components with Google Play Services checking
     */
    private void initializeFirebase() {
        try {
            // Check if Google Play Services is available
            com.google.android.gms.common.GoogleApiAvailability apiAvailability =
                com.google.android.gms.common.GoogleApiAvailability.getInstance();
            int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);

            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                Log.w(TAG, "Google Play Services not available. Result code: " + resultCode);

                if (apiAvailability.isUserResolvableError(resultCode)) {
                    Log.i(TAG, "Google Play Services error is user resolvable");
                } else {
                    Log.e(TAG, "Google Play Services not available and not resolvable");
                }

                // Continue with fallback mode
                initializeFirebaseWithFallback();
                return;
            }

            // Initialize Firebase Auth with error handling
            try {
                mAuth = FirebaseAuth.getInstance();
                Log.d(TAG, "Firebase Auth initialized successfully");
            } catch (Exception authError) {
                Log.e(TAG, "Error initializing Firebase Auth", authError);
                mAuth = null;
            }

            // Initialize Firestore with error handling
            try {
                db = FirebaseFirestore.getInstance();
                Log.d(TAG, "Firestore initialized successfully");
            } catch (Exception firestoreError) {
                Log.e(TAG, "Error initializing Firestore", firestoreError);
                db = null;
            }

            Log.d(TAG, "Firebase components initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error initializing Firebase components", e);
            initializeFirebaseWithFallback();
        }
    }

    /**
     * Initialize Firebase with fallback for when Google Play Services is unavailable
     */
    private void initializeFirebaseWithFallback() {
        Log.w(TAG, "Initializing Firebase with fallback mode");

        try {
            // Set Firebase components to null for fallback mode
            mAuth = null;
            db = null;

            Log.i(TAG, "Firebase fallback initialization complete - using local storage");

        } catch (Exception e) {
            Log.e(TAG, "Even fallback Firebase initialization failed", e);
        }
    }

    /**
     * Check authentication status with fallback support
     */
    private boolean checkAuthenticationStatus() {
        try {
            // Try Firebase Auth first if available
            if (mAuth != null) {
                FirebaseUser currentUser = mAuth.getCurrentUser();
                if (currentUser != null) {
                    Log.d(TAG, "User authenticated via Firebase: " + currentUser.getEmail());
                    return true;
                }
            }

            // Fallback: check SharedPreferences for auth state
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            String userEmail = prefs.getString("user_email", "");

            if (!userEmail.isEmpty()) {
                Log.d(TAG, "User authenticated via SharedPreferences fallback: " + userEmail);
                return true;
            }

            Log.d(TAG, "No authentication found in Firebase or SharedPreferences");
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking authentication status", e);
            return false;
        }
    }

    private void finishInitialization() {
        try {
            // Set initialization in progress flag
            initializationInProgress = true;

            setContentView(R.layout.activity_main);

            // Setup app bar
            setupAppBar();

            // Initialize views first before any operations that use them
            initializeViews();

            setupDeviceAdmin();
            checkPermissions();

            // Only check Firestore permissions if Firebase is available
            if (mAuth != null && db != null) {
                checkFirestorePermissions();
                updateFirebaseUserIfNeeded();
            } else {
                Log.w(TAG, "Skipping Firebase operations - services not available");
                firestorePermissionsChecked = true; // Mark as checked to prevent retries

                // Show user that cloud features are disabled
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this,
                            "⚠️ Cloud features disabled (Google Services unavailable)",
                            Toast.LENGTH_LONG).show();
                    }
                });
            }

            // Update statistics after views are initialized
            updateStats();

        } catch (Exception e) {
            Log.e(TAG, "Error in finishInitialization", e);
        } finally {
            // Clear initialization flag when done
            initializationInProgress = false;
        }
    }

    private void checkFirestorePermissions() {
        if (firestorePermissionsChecked || db == null) return;

        FirebaseHelper.checkFirestorePermissions(this, new FirebaseHelper.FirestorePermissionCallback() {
            @Override
            public void onResult(boolean success, String message) {
                firestorePermissionsChecked = true;
                if (!success) {
                    Log.w(TAG, "Firestore permissions issue: " + message);
                    // Still try to continue with app functionality
                }
            }
        });
    }

    private void updateFirebaseUserIfNeeded() {
        try {
            // Skip if Firebase Auth isn't initialized
            if (mAuth == null) {
                Log.w(TAG, "Firebase Auth is not initialized, skipping profile update");
                return;
            }

            FirebaseUser user = mAuth.getCurrentUser();
            if (user != null && (user.getDisplayName() == null || user.getDisplayName().isEmpty())) {
                SharedPreferences prefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
                String userName = prefs.getString("user_name", "User");

                UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                    .setDisplayName(userName)
                    .build();

                user.updateProfile(profileUpdates)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "User profile updated on app restart: " + userName);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Failed to update user profile", e);
                    });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating Firebase user", e);
        }
    }

    private void setupAppBar() {
        // Hide default action bar since we're using custom top bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Set status bar color to match top bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.primary_blue));
        }
    }

    private void initializeViews() {
        statusText = findViewById(R.id.statusText);
        enableButton = findViewById(R.id.enableButton);
        viewPhotosButton = findViewById(R.id.viewPhotosButton);
        photosCountText = findViewById(R.id.photosCountText);
        lastCaptureText = findViewById(R.id.lastCaptureText);
        cloudBackupButton = findViewById(R.id.cloudBackupButton);
        settingsButton = findViewById(R.id.settingsButton);  // Initialize the settings button

        // Set up the click listener only once
        enableButton.setOnClickListener(v -> {
            boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
            if (isAdminActive) {
                // Show confirmation dialog before disabling
                new AlertDialog.Builder(this)
                    .setTitle("Disable Security")
                    .setMessage("Are you sure you want to disable security monitoring?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        // Disable admin
                        devicePolicyManager.removeActiveAdmin(adminComponent);


                        // Update UI immediately with forced state
                        enableButton.setText("Enable Security");
                        enableButton.setBackgroundColor(ContextCompat.getColor(this, R.color.primary_blue));
                        statusText.setText("Security monitoring is INACTIVE");
                        statusText.setSelected(false);

                        // Schedule a complete UI refresh after a brief delay
                        enableButton.postDelayed(() -> {
                            updateStatus();
                        }, 300);
                    })
                    .setNegativeButton("No", null)
                    .show();
            } else {
                enableDeviceAdmin();
            }
        });

        viewPhotosButton.setOnClickListener(v -> viewSecurityPhotos());

        cloudBackupButton.setOnClickListener(v -> showCloudBackupOptions());

        // Modify this click listener to show main settings menu instead of directly showing app lock
        settingsButton.setOnClickListener(v -> showMainSettings());

        updateStats();
    }

    /**
     * Show main settings menu (from the settings icon)
     */
    private void showMainSettings() {
        String[] options = {
            "Account",
            "Sign Out"
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings");

        builder.setItems(options, (dialog, which) -> {
            switch(which) {
                case 0:
                    showAccountInfo();
                    break;
                case 1:
                    confirmSignOut();
                    break;
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void setupDeviceAdmin() {
        devicePolicyManager = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, SecurityDeviceAdminReceiver.class);

        updateStatus();
    }

    private void checkPermissions() {
        // Create a list to hold all permissions we need to request
        List<String> permissionsNeeded = new ArrayList<>();

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        // Check location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // Check WiFi state permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_WIFI_STATE);
        }

        // Check background location permission for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Only request background location if we already have foreground location
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                }
            }
        }

        // Request all permissions at once if any are missing
        if (!permissionsNeeded.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsNeeded.toString());
            ActivityCompat.requestPermissions(this,
                permissionsNeeded.toArray(new String[0]),
                REQUEST_MULTIPLE_PERMISSIONS);
        } else {
            Log.d(TAG, "All required permissions are already granted");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_MULTIPLE_PERMISSIONS) {
            boolean allGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (deniedPermissions.length() > 0) {
                        deniedPermissions.append(", ");
                    }
                    deniedPermissions.append(permissions[i]);
                }
            }

            if (allGranted) {
                Log.d(TAG, "✅ All permissions granted");
                Toast.makeText(this, "✅ All permissions granted - Security features fully enabled", Toast.LENGTH_LONG).show();
            } else {
                Log.w(TAG, "❌ Some permissions denied: " + deniedPermissions.toString());
            }
        }
    }

    /**
     * Show dialog explaining why location permission is important - REMOVED
     * This method is no longer called but kept for reference if needed later
     */
    private void showLocationPermissionDialog() {
        // Method body removed - no longer shows popup
        Log.d(TAG, "Location permission dialog suppressed");
    }

    private boolean isAccessibilityServiceEnabled() {
        String settingValue = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);

            while (splitter.hasNext()) {
                String serviceName = splitter.next();
            }
        }
        return false;
    }

    private void enableDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Enable device admin to monitor failed unlock attempts and capture intruder photos.");
        startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_ENABLE_ADMIN) {
            // Add delay to ensure device admin state is properly updated
            new Handler().postDelayed(() -> {
                updateStatus();
                Toast.makeText(this,
                    devicePolicyManager.isAdminActive(adminComponent) ?
                    "Security monitoring enabled!" :
                    "Security monitoring not enabled",
                    Toast.LENGTH_SHORT).show();
            }, 300);
        }
    }

    private void updateStatus() {
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);

        String statusMessage;

        if (isAdminActive) {
            statusMessage = "Intruder detection is ACTIVE\n(PIN/Pattern)";
            statusText.setSelected(true);

            // Set green background for active status
            int activeColor = ContextCompat.getColor(this, android.R.color.holo_green_dark);
            setRoundedBackground(statusText, activeColor);

            // When security is enabled, use transparent button with blue border
            enableButton.setText("Disable Detection");

            // Transparent button with blue border
            android.graphics.drawable.GradientDrawable transparentBorderDrawable = new android.graphics.drawable.GradientDrawable();
            transparentBorderDrawable.setCornerRadius(30); // Make it circular
            transparentBorderDrawable.setColor(Color.TRANSPARENT); // Transparent background
            transparentBorderDrawable.setStroke(2, ContextCompat.getColor(this, R.color.primary_blue)); // Blue border

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                enableButton.setBackground(transparentBorderDrawable);
            } else {
                enableButton.setBackgroundDrawable(transparentBorderDrawable);
            }

            enableButton.setTextColor(ContextCompat.getColor(this, R.color.primary_blue));
        } else {
            statusMessage = "Intruder detection is INACTIVE";
            statusText.setSelected(false);

            // Use the same rounded shape for inactive status as active status
            int inactiveColor = ContextCompat.getColor(this, android.R.color.holo_red_light);
            setRoundedBackground(statusText, inactiveColor);

            // Use transparent with border style like when active, but with solid blue button
            android.graphics.drawable.GradientDrawable blueButtonDrawable = new android.graphics.drawable.GradientDrawable();
            blueButtonDrawable.setCornerRadius(30); // Same corner radius as active state
            blueButtonDrawable.setColor(ContextCompat.getColor(this, R.color.primary_blue)); // Blue background
            // No border needed for filled button

            enableButton.setText("Enable Detection");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                enableButton.setBackground(blueButtonDrawable);
            } else {
                enableButton.setBackgroundDrawable(blueButtonDrawable);
            }

            enableButton.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        }

        statusText.setText(statusMessage);
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.white));

        // Force UI refresh
        statusText.invalidate();
        enableButton.invalidate();

        updateStats();
    }

    // Helper method to create rounded background for the status text
    private void setRoundedBackground(TextView textView, int backgroundColor) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(30); // More moderate corner radius for modern look
        shape.setColor(backgroundColor);

        // Add a slight stroke for enhanced appearance
        shape.setStroke(2, Color.parseColor("#33FFFFFF"));

        // Apply padding to make it look better
        textView.setPadding(32, 24, 32, 24);

        // Add text shadow for better readability
        textView.setShadowLayer(3, 1, 1, Color.parseColor("#66000000"));

        // Set the background drawable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            textView.setBackground(shape);
        } else {
            textView.setBackgroundDrawable(shape);
        }
    }

    /**
     * Find security photos in all possible directories with comprehensive search
     */
    private File[] findAllSecurityPhotos() {
        // Check if we have a recent cache we can use
        long currentTime = System.currentTimeMillis();
        if (!cachedPhotosList.isEmpty() && (currentTime - lastPhotoSearchTime) < PHOTO_CACHE_DURATION) {
            // Don't log every time to reduce spam
            return cachedPhotosList.toArray(new File[0]);
        }

        // Reset cache
        cachedPhotosList.clear();
        lastPhotoSearchTime = currentTime;

        Log.d(TAG, "Starting comprehensive photo search...");

        // Create a set to track unique paths to avoid duplicates more efficiently
        Set<String> uniqueFilePaths = new HashSet<>();

        // 1. Check user-specific directory from UserManager
        File userDir = getUserPhotosDirectory(this);
        Log.d(TAG, "Checking user directory: " + userDir.getAbsolutePath());

        if (userDir.exists()) {
            File[] userPhotos = userDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
            if (userPhotos != null && userPhotos.length > 0) {
                Log.d(TAG, "Found " + userPhotos.length + " photos in user directory");
                for (File photo : userPhotos) {
                    if (uniqueFilePaths.add(photo.getAbsolutePath())) {
                        cachedPhotosList.add(photo);
                    }
                }
            } else {
                Log.d(TAG, "No photos found in user directory");
            }
        } else {
            Log.d(TAG, "User photos directory doesn't exist: " + userDir.getAbsolutePath());
            // Try to create it for future use
            boolean created = userDir.mkdirs();
            Log.d(TAG, "Created user directory: " + created);
        }

        // 2. Check the root security photos directory
        File rootDir = new File(getFilesDir(), "security_photos");
        Log.d(TAG, "Checking root directory: " + rootDir.getAbsolutePath());

        if (rootDir.exists()) {
            // Check for photos directly in root directory
            File[] rootPhotos = rootDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") && new File(dir, name).isFile());

            if (rootPhotos != null && rootPhotos.length > 0) {
                Log.d(TAG, "Found " + rootPhotos.length + " photos in root directory");
                for (File photo : rootPhotos) {
                    if (uniqueFilePaths.add(photo.getAbsolutePath())) {
                        cachedPhotosList.add(photo);
                    }
                }
            }

            // 3. Check subdirectories in the security_photos directory
            File[] subdirs = rootDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    if (!subdir.equals(userDir)) { // Skip if it's the user directory we already checked
                        Log.d(TAG, "Checking subdirectory: " + subdir.getAbsolutePath());
                        File[] subdirPhotos = subdir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
                        if (subdirPhotos != null && subdirPhotos.length > 0) {
                            Log.d(TAG, "Found " + subdirPhotos.length + " photos in " + subdir.getName());
                            for (File photo : subdirPhotos) {
                                if (uniqueFilePaths.add(photo.getAbsolutePath())) {
                                    cachedPhotosList.add(photo);
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Root security photos directory doesn't exist, creating it");
            boolean created = rootDir.mkdirs();
            Log.d(TAG, "Created root directory: " + created);
        }

        // 4. Check files directory directly for any orphaned photos - only if cache is empty
        if (cachedPhotosList.isEmpty()) {
            File filesDir = getFilesDir();
            File[] orphanedPhotos = filesDir.listFiles((dir, name) ->
                name.toLowerCase().startsWith("security_") && name.toLowerCase().endsWith(".jpg"));
            if (orphanedPhotos != null && orphanedPhotos.length > 0) {
                Log.d(TAG, "Found " + orphanedPhotos.length + " orphaned photos in files directory");
                for (File photo : orphanedPhotos) {
                    if (uniqueFilePaths.add(photo.getAbsolutePath())) {
                        cachedPhotosList.add(photo);
                    }
                }
            }
        }

        // Log final results
        if (!cachedPhotosList.isEmpty()) {
            Log.d(TAG, "Found a total of " + cachedPhotosList.size() + " unique photos across all directories");
            // Limit logging to first photo only to prevent log spam
            File firstPhoto = cachedPhotosList.get(0);
            Log.d(TAG, "  - " + firstPhoto.getName() + " (" + (firstPhoto.length() / 1024) + " KB) in " + firstPhoto.getParent());

            if (cachedPhotosList.size() > 1) {
                Log.d(TAG, "  - ... and " + (cachedPhotosList.size() - 1) + " more photos");
            }
        } else {
            Log.d(TAG, "🔍 No photos found in any directory");
        }

        return cachedPhotosList.toArray(new File[0]);
    }

    private void updateStats() {
        // Check if views are initialized to prevent NullPointerException
        if (photosCountText == null || lastCaptureText == null) {
            Log.w(TAG, "updateStats called before views were initialized");
            return;
        }

        // Use cached photos when possible to avoid filesystem scans
        File[] allPhotos = findAllSecurityPhotos();

        if (allPhotos != null && allPhotos.length > 0) {
            photosCountText.setText(String.valueOf(allPhotos.length));

            // Find most recent photo
            File mostRecent = allPhotos[0];
            long totalSize = 0;

            for (File photo : allPhotos) {
                totalSize += photo.length();
                if (photo.lastModified() > mostRecent.lastModified()) {
                    mostRecent = photo;
                }
            }

            String lastCapture = new java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(mostRecent.lastModified()));
            lastCaptureText.setText(lastCapture);

            // Log summary only - detailed logging happens in findAllSecurityPhotos
            long totalSizeKB = totalSize / 1024;
            Log.d(TAG, "📊 Photo stats: " + allPhotos.length + " photos, " + totalSizeKB + " KB total");

        } else {
            photosCountText.setText("0");
            lastCaptureText.setText("Never");
            Log.d(TAG, "📊 No photos found in any directory");
        }
    }

    /**
     * Show cloud backup options
     */
    private void showCloudBackupOptions() {
        // Check if Firebase is available for cloud features
        if (mAuth == null || db == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("☁️ Cloud Backup Unavailable");
            builder.setMessage("Cloud backup features are currently unavailable because Google Services is not properly configured.\n\n" +
                              "Your photos are still being saved locally and the security monitoring continues to work normally.\n\n" +
                              "Cloud backup will be automatically enabled when Google Services becomes available.");
            builder.setPositiveButton("OK", null);
            builder.show();
            return;
        }

        // Check if user has already seen the explanation
        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
        boolean hasSeenExplanation = prefs.getBoolean("has_seen_backup_explanation", false);

        if (!hasSeenExplanation) {
            // Show advantages and force enable backup
            showBackupAdvantagesAndEnable(prefs);
        } else {
            // Show access instructions and statistics
            showBackupAccessAndStats();
        }
    }

    /**
     * Show backup advantages and automatically enable backup (first time only)
     */
    private void showBackupAdvantagesAndEnable(SharedPreferences prefs) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("☁️ Cloud Backup Activated");

        String advantages = "🔒 Your Security Just Got Better!\n\n" +
                           "✅ Photos automatically saved online\n" +
                           "✅ Access from ANY device with your account\n" +
                           "✅ Never lose evidence if phone is stolen\n" +
                           "✅ Catch intruders even without your phone\n\n" +
                           "📱 Example: Phone stolen at 2 PM\n" +
                           "🔍 Login from friend's phone at 3 PM\n" +
                           "📸 See who stole your phone!\n\n" +
                           "Auto backup is now ENABLED for your protection.";

        builder.setMessage(advantages);

        builder.setPositiveButton("Got It!", (dialog, which) -> {
            // Force enable auto backup
            prefs.edit()
                .putBoolean("has_seen_backup_explanation", true)
                .putBoolean("auto_backup_enabled", true)
                .putBoolean("wifi_only_backup", false)
                .putLong("backup_enabled_time", System.currentTimeMillis())
                .apply();

            Toast.makeText(this, "🔄 Auto backup enabled for your protection!", Toast.LENGTH_LONG).show();

            // Start immediate backup if photos exist
            File[] localPhotos = findAllSecurityPhotos();
            if (localPhotos != null && localPhotos.length > 0) {
                startImmediateBackup();
            }
        });

        builder.setCancelable(false); // User must acknowledge
        builder.show();
    }

    /**
     * Show backup access instructions and statistics (subsequent times)
     */
    private void showBackupAccessAndStats() {
        // First fetch current cloud photo count with debugging
        Toast.makeText(this, "Loading cloud statistics...", Toast.LENGTH_SHORT).show();

        FirebaseHelper.fetchCloudPhotos(this, new FirebaseHelper.CloudPhotosCallback() {
            @Override
            public void onResult(boolean success, String message, List<com.google.firebase.storage.StorageReference> photos) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Cloud photos fetch: success=" + success + ", message=" + message + ", count=" + (photos != null ? photos.size() : 0));

                    if (success && photos != null) {
                        currentCloudPhotoCount = photos.size();
                        Log.d(TAG, "Cloud photos found: " + currentCloudPhotoCount);
                    } else {
                        currentCloudPhotoCount = 0;
                        Log.w(TAG, "No cloud photos or error: " + message);
                        // Show error details to user for debugging
                        if (!success) {
                            Toast.makeText(MainActivity.this, "Cloud access issue: " + message, Toast.LENGTH_LONG).show();
                        }
                    }

                    showBackupStatsDialog();
                });
            }
        });
    }

    /**
     * Show backup statistics and access options
     */
    private void showBackupStatsDialog() {
        File[] localPhotos = findAllSecurityPhotos();
        int localCount = localPhotos != null ? localPhotos.length : 0;

        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
        long lastBackupTime = prefs.getLong("last_backup_time", 0);
        String lastBackupText = lastBackupTime > 0 ?
            new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(lastBackupTime)) : "Never";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("☁️ Cloud Backup Status");

        StringBuilder message = new StringBuilder();
        message.append("📊 BACKUP STATISTICS\n\n");
        message.append("📱 Photos on this device: ").append(localCount).append("\n");
        message.append("☁️ Photos in cloud: ").append(currentCloudPhotoCount).append("\n");
        message.append("🕒 Last backup: ").append(lastBackupText).append("\n\n");

        message.append("🌐 ACCESS FROM ANY DEVICE:\n\n");
        message.append("1️⃣ Install GetIntruder app on any Android\n");
        message.append("2️⃣ Login with: ").append(getCurrentUserEmail()).append("\n");
        message.append("3️⃣ Go to Cloud Backup\n");
        message.append("4️⃣ Download your photos\n\n");
        message.append("🔒 Works even if this phone is stolen!");

        builder.setMessage(message.toString());

        // Only show download option if there are cloud photos
        if (currentCloudPhotoCount > 0) {
            builder.setPositiveButton("📥 Download Photos (" + currentCloudPhotoCount + ")", (dialog, which) -> {
                showDownloadOptionsDialog();
            });
        }

        builder.setNegativeButton("Close", null);
        builder.show();
    }

    /**
     * Show download options with quantity selection
     */
    private void showDownloadOptionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📥 Download Cloud Photos");

        String message = "How many photos would you like to download?\n\n" +
                        "📁 Photos will be saved to your device gallery\n" +
                        "📍 Location: Gallery → GetIntruder album\n" +
                        "🔄 Latest photos downloaded first";

        builder.setMessage(message);

        if (currentCloudPhotoCount <= 5) {
            builder.setPositiveButton("All " + currentCloudPhotoCount + " Photos", (dialog, which) -> {
                startCloudPhotoDownload(currentCloudPhotoCount);
            });
        } else {
            builder.setPositiveButton("Latest 5 Photos", (dialog, which) -> {
                startCloudPhotoDownload(5);
            });

            builder.setNeutralButton("Latest 10 Photos", (dialog, which) -> {
                startCloudPhotoDownload(Math.min(10, currentCloudPhotoCount));
            });

            builder.setNegativeButton("All " + currentCloudPhotoCount + " Photos", (dialog, which) -> {
                startCloudPhotoDownload(currentCloudPhotoCount);
            });
        }

        builder.show();
    }

    /**
     * Start downloading cloud photos to external gallery
     */
    private void startCloudPhotoDownload(int photoCount) {
        if (!NetworkHelper.isNetworkAvailable(this)) {
            Toast.makeText(this, "❌ No internet connection", Toast.LENGTH_LONG).show();
            return;
        }

        // Show progress dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📥 Downloading " + photoCount + " Photos");
        builder.setMessage("Preparing download...\n\n" +
                          "📁 Saving to Gallery → GetIntruder\n" +
                          "🔄 Please wait...");
        builder.setCancelable(false);
        AlertDialog progressDialog = builder.create();
        progressDialog.show();

        // Fetch cloud photos and download them
        FirebaseHelper.fetchCloudPhotos(this, new FirebaseHelper.CloudPhotosCallback() {
            @Override
            public void onResult(boolean success, String message, List<com.google.firebase.storage.StorageReference> photos) {
                if (success && !photos.isEmpty()) {
                    downloadPhotosToGallery(photos, photoCount, progressDialog);
                } else {
                    progressDialog.dismiss();
                    Toast.makeText(MainActivity.this, "❌ No photos found to download", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /**
     * Download photos to external gallery (not intruder folder)
     */
    private void downloadPhotosToGallery(List<com.google.firebase.storage.StorageReference> photos, int count, AlertDialog progressDialog) {
        new Thread(() -> {
            try {
                // Create GetIntruder album in Pictures directory for gallery access
                File galleryDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "GetIntruder");

                if (!galleryDir.exists()) {
                    boolean created = galleryDir.mkdirs();
                    Log.d(TAG, "Created GetIntruder gallery directory: " + created);
                }

                int downloaded = 0;
                int totalToDownload = Math.min(count, photos.size());

                for (int i = 0; i < totalToDownload; i++) {
                    com.google.firebase.storage.StorageReference photoRef = photos.get(i);
                    String fileName = photoRef.getName();

                    // Create unique filename to avoid conflicts
                    String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                        .format(new java.util.Date());
                    String uniqueFileName = "cloud_" + timestamp + "_" + i + ".jpg";
                    File localFile = new File(galleryDir, uniqueFileName);

                    try {
                        // Download synchronously
                        com.google.android.gms.tasks.Tasks.await(photoRef.getFile(localFile));

                        // Add to gallery
                        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        mediaScanIntent.setData(android.net.Uri.fromFile(localFile));
                        sendBroadcast(mediaScanIntent);

                        downloaded++;

                        // Update progress on UI thread
                        final int currentCount = downloaded;
                        runOnUiThread(() -> {
                            if (progressDialog.isShowing()) {
                                progressDialog.setMessage("Downloaded " + currentCount + " of " + totalToDownload + " photos...\n\n" +
                                                         "📁 Saving to Gallery → GetIntruder\n" +
                                                         "🔄 Please wait...");
                            }
                        });

                        // Small delay to prevent overwhelming
                        Thread.sleep(300);

                    } catch (Exception e) {
                        Log.e(TAG, "Error downloading photo: " + fileName, e);
                    }
                }

                // Show completion on UI thread
                final int finalDownloaded = downloaded;
                runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }

                    if (finalDownloaded > 0) {
                        AlertDialog.Builder completionBuilder = new AlertDialog.Builder(this);
                        completionBuilder.setTitle("Download Complete!");
                        completionBuilder.setMessage("Successfully downloaded " + finalDownloaded + " photos!\n\n" +
                                                    "Location: Gallery → Albums → GetIntruder\n" +
                                                    "You can view them in your gallery app");

                        completionBuilder.setPositiveButton("Open Gallery", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setType("image/*");
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(this, "Please check your Gallery app", Toast.LENGTH_SHORT).show();
                            }
                        });

                        completionBuilder.setNegativeButton("OK", null);
                        completionBuilder.show();
                    } else {
                        Toast.makeText(this, "No photos were downloaded", Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error in download process", e);
                runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Copy login information for sharing with trusted people
     */
    private void copyLoginInfoForSharing() {
        String email = getCurrentUserEmail();
        String loginInfo = "GetIntruder Security App Access\n\n" +
                          "Login Email: " + email + "\n\n" +
                          "Steps to access photos:\n" +
                          "1. Download GetIntruder app\n" +
                          "2. Login with above email\n" +
                          "3. Go to Cloud Backup\n" +
                          "4. Download photos\n\n" +
                          "Use this if your phone is stolen!";

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("GetIntruder Access Info", loginInfo);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, "Login information copied!\nShare with trusted family member.", Toast.LENGTH_LONG).show();
    }

    /**
     * Start immediate backup after enabling
     */
    private void startImmediateBackup() {
        Intent backupIntent = new Intent(this, CloudBackupService.class);
        backupIntent.setAction("UPLOAD_ALL");
        backupIntent.putExtra("automatic", true);
        backupIntent.putExtra("initial_setup", true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(backupIntent);
            } else {
                startService(backupIntent);
            }

            Toast.makeText(this, "Starting backup of your photos...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error starting initial backup", e);
        }
    }


    @Override
    protected void onResume() {
        super.onResume();

        isVisible = true;
        lastVisibilityChangeTime = System.currentTimeMillis();
        Log.d(TAG, "onResume called");

        // Update status when returning to activity to ensure real-time updates
        if (devicePolicyManager != null && adminComponent != null) {
            updateStatus();
        }

        // Only update stats if views are initialized
        try {
            if (photosCountText != null && lastCaptureText != null) {
                updateStats();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating stats in onResume", e);
        }

        // Start periodic stats updates
        statsHandler.post(statsUpdater);

        // Register broadcast receiver for photo capture notifications
        registerPhotoCaptureReceiver();
    }

    @Override
    protected void onPause() {
        super.onPause();

        isVisible = false;
        lastVisibilityChangeTime = System.currentTimeMillis();
        Log.d(TAG, "onPause called");

        // Stop periodic stats updates
        statsHandler.removeCallbacks(statsUpdater);

        // Unregister broadcast receiver
        unregisterPhotoCaptureReceiver();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.d(TAG, "onWindowFocusChanged: " + hasFocus);
    }

    // Check for visibility changes
    private void checkVisibilityChange() {
        boolean isViewVisible = isActivityVisible();
        if (isViewVisible != isVisible) {
            long currentTime = System.currentTimeMillis();
            // Log visibility change with timestamp
            Log.d(TAG, "Activity visibility changed: " + isVisible + " -> " + isViewVisible +
                  " (time since last change: " + (currentTime - lastVisibilityChangeTime) + "ms)");

            isVisible = isViewVisible;
            lastVisibilityChangeTime = currentTime;
        }
    }

    private boolean isActivityVisible() {
        return getWindow() != null &&
               getWindow().getDecorView() != null &&
               getWindow().getDecorView().isShown() &&
               getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }


    /**
     * Fetch cloud photos in background
     */
    private void fetchCloudPhotos() {
        Log.d(TAG, "Fetching cloud photos...");
        FirebaseHelper.fetchCloudPhotos(this, new FirebaseHelper.CloudPhotosCallback() {
            @Override
            public void onResult(boolean success, String message, List<com.google.firebase.storage.StorageReference> photos) {
                if (success) {
                    cloudPhotoRefs = photos;
                    currentCloudPhotoCount = photos.size(); // Update count
                    Log.d(TAG, "✅ Retrieved " + photos.size() + " cloud photos");

                    // If we're currently in cloud view mode but showing no photos, update the UI
                    if (isShowingCloudPhotos && isVisible) {
                        runOnUiThread(() -> {
                            if (!photos.isEmpty()) {
                                showCloudPhotosListForAdmin();
                            }
                        });
                    }
                } else {
                    currentCloudPhotoCount = 0; // Reset count on error
                    Log.e(TAG, "❌ Failed to fetch cloud photos: " + message);
                }
            }
        });
    }

    /**
     * Delete all security photos with confirmation
     */
    private void deleteAllPhotos() {
        File[] photos = findAllSecurityPhotos();

        if (photos == null || photos.length == 0) {
            Toast.makeText(this, "No photos to delete", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show confirmation dialog
        new AlertDialog.Builder(this)
            .setTitle("⚠️ Delete All Photos")
            .setMessage("Are you sure you want to delete all " + photos.length + " intruder photos?\n\nThis action cannot be undone!")
            .setPositiveButton("Delete All", (dialog, which) -> {
                int deletedCount = 0;

                for (File photo : photos) {
                    try {
                        if (photo.exists() && photo.delete()) {
                            deletedCount++;
                            Log.d(TAG, "Deleted photo: " + photo.getName());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error deleting photo: " + photo.getName(), e);
                    }
                }

                // Clear photo cache
                cachedPhotosList.clear();
                lastPhotoSearchTime = 0;

                // Update stats
                updateStats();

                // Show result
                if (deletedCount > 0) {
                    Toast.makeText(this, "✅ Deleted " + deletedCount + " photos", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "❌ No photos were deleted", Toast.LENGTH_SHORT).show();
                }

                // Close the photos list dialog if it's open
                if (currentPhotosListDialog != null && currentPhotosListDialog.isShowing()) {
                    currentPhotosListDialog.dismiss();
                    currentPhotosListDialog = null;
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Delete a single photo with confirmation
     */
    private void deletePhoto(File photoFile, Runnable onDeleted) {
        if (photoFile == null || !photoFile.exists()) {
            Toast.makeText(this, "Photo file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show confirmation dialog
        new AlertDialog.Builder(this)
            .setTitle("🗑️ Delete Photo")
            .setMessage("Are you sure you want to delete this photo?\n\n" + photoFile.getName())
            .setPositiveButton("Delete", (dialog, which) -> {
                try {
                    if (photoFile.delete()) {
                        Log.d(TAG, "Deleted photo: " + photoFile.getName());
                        Toast.makeText(this, "✅ Photo deleted", Toast.LENGTH_SHORT).show();

                        // Clear photo cache
                        cachedPhotosList.clear();
                        lastPhotoSearchTime = 0;

                        // Update stats
                        updateStats();

                        // Call the callback if provided
                        if (onDeleted != null) {
                            onDeleted.run();
                        }
                    } else {
                        Toast.makeText(this, "❌ Failed to delete photo", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error deleting photo: " + photoFile.getName(), e);
                    Toast.makeText(this, "❌ Error deleting photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * Share a photo using system share intent
     */
    private void sharePhoto(File photoFile) {
        if (photoFile == null || !photoFile.exists()) {
            Toast.makeText(this, "Photo file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // Create a content URI for the photo using FileProvider
            android.net.Uri photoUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getApplicationContext().getPackageName() + ".fileprovider",
                photoFile
            );

            // Create share intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/jpeg");
            shareIntent.putExtra(Intent.EXTRA_STREAM, photoUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Intruder Photo");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "Intruder photo captured by Security App");

            // Grant temporary read permission
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Create chooser
            Intent chooser = Intent.createChooser(shareIntent, "Share Intruder Photo");

            // Check if there are apps that can handle this intent
            if (shareIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(chooser);
                Log.d(TAG, "Photo share intent launched for: " + photoFile.getName());
            } else {
                Toast.makeText(this, "No apps available to share photos", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error sharing photo: " + photoFile.getName(), e);
            Toast.makeText(this, "❌ Error sharing photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * View security photos in a dialog with options
     */
    private void viewSecurityPhotos() {
        File[] photos = findAllSecurityPhotos();

        if (photos == null || photos.length == 0) {
            Toast.makeText(this, "No intruder photos found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Sort photos by modification date (newest first)
        java.util.Arrays.sort(photos, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Intruder Photos (" + photos.length + ")");

        // Create a custom view with image previews
        LinearLayout photoListContainer = new LinearLayout(this);
        photoListContainer.setOrientation(LinearLayout.VERTICAL);
        photoListContainer.setPadding(16, 16, 16, 16);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(photoListContainer);

        // Add each photo as a preview item
        for (int i = 0; i < Math.min(photos.length, 20); i++) { // Limit to 20 for performance
            File photo = photos[i];
            LinearLayout photoItem = createPhotoPreviewItem(photo, i);
            photoItem.setOnClickListener(v -> {
                builder.create().dismiss();
                showPhotoDialog(photo);
            });
            photoListContainer.addView(photoItem);
        }

        // If there are more than 20 photos, add a note
        if (photos.length > 20) {
            TextView moreText = new TextView(this);
            moreText.setText("... and " + (photos.length - 20) + " more photos");
            moreText.setTextColor(getResources().getColor(R.color.bw_secondary_text, null));
            moreText.setPadding(16, 8, 16, 8);
            moreText.setGravity(android.view.Gravity.CENTER);
            photoListContainer.addView(moreText);
        }

        builder.setView(scrollView);

        // Add management buttons
        builder.setPositiveButton("Stats", (dialog, which) -> {
            showPhotoStatistics(photos);
        });

        builder.setNeutralButton("Delete All", (dialog, which) -> deleteAllPhotos());

        builder.setNegativeButton("Close", null);

        currentPhotosListDialog = builder.create();
        currentPhotosListDialog.show();
    }

    /**
     * Create a photo preview item with thumbnail and details
     */
    private LinearLayout createPhotoPreviewItem(File photoFile, int index) {
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(8, 8, 8, 8);
        itemLayout.setBackgroundColor(getResources().getColor(R.color.bw_surface, null));

        // Add margin between items
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        layoutParams.setMargins(0, 0, 0, 8);
        itemLayout.setLayoutParams(layoutParams);

        // Add ripple effect
        itemLayout.setBackground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        itemLayout.setClickable(true);
        itemLayout.setFocusable(true);

        try {
            // Create thumbnail
            ImageView thumbnail = new ImageView(this);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(80, 80);
            thumbParams.setMargins(0, 0, 16, 0);
            thumbnail.setLayoutParams(thumbParams);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);

            // Load thumbnail bitmap
            Bitmap thumbnailBitmap = createThumbnail(photoFile, 80);
            if (thumbnailBitmap != null) {
                thumbnail.setImageBitmap(thumbnailBitmap);
            } else {
                // Fallback to default image icon
                thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                thumbnail.setColorFilter(getResources().getColor(R.color.bw_accent, null));
            }

            itemLayout.addView(thumbnail);

        } catch (Exception e) {
            Log.e(TAG, "Error creating thumbnail for " + photoFile.getName(), e);
            // Add placeholder icon
            ImageView placeholder = new ImageView(this);
            LinearLayout.LayoutParams placeholderParams = new LinearLayout.LayoutParams(80, 80);
            placeholderParams.setMargins(0, 0, 16, 0);
            placeholder.setLayoutParams(placeholderParams);
            placeholder.setImageResource(android.R.drawable.ic_menu_gallery);
            placeholder.setColorFilter(getResources().getColor(R.color.bw_accent, null));
            itemLayout.addView(placeholder);
        }

        // Create text container
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        // Photo name
        TextView nameText = new TextView(this);
        nameText.setText(photoFile.getName());
        nameText.setTextSize(16);
        nameText.setTextColor(getResources().getColor(R.color.bw_primary_text, null));
        nameText.setTypeface(null, android.graphics.Typeface.BOLD);
        nameText.setMaxLines(1);
        nameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textContainer.addView(nameText);

        // Date and size
        String date = new java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault())
            .format(new java.util.Date(photoFile.lastModified()));
        long sizeKB = photoFile.length() / 1024;

        TextView detailsText = new TextView(this);
        detailsText.setText(date + " • " + sizeKB + " KB");
        detailsText.setTextSize(14);
        detailsText.setTextColor(getResources().getColor(R.color.bw_secondary_text, null));
        textContainer.addView(detailsText);

        itemLayout.addView(textContainer);

        return itemLayout;
    }

    /**
     * Create a thumbnail bitmap from photo file with correct orientation
     */
    private Bitmap createThumbnail(File photoFile, int size) {
        try {
            // First, decode with inSampleSize to reduce memory usage
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);

            // Calculate inSampleSize
            options.inSampleSize = calculateInSampleSize(options, size, size);
            options.inJustDecodeBounds = false;

            Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
            if (bitmap == null) return null;

            // Apply rotation correction based on EXIF data
            bitmap = applyRotationCorrection(bitmap, photoFile);

            // Scale to exact thumbnail size
            Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap, size, size, true);

            // Recycle original if different
            if (thumbnail != bitmap) {
                bitmap.recycle();
            }

            return thumbnail;
        } catch (Exception e) {
            Log.e(TAG, "Error creating thumbnail for " + photoFile.getName(), e);
            return null;
        }
    }

    /**
     * Apply rotation correction to bitmap based on EXIF data
     */
    private Bitmap applyRotationCorrection(Bitmap originalBitmap, File photoFile) {
        try {
            // Read EXIF data to get orientation
            androidx.exifinterface.media.ExifInterface exifInterface =
                new androidx.exifinterface.media.ExifInterface(photoFile.getAbsolutePath());

            int orientation = exifInterface.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);

            // Calculate rotation angle based on EXIF orientation
            int rotationAngle = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                    rotationAngle = 90;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                    rotationAngle = 180;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                    rotationAngle = 270;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL:
                default:
                    rotationAngle = 0;
                    break;
            }

            // If no rotation needed, return original bitmap
            if (rotationAngle == 0) {
                return originalBitmap;
            }

            // Create rotation matrix
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationAngle);

            // Apply rotation
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0,
                originalBitmap.getWidth(), originalBitmap.getHeight(),
                matrix, true);

            // Recycle original bitmap to free memory
            if (rotatedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }

            return rotatedBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error applying rotation correction", e);
            return originalBitmap; // Return original on error
        }
    }

    /**
     * Calculate sample size for bitmap loading
     */
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    /**
     * Show detailed photo statistics
     */
    private void showPhotoStatistics(File[] photos) {
        if (photos == null || photos.length == 0) {
            Toast.makeText(this, "No photos to analyze", Toast.LENGTH_SHORT).show();
            return;
        }

        long totalSize = 0;
        File oldestPhoto = photos[0];
        File newestPhoto = photos[0];

        for (File photo : photos) {
            totalSize += photo.length();
            if (photo.lastModified() < oldestPhoto.lastModified()) {
                oldestPhoto = photo;
            }
            if (photo.lastModified() > newestPhoto.lastModified()) {
                newestPhoto = photo;
            }
        }

        String oldestDate = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            .format(new java.util.Date(oldestPhoto.lastModified()));
        String newestDate = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            .format(new java.util.Date(newestPhoto.lastModified()));

        long totalSizeKB = totalSize / 1024;
        double averageSizeKB = photos.length > 0 ? (double)totalSizeKB / photos.length : 0;

        StringBuilder stats = new StringBuilder();
        stats.append("PHOTO STATISTICS\n\n");
        stats.append("Total Photos: ").append(photos.length).append("\n");
        stats.append("Total Size: ").append(totalSizeKB).append(" KB\n");
        stats.append("Average Size: ").append(String.format("%.1f", averageSizeKB)).append(" KB\n\n");
        stats.append("Oldest Photo: ").append(oldestDate).append("\n");
        stats.append("Newest Photo: ").append(newestDate).append("\n");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Photo Statistics");
        builder.setMessage(stats.toString());
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    /**
     * Show individual photo in a dialog with options - FIXED: Handle image rotation properly
     */
    private void showPhotoDialog(File photoFile) {
        try {
            // Load and display the photo with proper orientation
            Bitmap bitmap = loadBitmapWithCorrectOrientation(photoFile);
            if (bitmap == null) {
                Toast.makeText(this, "Failed to load photo", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create ImageView
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmap);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

            // Create scrollable container
            ScrollView scrollView = new ScrollView(this);
            scrollView.addView(imageView);

            // Get photo info
            long sizeKB = photoFile.length() / 1024;
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(photoFile.lastModified()));

            String title = photoFile.getName() + "\n" + sizeKB + " KB - " + date;

            AlertDialog photoDialog = new AlertDialog.Builder(this)
                .setTitle("Intruder Photo")
                .setMessage(title)
                .setView(scrollView)
                .setPositiveButton("Delete", (dialog, which) -> {
                    deletePhoto(photoFile, () -> {
                        // Refresh the photos list if it's still showing
                        if (currentPhotosListDialog != null && currentPhotosListDialog.isShowing()) {
                            currentPhotosListDialog.dismiss();
                            viewSecurityPhotos(); // Reopen with updated list
                        }
                    });
                })
                .setNeutralButton("Share", (dialog, which) -> {
                    sharePhoto(photoFile);
                })
                .setNegativeButton("Close", null)
                .create();

            photoDialog.show();

            // Adjust dialog size
            photoDialog.getWindow().setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );

        } catch (Exception e) {
            Toast.makeText(this, "Error loading photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error showing photo dialog", e);
        }
    }

    /**
     * Load bitmap with correct orientation based on EXIF data
     */
    private Bitmap loadBitmapWithCorrectOrientation(File photoFile) {
        try {
            // First, decode the image
            Bitmap originalBitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
            if (originalBitmap == null) {
                return null;
            }

            // Read EXIF data to get orientation
            androidx.exifinterface.media.ExifInterface exifInterface =
                new androidx.exifinterface.media.ExifInterface(photoFile.getAbsolutePath());

            int orientation = exifInterface.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);

            // Calculate rotation angle based on EXIF orientation
            int rotationAngle = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                    rotationAngle = 90;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                    rotationAngle = 180;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                    rotationAngle = 270;
                    break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL:
                default:
                    rotationAngle = 0;
                    break;
            }


            // If no rotation needed, return original bitmap
            if (rotationAngle == 0) {
                return originalBitmap;
            }

            // Create rotation matrix
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationAngle);

            // Apply rotation
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                originalBitmap, 0, 0,
                originalBitmap.getWidth(), originalBitmap.getHeight(),
                matrix, true);

            // Recycle original bitmap to free memory
            if (rotatedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }

            Log.d(TAG, "Image rotated " + rotationAngle + " degrees for display");
            return rotatedBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap with correct orientation", e);

            // Fallback: try to load without rotation
            try {
                return BitmapFactory.decodeFile(photoFile.getAbsolutePath());
            } catch (Exception fallbackException) {
                Log.e(TAG, "Fallback bitmap loading also failed", fallbackException);
                return null;
            }
        }
    }

    /**
     * Get current user email for display and sharing
     */
    private String getCurrentUserEmail() {
        // Try Firebase Auth first (if available)
        if (mAuth != null) {
            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null && currentUser.getEmail() != null) {
                return currentUser.getEmail();
            }
        }

        // Fallback to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
        String email = prefs.getString("user_email", "");

        if (email != null && !email.isEmpty()) {
            return email;
        }

        return "No email available";
    }

    /**
     * Show account information with username editing functionality
     */
    private void showAccountInfo() {
        String userEmail = getCurrentUserEmail();
        String userName = "User";

        // Try to get username from Firebase if available
        if (mAuth != null) {
            FirebaseUser user = mAuth.getCurrentUser();
            if (user != null && user.getDisplayName() != null) {
                userName = user.getDisplayName();
            }
        }

        // Fallback to SharedPreferences
        if ("User".equals(userName)) {
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            userName = prefs.getString("user_name", "User");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("👤 Account Information");

        StringBuilder accountInfo = new StringBuilder();
        accountInfo.append("Name: ").append(userName).append("\n\n");
        accountInfo.append("Email: ").append(userEmail).append("\n\n");

        // Add service status
        if (mAuth == null || db == null) {
            accountInfo.append("Status: Limited mode (Cloud features disabled)\n");
        } else {
            accountInfo.append("Status: Full access\n");
        }

        builder.setMessage(accountInfo.toString());

        // Always show edit button (works with SharedPreferences fallback)
        builder.setPositiveButton("✏️ Edit Name", (dialog, which) -> {
            showEditUsernameDialog();
        });

        builder.setNegativeButton("Close", null);
        builder.show();
    }

    /**
     * Show dialog to edit username
     */
    private void showEditUsernameDialog() {
        // Get current username
        String currentUserName = "User";

        // Try to get from Firebase first
        if (mAuth != null) {
            FirebaseUser user = mAuth.getCurrentUser();
            if (user != null && user.getDisplayName() != null) {
                currentUserName = user.getDisplayName();
            }
        }

        // Fallback to SharedPreferences
        if ("User".equals(currentUserName)) {
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            currentUserName = prefs.getString("user_name", "User");
        }

        // Create input field
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(currentUserName);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        input.setHint("Enter your name");

        // Set padding for better appearance
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("✏️ Edit Name");
        builder.setMessage("Enter your preferred display name:");
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newUserName = input.getText().toString().trim();

            if (newUserName.isEmpty()) {
                Toast.makeText(this, "❌ Name cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            if (newUserName.length() > 50) {
                Toast.makeText(this, "❌ Name too long (max 50 characters)", Toast.LENGTH_SHORT).show();
                return;
            }

            updateUserName(newUserName);
        });

        builder.setNegativeButton("Cancel", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // Focus the input and show keyboard
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /**
     * Update username in both Firebase and SharedPreferences
     */
    private void updateUserName(String newUserName) {
        try {
            // Save to SharedPreferences first (always works)
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit().putString("user_name", newUserName).apply();

            // Also save to get_intruder preferences for compatibility
            SharedPreferences getIntruderPrefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
            getIntruderPrefs.edit().putString("user_name", newUserName).apply();

            Log.d(TAG, "Username updated in SharedPreferences: " + newUserName);

            // Update Firebase profile if available
            if (mAuth != null) {
                FirebaseUser user = mAuth.getCurrentUser();
                if (user != null) {
                    UserProfileChangeRequest profileUpdates = new UserProfileChangeRequest.Builder()
                        .setDisplayName(newUserName)
                        .build();

                    user.updateProfile(profileUpdates)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Firebase profile updated successfully: " + newUserName);
                            Toast.makeText(this, "✅ Name updated: " + newUserName, Toast.LENGTH_SHORT).show();
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "❌ Failed to update Firebase profile", e);
                            // Still show success since SharedPreferences update worked
                            Toast.makeText(this, "✅ Name updated: " + newUserName + " (local only)", Toast.LENGTH_LONG).show();
                        });
                } else {
                    Toast.makeText(this, "✅ Name updated: " + newUserName, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "✅ Name updated: " + newUserName, Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating username", e);
            Toast.makeText(this, "❌ Error updating name: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Confirm sign out with dialog
     */
    private void confirmSignOut() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🚪 Sign Out");
        builder.setMessage("Are you sure you want to sign out?\n\nThis will clear all local data and return you to the login screen.");

        builder.setPositiveButton("Yes, Sign Out", (dialog, which) -> {
            performSignOut();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Perform sign out operation
     */
    private void performSignOut() {
        try {
            // Sign out from Firebase if available
            if (mAuth != null) {
                mAuth.signOut();
            }

            // Clear local data
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit().clear().apply();

            SharedPreferences getIntruderPrefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
            getIntruderPrefs.edit().clear().apply();

            // Show toast
            Toast.makeText(this, "👋 Signed out successfully", Toast.LENGTH_SHORT).show();

            // Redirect to auth activity
            Intent intent = new Intent(this, AuthActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();

        } catch (Exception e) {
            Log.e(TAG, "Error signing out", e);
            Toast.makeText(this, "Error signing out: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Register broadcast receiver to listen for photo captures
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerPhotoCaptureReceiver() {
        if (photoCaptureReceiver == null) {
            photoCaptureReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.example.securityapp.PHOTO_CAPTURED".equals(intent.getAction())) {
                        String photoFileName = intent.getStringExtra("photo_filename");
                        Log.d(TAG, "📸 Received photo capture broadcast: " + photoFileName);

                        // Update stats immediately
                        updateStats();

                        // Show brief notification that photo was captured and email sent
                        Toast.makeText(MainActivity.this,
                            "📸 Intruder photo captured\n📧 Email notification sent",
                            Toast.LENGTH_LONG).show();
                    }
                }
            };
        }

        IntentFilter filter = new IntentFilter("com.example.securityapp.PHOTO_CAPTURED");

        // Register with proper flags for Android API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(photoCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            registerReceiver(photoCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // For older Android versions
            registerReceiver(photoCaptureReceiver, filter);
        }

        Log.d(TAG, "Photo capture broadcast receiver registered with proper flags");
    }

    /**
     * Unregister broadcast receiver
     */
    private void unregisterPhotoCaptureReceiver() {
        if (photoCaptureReceiver != null) {
            try {
                unregisterReceiver(photoCaptureReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering photo capture receiver", e);
            }
        }
    }

    /**
     * Show cloud photos list for admin view
     */
    private void showCloudPhotosListForAdmin() {
        if (mAuth == null || db == null) {
            Toast.makeText(this, "Cloud features not available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (cloudPhotoRefs.isEmpty()) {
            Toast.makeText(this, "No cloud photos found", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("☁️ Cloud Photos (" + cloudPhotoRefs.size() + ")");

        // Create list of cloud photo names
        String[] photoNames = new String[cloudPhotoRefs.size()];
        for (int i = 0; i < cloudPhotoRefs.size(); i++) {
            com.google.firebase.storage.StorageReference photoRef = cloudPhotoRefs.get(i);
            String name = photoRef.getName();

            // Try to extract timestamp from filename for better display
            try {
                if (name.contains("_")) {
                    String[] parts = name.split("_");
                    if (parts.length >= 3) {
                        String datePart = parts[1];
                        String timePart = parts[2].replace(".jpg", "");
                        if (datePart.length() == 8 && timePart.length() == 6) {
                            String formattedDate = datePart.substring(6, 8) + "/" +
                                                 datePart.substring(4, 6) + "/" +
                                                 datePart.substring(2, 4);
                            String formattedTime = timePart.substring(0, 2) + ":" +
                                                 timePart.substring(2, 4);
                            photoNames[i] = formattedDate + " " + formattedTime + "\n" + name;
                        } else {
                            photoNames[i] = name;
                        }
                    } else {
                        photoNames[i] = name;
                    }
                } else {
                    photoNames[i] = name;
                }
            } catch (Exception e) {
                photoNames[i] = name;
            }
        }

        builder.setItems(photoNames, (dialog, which) -> {
            com.google.firebase.storage.StorageReference selectedPhotoRef = cloudPhotoRefs.get(which);
            showCloudPhotoOptions(selectedPhotoRef);
        });

        builder.setNegativeButton("Close", null);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Show options for a selected cloud photo
     */
    private void showCloudPhotoOptions(com.google.firebase.storage.StorageReference photoRef) {
        String photoName = photoRef.getName();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("☁️ " + photoName);

        String[] options = {"📥 Download", "🗑️ Delete from Cloud", "ℹ️ Photo Info"};

        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0: // Download
                    downloadSingleCloudPhoto(photoRef);
                    break;
                case 1: // Delete
                    confirmDeleteCloudPhoto(photoRef);
                    break;
                case 2: // Info
                    showCloudPhotoInfo(photoRef);
                    break;
            }
        });

        builder.setNegativeButton("Back", null);
        builder.show();
    }

    /**
     * Download a single cloud photo
     */
    private void downloadSingleCloudPhoto(com.google.firebase.storage.StorageReference photoRef) {
        if (!NetworkHelper.isNetworkAvailable(this)) {
            Toast.makeText(this, "❌ No internet connection", Toast.LENGTH_SHORT).show();
            return;
        }

        String fileName = photoRef.getName();
        Toast.makeText(this, "📥 Downloading " + fileName + "...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // Create download location in gallery
                File galleryDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "GetIntruder");

                if (!galleryDir.exists()) {
                    galleryDir.mkdirs();
                }

                // Create unique filename
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
                String uniqueFileName = "cloud_" + timestamp + "_" + fileName;
                File localFile = new File(galleryDir, uniqueFileName);

                // Download synchronously
                com.google.android.gms.tasks.Tasks.await(photoRef.getFile(localFile));

                // Add to gallery
                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScanIntent.setData(android.net.Uri.fromFile(localFile));
                sendBroadcast(mediaScanIntent);

                runOnUiThread(() -> {
                    Toast.makeText(this, "✅ Downloaded: " + fileName, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error downloading cloud photo", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "❌ Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /**
     * Confirm deletion of cloud photo
     */
    private void confirmDeleteCloudPhoto(com.google.firebase.storage.StorageReference photoRef) {
        String fileName = photoRef.getName();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🗑️ Delete Cloud Photo");
        builder.setMessage("Are you sure you want to delete this photo from cloud storage?\n\n" +
                          fileName + "\n\n⚠️ This cannot be undone!");

        builder.setPositiveButton("Delete", (dialog, which) -> {
            deleteCloudPhoto(photoRef);
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Delete cloud photo
     */
    private void deleteCloudPhoto(com.google.firebase.storage.StorageReference photoRef) {
        String fileName = photoRef.getName();
        Toast.makeText(this, "🗑️ Deleting " + fileName + "...", Toast.LENGTH_SHORT).show();

        photoRef.delete()
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ Cloud photo deleted: " + fileName);
                Toast.makeText(this, "✅ Deleted: " + fileName, Toast.LENGTH_SHORT).show();

                // Remove from local list
                cloudPhotoRefs.remove(photoRef);
                currentCloudPhotoCount--;
            })
            .addOnFailureListener(exception -> {
                Log.e(TAG, "❌ Failed to delete cloud photo: " + fileName, exception);
                Toast.makeText(this, "❌ Delete failed: " + exception.getMessage(), Toast.LENGTH_LONG).show();
            });
    }

    /**
     * Show cloud photo information
     */
    private void showCloudPhotoInfo(com.google.firebase.storage.StorageReference photoRef) {
        String fileName = photoRef.getName();
        Toast.makeText(this, "📊 Loading photo info...", Toast.LENGTH_SHORT).show();

        photoRef.getMetadata()
            .addOnSuccessListener(metadata -> {
                StringBuilder info = new StringBuilder();
                info.append("📁 Name: ").append(fileName).append("\n\n");

                if (metadata.getSizeBytes() > 0) {
                    long sizeKB = metadata.getSizeBytes() / 1024;
                    info.append("💾 Size: ").append(sizeKB).append(" KB\n\n");
                }

                if (metadata.getCreationTimeMillis() != 0) {
                    String uploadTime = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
                        .format(new java.util.Date(metadata.getCreationTimeMillis()));
                    info.append("📅 Uploaded: ").append(uploadTime).append("\n\n");
                }

                if (metadata.getCustomMetadata("deviceModel") != null) {
                    info.append("📱 Device: ").append(metadata.getCustomMetadata("deviceModel")).append("\n\n");
                }

                info.append("🌐 Path: ").append(photoRef.getPath());

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("ℹ️ Photo Information");
                builder.setMessage(info.toString());
                builder.setPositiveButton("OK", null);
                builder.show();
            })
            .addOnFailureListener(exception -> {
                Log.e(TAG, "Failed to get photo metadata", exception);
                Toast.makeText(this, "❌ Failed to load photo info", Toast.LENGTH_SHORT).show();
            });
    }
}
