// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Base Crypto"

// Panama FFM in LibSodiumEd25519 requires native access for SymbolLookup.libraryLookup
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=org.hiero.base.crypto") }

mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    // lazysodium-java provides bundled libsodium native binaries as classpath resources.
    // Not required at compile time — only needed at runtime for environments without system
    // libsodium.
    runtimeOnly("com.goterl.lazysodium")
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
