// lab/settings.gradle.kts: a SEPARATE build. It never evaluates ../settings.gradle.kts.
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

rootProject.name = "asom-lab"

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

listOf(
    "json", "bench-core", "manifest", "ledger-model", "mesh-policy",
    "mesh-proto", "mesh-router", "mesh-sim", "conformance-runner",
).forEach { include(":$it") }
