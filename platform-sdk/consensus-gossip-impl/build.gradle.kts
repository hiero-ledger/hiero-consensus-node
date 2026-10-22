// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.benchmark-tracking")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Default Consensus Gossip Implementation"

jmhModuleInfo {
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.gossip.impl")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("jmh.core")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("GossipEventSerializationBenchmark", "ShadowgraphBenchmark"))
}

benchmarkTracking {
    // the recording of 32 nodes takes about 10 s per fork; one parameter set is enough for the
    // smoke run
    smokeArgs = listOf("-p", "numNodes=10")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.gossip.impl.test.fixtures")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("awaitility")
    requires("java.management")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.swirlds.base.test.fixtures") // injection via reflection
    opensTo("org.hiero.junit.extensions")
}
