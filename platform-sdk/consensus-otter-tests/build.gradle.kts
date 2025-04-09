// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus Otter Test Framework"

testModuleInfo {
    requires("com.swirlds.logging")
    requires("org.hiero.consensus.utility")
    requires("org.hiero.otter.fixtures")
}
