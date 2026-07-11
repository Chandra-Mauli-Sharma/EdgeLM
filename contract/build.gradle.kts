plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")   // published transitively with the SDK (JitPack)
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

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Chandra-Mauli-Sharma.EdgeLM"
            artifactId = "contract"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
