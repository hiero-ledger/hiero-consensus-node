// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
}

testModuleInfo {
    requires("org.assertj.core")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.merkledb.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    runtimeOnly("com.swirlds.config.api")
    runtimeOnly("com.swirlds.config.impl")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
