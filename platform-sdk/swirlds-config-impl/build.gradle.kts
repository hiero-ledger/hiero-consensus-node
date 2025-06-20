// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

mainModuleInfo { annotationProcessor("com.google.auto.service.processor") }

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("com.swirlds.common")
    runtimeOnly("com.swirlds.platform.core")

    exportsTo("com.swirlds.config.extensions")
}

jmhModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("jmh.core")
}
