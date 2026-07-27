// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Utilities"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

tasks.test {
    jvmArgs(
        "--enable-native-access=com.hedera.common.nativesupport,com.hedera.cryptography.libsecp256k1"
    )
}
