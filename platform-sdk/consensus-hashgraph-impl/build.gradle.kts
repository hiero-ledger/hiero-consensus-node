// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
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
    requires("org.hiero.base.concurrent")
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("jmh.core")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("ConsensusImplBenchmark"))
    resultFormat = "JSON"
    // ConsensusImplBenchmark reports its latency through this profiler and fails without it
    profilers.add("org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler")
    // without it, JMH exits successfully when a benchmark throws; no effect until the JMH plugin
    // passes "-foe true", not "-foe 1" (https://github.com/melix/jmh-gradle-plugin/issues/255)
    failOnError = true
}
