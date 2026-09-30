plugins {
    alias(libs.plugins.kotlin.jvm)
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
    mainClass.set("xyz.mdhv.asom.lab.sim.SimMainKt")
}

dependencies {
    api(project(":mesh-router"))
    api(project(":mesh-policy"))
    api(project(":ledger-model"))
    api(project(":core:catalogue"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}

tasks.named<JavaExec>("run") {
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
