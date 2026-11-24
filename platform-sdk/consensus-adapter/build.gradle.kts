// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "The adapter layer between the Execution and Consensus layers"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    opensTo("org.hiero.junit.extensions")
}
