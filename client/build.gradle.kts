plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :client ships inside other people's apps — keep dependencies minimal
// (brief §4 law). contract only, plus okhttp for SSE transport.

android {
    namespace = "xyz.mdhv.asom.client"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
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
    // api: OkHttpClient appears in AsomChat's public constructor (callers may
    // supply their own client); coroutines Flow appears in AsomStream.
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
