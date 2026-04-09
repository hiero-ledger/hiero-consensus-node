// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus Network Simulation"

testModuleInfo {
    requires("com.hedera.node.hapi")
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.config.api")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.hashgraph")
    requires("org.hiero.consensus.hashgraph.impl")
    requires("org.hiero.consensus.model")
    requires("org.hiero.consensus.network.simulation.test.fixtures")
    requires("org.junit.jupiter.api")
}
