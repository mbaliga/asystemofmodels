// Root build file. Plugin versions live in gradle/libs.versions.toml;
// each module applies what it needs via the version catalog.

// Aggregate task for the pure-JVM side of the build. This is the set of
// modules that must pass on a bare JDK with no Android SDK present.
tasks.register("jvmTest") {
    group = "verification"
    description = "Runs tests for all pure-JVM modules (:core:*, :server)."
    dependsOn(
        ":core:contract:test",
        ":core:catalogue:test",
        ":core:routing:test",
        ":core:inference-api:test",
        ":server:test",
    )
}
