plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "xyz.mdhv.asom"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.mdhv.asom"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core:catalogue"))
    implementation(project(":core:routing"))
    implementation(project(":core:inference-api"))
    implementation(project(":server"))
    implementation(project(":vault"))
    implementation(project(":pairing"))
    implementation(project(":storage"))
    implementation(project(":ledger"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The committed fixture is the single source of truth (§6); mirror it into
// assets at build time instead of committing a copy that can drift.
val syncFixtureAsset = tasks.register<Copy>("syncFixtureAsset") {
    from(rootProject.file("fixtures/catalogue.v1.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncFixtureAsset)
}
