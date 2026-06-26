// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Transaction Handling"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.merkledb.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}
