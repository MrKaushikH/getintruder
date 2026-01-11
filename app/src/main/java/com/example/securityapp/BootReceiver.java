package com.example.securityapp;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Boot receiver to ensure device admin functionality remains active after restart
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            Intent.ACTION_PACKAGE_REPLACED.equals(action)) {


            try {
                DevicePolicyManager devicePolicyManager = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminReceiver = new ComponentName(context, SecurityDeviceAdminReceiver.class);

                boolean isAdminActive = devicePolicyManager != null &&
                                      devicePolicyManager.isAdminActive(adminReceiver);

                SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
                long bootTime = System.currentTimeMillis();

                prefs.edit()
                    .putLong("last_boot_time", bootTime)
                    .putBoolean("security_active_after_boot", isAdminActive)
                    .putBoolean("device_ready_for_monitoring", isAdminActive)
                    .apply();

                if (isAdminActive) {
                    initializeSecurityServices(context);
                }

            } catch (Exception e) {
                SharedPreferences prefs = context.getSharedPreferences("security_app", Context.MODE_PRIVATE);
                prefs.edit()
                    .putBoolean("security_active_after_boot", false)
                    .putBoolean("device_ready_for_monitoring", false)
                    .apply();
            }
        } else {
            // Silent handling - no action needed for unhandled actions
        }
    }

    private void initializeSecurityServices(Context context) {
        try {
            // Pre-warm camera service for faster capture
            Intent prewarmIntent = new Intent(context, CameraService.class);
            prewarmIntent.putExtra("action", "PREWARM_CAMERA");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(prewarmIntent);
            } else {
                context.startService(prewarmIntent);
            }

        } catch (Exception e) {
            // Silent fail - service will initialize when needed
        }
    }
}
