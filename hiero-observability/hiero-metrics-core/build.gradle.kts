// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Hiero Metrics Core"

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.hiero.metrics.test.fixtures")
    runtimeOnly("com.swirlds.config.impl")
}

jmhModuleInfo { requires("org.hiero.metrics.test.fixtures") }
