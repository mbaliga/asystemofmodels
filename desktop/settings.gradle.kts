// desktop/settings.gradle.kts: a SEPARATE build. It never evaluates ../settings.gradle.kts and never uses includeBuild
// (PLATFORM_PLAN P3; LAB_SPEC 2.2 mechanism; REVIEW_ROUND3 R3-CONFORMANCE-3: no composite fallback, ever).
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
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "asom-desktop"

// The five pure-JVM root projects, mapped by directory under their ROOT project paths.
// The paths must be identical to the root build's, because server/build.gradle.kts
// refers to project(":core:contract") and so on.
val mapped = linkedMapOf(
    ":core" to "../core",
    ":core:contract" to "../core/contract",
    ":core:catalogue" to "../core/catalogue",
    ":core:routing" to "../core/routing",
    ":core:inference-api" to "../core/inference-api",
    ":server" to "../server",
)
mapped.forEach { (path, dir) ->
    include(path)
    project(path).projectDir = file(dir)
}

include(":node-core")
include(":node")

// ---- One include line per later track (PLATFORM_PLAN P1). Append-only; do not reorder or edit the lines above. ----
// WINDOWS TRACK adds exactly one line directly below this comment:   include(":packaging:windows:winplatform")
// MACOS TRACK adds exactly one line directly below this comment:     include(":packaging:macos:macplatform")
