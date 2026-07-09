plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.edgelm.contract"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        aidl = true   // this module exists purely to publish the Binder contract
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
