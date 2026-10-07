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
    // Desktop-runnable server (brief P3 gate: `./gradlew :server:run`).
    mainClass.set("xyz.mdhv.asom.server.MainKt")
}

dependencies {
    api(project(":core:contract"))
    api(project(":core:catalogue"))
    api(project(":core:routing"))
    api(project(":core:inference-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    // Upstream provider drivers use OkHttp (brief §3 pins OkHttp for SSE).
    // api: OkHttpClient appears in the drivers' public constructors.
    api(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
