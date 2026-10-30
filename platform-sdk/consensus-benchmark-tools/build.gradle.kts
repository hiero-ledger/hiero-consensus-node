// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Consensus Benchmark Tools"

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}

jmhModuleInfo {
    requires("org.hiero.consensus.benchmark.tools")
}
