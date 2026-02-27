// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

mainModuleInfo { runtimeOnly("org.junit.jupiter.engine") }

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
