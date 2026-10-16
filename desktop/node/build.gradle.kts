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
    applicationName = "asom-node"
    mainClass.set("xyz.mdhv.asom.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-XX:-CreateCoredumpOnCrash")
}

dependencies {
    api(project(":node-core"))
    // No third-party runtime dependency beyond what :node-core and :server already bring.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The second launcher, `asom` (the owner CLI), shares the runtime classpath of `asom-node`.
val cliStartScripts = tasks.register<CreateStartScripts>("cliStartScripts") {
    applicationName = "asom"
    mainClass.set("xyz.mdhv.asom.desktop.cli.AsomCliMainKt")
    outputDir = layout.buildDirectory.dir("cliScripts").get().asFile
    classpath = tasks.named<CreateStartScripts>("startScripts").get().classpath
    defaultJvmOpts = listOf("-Dfile.encoding=UTF-8", "-XX:-CreateCoredumpOnCrash")
}
distributions {
    main {
        contents {
            from(cliStartScripts) { into("bin") }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    systemProperty("asom.fixtures", layout.projectDirectory.dir("src/test/resources/fixtures/sysfs").asFile.absolutePath)
    systemProperty("asom.report", layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.absolutePath)
    val cp = sourceSets.test.get().runtimeClasspath
    doFirst { systemProperty("asom.testClasspath", cp.asPath) }
    // The launcher test runs the REAL installed scripts (asom-node, asom), so the distribution must exist first.
    dependsOn(tasks.named("installDist"))
    systemProperty("asom.installDir", layout.buildDirectory.dir("install/asom-node").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst { layout.buildDirectory.file("reports/desktop/report.txt").get().asFile.apply { parentFile.mkdirs(); delete() } }
    maxParallelForks = 1
}
