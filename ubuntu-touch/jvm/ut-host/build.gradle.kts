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
    api(project(":node-core"))
    api(project(":json"))
    api(project(":manifest"))
    api(project(":ledger-model"))
    // Only for its family checkers M01-M03 (pure, no listener); the self-test never calls a server-driven family.
    api(project(":conformance-runner"))
    testImplementation(libs.kotlin.test)
}

val repoRoot: File = rootProject.projectDir.parentFile.parentFile

// The self-test re-runs the lab's M01-M03 vector files inside the shipped jar; only those files are bundled, with a list
// file so the jar can be enumerated without scanning it.
val vectorRoot: File = repoRoot.resolve("lab/conformance")
val vectorGlobs = listOf("json/M01*.json", "manifest/M01*.json", "manifest/M02*.json", "manifest/M03*.json")
val generatedResources = layout.buildDirectory.dir("generated/utResources")

val utVectorList = tasks.register("utVectorList") {
    inputs.files(fileTree(vectorRoot) { include(vectorGlobs) })
    outputs.dir(generatedResources)
    doLast {
        val names = fileTree(vectorRoot) { include(vectorGlobs) }.files.map { it.relativeTo(vectorRoot).invariantSeparatorsPath }.sorted()
        check(names.size == 5) { "expected 5 vector files (M01 x2, M02, M03 x2), found $names" }
        val out = generatedResources.get().file("asom-ut/vectors/FILES.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(names.joinToString("\n") + "\n")
    }
}

sourceSets.main { resources.srcDir(generatedResources) }

tasks.processResources {
    dependsOn(utVectorList)
    from(vectorRoot) {
        include(vectorGlobs)
        include("VERSION")
        into("asom-ut/vectors")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", repoRoot.absolutePath)
    systemProperty("asom.utRoot", repoRoot.resolve("ubuntu-touch").absolutePath)
    val cp = sourceSets.test.get().runtimeClasspath
    doFirst { systemProperty("asom.testClasspath", cp.asPath) }
    testLogging {
        showStandardStreams = true
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The tests fork JVMs and read vector files outside the source set: a cached or up-to-date result proves nothing.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    maxParallelForks = 1
}

val utNodeJar = tasks.register<Jar>("utNodeJar") {
    group = "build"
    description = "The node jar that ships in the click. UNSIGNED, not for release."
    archiveFileName.set("asom-ut-node.jar")
    outputs.upToDateWhen { false }
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "xyz.mdhv.asom.ut.MainKt")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
    // :node-core and :conformance-runner bring the whole v1 server with them (ktor, okhttp, kotlin-reflect, coroutines, logging). The
    // host never loads any of it, and it is most of the jar, so it stays out. `--selftest`, `--fake-ui` and a `--profile=ut`
    // session are run from THIS jar by tools/check_jar.sh: a class it needed and could not find would fail there, not on a phone.
    exclude(
        "io/ktor/**", "kotlin/reflect/full/**", "kotlin/reflect/jvm/**", "kotlinx/coroutines/**", "kotlinx/io/**", "okhttp3/**", "okio/**", "org/fusesource/**",
        "com/typesafe/**", "org/slf4j/**", "org/jline/**", "xyz/mdhv/asom/server/**", "META-INF/native/**", "META-INF/proguard/**",
    )
}
