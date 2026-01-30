// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Default Consensus Hashgraph Implementation"

tasks.test {
    // Allow the build to succeed when no tests are present yet
    failOnNoDiscoveredTests = false
}
