// ubuntu-touch/jvm/settings.gradle.kts: a SEPARATE build. It never evaluates ../../settings.gradle.kts and never uses
// the composite-build include (PLATFORM_PLAN P3; LAB_SPEC 2.2 mechanism; REVIEW_ROUND3 R3-CONFORMANCE-3: no composite fallback, ever).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "asom-ut"

// Every mapped project keeps its ROOT project path, because the mapped build files refer to project(":core:contract"),
// project(":json") and so on. Mapped by directory; nothing here evaluates another settings file.
val mapped = linkedMapOf(
    ":core" to "../../core",
    ":core:contract" to "../../core/contract",
    ":core:catalogue" to "../../core/catalogue",
    ":core:routing" to "../../core/routing",
    ":core:inference-api" to "../../core/inference-api",
    ":server" to "../../server",
    // The lab modules (LAB_SPEC 1.2). PLATFORM_PLAN section 7: "maps ../../core/*, ../../lab/* and ../../desktop/node-core".
    // From UT-1 on these become the PROMOTED modules (R3-CONFORMANCE-14); see ubuntu-touch/ERRATA.md ERR-UT-MAP-1.
    ":json" to "../../lab/json",
    ":bench-core" to "../../lab/bench-core",
    ":manifest" to "../../lab/manifest",
    ":ledger-model" to "../../lab/ledger-model",
    ":mesh-policy" to "../../lab/mesh-policy",
    ":mesh-proto" to "../../lab/mesh-proto",
    ":mesh-router" to "../../lab/mesh-router",
    ":mesh-sim" to "../../lab/mesh-sim",
    ":conformance-runner" to "../../lab/conformance-runner",
    // The host-agnostic node (desktop track, DL0).
    ":node-core" to "../../desktop/node-core",
)
mapped.forEach { (path, dir) ->
    include(path)
    project(path).projectDir = file(dir)
}

include(":ut-host")
