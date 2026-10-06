// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.gradlex.java-module-packaging")
}

mainModuleInfo { runtimeOnly("org.junit.jupiter.engine") }

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
