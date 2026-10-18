plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":core:inference-api"))
    api(project(":server"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("asom.moduleDir", projectDir.absolutePath)
    systemProperty("asom.report", layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.absolutePath)
    // The forked-JVM harnesses need the test runtime classpath as a plain string.
    val cp = sourceSets.test.get().runtimeClasspath
    doFirst { systemProperty("asom.testClasspath", cp.asPath) }
    // These tests fork JVMs and read files outside the source set, so a cached or up-to-date result proves nothing.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    // A report file per run; the aggregate desktopTest task prints it.
    doFirst { layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.apply { parentFile.mkdirs(); delete() } }
    maxParallelForks = 1
}
