// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Openmetrics HTTP Server for Hiero Metrics"

mainModuleInfo { annotationProcessor("com.google.auto.service.processor") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    runtimeOnly("com.swirlds.config.impl")
}
