// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Default Hedera Smart Contract Service Implementation"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.consensus.model")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
