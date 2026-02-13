// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus PCES-file handling"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.concurrent")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.junit.extensions")
    requires("org.junit.jupiter.api")
    requires("org.mockito")

    opensTo("org.hiero.junit.extensions")
}
