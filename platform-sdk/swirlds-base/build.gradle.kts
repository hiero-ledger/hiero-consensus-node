// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-varargs,-static")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.assertj.core")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    requires("awaitility")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
}
