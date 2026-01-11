package com.example.securityapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

public class NetworkHelper {
    private static final String TAG = "NetworkHelper";
    private static boolean isNetworkConnected = false;
    private static boolean isWifiConnected = false;
    private static ConnectivityManager.NetworkCallback networkCallback;
    private static ConnectivityManager connectivityManager;

    public static void registerNetworkCallback(Context context) {
        try {
            if (context == null) return;

            connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) return;

            // Check initial network state
            updateNetworkStatus(connectivityManager);

            // Register network callback
            if (networkCallback == null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        isNetworkConnected = true;
                        updateWifiStatus(connectivityManager);
                        Log.d(TAG, "Network connected: WiFi=" + isWifiConnected);

                        // Network is now available - removed call to non-existent method
                        // FirebaseHelper.retryPendingOperations(context);
                    }

                    @Override
                    public void onLost(Network network) {
                        isNetworkConnected = false;
                        isWifiConnected = false;
                        Log.d(TAG, "Network disconnected");
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                        boolean oldWifiState = isWifiConnected;
                        isWifiConnected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

                        if (oldWifiState != isWifiConnected) {
                            Log.d(TAG, "WiFi status changed: " + isWifiConnected);
                        }
                    }
                };

                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                connectivityManager.registerNetworkCallback(request, networkCallback);
                Log.d(TAG, "Registered network callback for API " + Build.VERSION.SDK_INT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering network callback", e);
        }
    }

    public static void unregisterNetworkCallback(Context context) {
        try {
            if (context == null) return;

            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null) {
                cm.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
                Log.d(TAG, "Unregistered network callback");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering network callback", e);
        }
    }

    private static void updateNetworkStatus(ConnectivityManager cm) {
        if (cm == null) return;

        try {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) {
                isNetworkConnected = false;
                isWifiConnected = false;
                return;
            }

            NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
            isNetworkConnected = capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            isWifiConnected = capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            Log.e(TAG, "Error checking network status", e);
        }
    }

    private static void updateWifiStatus(ConnectivityManager cm) {
        if (cm == null) return;

        try {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                isWifiConnected = capabilities != null &&
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } else {
                isWifiConnected = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking WiFi status", e);
        }
    }

    public static boolean isNetworkAvailable(Context context) {
        if (networkCallback != null) {
            // Use cached status if callback is registered
            return isNetworkConnected;
        } else {
            // Otherwise check directly
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return false;

                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return false;

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (Exception e) {
                Log.e(TAG, "Error checking network availability", e);
                return false;
            }
        }
    }

    public static boolean isWifiConnection(Context context) {
        if (networkCallback != null) {
            // Use cached status if callback is registered
            return isWifiConnected;
        } else {
            // Otherwise check directly
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) return false;

                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork == null) return false;

                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            } catch (Exception e) {
                Log.e(TAG, "Error checking WiFi status", e);
                return false;
            }
        }
    }

    /**
     * Alias method for backward compatibility
     */
    public static boolean isConnected() {
        return isNetworkConnected;
    }

    /**
     * Check network connection with context parameter for compatibility
     */
    public static boolean isConnected(Context context) {
        return isNetworkAvailable(context);
    }
}
