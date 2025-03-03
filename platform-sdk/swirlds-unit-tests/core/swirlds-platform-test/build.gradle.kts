// SPDX-License-Identifier: Apache-2.0
plugins {
    id("java-library")
    id("jacoco")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.version")
    id("org.hiero.gradle.check.dependencies")
    id("org.hiero.gradle.check.javac-lint")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-java")
    id("org.hiero.gradle.check.spotless-kotlin")
    id("org.hiero.gradle.feature.git-properties-file")
    id("org.hiero.gradle.feature.java-compile")
    id("org.hiero.gradle.feature.java-doc")
    id("org.hiero.gradle.feature.java-execute")
    id("org.hiero.gradle.feature.test")
    id("org.hiero.gradle.report.test-logger")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-lossy-conversions")
}

testModuleInfo {
    requires("com.swirlds.merkle")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.state.impl")
    requires("awaitility")
    requires("org.junit.jupiter.params")
    requires("org.mockito.junit.jupiter")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.event.creator")
    requires("org.hiero.event.creator.impl")
    requires("org.mockito")
    requires("org.hiero.consensus.gossip")
    requiresStatic("com.github.spotbugs.annotations")
}
