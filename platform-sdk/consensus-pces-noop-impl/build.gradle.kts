// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Consensus no-op implementation of PCES-file handling"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }
