// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo {
    annotationProcessor("com.google.auto.service.processor")
    annotationProcessor("org.apache.logging.log4j.core")
}

testModuleInfo {
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    runtimeOnly("com.swirlds.config.impl")
}
