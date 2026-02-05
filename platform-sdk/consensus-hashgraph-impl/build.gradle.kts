// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Default Consensus Hashgraph Implementation"

// Suppress exports warnings in testFixtures - classes from targeted exports are used in public
// APIs,
// but consumers of testFixtures are expected to also require hashgraph-impl directly.
tasks.named<JavaCompile>("compileTestFixturesJava") { options.compilerArgs.add("-Xlint:-exports") }

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.pces.impl.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requiresStatic("com.github.spotbugs.annotations")
}
