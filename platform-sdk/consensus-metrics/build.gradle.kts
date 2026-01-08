// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Consensus Metrics"

mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    annotationProcessor("com.google.auto.service.processor")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.metrics.impl")
    requires("simpleclient")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
