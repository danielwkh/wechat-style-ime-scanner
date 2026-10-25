plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.danielwkh.weime"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.danielwkh.weime"
        minSdk = 26
        targetSdk = 34
        // 由 GitHub Actions 依標籤自動帶入；本機編譯用預設值
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 9
        versionName = (project.findProperty("vn") as String?) ?: "1.8"
        ndk { abiFilters.add("arm64-v8a") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // 解壓離線語音模型（tar.bz2）
    implementation("org.apache.commons:commons-compress:1.26.2")
}
