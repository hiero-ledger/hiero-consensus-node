// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Consensus Roster"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("org.junit.jupiter.api")
    //    requires("org.junit.jupiter.params")
    //    requires("org.mockito")
    //    requires("org.mockito.junit.jupiter")
}
