# ============================================================================
# COMPREHENSIVE PROGUARD RULES FOR GET INTRUDER APP
# Optimized for SDKs and reflection-based logic preservation
# ============================================================================

# Basic ProGuard settings
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Keep line numbers for debugging crashes but hide source file names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove debug logging in release builds only
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# Remove System.out.print statements
-assumenosideeffects class java.lang.System {
    public static void out.print(...);
    public static void out.println(...);
    public static void err.print(...);
    public static void err.println(...);
}

# ============================================================================
# FIREBASE AND GOOGLE PLAY SERVICES - CRITICAL SDK PRESERVATION
# ============================================================================

# Firebase Core - Essential for app functionality
-keep class com.google.firebase.** { *; }
-keep interface com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Firebase Authentication - Keep all reflection-based methods
-keep class com.google.firebase.auth.** { *; }
-keep interface com.google.firebase.auth.** { *; }
-keepclassmembers class com.google.firebase.auth.** { *; }

# Firebase Firestore - Database operations
-keep class com.google.firebase.firestore.** { *; }
-keep interface com.google.firebase.firestore.** { *; }
-keepclassmembers class com.google.firebase.firestore.** { *; }

# Firebase Storage - File upload/download
-keep class com.google.firebase.storage.** { *; }
-keep interface com.google.firebase.storage.** { *; }

# Firebase App Check - Security verification
-keep class com.google.firebase.appcheck.** { *; }
-keep interface com.google.firebase.appcheck.** { *; }
-keep class com.google.firebase.appcheck.debug.** { *; }
-keep class com.google.firebase.appcheck.playintegrity.** { *; }

# Firebase Messaging
-keep class com.google.firebase.messaging.** { *; }
-keep interface com.google.firebase.messaging.** { *; }

# Google Play Services - Core functionality
-keep class com.google.android.gms.** { *; }
-keep interface com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Google Play Services Auth
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# ============================================================================
# ANDROID FRAMEWORK - CRITICAL SYSTEM CLASSES
# ============================================================================

# Activities, Services, Receivers
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# Device Admin Receiver - Critical for security functionality
-keep public class * extends android.app.admin.DeviceAdminReceiver
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver { *; }

# Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Enums - Keep values() and valueOf() methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep annotations and signatures for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# View constructors for XML inflation
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# onClick methods referenced in XML
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# ============================================================================
# ANDROIDX AND SUPPORT LIBRARIES
# ============================================================================

# AndroidX libraries
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Biometric authentication
-keep class androidx.biometric.** { *; }
-keep interface androidx.biometric.** { *; }

# CardView and other UI components
-keep class androidx.cardview.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }

# ============================================================================
# APPLICATION-SPECIFIC CLASSES - STRATEGIC PRESERVATION
# ============================================================================

# Main Application class
-keep public class com.example.securityapp.SecurityApp {
    public void onCreate();
    public void onTerminate();
    public static ** getAppContext();
    public static boolean isFirebaseInitialized();
    public static boolean isAppCheckInitialized(android.content.Context);
    public static java.lang.String getAppCheckDebugToken(android.content.Context);
}

# Main Activities - Keep lifecycle methods and essential functionality
-keep public class com.example.securityapp.MainActivity {
    protected void onCreate(android.os.Bundle);
    protected void onResume();
    protected void onPause();
    public void onWindowFocusChanged(boolean);
    private void checkAppLock();
    private void updateStats();
}

-keep public class com.example.securityapp.AuthActivity {
    protected void onCreate(android.os.Bundle);
    protected void onResume();
}

-keep public class com.example.securityapp.AppLockActivity {
    protected void onCreate(android.os.Bundle);
}

# Device Admin Receiver - Critical for intruder detection
-keep public class com.example.securityapp.SecurityDeviceAdminReceiver {
    public void onPasswordFailed(android.content.Context, android.content.Intent);
    public void onPasswordSucceeded(android.content.Context, android.content.Intent);
    public void onEnabled(android.content.Context, android.content.Intent);
    public void onDisabled(android.content.Context, android.content.Intent);
}

# Services - Essential for background operations
-keep public class com.example.securityapp.CameraService {
    public int onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
    public void onCreate();
    public void onDestroy();
}

-keep public class com.example.securityapp.CloudBackupService {
    public int onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
}

# ============================================================================
# UTILITY CLASSES - PRESERVE PUBLIC APIS BUT ALLOW INTERNAL OBFUSCATION
# ============================================================================

# UserManager - Keep public static methods
-keep class com.example.securityapp.UserManager {
    public static void signOut(android.content.Context);
    public static java.io.File getUserPhotosDirectory(android.content.Context);
    public static void triggerManualBackup(android.content.Context);
    public static boolean isUserAuthenticated();
    public static com.google.firebase.auth.FirebaseUser getCurrentUser();
}

