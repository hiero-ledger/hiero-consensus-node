// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-time-consuming")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-deprecation,-exports,-removal,-varargs")
}

mainModuleInfo { annotationProcessor("com.google.auto.service.processor") }

testModuleInfo {
    requires("org.apache.logging.log4j.core")
    requires("org.apache.commons.lang3")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.logging.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("jakarta.inject")

    opensTo("com.swirlds.base.test.fixtures") // injection via reflection
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.logging.test.fixtures")
    requires("jakarta.inject")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    runtimeOnly("com.swirlds.common.test.fixtures")

    opensTo("com.swirlds.base.test.fixtures") // injection via reflection
}

jmhModuleInfo {
    requires("com.swirlds.logging")
    requires("org.apache.logging.log4j")
    requires("com.swirlds.config.api")
    runtimeOnly("com.swirlds.config.impl")
    requires("com.swirlds.config.extensions")
    runtimeOnly("com.swirlds.logging.log4j.appender")
    requires("org.apache.logging.log4j.core")
    requires("com.github.spotbugs.annotations")
    requires("jmh.core")
}
