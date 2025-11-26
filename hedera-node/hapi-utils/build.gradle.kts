// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Utilities"

dependencies { implementation("com.github.luben:zstd-jni") }

mainModuleInfo {
    annotationProcessor("dagger.compiler")
    runtimeOnly("com.github.luben:zstd-jni")
}

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.assertj.core")
}
