// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Consensus Event Intake API"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

// TEMPORARY (experiment of #27832): IntakeReplayBenchmark adds benchmark.tools, metrics, and
// otter.fixtures
jmhModuleInfo {
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.crypto")
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.otter.fixtures")
    requires("jmh.core")

    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    runtimeOnly("org.hiero.consensus.event.intake.impl")
}
