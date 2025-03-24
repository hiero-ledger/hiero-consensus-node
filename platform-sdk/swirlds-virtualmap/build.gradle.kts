// SPDX-License-Identifier: Apache-2.0
import me.champeau.jmh.JMHTask

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-hammer")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-lossy-conversions,-synchronization")
}

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

jmhModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.virtualmap.test.fixtures")
    requires("org.hiero.consensus.model")
    requires("jmh.core")
    requires("org.junit.jupiter.api")
}

testModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.virtualmap.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.virtualmap.test.fixtures")
    requires("org.hiero.consensus.model")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

hammerModuleInfo {
    requires("com.swirlds.virtualmap")
    requires("com.swirlds.virtualmap.test.fixtures")
    requires("com.swirlds.config.api")
    requires("org.junit.jupiter.api")
    runtimeOnly("com.swirlds.config.impl")
}

tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("Reconnect.*"))
    jvmArgs.set(listOf("-Xmx16g"))
    fork.set(1)
    warmupIterations.set(2)
    iterations.set(5)

    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}
