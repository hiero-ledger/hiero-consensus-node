// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

jmhModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.component.framework")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.event.creator")
    requires("org.hiero.consensus.event.creator.impl")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("com.hedera.node.hapi")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("jmh.core")
}

description = "Default Consensus Event Creator Implementation"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.hiero.junit.extensions")

    opensTo("org.hiero.junit.extensions")
}
