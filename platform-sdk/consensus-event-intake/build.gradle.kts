// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Consensus Event Intake API"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

jmhModuleInfo {
    requires("jmh.core")
    requires("com.hedera.node.hapi")
    requires("com.swirlds.common.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.crypto")
    requires("com.hedera.pbj.runtime")
    runtimeOnly("org.hiero.consensus.event.intake.impl")
    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
}
