// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus Otter Test Framework"

testModuleInfo {
    requires("com.swirlds.logging")
    requires("org.hiero.otter.fixtures")
}
