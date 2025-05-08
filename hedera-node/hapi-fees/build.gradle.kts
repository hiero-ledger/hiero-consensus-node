// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Fees"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("org.apache.commons.lang3") // allow using 'reflectionEquals'
}
