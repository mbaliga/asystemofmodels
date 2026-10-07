// lab/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The mapped projects must never write into ../core/*/build or ../server/build.
val mappedPaths = setOf(":core", ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server")
subprojects {
    if (path in mappedPaths) {
        layout.buildDirectory.set(
            rootProject.layout.buildDirectory.dir("mapped/" + path.removePrefix(":").replace(':', '_'))
        )
    }
}

val labModules = listOf(
    "json", "bench-core", "manifest", "ledger-model", "mesh-policy",
    "mesh-proto", "mesh-router", "mesh-sim", "conformance-runner",
)

tasks.register("labTest") {
    group = "verification"
    description = "Runs every lab test. The mapped root projects' own tests belong to the root jvmTest, not here."
    dependsOn(labModules.map { ":$it:test" })
}
