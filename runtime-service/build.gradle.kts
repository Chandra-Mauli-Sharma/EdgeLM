import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven by keystore.properties at the repo root (gitignored).
// If it's absent, everything still builds — release just comes out unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

android {
    namespace = "ai.edgelm.runtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.edgelm.runtime"   // the SDK binds to this package
        minSdk = 26
        targetSdk = 35                         // Play requires API 35+ (Android 15)
        versionCode = 12
        versionName = "0.1.12"

        ndk {
            // arm64-v8a ONLY while the Vulkan backend is enabled: ggml's Vulkan code
            // doesn't compile for 32-bit ARM (strongly-typed Vulkan-Hpp handles break the
            // casts), and every Vulkan-capable device is 64-bit. clear() first so a stale
            // armeabi-v7a can't linger. Restore "armeabi-v7a" for the CPU-only release.
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Static STL: with a single native lib there's no libc++_shared.so
                // to ship (or to misalign for 16 KB devices). Switch back to
                // c++_shared only if you add a second .so that must share the STL.
                arguments += "-DANDROID_STL=c++_static"
                // GPU acceleration (PHASE1-VULKAN-GPU.md): requires the LunarG Vulkan
                // SDK so `glslc` is on PATH at build time. Comment this out again if you
                // build on a machine without the Vulkan toolchain.
                arguments += "-DEDGELM_VULKAN=ON"
                // Vulkan 1.1 entry points (vkGetPhysicalDeviceFeatures2, …) only appear in
                // the NDK's libvulkan stub at API 28+, so link the NATIVE lib against
                // android-28. Any device with usable Vulkan compute supports 1.1. Remove
                // these two lines when you disable EDGELM_VULKAN for the CPU-only release.
                arguments += "-DANDROID_PLATFORM=android-28"
                arguments += "-DCMAKE_SYSTEM_VERSION=28"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 shrink + obfuscate
            isShrinkResources = true        // drop unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        // NOTE: debug keeps the same applicationId (ai.edgelm.runtime) on purpose —
        // the SDK/demo apps bind that exact package, so don't suffix it.
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
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // resilient model downloads
}