# AppLockManager - Keep public interface but obfuscate internals
-keep class com.example.securityapp.AppLockManager {
    public static com.example.securityapp.AppLockManager getInstance(android.content.Context);
    public boolean isLockEnabled();
    public boolean isAuthenticationRequired();
    public void resetFreshAuthentication();
    public boolean isBiometricAvailable();
    public boolean isBiometricEnabled();
    public boolean hasPinSet();
    public void setLockEnabled(boolean);
    public void setBiometricEnabled(boolean);
    public boolean setPin(java.lang.String);
    public long getLockTimeout();
    public void setLockTimeout(long);
    # Keep timeout constants
    public static final long TIMEOUT_*;
}

# FirebaseHelper - Keep callback interfaces and public methods
-keep class com.example.securityapp.FirebaseHelper {
    public static void checkFirestorePermissions(android.content.Context, com.example.securityapp.FirebaseHelper$FirestorePermissionCallback);
    public static void fetchCloudPhotos(android.content.Context, com.example.securityapp.FirebaseHelper$CloudPhotosCallback);
    public static void downloadCloudPhoto(com.google.firebase.storage.StorageReference, java.io.File, com.example.securityapp.FirebaseHelper$CloudPhotoDownloadCallback);
    public static void configureFirestore();
    public static void saveAppCheckDebugToken(android.content.Context, java.lang.String);
    public static java.lang.String getAppCheckDebugToken(android.content.Context);

    # Keep all callback interfaces
    public interface *;
    public static interface *;
}

# OTPService - Keep public API
-keep class com.example.securityapp.OTPService {
    public static void requestAdminAccess(android.content.Context, com.example.securityapp.OTPService$AdminAccessCallback);
    public static void verifyOTP(android.content.Context, java.lang.String, com.example.securityapp.OTPService$VerifyOTPCallback);
    public static long getResendRemainingSeconds();

    # Keep callback interfaces
    public interface *;
    public static interface *;
}

# NetworkHelper - Keep network monitoring methods
-keep class com.example.securityapp.NetworkHelper {
    public static void registerNetworkCallback(android.content.Context);
    public static void unregisterNetworkCallback(android.content.Context);
    public static boolean isNetworkAvailable(android.content.Context);
    public static boolean isWifiConnection(android.content.Context);
}

# ============================================================================
# REFLECTION AND DYNAMIC CODE - PRESERVE FOR PROPER FUNCTIONALITY
# ============================================================================

# Keep classes that use reflection
-keepclassmembers class * {
    @java.lang.reflect.** <methods>;
}

# Keep classes with @Keep annotation
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep classes that are loaded dynamically
-keep class * implements java.lang.reflect.InvocationHandler
-keep class * implements java.lang.reflect.Proxy

# ============================================================================
# MULTIDEX SUPPORT
# ============================================================================
-keep class androidx.multidex.** { *; }
-keep class android.support.multidx.** { *; }
-dontwarn androidx.multidex.**

# ============================================================================
# JSON AND SERIALIZATION
# ============================================================================

# Keep JSON serialization classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep classes used with GSON
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ============================================================================
# SECURITY AND ENCRYPTION
# ============================================================================

# Keep cryptographic classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-dontwarn javax.crypto.**
-dontwarn java.security.**

# Keep security providers
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.openjsse.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ============================================================================
# REMOVE UNUSED CODE AND WARNINGS
# ============================================================================

# Remove BuildConfig debug information in release
-assumenosideeffects class **.BuildConfig {
    public static final boolean DEBUG;
    public static final String APPLICATION_ID;
    public static final String BUILD_TYPE;
    public static final String FLAVOR;
    public static final int VERSION_CODE;
    public static final String VERSION_NAME;
}

# Suppress warnings for missing classes that are handled at runtime
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.invoke.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Remove reflection warnings
-dontwarn java.lang.reflect.**

# ============================================================================
# OPTIMIZATION SETTINGS
# ============================================================================

# Enable optimization
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable
-optimizationpasses 5
-allowaccessmodification

# ============================================================================
# FINAL RULES FOR RELEASE BUILD OBFUSCATION
# ============================================================================

# Aggressive obfuscation for non-public classes
-keep,allowobfuscation class com.example.securityapp.** {
    !public <methods>;
    !public <fields>;
}

# Repackage classes for additional security
-repackageclasses 'a'

# Print mapping files for debugging
-printmapping mapping.txt
-printusage usage.txt
-printseeds seeds.txt
-printconfiguration configuration.txt
