plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    api(project(":json"))
    testImplementation(libs.kotlin.test)
}

val repoRootPath: String = rootProject.projectDir.parentFile.absolutePath

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", repoRootPath)
    systemProperty("asom.lab.regen", "false")
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The tests read the vector files and the design session's reference outputs outside the source set: never report a cached result as a pass.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// Regenerates lab/conformance/bench vectors: `./gradlew -p lab :bench-core:genBenchVectors` (oracle: self).
tasks.register<Test>("genBenchVectors") {
    group = "verification"
    description = "Regenerates the M04 and M05 body vector files from this implementation (oracle: self)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*RegenerateBenchVectors*") }
    systemProperty("asom.repoRoot", repoRootPath)
    systemProperty("asom.lab.regen", "true")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
