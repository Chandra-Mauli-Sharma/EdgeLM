plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.edgelm.runtime"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.edgelm.runtime"   // the SDK binds to this package
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-phase0"

        ndk {
            // arm64-v8a is the primary target; armeabi-v7a adds legacy 32-bit reach
            // (much slower — small models only). Add "x86_64" to run on an emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Static STL: with a single native lib there's no libc++_shared.so
                // to ship (or to misalign for 16 KB devices). Switch back to
                // c++_shared only if you add a second .so that must share the STL.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true   // BuildConfig.DEBUG gates the localhost HTTP shim
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":contract"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // OpenAI-compatible HTTP shim
    implementation("androidx.activity:activity-ktx:1.9.0")   // landing/status screen
    implementation("androidx.core:core-splashscreen:1.0.1")  // branded splash screen
}
