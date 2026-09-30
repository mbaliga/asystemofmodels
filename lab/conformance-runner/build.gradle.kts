plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("xyz.mdhv.asom.lab.conformance.MainKt")
}

dependencies {
    api(project(":core:contract"))
    api(project(":core:catalogue"))
    api(project(":core:routing"))
    api(project(":core:inference-api"))
    api(project(":server"))
    api(project(":json"))
    api(project(":bench-core"))
    api(project(":manifest"))
    api(project(":ledger-model"))
    api(project(":mesh-policy"))
    api(project(":mesh-proto"))
    api(project(":mesh-router"))
    api(project(":mesh-sim"))
    // Vector and scenario files only (LAB_SPEC 1.3 item 3); no normative parser uses this.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

val repoRootPath: String = rootProject.projectDir.parentFile.absolutePath

tasks.named<JavaExec>("run") {
    systemProperty("asom.repoRoot", repoRootPath)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", repoRootPath)
    // The family lines (`family W00: 7 vectors, ...`) are printed by the suite; show them in `labTest`.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The generator test only runs through genVectors.
    systemProperty("asom.lab.regen", "false")
    // The suite reads vectors and fixtures outside the source set and starts a real server: never
    // report a cached or up-to-date result as a pass.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// Regenerates the recorded (real-v1-code) vector files: `./gradlew -p lab :conformance-runner:genVectors`.
// It is a test task so that its loopback listener is "inside a test" (LAB_SPEC R5).
tasks.register<Test>("genVectors") {
    group = "verification"
    description = "Regenerates lab/conformance recorded vectors from the real v1 code (oracle: self)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*RegenerateVectors*") }
    systemProperty("asom.repoRoot", repoRootPath)
    systemProperty("asom.lab.regen", "true")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
