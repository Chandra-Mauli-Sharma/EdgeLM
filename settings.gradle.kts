pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EdgeLM"

// Phase 0 spike modules
include(":contract")        // shared AIDL Binder contract
include(":runtime-service") // the shared, out-of-process inference service (hosts llama.cpp via JNI)
include(":sdk")             // thin client: EdgeLM.chat() — hides Binder from apps
include(":demo-app-a")      // first consumer app
include(":demo-app-b")      // second consumer app (proves shared-memory across UIDs)
