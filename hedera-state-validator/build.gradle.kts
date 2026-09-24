// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

mainModuleInfo { runtimeOnly("org.junit.jupiter.engine") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
}

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
