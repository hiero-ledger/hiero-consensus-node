// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Base Crypto"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
}

timingSensitiveModuleInfo {
    runtimeOnly("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.apache.logging.log4j")
    requires("org.apache.logging.log4j.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
