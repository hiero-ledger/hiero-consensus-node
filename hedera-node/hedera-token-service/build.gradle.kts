// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Hedera Token Service API"

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
}
