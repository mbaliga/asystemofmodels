plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.mdhv.asom.ledger"
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
    implementation(project(":core:contract"))
    implementation(libs.androidx.core.ktx)
    // api: LedgerDatabase (extends RoomDatabase) and Flow-returning DAOs are
    // part of this module's public surface consumed by :app.
    api(libs.room.runtime)
    api(libs.room.ktx)
    api(libs.androidx.work.runtime.ktx) // VerbosePurgeWorker.schedule/cancel in the public surface
    ksp(libs.room.compiler)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
