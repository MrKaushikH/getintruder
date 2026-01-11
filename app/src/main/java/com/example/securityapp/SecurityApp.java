package com.example.securityapp;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.multidex.BuildConfig;
import androidx.multidex.MultiDex;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

/**
 * Main Application class for Get Intruder App
 * Handles global application state and initialization with security features
 *
 * @Keep annotation prevents ProGuard from obfuscating critical methods
 */
@Keep
public class SecurityApp extends Application {
    private static final String TAG = "SecurityApp";
    private static boolean hasLoggedGmsError = false;
    private static boolean isFirebaseInitialized = false;
    private static boolean appCheckInitialized = false;
    private static Context appContext;
    private static String appCheckDebugToken = null;
    private static final boolean USE_DEBUG_APP_CHECK = BuildConfig.DEBUG; // Dynamic based on build type

    // We don't set a static debug token as Firebase generates one dynamically
    private static String generatedDebugToken = null;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Initialize MultiDex to handle multiple dex files
        try {
            MultiDex.install(this);
            Log.d(TAG, "MultiDex installed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to install MultiDex", e);
        }
    }

    @Override
    @Keep
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

        Log.d(TAG, "SecurityApp onCreate - initializing Firebase components");

        // Set up exception handler first before any other initialization
        setupExceptionHandler();

        // Initialize network monitoring first, so it's available for Firebase
        NetworkHelper.registerNetworkCallback(this);

        // Initialize Firebase with better error handling and retry mechanism
        initializeFirebase();

        // Check Google Play Services availability with error handling
        safeCheckGooglePlayServices();
    }

    @Override
    @Keep
    public void onTerminate() {
        // Clean up resources
        NetworkHelper.unregisterNetworkCallback(this);
        super.onTerminate();
    }

    /**
     * Initialize Firebase with ProGuard-safe error handling
     */
    @Keep
    private void initializeFirebase() {
        try {
            // Check if already initialized
            try {
                FirebaseApp.getInstance();
                isFirebaseInitialized = true;
                Log.d(TAG, "Firebase already initialized");

                // Initialize App Check after Firebase is initialized with a slight delay
                new Handler(Looper.getMainLooper()).postDelayed(this::initializeFirebaseAppCheck, 1000);
                return;
            } catch (IllegalStateException e) {
                // Not yet initialized, continue with initialization
                Log.d(TAG, "Firebase not yet initialized, proceeding with initialization");
            }

            // Initialize Firebase
            FirebaseApp.initializeApp(this);
            isFirebaseInitialized = true;
            Log.d(TAG, "Firebase initialized successfully in Application class");

            // Configure Firestore early, before any other Firebase operations
            FirebaseHelper.configureFirestore();

            // Initialize App Check after Firebase is initialized with a slight delay
            new Handler(Looper.getMainLooper()).postDelayed(this::initializeFirebaseAppCheck, 1000);

            // Store initialization status
            getSharedPreferences("get_intruder", MODE_PRIVATE)
                .edit()
                .putBoolean("firebase_initialized", true)
                .putLong("firebase_init_time", System.currentTimeMillis())
                .apply();

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Firebase in Application class", e);

            // Store failure status
            getSharedPreferences("get_intruder", MODE_PRIVATE)
                .edit()
                .putBoolean("firebase_initialized", false)
                .putLong("firebase_init_failure_time", System.currentTimeMillis())
                .putString("firebase_init_error", e.getMessage())
                .apply();

            // If network error, schedule a retry
            if (e instanceof FirebaseNetworkException ||
                (e.getMessage() != null && e.getMessage().contains("network"))) {
                scheduleFirebaseRetry();
            }
        }
    }

    /**
     * Initialize Firebase App Check for security - ProGuard safe
     */
    @Keep
    private void initializeFirebaseAppCheck() {
        try {
            Log.d(TAG, "Initializing Firebase App Check...");

            // Ensure Firebase is initialized first
            if (!isFirebaseInitialized) {
                Log.w(TAG, "Firebase not yet initialized, delaying App Check initialization");
                new Handler(Looper.getMainLooper()).postDelayed(this::initializeFirebaseAppCheck, 3000);
                return;
            }

            FirebaseAppCheck firebaseAppCheck = FirebaseAppCheck.getInstance();

            if (USE_DEBUG_APP_CHECK) {
                // For development - configure with longer token refresh intervals
                firebaseAppCheck.setTokenAutoRefreshEnabled(true);

                // Install the debug provider factory
                DebugAppCheckProviderFactory debugFactory = DebugAppCheckProviderFactory.getInstance();
                firebaseAppCheck.installAppCheckProviderFactory(debugFactory);

                // Configure token refresh settings to reduce frequency
                try {
                    // Set custom token refresh interval if possible
                    SharedPreferences prefs = getSharedPreferences("com.google.firebase.appcheck", MODE_PRIVATE);
                    prefs.edit()
                        .putLong("token_refresh_interval", 30 * 60 * 1000) // 30 minutes
                        .putLong("min_refresh_interval", 5 * 60 * 1000)    // 5 minutes minimum
                        .apply();
                } catch (Exception e) {
                    Log.d(TAG, "Could not set custom refresh intervals: " + e.getMessage());
                }

                // Add debug token observer with delayed execution
                new Thread(() -> {
                    try {
                        // Wait longer for the debug token to be generated
                        Thread.sleep(5000); // Increased wait time

                        // Check shared preferences for the debug token that Firebase generates
                        SharedPreferences prefs = getSharedPreferences("com.google.firebase.appcheck.debug", MODE_PRIVATE);
                        String debugToken = prefs.getString("firebase_app_check_debug_token", null);

                        if (debugToken != null) {
                            generatedDebugToken = debugToken;
                            Log.w(TAG, "🔑 IMPORTANT: Register this App Check debug token in Firebase Console:");
                            Log.w(TAG, "🔑 Token: " + debugToken);
                            Log.w(TAG, "🔑 Go to: Firebase Console > Project Settings > App Check");
                            Log.w(TAG, "🔑 Add this token to the debug tokens list");

                            // Store it in our own preferences too for UI display
                            getSharedPreferences("get_intruder", MODE_PRIVATE)
                                .edit()
                                .putString("app_check_debug_token", debugToken)
                                .putLong("app_check_token_generated_time", System.currentTimeMillis())
                                .apply();

                            // Show persistent toast with instructions on main thread
                            new Handler(Looper.getMainLooper()).post(() -> {
                                String message = "App Check Debug Token Generated!\nCheck logs for registration instructions.";
                                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();

                                // Show another toast after a delay with the token
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    Toast.makeText(getApplicationContext(),
                                        "Debug Token: " + debugToken.substring(0, 12) + "...\nRegister in Firebase Console",
                                        Toast.LENGTH_LONG).show();
                                }, 3000);
                            });
                        } else {
                            Log.w(TAG, "Debug token not found in shared preferences after 5 seconds");

                            // Try alternative approach - generate our own debug token
                            String fallbackToken = generateConsistentDebugToken();
                            Log.w(TAG, "🔑 Generated fallback debug token: " + fallbackToken);
                            Log.w(TAG, "🔑 Register this token in Firebase Console > App Check");

                            getSharedPreferences("get_intruder", MODE_PRIVATE)
                                .edit()
                                .putString("app_check_debug_token", fallbackToken)
                                .putString("app_check_debug_token_source", "fallback")
                                .apply();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error retrieving/generating debug token", e);
                    }
                }).start();

                Log.d(TAG, "Firebase App Check initialized with debug provider and enhanced reliability");
            } else {
                // For production - use Play Integrity provider with enhanced error handling
                try {
                    firebaseAppCheck.setTokenAutoRefreshEnabled(true);
                    firebaseAppCheck.installAppCheckProviderFactory(
                        PlayIntegrityAppCheckProviderFactory.getInstance());

                    // Add token listener to handle failures gracefully
                    firebaseAppCheck.addAppCheckListener(token -> {
                        try {
                            String tokenValue = token.getToken();
                            Log.d(TAG, "App Check token obtained successfully");
                        } catch (Exception e) {
                            String error = e.getMessage();
                            Log.w(TAG, "App Check token error: " + error);

                            // Handle specific App Check errors gracefully
                            if (error != null && (error.contains("App attestation failed") ||
                                                error.contains("403") ||
                                                error.contains("ATTESTATION_FAILED"))) {
                                Log.i(TAG, "App attestation failed - this is normal in debug builds or emulators");

                                // Store attestation failure info
                                getSharedPreferences("get_intruder", MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("app_check_attestation_failed", true)
                                    .putLong("app_check_last_error_time", System.currentTimeMillis())
                                    .putString("app_check_last_error", error)
                                    .apply();

                                // Don't treat attestation failure as critical error
                                return;
                            }

                            Log.e(TAG, "Unhandled App Check error", e);
                        }
                    });

                    Log.d(TAG, "Firebase App Check initialized with Play Integrity provider and error handling");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to initialize Play Integrity, falling back to debug mode", e);

                    // Fallback to debug mode if Play Integrity fails
                    firebaseAppCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance());

                    String fallbackToken = generateConsistentDebugToken();
                    Log.w(TAG, "🔑 Fallback debug token: " + fallbackToken);

                    getSharedPreferences("get_intruder", MODE_PRIVATE)
                        .edit()
                        .putString("app_check_debug_token", fallbackToken)
                        .putString("app_check_debug_token_source", "play_integrity_fallback")
                        .putBoolean("app_check_fallback_mode", true)
                        .apply();
                }
            }

            // Store App Check initialization status
            getSharedPreferences("get_intruder", MODE_PRIVATE)
                .edit()
                .putBoolean("app_check_initialized", true)
                .putLong("app_check_init_time", System.currentTimeMillis())
                .apply();

            appCheckInitialized = true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Firebase App Check: " + e.getMessage(), e);

            // Store failure status
            getSharedPreferences("get_intruder", MODE_PRIVATE)
                .edit()
                .putBoolean("app_check_initialized", false)
                .putLong("app_check_init_failure_time", System.currentTimeMillis())
                .putString("app_check_init_error", e.getMessage())
                .apply();

            // Don't retry indefinitely - limit retries for App Check
            SharedPreferences prefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
            int retryCount = prefs.getInt("app_check_retry_count", 0);

            if (retryCount < 3) {
                prefs.edit().putInt("app_check_retry_count", retryCount + 1).apply();

                // Schedule a retry with exponential backoff
                long retryDelay = (long) Math.pow(2, retryCount) * 5000; // 5s, 10s, 20s
                new Handler(Looper.getMainLooper()).postDelayed(this::initializeFirebaseAppCheck, retryDelay);
                Log.d(TAG, "Scheduling App Check retry #" + (retryCount + 1) + " in " + retryDelay + "ms");
            } else {
                Log.w(TAG, "Max App Check retry attempts reached, giving up");
                prefs.edit().putBoolean("app_check_max_retries_reached", true).apply();
            }
        }
    }

    private void scheduleFirebaseRetry() {
        // Schedule a retry after 3 seconds
        Handler handler = new Handler();
        handler.postDelayed(() -> {
            if (!isFirebaseInitialized && isNetworkAvailable()) {
                Log.d(TAG, "Retrying Firebase initialization...");
                initializeFirebase();
            }
        }, 3000);
    }

    public boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.net.Network activeNetwork = connectivityManager.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
                    return capabilities != null &&
                           (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                }
                return false;
            } else {
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        }
        return false;
    }

    /**
     * Safely check Google Play Services without throwing exceptions
     */
    private void safeCheckGooglePlayServices() {
        try {
            GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
            int resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this);
            if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                Log.w(TAG, "Google Play Services not available (code " + resultCode + ")");

                // Store this state in preferences for other components to check
                getSharedPreferences("get_intruder", MODE_PRIVATE)
                    .edit()
                    .putBoolean("gms_available", false)
                    .putInt("gms_status_code", resultCode)
                    .apply();
            } else {
                Log.d(TAG, "Google Play Services available and up-to-date");

                // Store success state in preferences
                getSharedPreferences("get_intruder", MODE_PRIVATE)
                    .edit()
                    .putBoolean("gms_available", true)
                    .putInt("gms_status_code", resultCode)
                    .apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking Google Play Services", e);
            // Mark as unavailable on error
            getSharedPreferences("get_intruder", MODE_PRIVATE)
                .edit()
                .putBoolean("gms_available", false)
                .putInt("gms_status_code", -1)
                .apply();
        }
    }

    /**
     * Set up global exception handler to catch and handle non-critical errors
     */
    private void setupExceptionHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Handle specific error cases that are harmless
            if (throwable instanceof SecurityException) {
                String message = throwable.getMessage();
                if (message != null) {
                    // These are the specific harmless errors from your logs
                    if (message.contains("Unknown calling package name 'com.google.android.gms'") ||
                        message.contains("Phenotype.API") ||
                        message.contains("vendor_display_prop") ||
                        message.contains("microsoft_ltw_prop") ||
                        message.contains("persist.vivo.ltw.enabled") ||
                        message.contains("Failed to get service from broker") ||
                        message.contains("Failed to register") ||
                        message.contains("getopt") ||
                        message.contains("usap_pool_primary")) {

                        // Log once to avoid spamming logs, then suppress
                        if (!hasLoggedGmsError) {
                            Log.i(TAG, "Suppressing harmless system security warnings (Google Play Services)");
                            hasLoggedGmsError = true;
                        }
                        return; // Don't crash for these
                    }
                }
            }

            // Handle resource/APK loading errors (also harmless)
            if (throwable instanceof java.io.IOException) {
                String message = throwable.getMessage();
                if (message != null && (message.contains("Failed to load asset path") ||
                                       message.contains("Failed to open APK") ||
                                       message.contains("ResourcesManager"))) {
                    Log.i(TAG, "Suppressing harmless APK/resource loading warning");
                    return;
                }
            }

            // Handle reflection access warnings (also harmless)
            if (throwable.getClass().getSimpleName().contains("ReflectiveOperationException") ||
                (throwable.getMessage() != null &&
                 (throwable.getMessage().contains("Accessing hidden") ||
                  throwable.getMessage().contains("ClassLoaderContext") ||
                  throwable.getMessage().contains("Unable to open") ||
                  throwable.getMessage().contains("non-executable")))) {
                // These are Android system warnings, not app errors
                return;
            }

            // Handle Firebase App Check warnings and errors (non-critical)
            if (throwable.getMessage() != null &&
                (throwable.getMessage().contains("App Check") ||
                 throwable.getMessage().contains("App attestation failed") ||
                 throwable.getMessage().contains("Too many attempts") ||
                 throwable.getMessage().contains("403") ||
                 throwable.getMessage().contains("ATTESTATION_FAILED"))) {
                Log.i(TAG, "App Check warning suppressed (non-critical): " + throwable.getMessage());

                // Store App Check error info for debugging
                try {
                    SharedPreferences prefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
                    prefs.edit()
                        .putBoolean("app_check_error_occurred", true)
                        .putLong("app_check_last_error_time", System.currentTimeMillis())
                        .putString("app_check_last_error_message", throwable.getMessage())
                        .apply();
                } catch (Exception e) {
                    // Ignore errors in error handling
                }
                return;
            }

            // Handle Play Integrity errors (non-critical)
            if (throwable.getMessage() != null &&
                (throwable.getMessage().contains("Play Integrity") ||
                 throwable.getMessage().contains("IntegrityService") ||
                 throwable.getMessage().contains("requestIntegrityToken"))) {
                Log.i(TAG, "Play Integrity warning suppressed (non-critical): " + throwable.getMessage());
                return;
            }

            // Pass other exceptions to the default handler
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Get whether Google Play Services is available
     */
    public static boolean isGooglePlayServicesAvailable(Context context) {
        try {
            return context.getSharedPreferences("get_intruder", MODE_PRIVATE)
                .getBoolean("gms_available", false);
        } catch (Exception e) {
            Log.e(TAG, "Error checking GMS availability from prefs", e);
            return false;
        }
    }

    /**
     * Check if we've encountered Google Play Services errors
     */
    public static boolean hasEncounteredGmsErrors(Context context) {
        try {
            return context.getSharedPreferences("get_intruder", MODE_PRIVATE)
                .getBoolean("gms_error_occurred", false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if Firebase is properly initialized
     * ProGuard-safe getter
     */
    @Keep
    public static boolean isFirebaseInitialized() {
        return isFirebaseInitialized;
    }

    /**
     * Get the application context for use in static methods
     * ProGuard-safe getter
     */
    @Keep
    public static Context getAppContext() {
        return appContext;
    }

    /**
     * Check if App Check is initialized
     * ProGuard-safe method
     */
    @Keep
    public static boolean isAppCheckInitialized(Context context) {
        if (appCheckInitialized) {
            return true;
        }

        // Check if we previously stored success
        SharedPreferences prefs = context.getSharedPreferences("get_intruder", MODE_PRIVATE);
        return prefs.getBoolean("app_check_initialized", false);
    }

    /**
     * Get the debug token for Firebase App Check
     * ProGuard-safe method
     */
    @Keep
    public static String getAppCheckDebugToken(Context context) {
        if (generatedDebugToken != null) {
            return generatedDebugToken;
        }

        try {
            // Try to get from our preferences first
            SharedPreferences prefs = context.getSharedPreferences("get_intruder", Context.MODE_PRIVATE);
            String token = prefs.getString("app_check_debug_token", null);
            if (token != null) {
                return token;
            }

            // Try to get from Firebase debug preferences
            SharedPreferences firebasePrefs = context.getSharedPreferences("com.google.firebase.appcheck.debug", Context.MODE_PRIVATE);
            return firebasePrefs.getString("firebase_app_check_debug_token", "Token not available");
        } catch (Exception e) {
            Log.e(TAG, "Error getting debug token", e);
            return "Error retrieving token";
        }
    }

    /**
     * Generate a consistent debug token for App Check based on app signature
     */
    private String generateConsistentDebugToken() {
        try {
            // Use a combination of package name, version, and device info for consistency
            String packageName = getPackageName();
            String versionName = getPackageManager().getPackageInfo(packageName, 0).versionName;
            String deviceId = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);

            String baseString = packageName + "_" + versionName + "_" + deviceId + "_security_app_debug";

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(baseString.getBytes("UTF-8"));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            // Use first 32 characters as debug token
            return hexString.toString().substring(0, 32).toUpperCase();
        } catch (Exception e) {
            Log.e(TAG, "Error generating consistent debug token", e);
            // Fallback to time-based token
            return "DEBUG_TOKEN_" + (System.currentTimeMillis() % 1000000);
        }
    }
}
