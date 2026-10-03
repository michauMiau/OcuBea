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

    signingConfigs {
        getByName("debug") {
            // Keystore committed to repo — keeps the same debug signature on CI and locally
            storeFile = rootProject.file("debug.keystore")
            storePassword = "ocubea123"
            keyAlias = "ocubea"
            keyPassword = "ocubea123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed only when the release keystore is present and named on the
            // command line. Deliberately not defaulted: a release build that
            // silently falls back to no signing is how unsigned APKs reach users,
            // and one signed with the committed debug key is worse still, because
            // that key is public. Configure with:
            //   ./gradlew :app:assembleRelease \
            //     -PocubeaStoreFile=/abs/path/release.keystore \
            //     -PocubeaStorePass=... -PocubeaKeyAlias=... -PocubeaKeyPass=...
            val releaseStore = (findProperty("ocubeaStoreFile") as String?)
            if (releaseStore != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(releaseStore)
                    storePassword = findProperty("ocubeaStorePass") as String
                    keyAlias = findProperty("ocubeaKeyAlias") as String
                    keyPassword = findProperty("ocubeaKeyPass") as String
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }

    // android.util.Log throws in a JVM unit test ("Method w in android.util.Log
    // not mocked"), so any test that reaches a code path which logs fails for a
    // reason that has nothing to do with what it is checking. The stop/teardown
    // path logs on purpose -- it warns when it deliberately keeps a codec alive
    // rather than risk a native crash -- and that warning is exactly the
    // behaviour worth testing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // minSdk is 23, and the app is expected to run on Android 6. An API
    // introduced after 23 throws NoSuchMethodError at runtime on that device —
    // an Error, so `catch (_: Exception)` does not catch it and the process
    // dies instead of falling back.
    //
    // Lint's NewApi check finds these, but it only runs under `./gradlew lint`,
    // never as part of `assembleDebug`. With compileSdk 34 the symbols resolve,
    // so the app built cleanly and shipped two such calls: isHardwareAccelerated
    // (API 29) and ConcurrentHashMap.computeIfAbsent (API 24). Both are fixed,
    // and abortOnError makes the next one a build failure rather than a crash
    // found on a phone.
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // NewApi is the point of this block; a baseline would hide the very
        // regressions it exists to catch, so none is used.
        warningsAsErrors = false
        disable += setOf(
            "GradleDependency",
            "OldTargetApi",
            // Checked instead by tools/strings_verify.js, which compares the
            // English and Polish resource sets for coverage and placeholders.
            "UnusedResources",
            // The remaining HardcodedText hits are emoji used as button icons,
            // the app's own name, and layout placeholders. None is prose, so
            // none belongs in a translated resource.
            "HardcodedText",
            "ButtonStyle",
        )
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // CameraX for camera access and preview
    val cameraxVersion = "1.3.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    
    // MediaCodec for hardware encoding (H.264, H.265)
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-common:1.2.0")
    // PlayerView for the native clip player; the exoplayer alone is headless.
    implementation("androidx.media3:media3-ui:1.2.0")
    
    // Networking - lightweight HTTP server and client
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // JSON handling
    implementation("org.json:json:20231013")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    // StreamServer takes five collaborators by type, and three of them
    // (CameraManager, MotionRecorder, OnvifDiscovery) are final Kotlin classes
    // with no interface to stand in for. Mockito 5's inline mock maker is the
    // default there, so a final class can be mocked without any production
    // seam; mockito-inline is not needed on top of it.
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// A few guards assert on source shape rather than runtime behaviour, because
// the path they cover (a failed MediaCodec.configure) is unreachable from a
// plain JVM - android.jar throws before a codec is ever allocated. Passing the
// source root lets those tests fail loudly when their file moves, instead of
// silently checking nothing.
tasks.withType<Test>().configureEach {
    systemProperty("ocubea.srcRoot", rootProject.projectDir.absolutePath)
}
