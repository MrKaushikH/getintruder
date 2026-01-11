// GetIntruder Security App - Build Configuration
//
// IMPORTANT: Before releasing your app, customize these values:
// 1. Change applicationId from "com.example.securityapp" to your own package name
// 2. Update versionCode and versionName for your releases
// 3. Configure release signing (uncomment signingConfig in release block)
//
// NOTE: minSdk 26 (Android 8.0+) is required for adaptive icons
//
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.securityapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.securityapp"
        minSdk = 26  // Android 8.0+ required for adaptive icons
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // ProGuard DISABLED for debug builds
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )

            versionNameSuffix = "-DEBUG"

            // Manifest placeholders for debug build
            manifestPlaceholders["enableCrashReporting"] = "true"
        }

        release {
            // ProGuard ENABLED for release builds
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Manifest placeholders for release build
            manifestPlaceholders["enableCrashReporting"] = "false"

            // Signing configuration (uncomment when you have keystore)
            // signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Updated packaging configuration
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin"
            )
        }
    }

    // Bundle configuration
    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = false
        }
        abi {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.firebase.firestore)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.storage)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.multidex)
    implementation(libs.google.firebase.storage)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.biometric)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.auth.v2060)
    implementation(libs.play.services.auth.v2070)
    implementation(libs.firebase.auth.v2230)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}