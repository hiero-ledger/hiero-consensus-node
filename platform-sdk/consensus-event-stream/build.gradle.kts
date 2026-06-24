// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Consensus event-stream file writing"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.event.stream.test.fixtures")
    requires("org.hiero.consensus.utility")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

timingSensitiveModuleInfo {
    requires("org.hiero.consensus.event.stream")
    requires("org.hiero.consensus.event.stream.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
