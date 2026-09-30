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
    api(project(":core:contract"))
    api(project(":core:catalogue"))
    api(project(":core:routing"))
    api(project(":mesh-policy"))
    api(project(":json"))
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("asom.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
