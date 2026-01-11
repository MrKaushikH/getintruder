# 🔧 Development Setup Guide

This guide helps you set up the GetIntruder app for development and deployment.

## 📋 Prerequisites

- **Android Studio**: Arctic Fox (2020.3.1) or later
- **Java**: JDK 8 or higher
- **Android SDK**: API Level 26+ (Android 8.0+)
- **Firebase Account**: Free tier is sufficient for development

## 🔥 Firebase Configuration

### 1. Create Firebase Project
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click "Add project" and follow the setup wizard
3. Note your **Project ID** for later use

### 2. Enable Firebase Services
Enable these services in your Firebase project:

- **Authentication** → Sign-in method → Email/Password + Google
- **Firestore Database** → Create database in test mode
- **Storage** → Create default bucket
- **Functions** (optional) → For email notifications

### 3. Configure Android App
1. In Firebase Console, click "Add app" → Android
2. **Important**: Change the package name from `com.example.securityapp` to your own unique package name
3. Update the package name in `app/build.gradle.kts` (applicationId and namespace)
4. Download `google-services.json` for your package name
5. Place the file in `app/` directory (replace template)

### 4. Add SHA-1 Fingerprint
```bash
# Debug keystore (for development)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# Copy the SHA1 fingerprint to Firebase Console → Project Settings → Your apps
```

## 🛠️ Build Instructions

### Debug Build
```bash
# Clone repository
git clone <your-repo-url>
cd getintruder

# Build debug APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release Build
```bash
# Generate keystore (first time only)
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias release

# Build release APK
./gradlew assembleRelease
```

## 🔐 Security Configuration

### Firebase Security Rules
Upload these rules to your Firebase project:

**Firestore Rules:**
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
    match /email_requests/{requestId} {
      allow create: if request.auth != null;
    }
  }
}
```

**Storage Rules:**
```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /security_photos/{userId}/{photoId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

## ✅ Testing Checklist

After setup, verify these features work:

- [ ] User registration and login
- [ ] Google Sign-In (if configured)
- [ ] Device admin activation
- [ ] Photo capture on failed attempts
- [ ] Cloud photo backup
- [ ] Email notifications
- [ ] Photo gallery and preview

## 🚨 Troubleshooting

### Common Issues

**Google Sign-In fails:**
- Check SHA-1 fingerprint in Firebase Console
- Verify `google-services.json` is in correct location
- Enable Google Sign-In in Firebase Auth

**Photos not uploading:**
- Check Firebase Storage rules
- Verify internet connection
- Check Storage bucket configuration

**Email notifications not working:**
- Configure Firebase Functions for email
- Check Firestore rules allow email requests
- Verify user has valid email address

## 📞 Support

For development issues:
1. Check this setup guide
2. Review code comments
3. Create GitHub issue with details

---

**Happy coding! 🚀**
