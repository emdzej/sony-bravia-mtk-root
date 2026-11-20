plugins {
    id("com.android.application")
}

android {
    namespace = "com.mjaskols.tvhome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mjaskols.tvhome"
        minSdk = 26 // Bravia runs Android 8.0
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideloaded only — debug key is fine and avoids keystore management.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
