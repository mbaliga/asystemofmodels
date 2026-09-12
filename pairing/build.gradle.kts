plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "xyz.mdhv.asom.pairing"
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
    implementation(project(":core:contract"))
    implementation(libs.androidx.core.ktx)
    // api: PairingDatabase (extends RoomDatabase) is consumed by :app.
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
