// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.common.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")
}
