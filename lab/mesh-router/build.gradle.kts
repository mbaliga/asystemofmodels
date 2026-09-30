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
    api(project(":core:contract"))
    api(project(":core:catalogue"))
    api(project(":core:routing"))
    api(project(":mesh-policy"))
    api(project(":json"))
    testImplementation(libs.kotlin.test)
    // Loads the committed R04 vector file only (LAB_SPEC 1.3 item 3); no normative parser uses it.
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnitPlatform()
    // The laws print `RL<n> iterations: <count> violations: <count>`; show them in the gate output and never report a cached result as a pass.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
