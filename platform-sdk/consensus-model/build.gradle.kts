// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.benchmark-tracking")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Consensus Model"

testModuleInfo {
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.utility")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

jmhModuleInfo {
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.model")
    requires("org.hiero.otter.fixtures")
    requires("jmh.core")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("SequenceMapBenchmark"))
}

benchmarkTracking {
    // the recording of 28 nodes needs more than the default heap of 2 GB
    smokeJvmArgs = "-Xms3g -Xmx3g"
}

timingSensitiveModuleInfo {
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.concurrent.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
