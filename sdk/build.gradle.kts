plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")   // publish to JitPack (see jitpack.yml)
}

android {
    namespace = "ai.edgelm.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Publish only the release variant, with sources for consumers.
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api(project(":contract"))   // re-export the Binder types to consumers
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// JitPack overrides group/version from the git tag; artifactId stays the module name.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Chandra-Mauli-Sharma.EdgeLM"
            artifactId = "sdk"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
