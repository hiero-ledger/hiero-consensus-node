// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

description = "Component Framework"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(
        "-Xlint:-exports,-lossy-conversions,-overloads,-dep-ann,-text-blocks,-varargs"
    )
}

testModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("org.hiero.base.concurrent.test.fixtures")
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}
