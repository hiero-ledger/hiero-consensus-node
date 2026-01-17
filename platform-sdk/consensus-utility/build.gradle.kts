// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Consensus Utility"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("org.hiero.base.utility.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl")
    requires("com.swirlds.virtualmap")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
