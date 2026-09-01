// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Utilities"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

tasks.test {
    jvmArgs(
        "--enable-native-access=com.hedera.common.nativesupport,com.hedera.cryptography.libsecp256k1"
    )
}

// Runs ClprVolleyVisualizer.main on demand. Reuses the test task's JPMS / classpath
// configuration but disables the up-to-date cache so it always re-executes, and forwards
// the three relevant system properties from the gradle invocation. Use:
//   ./gradlew :app-hapi-utils:visualizeVolley
//   ./gradlew :app-hapi-utils:visualizeVolley -Dalice.records=/path -Dbob.records=/path
// -Dout.dir=/tmp/clpr-viz
tasks.register<Test>("visualizeVolley") {
    group = "documentation"
    description = "Render the CLPR PingPong volley timeline + Mermaid diagram (always re-runs)."
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    dependsOn(tasks.named("testClasses"))
    useJUnitPlatform()
    filter {
        includeTestsMatching("*ClprVolleyVisualizer*")
        isFailOnNoMatchingTests = true
    }
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    listOf("alice.records", "bob.records", "out.dir", "follow.seconds", "follow.interval")
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}
