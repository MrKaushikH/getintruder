package com.example.securityapp;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Security device admin receiver for handling password-related events
 */
public class SecurityDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "SecurityDeviceAdmin";

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        super.onPasswordFailed(context, intent);

        // Start camera service to capture intruder photo
        Intent cameraIntent = new Intent(context, CameraService.class);
        cameraIntent.putExtra("action", "CAPTURE_INTRUDER");
        cameraIntent.putExtra("trigger", "password_failed");
        cameraIntent.putExtra("send_notification", true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(cameraIntent);
            } else {
                context.startService(cameraIntent);
            }
        } catch (Exception e) {
            // Silent fail in production
        }
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d(TAG, "Password succeeded");
    }

    private void captureSecurityPhoto(Context context) {
        try {
            // Launch the photo capture service using CameraService instead of SecurityCaptureService
            Intent captureIntent = new Intent(context, CameraService.class);
            captureIntent.setAction("CAPTURE_PHOTO");
            // Flag this as a security password photo (high priority)
            captureIntent.putExtra("security_password_photo", true);

            // Use correct method to start foreground service based on Android version
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(captureIntent);
            } else {
                context.startService(captureIntent);
            }

            Log.d(TAG, "Triggered security photo capture after failed password attempt");
        } catch (Exception e) {
            Log.e(TAG, "Error starting capture service", e);
        }
    }
}
