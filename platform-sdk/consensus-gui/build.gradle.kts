// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Consensus Hashgraph GUI"

testModuleInfo {
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.pcli")
}

// TODO add real tests to 'src/test/java' or remove 'src/test/java'
tasks.test { failOnNoDiscoveredTests = false }
