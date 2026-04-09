// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Consensus Metrics"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.metrics.impl")
    requires("org.hiero.consensus.metrics")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
