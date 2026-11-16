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
    api(project(":manifest"))
    api(project(":ledger-model"))
    api(project(":mesh-policy"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    // a failed assertion prints its message in the CI log (the gate class asserts in an AfterAll, which has no other place to show it)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
