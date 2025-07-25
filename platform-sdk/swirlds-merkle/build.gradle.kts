// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

testModuleInfo {
    requires("com.swirlds.config.api")
    requires("com.swirlds.base")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.merkle.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}

timingSensitiveModuleInfo {
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.fchashmap")
    requires("com.swirlds.merkle.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}
