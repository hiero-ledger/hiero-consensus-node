// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Prometheus File Exporter for Hiero Metrics"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    runtimeOnly("com.swirlds.config.impl")
}
