// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

description = "Default Consensus Event Creator Implementation"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.hiero.junit.extensions")
    opensTo("org.hiero.junit.extensions")
}
