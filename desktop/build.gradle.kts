// desktop/build.gradle.kts
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

// Modules that carry desktop tests. A later track appends its own module here when it adds its include line.
val desktopModules = listOf("node-core", "node")

tasks.register("desktopTest") {
    group = "verification"
    description = "Runs every desktop test (node-core, node), then prints the per-law report lines. " +
        "The mapped root projects' own tests belong to the root jvmTest, not here."
    dependsOn(desktopModules.map { ":$it:test" })
    val reports = desktopModules.map { it to layout.projectDirectory.file("$it/build/reports/desktop/report.txt").asFile }
    doLast {
        reports.forEach { (m, f) ->
            if (f.exists()) {
                println("---- desktop report: $m (evidence label: LAB, NOT DEVICE EVIDENCE) ----")
                print(f.readText(Charsets.UTF_8))
            }
        }
    }
}
