// ubuntu-touch/jvm/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Every project mapped from outside this directory must never write into its own source tree
// (../../core/*/build, ../../lab/*/build, ../../desktop/node-core/build, ../../server/build).
val mappedPaths = setOf(
    ":core", ":core:contract", ":core:catalogue", ":core:routing", ":core:inference-api", ":server",
    ":json", ":bench-core", ":manifest", ":ledger-model", ":mesh-policy", ":mesh-proto", ":mesh-router", ":mesh-sim",
    ":conformance-runner", ":node-core",
)
subprojects {
    if (path in mappedPaths) {
        layout.buildDirectory.set(
            rootProject.layout.buildDirectory.dir("mapped/" + path.removePrefix(":").replace(':', '_'))
        )
    }
}

tasks.register("utTest") {
    group = "verification"
    description = "Runs the Ubuntu Touch host tests and the UTC01-UTC05 conformance families (UT0.1). " +
        "The mapped projects' own tests belong to the root jvmTest, ./gradlew -p lab labTest and ./gradlew -p desktop desktopTest."
    dependsOn(":ut-host:test")
}

tasks.register("utNodeJar") {
    group = "build"
    description = "Builds the UNSIGNED node jar that ships inside the click (ut-host/build/libs/asom-ut-node.jar)."
    dependsOn(":ut-host:utNodeJar")
}
