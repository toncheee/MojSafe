plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.safeimport"
    // NOTE: bump these to the latest values shown in Android Studio's SDK Manager
    // when you open this project (Android 16 = API level 36 at time of writing).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.safeimport"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 64-bit only, per the request: no armeabi-v7a / x86 (32-bit) output.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.security:security-crypto:1.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
