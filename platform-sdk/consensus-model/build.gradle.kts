// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Consensus Model"

testModuleInfo {
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.utility")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
}

timingSensitiveModuleInfo {
    requires("org.hiero.base.concurrent")
    requires("org.hiero.consensus.concurrent")
    requires("org.hiero.consensus.concurrent.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
