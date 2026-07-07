pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "asystemofmodels"

// Pure-JVM modules — always included. These MUST build and test on a bare JDK
// with no Android SDK present (ASOM_BUILD_BRIEF.md §3, "pure-JVM-first law").
include(":core:contract")
include(":core:catalogue")
include(":core:routing")
include(":core:inference-api")
include(":server")

// Android modules — included only when an Android SDK is available.
// This keeps `./gradlew :server:test` working on a bare JDK (e.g. the Deck
// distrobox). CI runners and any machine with ANDROID_HOME / local.properties
// sdk.dir get the full build.
val localProps = file("local.properties")
val hasSdkDir = localProps.exists() && localProps.readLines().any { it.trim().startsWith("sdk.dir") }
val hasAndroidSdk = hasSdkDir ||
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
    !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()

if (hasAndroidSdk) {
    include(":vault")
    include(":pairing")
    include(":storage")
    include(":ledger")
    include(":app")
    include(":client")
    include(":sample-client")
} else {
    logger.lifecycle("asom: no Android SDK detected — Android modules excluded (pure-JVM mode).")
}
