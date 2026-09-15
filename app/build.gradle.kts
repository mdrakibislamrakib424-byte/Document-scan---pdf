plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rakib.docscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rakib.docscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.65.0-phase6.1"
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
        viewBinding = true
    }

    androidResources {
        noCompress += "traineddata"
        // The layout model (~4.7 MB, already a dense binary blob) gains
        // nothing from the APK packer's own re-compression pass — it's
        // read back out with a plain streamed context.assets.open(...)
        // in LayoutEngine.init(), so unlike .traineddata this isn't a
        // correctness requirement, just avoiding pointless double-work at
        // build and first-install time.
        noCompress += "onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CameraX — for in-app document scanning
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // OpenCV — page detection, perspective correction, dewarping
    implementation("org.opencv:opencv:4.9.0")

    // Background processing off the UI thread for detection/warp/dewarp
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // EXIF orientation handling for camera/gallery photos
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // OCR (Phase 5) — Tesseract, since PaddleOCR's mobile-deployable models
    // don't cover Bengali (verified: PP-OCRv6 covers Chinese/English/Latin
    // scripts only; PaddleOCR-VL covers Bengali but is a 0.9B-parameter
    // model, not viable on a phone). Standard (single-threaded) variant.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // Layout detection (Phase 6) — PP-DocLayout-S via ONNX Runtime. Only
    // the *layout* half of PP-Structure carries over from the Phase 5
    // decision to drop PaddleOCR: a layout detector just finds shapes
    // (title/paragraph/table/image regions) on the page, which doesn't
    // depend on Bengali script support the way text recognition does —
    // see LayoutEngine's kdoc. Pinned to a specific stable release rather
    // than a floating version, same as every other dependency here.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
