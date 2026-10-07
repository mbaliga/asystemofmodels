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
        // CI runs this module on JDK 17 as well; the container has only JDK 21, so the JDK 17 API surface is enforced here.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// JNA is the one new third-party dependency of the Windows track (windows.md W-D5, the CD-D registry row under D23).
// The root version catalogue is read-only for this track, so the pin lives here. 5.19.1 was the latest 5.x release on
// Maven Central on 2026-09-30 (maven-metadata.xml <release>); the plan's "5.19.x" therefore exists.
val jnaVersion = "5.19.1"

dependencies {
    api(project(":node-core"))
    implementation("net.java.dev.jna:jna:$jnaVersion")
    implementation("net.java.dev.jna:jna-platform:$jnaVersion")

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.moduleDir", projectDir.absolutePath)
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("asom.report", layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.absolutePath)
    // The Windows-only integration tests are SKIPPED on other systems. A skipped test proves nothing, so a cached or
    // up-to-date result would hide that; always run, and always print the counts.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst { layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.apply { parentFile.mkdirs(); delete() } }
    maxParallelForks = 1
    testLogging {
        events("skipped", "failed")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) {
            println(
                "winplatform test summary: ${result.testCount} tests, ${result.successfulTestCount} passed, " +
                    "${result.failedTestCount} failed, ${result.skippedTestCount} skipped   " +
                    "(os=${System.getProperty("os.name")}; skipped tests are the @EnabledOnOs(WINDOWS) integration tests and the assumption-gated ones)"
            )
        }
    }))
}
