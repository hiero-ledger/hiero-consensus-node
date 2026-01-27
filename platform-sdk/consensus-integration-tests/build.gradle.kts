// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
}

description = "Consensus Integration Tests"

tasks.test {
    failOnNoDiscoveredTests = false
}
