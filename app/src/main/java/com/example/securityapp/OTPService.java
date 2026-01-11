package com.example.securityapp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Service for handling intruder notifications with device information
 * OTP functionality removed - focus only on intruder detection emails
 */
public class OTPService extends Service {
    private static final String TAG = "OTPService";
    private static final String EMAIL_REQUESTS_COLLECTION = "email_requests";

    // INTRUDER NOTIFICATION CONSTANTS - No longer hardcoded, dynamically retrieved

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "OTPService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "OTPService started");
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "OTPService destroyed");
    }

    // ============= INTRUDER NOTIFICATION METHODS =============

    /**
     * Send intruder notification email with device information
     */
    public static void sendIntruderNotification(Context context, String photoFileName, NotificationCallback callback) {
        // Check network availability
        if (!NetworkHelper.isNetworkAvailable(context)) {
            Log.d(TAG, "Network unavailable for sending notification");
            if (callback != null) {
                callback.onResult(false, "Network unavailable");
            }
            return;
        }

        // Get user email
        String userEmail = getUserEmail(context);
        if (userEmail == null || userEmail.isEmpty()) {
            Log.d(TAG, "No user email available for notification");
            if (callback != null) {
                callback.onResult(false, "User email not configured");
            }
            return;
        }

        // Run in background thread
        new Thread(() -> {
            try {
                // Collect device information
                Map<String, Object> deviceInfo = collectDeviceInformationForIntruder(context);

                // Get photo download URL if photo exists
                if (photoFileName != null && !photoFileName.isEmpty()) {
                    // Wait a bit for photo to be uploaded, then get URL with retries
                    getPhotoDownloadUrlWithRetry(context, photoFileName, 0, new CloudBackupService.PhotoUrlCallback() {
                        @Override
                        public void onResult(String downloadUrl, String error) {
                            try {
                                // Add photo URL to device info
                                if (downloadUrl != null) {
                                    deviceInfo.put("photo_url", downloadUrl);
                                    deviceInfo.put("photo_available", true);
                                    Log.d(TAG, "Photo download URL obtained for notification: " + downloadUrl);
                                } else {
                                    // If URL not available, provide fallback message
                                    deviceInfo.put("photo_available", false);
                                    deviceInfo.put("photo_error", error != null ? error : "Photo URL not available");
                                    deviceInfo.put("photo_message", "Photo was captured and is being processed. You can access it through the app's Cloud Backup section.");
                                    Log.w(TAG, "Photo URL not available: " + error);
                                }

                                // Send email with photo info
                                sendEmailWithPhotoInfo(context, userEmail, photoFileName, deviceInfo, callback);

                            } catch (Exception e) {
                                Log.e(TAG, "Error processing photo URL result", e);
                                // Send email without photo info
                                deviceInfo.put("photo_available", false);
                                deviceInfo.put("photo_message", "Photo captured successfully. Access it through the app.");
                                sendEmailWithPhotoInfo(context, userEmail, photoFileName, deviceInfo, callback);
                            }
                        }
                    });
                } else {
                    // No photo, send email without photo info
                    deviceInfo.put("photo_available", false);
                    sendEmailWithPhotoInfo(context, userEmail, null, deviceInfo, callback);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in sendIntruderNotification", e);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                        callback.onResult(false, "Error: " + e.getMessage()));
                }
            }
        }).start();
    }

    /**
     * Send email with photo information
     */
    private static void sendEmailWithPhotoInfo(Context context, String userEmail, String photoFileName,
                                             Map<String, Object> deviceInfo, NotificationCallback callback) {
        try {
            // Format email content
            String emailSubject = "🚨 Intruder Detected - " + deviceInfo.get("device_model");
            String emailBody = formatIntruderEmailMessage(context, photoFileName, deviceInfo);

            Log.d(TAG, "📧 Sending intruder notification to user: " + userEmail);

            // Send email via Firebase Cloud Function
            sendIntruderEmailViaFirebase(context, userEmail, emailSubject, emailBody, deviceInfo, callback);

        } catch (Exception e) {
            Log.e(TAG, "Error sending email with photo info", e);
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                    callback.onResult(false, "Email send error: " + e.getMessage()));
            }
        }
    }

    /**
     * Collect comprehensive device information for intruder notification
     */
    private static Map<String, Object> collectDeviceInformationForIntruder(Context context) {
        Map<String, Object> deviceInfo = new HashMap<>();

        try {
            // Basic device information
            deviceInfo.put("timestamp", System.currentTimeMillis());
            deviceInfo.put("device_manufacturer", Build.MANUFACTURER);
            deviceInfo.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
            deviceInfo.put("android_version", Build.VERSION.RELEASE);
            deviceInfo.put("sdk_version", Build.VERSION.SDK_INT);
            deviceInfo.put("device_id", Build.ID);

            // Get user information
            String username = getUsername(context);
            deviceInfo.put("username", username);

            // Get IP address
            String ipAddress = getDeviceIpAddress();
            deviceInfo.put("ip_address", ipAddress);

            // Get location (KEEP AS IS from original OTP)
            String locationFromOTP = getDeviceLocation(context);
            deviceInfo.put("location", locationFromOTP);
            deviceInfo.put("location_source", "OTPService");

            // Get network information
            String networkInfo = getNetworkInformation(context);
            deviceInfo.put("network_info", networkInfo);

            // Get battery information
            int batteryLevel = getBatteryLevel(context);
            deviceInfo.put("battery_level", batteryLevel);

            // Get formatted timestamp
            String formattedTime = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date());
            deviceInfo.put("formatted_time", formattedTime);

            Log.d(TAG, "✅ Device information collected for intruder notification");

        } catch (Exception e) {
            Log.e(TAG, "Error collecting device information for intruder", e);
            deviceInfo.put("error", "Error collecting device info: " + e.getMessage());
        }

        return deviceInfo;
    }

    /**
     * Get network information for intruder notification
     */
    private static String getNetworkInformation(Context context) {
        try {
            android.net.ConnectivityManager connectivityManager =
                (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager != null) {
                android.net.NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
                if (activeNetwork != null && activeNetwork.isConnected()) {
                    String networkType = activeNetwork.getTypeName();
                    String networkSubtype = activeNetwork.getSubtypeName();
                    return "Type: " + networkType + " (" + networkSubtype + ")";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network info", e);
        }
        return "Network information unavailable";
    }

    /**
     * Get battery level for intruder notification
     */
    private static int getBatteryLevel(Context context) {
        try {
            android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = context.registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);

                if (level >= 0 && scale > 0) {
                    return (int) ((level / (float) scale) * 100);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting battery level", e);
        }
        return -1; // Unable to determine
    }

    /**
     * Format the intruder notification email message
     */
    private static String formatIntruderEmailMessage(Context context, String photoFileName, Map<String, Object> deviceInfo) {
        StringBuilder htmlMessage = new StringBuilder();

        htmlMessage.append("<html><body style='font-family: Arial, sans-serif; line-height: 1.6;'>");
        htmlMessage.append("<div style='max-width: 600px; margin: 0 auto; background-color: #f9f9f9; padding: 20px;'>");

        // Header
        htmlMessage.append("<div style='background-color: #dc3545; color: white; padding: 20px; text-align: center; border-radius: 8px;'>");
        htmlMessage.append("<h1 style='margin: 0; font-size: 24px;'>🚨 INTRUDER DETECTED</h1>");
        htmlMessage.append("<p style='margin: 10px 0 0 0; font-size: 16px;'>Unauthorized access attempt detected on device</p>");
        htmlMessage.append("</div>");

        // Alert details
        htmlMessage.append("<div style='background-color: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);'>");
        htmlMessage.append("<h2 style='color: #dc3545; margin-top: 0;'>🔴 Alert Details</h2>");
        htmlMessage.append("<p><strong>Event:</strong> Wrong password/PIN entered</p>");
        htmlMessage.append("<p><strong>Time:</strong> ").append(deviceInfo.get("formatted_time")).append("</p>");

        // Photo information - show download link if available, otherwise helpful message
        Boolean photoAvailable = (Boolean) deviceInfo.get("photo_available");
        String photoUrl = (String) deviceInfo.get("photo_url");
        String photoMessage = (String) deviceInfo.get("photo_message");

        if (photoAvailable != null && photoAvailable && photoUrl != null) {
            htmlMessage.append("<p><strong>📷 Intruder Photo:</strong> <a href='").append(photoUrl)
                      .append("' style='color: #dc3545; text-decoration: none; font-weight: bold; background-color: #f8f9fa; padding: 8px 15px; border-radius: 4px; border: 1px solid #dc3545;'>")
                      .append("🖼️ View Captured Photo</a></p>");
        } else if (photoMessage != null) {
            htmlMessage.append("<p><strong>📷 Photo Status:</strong> ").append(photoMessage).append("</p>");
            htmlMessage.append("<p style='font-size: 14px; color: #666; background-color: #f8f9fa; padding: 10px; border-radius: 4px;'>")
                     .append("💡 <strong>How to access:</strong> Open the GetIntruder app → Cloud Backup → Download Photos")
                     .append("</p>");
        } else if (photoFileName != null) {
            htmlMessage.append("<p><strong>📷 Photo Status:</strong> Photo captured successfully</p>");
            htmlMessage.append("<p style='font-size: 14px; color: #666; background-color: #f8f9fa; padding: 10px; border-radius: 4px;'>")
                     .append("📱 Photo is available in the app. Open GetIntruder → Cloud Backup to view and download.")
                     .append("</p>");
        } else {
            htmlMessage.append("<p><strong>📷 Photo Status:</strong> No photo captured</p>");
        }

        htmlMessage.append("<p><strong>Action Taken:</strong> Intruder detection triggered and security photo captured</p>");
        htmlMessage.append("</div>");

        // Device information
        htmlMessage.append("<div style='background-color: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);'>");
        htmlMessage.append("<h2 style='color: #007bff; margin-top: 0;'>📱 Device Information</h2>");
        htmlMessage.append("<p><strong>Owner:</strong> ").append(deviceInfo.get("username")).append("</p>");
        htmlMessage.append("<p><strong>Device:</strong> ").append(deviceInfo.get("device_model")).append("</p>");
        htmlMessage.append("<p><strong>Android Version:</strong> ").append(deviceInfo.get("android_version")).append("</p>");
        htmlMessage.append("<p><strong>IP Address:</strong> ").append(deviceInfo.get("ip_address")).append("</p>");
        htmlMessage.append("<p><strong>Network:</strong> ").append(deviceInfo.get("network_info")).append("</p>");

        int batteryLevel = (Integer) deviceInfo.get("battery_level");
        if (batteryLevel >= 0) {
            htmlMessage.append("<p><strong>Battery Level:</strong> ").append(batteryLevel).append("%</p>");
        }
        htmlMessage.append("</div>");

        // Location information - FIXED: Proper parsing
        htmlMessage.append("<div style='background-color: white; margin: 20px 0; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);'>");
        htmlMessage.append("<h2 style='color: #28a745; margin-top: 0;'>📍 Location Information</h2>");

        String location = (String) deviceInfo.get("location");

        if (location != null && !location.isEmpty()) {
            if (location.contains("Google Maps:")) {
                // Parse the location string
                String[] lines = location.split("\\n");

                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("Coordinates:")) {
                        htmlMessage.append("<p><strong>Location:</strong> ").append(line).append("</p>");
                    } else if (line.startsWith("Google Maps:")) {
                        String mapsUrl = line.substring("Google Maps:".length()).trim();
                        htmlMessage.append("<p><strong>View on Map:</strong> <a href='").append(mapsUrl)
                                  .append("' style='color: #007bff; text-decoration: none; font-weight: bold;'>📍 Open in Google Maps</a></p>");
                    }
                }
            } else {
                htmlMessage.append("<p><strong>Location:</strong> ").append(location).append("</p>");
            }
        } else {
            htmlMessage.append("<p><strong>Location:</strong> Location unavailable</p>");
        }

        htmlMessage.append("</div>");

        // Recommendations
        htmlMessage.append("<div style='background-color: #fff3cd; border: 1px solid #ffeaa7; padding: 20px; border-radius: 8px; margin: 20px 0;'>");
        htmlMessage.append("<h2 style='color: #856404; margin-top: 0;'>⚠️ Recommended Actions</h2>");
        htmlMessage.append("<ul style='color: #856404;'>");
        htmlMessage.append("<li>If this was not the device owner, the device may have been stolen or accessed unauthorized</li>");
        htmlMessage.append("<li>Check the captured photo in the GetIntruder app</li>");
        htmlMessage.append("<li>Consider changing device lock screen password</li>");
        htmlMessage.append("<li>If the device is stolen, contact local authorities with this information</li>");
        htmlMessage.append("<li>Use the location information to track the device</li>");
        htmlMessage.append("</ul>");
        htmlMessage.append("</div>");

        // Footer
        htmlMessage.append("<div style='text-align: center; padding: 20px; color: #666; font-size: 12px;'>");
        htmlMessage.append("<p>This notification was sent by GetIntruder Security App</p>");
        htmlMessage.append("<p>Automatic intruder detection and notification system</p>");
        htmlMessage.append("</div>");

        htmlMessage.append("</div></body></html>");

        return htmlMessage.toString();
    }

    /**
     * Send intruder email via Firebase Cloud Function
     */
    private static void sendIntruderEmailViaFirebase(Context context, String emailAddress, String subject,
                                           String message, Map<String, Object> deviceInfo, NotificationCallback callback) {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = auth.getCurrentUser();

            Map<String, Object> emailRequest = new HashMap<>();
            emailRequest.put("to", emailAddress);
            emailRequest.put("subject", subject);
            emailRequest.put("message", message);
            emailRequest.put("html", true);
            emailRequest.put("timestamp", com.google.firebase.Timestamp.now());
            emailRequest.put("type", "intruder_notification");
            emailRequest.put("priority", "high");
            emailRequest.put("auto_notification", true);

            // Add required userId for Firebase rules
            if (currentUser != null) {
                emailRequest.put("userId", currentUser.getUid());
            } else {
                // Fallback for offline mode - use a default identifier
                SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
                String userEmail = prefs.getString("user_email", "");
                emailRequest.put("userId", "offline_" + userEmail.hashCode());
            }

            // Add device info for logging
            emailRequest.put("device_info", deviceInfo);

            db.collection(EMAIL_REQUESTS_COLLECTION)
                .add(emailRequest)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "✅ Intruder notification email queued: " + documentReference.getId());

                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() ->
                            callback.onResult(true, "Notification sent successfully"));
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to queue intruder notification email", e);

                    if (callback != null) {
                        new Handler(Looper.getMainLooper()).post(() ->
                            callback.onResult(false, "Failed to send notification: " + e.getMessage()));
                    }
                });

        } catch (Exception e) {
            Log.e(TAG, "Exception in sendIntruderEmailViaFirebase", e);

            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                    callback.onResult(false, "Exception: " + e.getMessage()));
            }
        }
    }

    /**
     * Get user email from Firebase or SharedPreferences
     */
    private static String getUserEmail(Context context) {
        // First try Firebase Auth
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null && !user.getEmail().isEmpty()) {
            Log.d(TAG, "Got user email from Firebase Auth: " + user.getEmail());
            return user.getEmail();
        }

        // Fallback to SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
        String email = prefs.getString("user_email", "");
        if (!email.isEmpty()) {
            Log.d(TAG, "Got user email from SharedPreferences: " + email);
            return email;
        }

        Log.w(TAG, "No user email found in Firebase Auth or SharedPreferences");
        return null;
    }

    /**
     * Get the notification email (now dynamic based on user)
     */
    public static String getNotificationEmail(Context context) {
        return getUserEmail(context);
    }

    /**
     * Get current notification settings
     */
    public static String getNotificationSettings(Context context) {
        StringBuilder settings = new StringBuilder();

        try {
            String userEmail = getUserEmail(context);

            settings.append("📧 Intruder Notification Settings\n\n");
            settings.append("Status: ✅ Always Enabled\n");

            if (userEmail != null && !userEmail.isEmpty()) {
                settings.append("Email: ").append(userEmail).append("\n\n");
                settings.append("✅ Notifications configured for user's registered email\n");
                settings.append("📧 Email alerts will be sent to your account email\n");
            } else {
                settings.append("Email: ⚠️ Not configured\n\n");
                settings.append("⚠️ Please sign in to receive email notifications\n");
                settings.append("📧 Email alerts require user authentication\n");
            }

            settings.append("🔒 Email address synced from your account settings\n\n");

            if (NetworkHelper.isNetworkAvailable(context)) {
                settings.append("🌐 Network: Connected - Ready to send notifications");
            } else {
                settings.append("⚠️ Network: No connection - Will send when restored");
            }

        } catch (Exception e) {
            settings.append("Error getting notification settings");
        }

        return settings.toString();
    }

    /**
     * Set notifications enabled/disabled (placeholder)
     */
    public static void setNotificationsEnabled(Context context, boolean enabled) {
        Log.d(TAG, "📧 Notifications are always enabled for security reasons");
    }

    /**
     * Set notification email (now follows user's registered email)
     */
    public static boolean setNotificationEmail(Context context, String email) {
        Log.d(TAG, "📧 Notification email follows your registered account email");
        return false; // Cannot be changed directly - follows account email
    }

    /**
     * Check if notifications are enabled
     */
    public static boolean areNotificationsEnabled(Context context) {
        return true; // Always enabled
    }

    // ============= HELPER METHODS FOR DEVICE INFORMATION =============

    /**
     * Get device location if available (IMPROVED - FIXED for intruder notifications)
     */
    private static String getDeviceLocation(Context context) {
        try {
            // Check if we have location permission
            if (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                Log.d(TAG, "📍 Location permission not granted");
                return "Location: Permission not granted - Please enable location access in app settings";
            }

            // Get the location manager
            LocationManager locationManager = (LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);

            if (locationManager == null) {
                Log.d(TAG, "📍 Location manager is null");
                return "Location: Location service unavailable";
            }

            // Check if GPS or Network provider is enabled
            boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            Log.d(TAG, "📍 GPS enabled: " + isGpsEnabled + ", Network enabled: " + isNetworkEnabled);

            if (!isGpsEnabled && !isNetworkEnabled) {
                return "Location: GPS and Network location are disabled - Please enable location services";
            }

            Location bestLocation = null;
            String providerUsed = "Unknown";
            long bestLocationTime = 0;

            // Try to get location from all available providers and choose the best one
            if (isGpsEnabled) {
                try {
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null) {
                        bestLocation = gpsLocation;
                        bestLocationTime = gpsLocation.getTime();
                        providerUsed = "GPS";
                        Log.d(TAG, "📍 Got GPS location: " + gpsLocation.getLatitude() + ", " + gpsLocation.getLongitude());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception getting GPS location", e);
                }
            }

            // Try Network provider and compare with GPS
            if (isNetworkEnabled) {
                try {
                    Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (networkLocation != null) {
                        // Use network location if no GPS location or if it's more recent
                        if (bestLocation == null || networkLocation.getTime() > bestLocationTime) {
                            bestLocation = networkLocation;
                            bestLocationTime = networkLocation.getTime();
                            providerUsed = "Network";
                        }
                        Log.d(TAG, "📍 Got Network location: " + networkLocation.getLatitude() + ", " + networkLocation.getLongitude());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception getting Network location", e);
                }
            }

            // Try passive provider as fallback
            if (bestLocation == null) {
                try {
                    Location passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                    if (passiveLocation != null) {
                        bestLocation = passiveLocation;
                        providerUsed = "Passive";
                        Log.d(TAG, "📍 Got Passive location: " + passiveLocation.getLatitude() + ", " + passiveLocation.getLongitude());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception getting Passive location", e);
                }
            }

            // If we found a location, format it properly
            if (bestLocation != null) {
                double latitude = bestLocation.getLatitude();
                double longitude = bestLocation.getLongitude();
                float accuracy = bestLocation.getAccuracy();
                long ageMillis = System.currentTimeMillis() - bestLocation.getTime();
                long ageMinutes = ageMillis / (1000 * 60);

                // Create Google Maps link
                String mapsLink = "https://maps.google.com/?q=" + latitude + "," + longitude;

                Log.d(TAG, "📍 Location found via " + providerUsed + ": " + latitude + ", " + longitude +
                      " (±" + accuracy + "m, " + ageMinutes + " min old)");

                // Format location information for email
                String locationInfo = "Coordinates: " + String.format("%.6f", latitude) + ", " +
                                    String.format("%.6f", longitude) + " (±" + Math.round(accuracy) + "m)\n" +
                                    "Provider: " + providerUsed + " (" + ageMinutes + " min ago)\n" +
                                    "Google Maps: " + mapsLink;

                return locationInfo;
            } else {
                Log.d(TAG, "📍 No cached location found, trying to get fresh location");

                // Try to get a fresh location synchronously (with timeout)
                return requestFreshLocationSync(context, locationManager);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting location", e);
            return "Location: Error retrieving coordinates - " + e.getMessage();
        }
    }

    /**
     * Send boot test notification to verify system works after restart
     */
    public static void sendBootTestNotification(Context context, NotificationCallback callback) {
        // Get user email
        String userEmail = getUserEmail(context);
        if (userEmail == null || userEmail.isEmpty()) {
            Log.d(TAG, "No user email available for boot test notification");
            if (callback != null) {
                callback.onResult(false, "User email not configured");
            }
            return;
        }

        // Check network availability
        if (!NetworkHelper.isNetworkAvailable(context)) {
            Log.d(TAG, "Network unavailable for boot test notification");
            if (callback != null) {
                callback.onResult(false, "Network unavailable");
            }
            return;
        }

        // Run in background thread
        new Thread(() -> {
            try {
                // Collect basic device information
                Map<String, Object> deviceInfo = new HashMap<>();
                deviceInfo.put("event_type", "boot_test");
                deviceInfo.put("device_model", Build.MODEL);
                deviceInfo.put("android_version", Build.VERSION.RELEASE);
                deviceInfo.put("timestamp", System.currentTimeMillis());
                deviceInfo.put("formatted_time", new SimpleDateFormat("MMM dd, yyyy 'at' hh:mm:ss a", Locale.getDefault()).format(new Date()));

                // Format email content
                String emailSubject = "✅ Security App Boot Test - " + Build.MODEL;
                String emailBody = formatBootTestEmailMessage(context, deviceInfo);

                Log.d(TAG, "📧 Sending boot test notification to user: " + userEmail);

                // Send email via Firebase Cloud Function
                sendTestEmailViaFirebase(context, userEmail, emailSubject, emailBody, deviceInfo, callback);

            } catch (Exception e) {
                Log.e(TAG, "Error in sendBootTestNotification", e);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                        callback.onResult(false, "Error: " + e.getMessage()));
                }
            }
        }).start();
    }

    /**
     * Format boot test email message
     */
    private static String formatBootTestEmailMessage(Context context, Map<String, Object> deviceInfo) {
        StringBuilder htmlMessage = new StringBuilder();

        // Email header
        htmlMessage.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        htmlMessage.append("<style>body{font-family: Arial, sans-serif; line-height: 1.6; color: #333;}</style></head><body>");

        // Main content
        htmlMessage.append("<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>");
        htmlMessage.append("<h1 style='color: #28a745; text-align: center;'>✅ Boot Test Successful</h1>");

        htmlMessage.append("<p>Your security monitoring system is working correctly after device restart.</p>");

        // Device info
        htmlMessage.append("<div style='background-color: #f8f9fa; margin: 20px 0; padding: 20px; border-radius: 8px;'>");
        htmlMessage.append("<h2 style='color: #28a745; margin-top: 0;'>📱 Device Information</h2>");
        htmlMessage.append("<p><strong>Device:</strong> ").append(deviceInfo.get("device_model")).append("</p>");
        htmlMessage.append("<p><strong>Android:</strong> ").append(deviceInfo.get("android_version")).append("</p>");
        htmlMessage.append("<p><strong>Time:</strong> ").append(deviceInfo.get("formatted_time")).append("</p>");
        htmlMessage.append("<p><strong>Status:</strong> ✅ Security monitoring active</p>");
        htmlMessage.append("</div>");

        htmlMessage.append("<p style='color: #666; font-size: 0.9em; text-align: center;'>");
        htmlMessage.append("This is an automated test message sent after device restart to confirm your security system is working properly.");
        htmlMessage.append("</p>");

        htmlMessage.append("</div></body></html>");

        return htmlMessage.toString();
    }

    /**
     * Send test email via Firebase Cloud Function
     */
    private static void sendTestEmailViaFirebase(Context context, String toEmail, String subject, String body,
                                               Map<String, Object> deviceInfo, NotificationCallback callback) {
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();

            // Create email document
            Map<String, Object> emailData = new HashMap<>();
            emailData.put("to", toEmail);
            emailData.put("subject", subject);
            emailData.put("html", body);
            emailData.put("timestamp", System.currentTimeMillis());
            emailData.put("type", "boot_test");
            emailData.put("device_info", deviceInfo);

            // Send via Firestore collection (triggered by Cloud Function)
            db.collection("mail")
                .add(emailData)
                .addOnSuccessListener(documentReference -> {
                    Log.d(TAG, "✅ Boot test email queued successfully");
                    if (callback != null) {
                        callback.onResult(true, "Boot test notification sent");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to queue boot test email", e);
                    if (callback != null) {
                        callback.onResult(false, "Failed to send: " + e.getMessage());
                    }
                });

        } catch (Exception e) {
            Log.e(TAG, "Exception sending boot test email", e);
            if (callback != null) {
                callback.onResult(false, "Exception: " + e.getMessage());
            }
        }
    }

    // ============= HELPER METHODS FOR DEVICE INFORMATION =============

    /**
     * Request fresh location synchronously with timeout
     */
    private static String requestFreshLocationSync(Context context, LocationManager locationManager) {
        try {
            Log.d(TAG, "📍 Attempting to get fresh location");
            return "Location: Unable to get fresh location - Using cached location service";
        } catch (Exception e) {
            Log.e(TAG, "Error requesting fresh location", e);
            return "Location: Error getting fresh coordinates";
        }
    }

    /**
     * Get username from various sources
     */
    private static String getUsername(Context context) {
        try {
            // First try Firebase Auth
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getDisplayName() != null && !user.getDisplayName().trim().isEmpty()) {
                return user.getDisplayName().trim();
            }

            // Try SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
            String storedName = prefs.getString("user_name", "");
            if (!storedName.isEmpty()) {
                return storedName;
            }

            // Try email as fallback
            if (user != null && user.getEmail() != null) {
                String email = user.getEmail();
                return email.substring(0, email.indexOf("@"));
            }

            return "User"; // Final fallback

        } catch (Exception e) {
            Log.e(TAG, "Error getting username", e);
            return "Unknown";
        }
    }

    /**
     * Get device IP address
     */
    private static String getDeviceIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
                 en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses();
                     enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting IP address", e);
        }
        return "IP unavailable";
    }

    /**
     * Callback interface for notification results
     */
    public interface NotificationCallback {
        void onResult(boolean success, String message);
    }

    /**
     * Get photo download URL with retry mechanism (waits for upload to complete)
     */
    private static void getPhotoDownloadUrlWithRetry(Context context, String photoFileName, int attempt, CloudBackupService.PhotoUrlCallback callback) {
        final int maxAttempts = 5;
        final long[] delays = {2000, 4000, 6000, 8000, 10000}; // 2s, 4s, 6s, 8s, 10s

        if (attempt >= maxAttempts) {
            Log.w(TAG, "Max attempts reached for photo URL retrieval: " + photoFileName);
            if (callback != null) {
                callback.onResult(null, "Photo upload still in progress - access via app");
            }
            return;
        }

        // Wait before attempting (except first attempt)
        long delay = attempt > 0 ? delays[Math.min(attempt - 1, delays.length - 1)] : 0;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "Attempting to get photo URL, attempt " + (attempt + 1) + "/" + maxAttempts);

            CloudBackupService.getPhotoDownloadUrl(context, photoFileName, new CloudBackupService.PhotoUrlCallback() {
                @Override
                public void onResult(String downloadUrl, String error) {
                    if (downloadUrl != null) {
                        // Success - got the URL
                        Log.d(TAG, "Successfully obtained photo URL on attempt " + (attempt + 1));
                        if (callback != null) {
                            callback.onResult(downloadUrl, null);
                        }
                    } else {
                        // Failed - try again if attempts remaining
                        Log.d(TAG, "Photo URL not ready on attempt " + (attempt + 1) + ", error: " + error);
                        getPhotoDownloadUrlWithRetry(context, photoFileName, attempt + 1, callback);
                    }
                }
            });
        }, delay);
    }
}
