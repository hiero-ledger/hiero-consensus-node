// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Default Consensus Event Intake Implementation"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
}

jmhModuleInfo {
    requires("jmh.core")
    requires("com.hedera.node.hapi")
    requires("com.swirlds.common.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
}
