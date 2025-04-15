// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Base Crypto"

testModuleInfo {
    requires("org.hiero.base.crypto")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
