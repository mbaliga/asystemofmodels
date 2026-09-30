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
    api(project(":ledger-model"))
    api(project(":json"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // The presence laws print `LP-<n> iterations: <count>`; show them in the gate output and never report a cached result as a pass.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
