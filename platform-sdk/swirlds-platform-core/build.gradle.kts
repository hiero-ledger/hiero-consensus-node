// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-overloads,-text-blocks,-dep-ann,-varargs")
}

mainModuleInfo {
    annotationProcessor("com.swirlds.config.processor")
    annotationProcessor("com.google.auto.service.processor")
    runtimeOnly("com.swirlds.config.impl")
}

jmhModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.hedera.node.hapi")
    requires("jmh.core")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.metrics.impl")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("awaitility")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}
