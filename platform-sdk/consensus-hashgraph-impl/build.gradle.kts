// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.benchmark-tracking")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Default Consensus Hashgraph Implementation"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.gui")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.pces.impl.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requiresStatic("com.github.spotbugs.annotations")
}

jmhModuleInfo {
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.otter.fixtures")
    requires("jmh.core")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("ConsensusEngineBenchmark", "ConsensusImplBenchmark"))
}

benchmarkTracking {
    // the recording of 32 nodes takes more than the smoke run's heap and over a minute per fork;
    // one parameter set is enough for the smoke run
    smokeArgs = listOf("-p", "numNodes=10")
}
