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
        versionCode = 15                       // set to (last uploaded to Play) + 1 before shipping
        versionName = "0.1.15"

        ndk {
            // arm64-v8a is the primary target; armeabi-v7a adds legacy 32-bit reach
            // (much slower — small models only). Keep BOTH for the CPU-only release so we
            // don't drop 32-bit devices on Play. (Restrict to arm64-v8a only if you re-enable
            // the Vulkan backend, which doesn't compile for 32-bit ARM.)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Static STL: with a single native lib there's no libc++_shared.so
                // to ship (or to misalign for 16 KB devices). Switch back to
                // c++_shared only if you add a second .so that must share the STL.
                arguments += "-DANDROID_STL=c++_static"
                // --- GPU (Vulkan) build — OFF for the Play release ---------------------
                // Enabling these gives GPU offload but (a) needs the LunarG Vulkan SDK +
                // MSVC on the build host, (b) drops armeabi-v7a (ggml Vulkan is 64-bit only),
                // and (c) bloats the APK with the compute shaders. The engine auto-probe
                // still picks CPU-vs-GPU correctly whenever this is on. To build a Vulkan
                // APK (e.g. for Adreno testing), uncomment all three AND set abiFilters to
                // arm64-v8a only above.
                // arguments += "-DEDGELM_VULKAN=ON"
                // arguments += "-DANDROID_PLATFORM=android-28"
                // arguments += "-DCMAKE_SYSTEM_VERSION=28"
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

    // --- LiteRT-LM (GPU) engine — shipped live in all builds. Gated at runtime by the SoC
    // allowlist + learned GPU verdict + picker filter, so it's only ever offered on devices where
    // it can run (Adreno). NOTE: this AAR bundles LiteRT's native libs and increases APK size —
    // acceptable trade for shipping the GPU path; revisit a feature module if Play size limits bite.
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Pure-JVM unit tests (engine routing / artifact selection — no device or native lib).
    // Run with: ./gradlew :runtime-service:testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
