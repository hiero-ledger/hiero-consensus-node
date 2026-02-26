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
    runtimeOnly("com.swirlds.config.impl")
    runtimeOnly("org.hiero.consensus.event.creator.impl")
    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    runtimeOnly("org.hiero.consensus.hashgraph.impl")
    runtimeOnly("org.hiero.consensus.pces.impl")
    runtimeOnly("org.hiero.consensus.gossip.impl")
    runtimeOnly("org.hiero.consensus.reconnect.impl")
}

jmhModuleInfo {
    requires("com.swirlds.common")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.hedera.node.hapi")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.pces")
    requires("org.hiero.consensus.pces.impl")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("jmh.core")
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.merkledb.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.model.test.fixtures")
    requires("org.hiero.consensus.reconnect")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.swirlds.base.test.fixtures") // injection via reflection
    opensTo("org.hiero.junit.extensions")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.state.impl")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.metrics")
    requires("org.junit.jupiter.api")
}
