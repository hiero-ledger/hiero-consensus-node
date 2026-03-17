// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Base Crypto"

// TODO: Temporary solution. Can be removed once
// https://github.com/hiero-ledger/hiero-gradle-conventions/issues/436 has become available
mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    tasks.javadoc {
        source(tasks.compileJava.flatMap { it.options.generatedSourceOutputDirectory })
    }
}

testModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.apache.logging.log4j.core")
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
