// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(
        "-Xlint:-exports,-lossy-conversions,-overloads,-dep-ann,-text-blocks,-varargs"
    )
}

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.junit.jupiter.api")

    exportsTo("org.hiero.base.utility")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.concurrent.test.fixtures")
    requires("org.hiero.consensus.concurrent")
    requires("org.hiero.consensus.model")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
