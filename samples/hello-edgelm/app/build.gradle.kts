plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.helloedgelm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.helloedgelm"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ── The only EdgeLM line you need ─────────────────────────────────────────
    // Pulls the thin client SDK (and its :contract) from JitPack. The permission
    // and package <queries> to reach the runtime come in automatically via manifest
    // merge — you don't touch AndroidManifest.xml.
    implementation("com.github.Chandra-Mauli-Sharma.EdgeLM:sdk:0.1.0")
    // ──────────────────────────────────────────────────────────────────────────

    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
