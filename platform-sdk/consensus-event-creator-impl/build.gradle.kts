// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

description = "Default Consensus Event Creator Implementation"
