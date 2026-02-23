// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus Roster"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.virtualmap")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.hiero.consensus.platformstate")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
