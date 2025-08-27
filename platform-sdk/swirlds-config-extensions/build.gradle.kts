// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
}

testModuleInfo {
    runtimeOnly("com.swirlds.config.impl")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.assertj.core")

    exportsTo("com.swirlds.config.impl") // for ConfigExportTest
}
