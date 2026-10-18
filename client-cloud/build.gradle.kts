plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :client-cloud ships inside other people's apps (brief §4/§10A law):
// contract + OkHttp + coroutines only. Its vault is deliberately tiny
// (Keystore + SharedPreferences ciphertext) — no Room, no extra deps.

android {
    namespace = "xyz.mdhv.asom.clientcloud"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:contract"))
    // api: OkHttpClient appears in CloudOnly's public constructor.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
