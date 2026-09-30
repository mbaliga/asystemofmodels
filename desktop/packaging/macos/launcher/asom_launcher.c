/* NOT-YET-IMPLEMENTED: PLATFORM_PLAN step MC4 (D-v2; entry criteria D21, D22, D23, D25, D27 and D28 not met: design 9.3, R3-CONFORMANCE-10, R3-CLOSURE-8).
 * This stub does nothing and cannot be built: launcher/CMakeLists.txt refuses to configure. A stock jpackage launcher is NOT a
 * substitute: it passes the caller's environment into the JVM (macos.md 7.3, AM26), so the node stays rated "same-user compromise =
 * node compromise" (C13) until the hardened launcher of macos.md 7.3 exists and step MC4's injection probe passes.
 * The mechanism to build (macos.md 7.3): unsetenv JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS, CLASSPATH and JAVA_HOME; accept only
 * --mode=agent|daemon|selftest (anything else exits 64 before the VM exists); resolve its own path and require libjvm.dylib and the
 * jars at fixed relative paths inside the bundle; chdir to the state root; dlopen libjvm.dylib and call JNI_CreateJavaVM on a secondary
 * thread with a FIXED option list including -XX:+DisableAttachMechanism.
 */
#error "NOT-YET-IMPLEMENTED: asom_launcher.c belongs to step MC4 of the macOS track"
