plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ocubea"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ocubea"
        minSdk = 23 // Android 6.0 Marshmallow (API 23)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // CameraX for camera access and preview
    val cameraxVersion = "1.3.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    
    // MediaCodec for hardware encoding (H.264, H.265)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-common:1.2.0")
    
    // Networking - lightweight HTTP server and client
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON handling
    implementation("org.json:json:20231013")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
