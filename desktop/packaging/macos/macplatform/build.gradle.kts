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

// Pure JVM, no JNA (macos.md 7.1: the Apple frameworks are reached through the Swift helper, not through native bindings from
// the JVM), and no dependency beyond :node-core (which brings the mapped root projects and kotlinx-serialization).
dependencies {
    api(project(":node-core"))

    testImplementation(libs.kotlin.test)
}

val vectorsDir = layout.projectDirectory.dir("../helper-protocol/vectors")

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.moduleDir", projectDir.absolutePath)
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("asom.vectors", vectorsDir.asFile.absolutePath)
    systemProperty("asom.report", layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.absolutePath)
    systemProperty("asom.lines", layout.buildDirectory.file("reports/desktop/helper-protocol.lines").get().asFile.absolutePath)
    // The real helper for the macOS-only integration tests: built by `swift build -c release --package-path helper` (CI does it first).
    systemProperty(
        "asom.macHelper",
        System.getenv("ASOM_MAC_HELPER") ?: layout.projectDirectory.file("../helper/.build/release/asom-mac-helper").asFile.absolutePath
    )
    // The forked-JVM tests (fake helper child, lock contention) need the test runtime classpath as a plain string.
    val cp = sourceSets.test.get().runtimeClasspath
    doFirst { systemProperty("asom.testClasspath", cp.asPath) }
    // The macOS-only integration tests are SKIPPED on other systems. A skipped test proves nothing, so a cached or up-to-date
    // result would hide that; always run, and always print the counts.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.apply { parentFile.mkdirs(); delete() }
        layout.buildDirectory.file("reports/desktop/helper-protocol.lines").get().asFile.delete()
    }
    maxParallelForks = 1
    testLogging {
        events("skipped", "failed")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null) {
            println(
                "macplatform test summary: ${result.testCount} tests, ${result.successfulTestCount} passed, " +
                    "${result.failedTestCount} failed, ${result.skippedTestCount} skipped   " +
                    "(os=${System.getProperty("os.name")}; skipped tests are the @EnabledOnOs(MAC) integration tests and the assumption-gated ones)"
            )
        }
    }))
}
