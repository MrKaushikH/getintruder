package com.example.securityapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CameraAccessException;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraService extends Service {
    private static final String TAG = "CameraService";
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    private String userName;

    // Camera-related variables
    private CameraDevice cameraDevice;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ImageReader imageReader;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private long captureStartTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CameraService created");

        // Set high priority for the service
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);

        // Get user name from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
        userName = prefs.getString("user_name", "Anonymous");

        createNotificationChannel();

        // Start foreground service with proper type
        Notification notification = createNotification("Starting camera...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Start background thread for camera operations
        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getStringExtra("action") : null;
        boolean sendNotification = intent != null ? intent.getBooleanExtra("send_notification", false) : false;

        if ("CAPTURE_INTRUDER".equals(action)) {
            startForeground(NOTIFICATION_ID, createNotification("Capturing intruder photo..."));
            captureIntruderPhotoFast(sendNotification);
        } else if ("PREWARM_CAMERA".equals(action)) {
            prewarmCamera();
            stopSelf(); // Stop service after prewarming
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Capturing security photo..."));
            captureIntruderPhotoFast(sendNotification);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        closeCamera();

        if (backgroundHandler != null) {
            backgroundHandler.postDelayed(() -> {
                stopBackgroundThread();
            }, 100);
        } else {
            stopBackgroundThread();
        }

        super.onDestroy();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground", Process.THREAD_PRIORITY_MORE_FAVORABLE);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            try {
                backgroundThread.quitSafely();
                if (Thread.currentThread() != backgroundThread) {
                    // Only join if we're not on the background thread itself
                    backgroundThread.join(500);
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            } finally {
                backgroundThread = null;
                backgroundHandler = null;
            }
        }
    }

    private void openCamera(boolean isSecurityPasswordPhoto) {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            // Find front camera
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    break;
                }
            }

            // If no front camera, use the first available camera
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (cameraId == null) {
                Log.e(TAG, "No camera available");
                stopSelf();
                return;
            }

            // Use smaller image size for faster processing (640x480)
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);

            // Set up image reader listener
            final boolean isSecurityPhoto = isSecurityPasswordPhoto;
            final ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try {
                        Image image = reader.acquireNextImage();
                        if (image != null) {
                            // Create a copy of the image data before closing the image
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);

                            // Close the image immediately after getting data
                            image.close();

                            // Process image data, passing security photo flag
                            saveImageToFile(bytes, isSecurityPhoto);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image: " + e.getMessage(), e);
                    } finally {
                        closeCamera();
                    }
                }
            };

            // Register listener on background thread
            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);

            // Acquire camera lock with timeout
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            // Open camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraOpenCloseLock.release();
                stopSelf();
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera opened successfully");
                    cameraDevice = camera;
                    cameraOpenCloseLock.release();
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera disconnected");
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Error opening camera: " + e.getMessage(), e);
            stopSelf();
        }
    }

    private void createCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                stopSelf();
                return;
            }

            // Create capture request
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // Auto focus and auto exposure
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 85);

            // Set proper orientation for vertical photos (270 degrees for front camera)
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 270);

            // Create capture session
            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                   @NonNull CaptureRequest request,
                                                                   @NonNull TotalCaptureResult result) {
                                        Log.d(TAG, "Image capture completed");
                                    }
                                }, backgroundHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to capture: " + e.getMessage(), e);
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure camera session");
                            stopSelf();
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Error creating capture session: " + e.getMessage(), e);
            stopSelf();
        }
    }

    /**
     * Get display rotation for proper JPEG orientation
     */
    private int getDisplayRotation() {
        try {
            android.view.WindowManager windowManager = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                android.view.Display display = windowManager.getDefaultDisplay();
                return display.getRotation();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display rotation", e);
        }
        return android.view.Surface.ROTATION_0;
    }

    /**
     * Calculate proper JPEG orientation based on device rotation and camera facing
     */
    private int getJpegOrientation(int deviceRotation) {
        try {
            // Fixed orientation for front-facing camera to capture straight photos
            int jpegOrientation = 270; // Default front camera correction

            // Front camera requires different orientation handling
            switch (deviceRotation) {
                case android.view.Surface.ROTATION_0:
                    jpegOrientation = 270; // Portrait - correct front camera rotation
                    break;
                case android.view.Surface.ROTATION_90:
                    jpegOrientation = 0; // Landscape left
                    break;
                case android.view.Surface.ROTATION_180:
                    jpegOrientation = 90; // Portrait upside down
                    break;
                case android.view.Surface.ROTATION_270:
                    jpegOrientation = 180; // Landscape right
                    break;
                default:
                    jpegOrientation = 270; // Default front camera correction
            }

            return jpegOrientation;

        } catch (Exception e) {
            Log.e(TAG, "Error calculating JPEG orientation", e);
            return 270; // Default front camera correction
        }
    }

    /**
     * Calculate proper JPEG orientation for optimized capture (front camera)
     */
    private int getJpegOrientationOptimized(int deviceRotation) {
        try {
            // Corrected orientations for optimized front camera capture
            int jpegOrientation;

            switch (deviceRotation) {
                case android.view.Surface.ROTATION_0:
                    jpegOrientation = 0; // Portrait - no rotation
                    break;
                case android.view.Surface.ROTATION_90:
                    jpegOrientation = 270; // Landscape left
                    break;
                case android.view.Surface.ROTATION_180:
                    jpegOrientation = 180; // Portrait upside down
                    break;
                case android.view.Surface.ROTATION_270:
                    jpegOrientation = 90; // Landscape right
                    break;
                default:
                    jpegOrientation = 0; // Default to no rotation
            }

            return jpegOrientation;

        } catch (Exception e) {
            Log.e(TAG, "Error calculating optimized JPEG orientation", e);
            return 0; // Default to no rotation
        }
    }

    /**
     * Process captured image and save to file
     */
    private void saveImageToFile(byte[] bytes, boolean isSecurityPasswordPhoto) {
        try {
            // Check image size before saving (10MB limit)
            double imageSizeInMB = bytes.length / (1024.0 * 1024.0);
            if (bytes.length > 10 * 1024 * 1024) { // 10MB in bytes
                Log.w(TAG, "Captured image too large: " + String.format("%.1f", imageSizeInMB) + "MB. Compressing...");

                // Compress the image to reduce size
                bytes = compressImageBytes(bytes);
                imageSizeInMB = bytes.length / (1024.0 * 1024.0);

                if (bytes.length > 10 * 1024 * 1024) {
                    Log.e(TAG, "Image still too large after compression: " + String.format("%.1f", imageSizeInMB) + "MB. Cannot save.");
                    Toast.makeText(this, "Image too large to save", Toast.LENGTH_SHORT).show();
                    stopSelf();
                    return;
                }

                Log.d(TAG, "Image compressed to: " + String.format("%.1f", imageSizeInMB) + "MB");
            }

            // Generate filename with timestamp and username
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());

            // Get actual username instead of falling back to "Anonymous"
            String actualUserName = getUserNameForPhoto();
            String fileName = "Security_" + timestamp + "_" +
                    actualUserName.replaceAll("\\s+", "_") + ".jpg";

            // Use UserManager to save to correct directory
            File file = UserManager.savePhotoToUserDirectory(this, bytes, fileName);

            if (file != null && file.exists()) {
                Log.d(TAG, "✅ Saved image to: " + file.getAbsolutePath() +
                        " [Size: " + String.format("%.1f", file.length() / 1024.0 / 1024.0) + "MB]" +
                        " [User: " + actualUserName + "]");

                // Update notification
                NotificationManager notificationManager =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(NOTIFICATION_ID,
                        createNotification("Photo captured successfully"));

                // Update capture count in SharedPreferences
                updateCaptureStats(file.lastModified());

                // Send intruder notification email if this was a security photo
                if (isSecurityPasswordPhoto) {
                    sendIntruderNotificationEmail(fileName);
                }

                // **ENHANCED AUTOMATIC CLOUD BACKUP**
                triggerCloudBackupWithValidation(file, isSecurityPasswordPhoto);

            } else {
                Log.e(TAG, "Failed to save photo using UserManager");
                // Fallback to old method if UserManager fails
                saveImageFallback(bytes, fileName, isSecurityPasswordPhoto);
            }

            // Add delay before stopping service
            backgroundHandler.postDelayed(() -> {
                Log.d(TAG, "Image capture and backup initiation completed");
                stopSelf();
            }, 2000);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving image", e);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // Create main security channel
            NotificationChannel securityChannel = new NotificationChannel(
                    "security_channel",
                    "Security Service",
                    NotificationManager.IMPORTANCE_LOW);
            securityChannel.setDescription("Used for security monitoring");
            notificationManager.createNotificationChannel(securityChannel);

            // Create camera service channel - FIX: Use the correct channel ID that matches the notification
            NotificationChannel cameraChannel = new NotificationChannel(
                    CHANNEL_ID, // This is "camera_service_channel"
                    "Camera Service",
                    NotificationManager.IMPORTANCE_HIGH);
            cameraChannel.setDescription("Camera capture notifications");
            cameraChannel.setVibrationPattern(new long[]{0, 500, 200, 500});
            notificationManager.createNotificationChannel(cameraChannel);

            Log.d(TAG, "Created notification channels: security_channel and " + CHANNEL_ID);
        }
    }

    private Notification createNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "security_channel")
                .setContentTitle("Security Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.secure)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        return builder.build();
    }

    /**
     * Send intruder notification email using OTPService
     */
    private void sendIntruderNotification(String photoFileName) {
        try {
            Log.d(TAG, "📧 Sending intruder notification for photo: " + photoFileName);

            OTPService.sendIntruderNotification(this, photoFileName, new OTPService.NotificationCallback() {
                @Override
                public void onResult(boolean success, String message) {
                    if (success) {
                        Log.d(TAG, "✅ Intruder notification sent successfully: " + message);
                        // Removed showNotificationSentAlert() - no user notification needed
                    } else {
                        Log.e(TAG, "❌ Failed to send intruder notification: " + message);
                        // Removed showNotificationFailedAlert() - no user notification needed
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Exception sending intruder notification", e);
        }
    }

    /**
     * Complete intruder photo capture process with all integrations
     */
    private void captureIntruderPhotoComplete(boolean sendNotification) {
        Log.d(TAG, "🚨 Starting complete intruder photo capture process");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Camera permission not granted");
            stopSelf();
            return;
        }

        // Start the capture process - this will replace the existing captureIntruderPhoto method
        openCameraForIntruder(sendNotification);
    }

    /**
     * Open camera specifically for intruder detection
     */
    private void openCameraForIntruder(boolean sendNotification) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "❌ Camera manager is null");
            stopSelf();
            return;
        }

        try {
            String[] cameraIds = manager.getCameraIdList();
            String frontCameraId = null;

            // Find front camera
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId;
                    break;
                }
            }

            if (frontCameraId == null) {
                Log.e(TAG, "❌ No front camera found");
                stopSelf();
                return;
            }

            Log.d(TAG, "📷 Opening front camera for intruder capture");

            // Create image reader for capture
            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);

            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Log.d(TAG, "📸 Image available from camera");
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            // Process the captured image
                            processIntruderImage(image, sendNotification);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error processing captured image", e);
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                        closeCamera();
                        stopSelf();
                    }
                }
            }, backgroundHandler);

            // Open the camera
            manager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "📷 Camera opened successfully for intruder capture");
                    cameraDevice = camera;
                    createIntruderCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "📷 Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "❌ Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "❌ Camera access exception", e);
            stopSelf();
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Security exception accessing camera", e);
            stopSelf();
        }
    }

    /**
     * Create capture session for intruder detection
     */
    private void createIntruderCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                Log.e(TAG, "❌ Camera device or image reader is null");
                stopSelf();
                return;
            }

            Log.d(TAG, "📷 Creating intruder capture session");

            // Create capture request
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // Configure capture settings
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 85);

            // Set orientation
            int rotation = getDisplayRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(rotation));

            // Create capture session
            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "📷 Capture session configured, taking picture");
                            try {
                                session.capture(captureBuilder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                           @NonNull CaptureRequest request,
                                                                           @NonNull TotalCaptureResult result) {
                                                Log.d(TAG, "✅ Intruder image capture completed");
                                            }
                                        }, backgroundHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Failed to capture intruder image", e);
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "❌ Failed to configure intruder capture session");
                            stopSelf();
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating intruder capture session", e);
            stopSelf();
        }
    }

    /**
     * Process captured intruder image
     */
    private void processIntruderImage(Image image, boolean sendNotification) {
        try {
            Log.d(TAG, "🔄 Processing intruder image");

            // Get image data
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Compress if needed
            if (bytes.length > 8 * 1024 * 1024) { // 8MB limit
                bytes = compressImageBytes(bytes);
            }

            // Generate filename
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            String actualUserName = getUserNameForPhoto();
            String fileName = "Intruder_" + timestamp + "_" +
                    actualUserName.replaceAll("\\s+", "_") + ".jpg";

            Log.d(TAG, "💾 Saving intruder image: " + fileName);

            // Save using UserManager
            File savedFile = UserManager.savePhotoToUserDirectory(this, bytes, fileName);

            if (savedFile != null && savedFile.exists()) {
                Log.d(TAG, "✅ Intruder photo saved successfully: " + savedFile.getAbsolutePath());

                // Update stats
                updateCaptureStats(savedFile.lastModified());

                // Send email notification
                if (sendNotification) {
                    Log.d(TAG, "📧 Sending intruder notification email");
                    sendIntruderNotification(fileName);
                }

                // Start cloud backup
                triggerCloudBackupWithValidation(savedFile, true);

                // Send broadcast to update UI
                Intent broadcastIntent = new Intent("com.example.securityapp.PHOTO_CAPTURED");
                broadcastIntent.putExtra("photo_filename", fileName);
                sendBroadcast(broadcastIntent);

                Log.d(TAG, "🎯 Intruder detection process completed successfully");

            } else {
                Log.e(TAG, "❌ Failed to save intruder photo");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error processing intruder image", e);
        }
    }

    /**
     * Get username for photo filename
     */
    private String getUserNameForPhoto() {
        try {
            // First try Firebase Auth
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = auth.getCurrentUser();

            if (currentUser != null && currentUser.getDisplayName() != null && !currentUser.getDisplayName().trim().isEmpty()) {
                return currentUser.getDisplayName().trim();
            }

            // Try SharedPreferences
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            String storedName = prefs.getString("user_name", "");
            if (!storedName.isEmpty()) {
                return storedName;
            }

            // Try alternative SharedPreferences location
            SharedPreferences getIntruderPrefs = getSharedPreferences("get_intruder", MODE_PRIVATE);
            String altName = getIntruderPrefs.getString("user_name", "");
            if (!altName.isEmpty()) {
                return altName;
            }

            // Try email as fallback
            if (currentUser != null && currentUser.getEmail() != null) {
                String email = currentUser.getEmail();
                return email.substring(0, email.indexOf("@"));
            }

            return "User"; // Final fallback

        } catch (Exception e) {
            Log.e(TAG, "Error getting username for photo", e);
            return "User";
        }
    }

    /**
     * Update capture statistics in SharedPreferences
     */
    private void updateCaptureStats(long captureTime) {
        try {
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Update capture count
            int currentCount = prefs.getInt("capture_count", 0);
            editor.putInt("capture_count", currentCount + 1);

            // Update last capture time
            editor.putLong("last_capture_time", captureTime);

            // Update total captures across all sessions
            int totalCaptures = prefs.getInt("total_captures", 0);
            editor.putInt("total_captures", totalCaptures + 1);

            // Update first capture time if this is the first
            if (!prefs.contains("first_capture_time")) {
                editor.putLong("first_capture_time", captureTime);
            }

            editor.apply();

            Log.d(TAG, "✅ Updated capture stats: count=" + (currentCount + 1) + ", time=" + captureTime);

        } catch (Exception e) {
            Log.e(TAG, "Error updating capture stats", e);
        }
    }

    /**
     * Compress image bytes to reduce size
     */
    private byte[] compressImageBytes(byte[] originalBytes) {
        try {
            // Decode the byte array into a Bitmap
            Bitmap originalBitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.length);
            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode image for compression");
                return originalBytes;
            }

            // Calculate new dimensions (reduce by 50% if too large)
            int originalWidth = originalBitmap.getWidth();
            int originalHeight = originalBitmap.getHeight();
            int newWidth = originalWidth;
            int newHeight = originalHeight;

            // If image is very large, scale it down
            if (originalWidth > 1920 || originalHeight > 1920) {
                float ratio = Math.min(1920f / originalWidth, 1920f / originalHeight);
                newWidth = Math.round(originalWidth * ratio);
                newHeight = Math.round(originalHeight * ratio);
            }

            // Create scaled bitmap if needed
            Bitmap scaledBitmap = originalBitmap;
            if (newWidth != originalWidth || newHeight != originalHeight) {
                scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true);
                originalBitmap.recycle(); // Free memory
            }

            // Compress with lower quality
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int quality = 75; // Start with 75% quality
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);

            // If still too large, reduce quality further
            while (outputStream.toByteArray().length > 8 * 1024 * 1024 && quality > 20) { // 8MB limit
                outputStream.reset();
                quality -= 10;
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
            }

            scaledBitmap.recycle(); // Free memory
            byte[] compressedBytes = outputStream.toByteArray();
            outputStream.close();

            Log.d(TAG, "Image compressed from " + (originalBytes.length / 1024) + "KB to " +
                    (compressedBytes.length / 1024) + "KB (quality: " + quality + "%)");

            return compressedBytes;

        } catch (Exception e) {
            Log.e(TAG, "Error compressing image", e);
            return originalBytes;
        }
    }


    /**
     * Send intruder notification email (wrapper method)
     */
    private void sendIntruderNotificationEmail(String fileName) {
        sendIntruderNotification(fileName);
    }

    /**
     * Save Image object to file and return filename
     */
    private String saveImage(Image image) {
        try {
            // Get image data
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Generate filename
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            String actualUserName = getUserNameForPhoto();
            String fileName = "Security_" + timestamp + "_" +
                    actualUserName.replaceAll("\\s+", "_") + ".jpg";

            // Use UserManager to save
            File savedFile = UserManager.savePhotoToUserDirectory(this, bytes, fileName);

            if (savedFile != null && savedFile.exists()) {
                Log.d(TAG, "✅ Image saved via saveImage method: " + fileName);
                return fileName;
            } else {
                Log.e(TAG, "❌ Failed to save image via UserManager");
                return null;
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in saveImage method", e);
            return null;
        }
    }

    /**
     * Enhanced cloud backup trigger with validation - MISSING METHOD
     */
    private void triggerCloudBackupWithValidation(File file, boolean isSecurityPasswordPhoto) {
        try {
            Log.d(TAG, "🔄 Initiating cloud backup validation for: " + file.getName());

            // Validate file exists and has content
            if (!file.exists() || file.length() == 0) {
                Log.e(TAG, "File validation failed for cloud backup: " + file.getName());
                return;
            }

            // Check network connectivity
            if (!NetworkHelper.isNetworkAvailable(this)) {
                Log.w(TAG, "No network available for cloud backup, will retry later");
                // Store for later retry
                SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
                prefs.edit().putString("pending_backup_" + System.currentTimeMillis(), file.getAbsolutePath()).apply();
                return;
            }

            // Check authentication
            com.google.firebase.auth.FirebaseAuth auth = com.google.firebase.auth.FirebaseAuth.getInstance();
            com.google.firebase.auth.FirebaseUser currentUser = auth.getCurrentUser();

            if (currentUser == null) {
                Log.e(TAG, "User not authenticated, cannot backup to cloud");
                return;
            }

            Log.d(TAG, "✅ Validation passed, starting cloud backup for: " + file.getName());

            // Start cloud backup service immediately with proper action and extras
            Intent backupIntent = new Intent(this, CloudBackupService.class);
            backupIntent.setAction("UPLOAD_SINGLE");
            backupIntent.putExtra("filepath", file.getAbsolutePath());
            backupIntent.putExtra("security_password_photo", isSecurityPasswordPhoto);
            backupIntent.putExtra("immediate_upload", true);

            Log.d(TAG, "📤 Starting CloudBackupService for: " + file.getName());
            Log.d(TAG, "📤 File path: " + file.getAbsolutePath());
            Log.d(TAG, "📤 File size: " + (file.length() / 1024) + " KB");

            // Start the service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(backupIntent);
                    Log.d(TAG, "✅ CloudBackupService started as foreground service");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start foreground service, trying regular service", e);
                    startService(backupIntent);
                }
            } else {
                startService(backupIntent);
                Log.d(TAG, "✅ CloudBackupService started as regular service");
            }

            // Set flag to indicate backup was initiated
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                    .putLong("last_backup_attempt", System.currentTimeMillis())
                    .putString("last_backup_file", file.getName())
                    .apply();

            Log.d(TAG, "✅ Cloud backup service started successfully for: " + file.getName());

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting cloud backup service for: " + file.getName(), e);

            // Store for retry later if service failed to start
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                    .putString("failed_backup_" + System.currentTimeMillis(), file.getAbsolutePath())
                    .apply();
        }
    }

    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // This service doesn't support binding
        return null;
    }

    /**
     * Fallback save method if UserManager fails
     */
    private void saveImageFallback(byte[] bytes, String fileName, boolean isSecurityPasswordPhoto) {
        try {
            Log.w(TAG, "Using fallback save method for: " + fileName);

            File photosDir = new File(getFilesDir(), "security_photos");
            if (!photosDir.exists()) {
                boolean created = photosDir.mkdirs();
                Log.d(TAG, "Created fallback photos directory: " + created);
            }

            File fallbackFile = new File(photosDir, fileName);
            try (java.io.FileOutputStream outputStream = new java.io.FileOutputStream(fallbackFile)) {
                outputStream.write(bytes);
                outputStream.flush();

                Log.d(TAG, "✅ Saved image to fallback location: " + fallbackFile.getAbsolutePath());

                updateCaptureStats(fallbackFile.lastModified());

                if (isSecurityPasswordPhoto) {
                    sendIntruderNotificationEmail(fileName);
                }

                // Trigger cloud backup for fallback file too
                triggerCloudBackupWithValidation(fallbackFile, isSecurityPasswordPhoto);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in fallback save method", e);
        }
    }

    /**
     * Fast intruder photo capture with minimal processing
     */
    private void captureIntruderPhotoFast(boolean sendNotification) {
        captureStartTime = System.currentTimeMillis();

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return;
            }

            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                stopSelf();
                return;
            }

            // Use smaller image size for speed (480x640)
            imageReader = ImageReader.newInstance(480, 640, ImageFormat.JPEG, 1);

            final ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            image.close();

                            // Save image with user notification flag
                            saveImageToFile(bytes, sendNotification);
                        }
                    } catch (Exception e) {
                        // Silent error handling
                    } finally {
                        closeCamera();
                    }
                }
            };

            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);

            // Quick camera access
            if (!cameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                stopSelf();
                return;
            }

            String frontCameraId = getFrontCameraId(manager);
            if (frontCameraId == null) {
                String[] cameraIds = manager.getCameraIdList();
                if (cameraIds.length > 0) {
                    frontCameraId = cameraIds[0];
                } else {
                    cameraOpenCloseLock.release();
                    stopSelf();
                    return;
                }
            }

            manager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    cameraOpenCloseLock.release();
                    createFastCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (Exception e) {
            stopSelf();
        }
    }

    private void createFastCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                stopSelf();
                return;
            }

            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // Simple settings for speed
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 70);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 270); // Vertical orientation

            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            stopSelf();
                        }
                    },
                    backgroundHandler);

        } catch (Exception e) {
            stopSelf();
        }
    }

    /**
     * Optimized intruder photo capture with faster initialization
     */
    private void captureIntruderPhotoOptimized(boolean sendNotification) {
        Log.d(TAG, "⚡ Starting optimized intruder photo capture");
        captureStartTime = System.currentTimeMillis();

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                Log.e(TAG, "Camera manager is null");
                stopSelf();
                return;
            }

            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) {
                Log.e(TAG, "No camera available");
                stopSelf();
                return;
            }

            // Use front camera if available, otherwise back camera
            String cameraId = getFrontCameraId(manager);
            if (cameraId == null) {
                cameraId = cameraIdList[0];  // Fallback to first available
            }

            Log.d(TAG, "Using camera: " + cameraId);

            // Get camera characteristics for optimized settings
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // Use smaller resolution for faster capture (security photos don't need high res)
            android.util.Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);

            // Choose medium resolution (balance between speed and quality)
            android.util.Size targetSize = chooseBestSize(sizes);
            Log.d(TAG, "Using optimized resolution: " + targetSize.getWidth() + "x" + targetSize.getHeight());

            // Create ImageReader with optimized settings
            imageReader = ImageReader.newInstance(
                    targetSize.getWidth(),
                    targetSize.getHeight(),
                    ImageFormat.JPEG,
                    1);

            // Set up image processing callback
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    try {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            long captureTime = System.currentTimeMillis() - captureStartTime;
                            Log.d(TAG, "📸 Photo captured in " + captureTime + "ms");

                            // Process image data quickly
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            image.close();

                            // Save and process in background
                            processOptimizedImage(bytes, sendNotification);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image: " + e.getMessage(), e);
                        stopSelf();
                    }
                }
            };

            imageReader.setOnImageAvailableListener(readerListener, backgroundHandler);

            // Fast camera opening with very short timeout for speed
            if (!cameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Camera lock timeout - stopping service");
                stopSelf();
                return;
            }

            // Check permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted");
                cameraOpenCloseLock.release();
                stopSelf();
                return;
            }

            // Open camera with optimized callback
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "⚡ Camera opened quickly");
                    cameraDevice = camera;
                    cameraOpenCloseLock.release();
                    createOptimizedCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera disconnected");
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    stopSelf();
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Error in optimized photo capture", e);
            stopSelf();
        }
    }

    /**
     * Choose optimal resolution for fast capture (smaller for speed)
     */
    private android.util.Size chooseBestSize(android.util.Size[] sizes) {
        // Sort sizes by area (smallest first)
        java.util.Arrays.sort(sizes, (size1, size2) ->
                Integer.compare(size1.getWidth() * size1.getHeight(),
                        size2.getWidth() * size2.getHeight()));

        // For fastest capture, use smallest resolution that's still useful for security
        for (android.util.Size size : sizes) {
            int area = size.getWidth() * size.getHeight();
            // Reduced from 800k-1.5M to 300k-800k for faster capture
            if (area >= 300000 && area <= 800000) { // 0.3MP to 0.8MP - very fast
                return size;
            }
        }

        // If no ideal size found, use smallest available (fastest)
        if (sizes.length > 0) {
            return sizes[0]; // Smallest resolution for maximum speed
        }

        // Fallback to VGA if nothing else
        return new android.util.Size(640, 480);
    }

    /**
     * Create optimized capture session with minimal settings
     */
    private void createOptimizedCaptureSession() {
        try {
            if (cameraDevice == null || imageReader == null) {
                stopSelf();
                return;
            }

            // Create minimal capture request for speed
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            // Minimal settings for fastest capture
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 75); // Lower quality for speed

            // Fix rotation: Use proper orientation even in optimized mode
            int rotation = getDisplayRotation();
            int jpegOrientation = getJpegOrientationOptimized(rotation);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);

            Log.d(TAG, "⚡ Using optimized JPEG orientation: " + jpegOrientation);

            // Create session and capture immediately
            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                // Capture immediately without delay
                                session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                                   @NonNull CaptureRequest request,
                                                                   @NonNull TotalCaptureResult result) {
                                        Log.d(TAG, "⚡ Capture completed quickly");
                                        // Session will be closed when image is processed
                                    }

                                    @Override
                                    public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                                                @NonNull CaptureRequest request,
                                                                @NonNull android.hardware.camera2.CaptureFailure failure) {
                                        Log.e(TAG, "Capture failed");
                                        stopSelf();
                                    }
                                }, backgroundHandler);

                            } catch (Exception e) {
                                Log.e(TAG, "Error during capture", e);
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configuration failed");
                            stopSelf();
                        }
                    }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Error creating optimized capture session", e);
            stopSelf();
        }
    }

    /**
     * Process captured image with optimization and proper email timing
     */
    private void processOptimizedImage(byte[] imageData, boolean sendNotification) {
        // Process in background thread to not block camera
        new Thread(() -> {
            try {
                long processStart = System.currentTimeMillis();

                // Save image directly without rotation correction for speed
                String fileName = saveImageDataOptimized(imageData);

                long processTime = System.currentTimeMillis() - processStart;
                long totalTime = System.currentTimeMillis() - captureStartTime;

                Log.d(TAG, "📸 Image processed in " + processTime + "ms, total: " + totalTime + "ms");

                if (fileName != null) {
                    updateNotification("Photo captured: " + fileName);

                    // Send notification if requested - coordinate with cloud upload
                    if (sendNotification) {
                        // Start cloud backup and send email after upload completes
                        startCloudBackupWithEmailNotification(fileName);
                    } else {
                        // Start cloud backup without email
                        startCloudBackup(fileName);
                        // Stop service quickly if no notification needed
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            closeCamera();
                            stopSelf();
                        }, 500);
                    }
                } else {
                    Log.e(TAG, "Failed to save image");
                    stopSelf();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing optimized image", e);
                stopSelf();
            }
        }).start();
    }

    /**
     * Save image data with optimization
     */
    private String saveImageDataOptimized(byte[] imageData) {
        try {
            // Generate filename
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "security_" + timeStamp + "_" + userName + ".jpg";

            // Get user directory
            File photosDir = UserManager.getUserPhotosDirectory(this);
            File imageFile = new File(photosDir, fileName);

            // Save directly without bitmap processing for speed
            FileOutputStream fos = new FileOutputStream(imageFile);
            fos.write(imageData);
            fos.close();

            Log.d(TAG, "✅ Image saved quickly: " + fileName + " (" + (imageData.length / 1024) + " KB)");

            // Update SharedPreferences
            SharedPreferences prefs = getSharedPreferences("security_app", MODE_PRIVATE);
            prefs.edit()
                    .putString("last_photo_filename", fileName)
                    .putLong("last_photo_timestamp", System.currentTimeMillis())
                    .putString("last_photo_path", imageFile.getAbsolutePath())
                    .apply();

            return fileName;

        } catch (Exception e) {
            Log.e(TAG, "Error saving optimized image", e);
            return null;
        }
    }

    /**
     * Prewarm camera for faster future captures
     */
    private void prewarmCamera() {
        Log.d(TAG, "🔥 Prewarming camera for faster captures");

        // Initialize camera manager and get camera list
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager != null) {
                String[] cameraIds = manager.getCameraIdList();
                Log.d(TAG, "Available cameras: " + cameraIds.length);

                // Cache camera characteristics
                for (String cameraId : cameraIds) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    Log.d(TAG, "Cached characteristics for camera: " + cameraId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error prewarming camera", e);
        }

        // Stop service after prewarming
        stopSelf();
    }

    /**
     * Get front camera ID
     */
    private String getFrontCameraId(CameraManager manager) {
        try {
            String[] cameraIds = manager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return cameraId;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding front camera", e);
        }
        return null;
    }

    /**
     * Start cloud backup for captured photo
     */
    private void startCloudBackup(String fileName) {
        try {
            File photosDir = UserManager.getUserPhotosDirectory(this);
            File photoFile = new File(photosDir, fileName);

            if (photoFile.exists()) {
                triggerCloudBackupWithValidation(photoFile, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting cloud backup", e);
        }
    }

    /**
     * Update foreground notification
     */
    private void updateNotification(String message) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, createNotification(message));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }

    /**
     * Start cloud backup and send email notification after upload completes
     */
    private void startCloudBackupWithEmailNotification(String fileName) {
        try {
            File photosDir = UserManager.getUserPhotosDirectory(this);
            File photoFile = new File(photosDir, fileName);

            if (photoFile.exists()) {
                Log.d(TAG, "📤 Starting cloud backup with email notification for: " + fileName);

                // Start cloud backup service with email notification flag
                Intent backupIntent = new Intent(this, CloudBackupService.class);
                backupIntent.setAction("UPLOAD_SINGLE");
                backupIntent.putExtra("filepath", photoFile.getAbsolutePath());
                backupIntent.putExtra("security_password_photo", true);
                backupIntent.putExtra("immediate_upload", true);
                backupIntent.putExtra("send_email_after_upload", true); // New flag
                backupIntent.putExtra("photo_filename", fileName);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(backupIntent);
                } else {
                    startService(backupIntent);
                }

                // Stop camera service after starting backup
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    closeCamera();
                    stopSelf();
                }, 1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting cloud backup with email notification", e);
        }
    }
}
