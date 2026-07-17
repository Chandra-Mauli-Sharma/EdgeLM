// Root build file. Plugin versions declared here, applied per-module.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    // Kotlin 2.2 (K2 compiler) — required by the LiteRT-LM AAR, which is built with Kotlin 2.x
    // and pulls in kotlin-stdlib 2.2.x. On 1.9 the whole module failed with "incompatible
    // metadata version" on every stdlib call. See docs/PHASE-B-LITERT-INTEGRATION.md.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
