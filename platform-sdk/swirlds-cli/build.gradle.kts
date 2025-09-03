// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.mockito")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-overloads,-text-blocks,-dep-ann,-varargs")
}
