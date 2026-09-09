// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.gradlex.java-module-packaging")
}

description = "Consensus-layer knowledge-base freshness checker"

application.mainClass = "org.hiero.consensus.kbfreshness.cli.Main"

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
}
