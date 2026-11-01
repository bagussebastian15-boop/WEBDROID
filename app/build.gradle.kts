plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.ringan.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ringan.browser"
        minSdk = 21          // Android 5.0
        targetSdk = 34
        versionCode = 4
        versionName = "0.4.0"
        // Tanpa library native -> satu APK untuk semua arsitektur (ARM 32/64, x86)
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

// Sengaja tanpa dependensi AndroidX/GeckoView: APK kecil, RAM hemat.
dependencies {
}
