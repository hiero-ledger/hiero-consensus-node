// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

testModuleInfo {
    requires("org.hiero.consensus.concurrent")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    runtimeOnly("com.swirlds.platform.core")

    exportsTo("com.swirlds.config.extensions")
}

jmhModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("jmh.core")
}
