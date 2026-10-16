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
    api(project(":json"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // The laws print `L-L<n> iterations: <count>` and the SIGKILL harness prints `rows intact after kill at <point>`; show them in the gate output.
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The laws and the durability harness are the point of the run: never report a cached result as a pass.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
