// SPDX-License-Identifier: Apache-2.0
import me.champeau.jmh.JMHTask

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.benchmark")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-static") }

jmhModuleInfo {
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.config.api")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.merkledb")
    requires("com.swirlds.virtualmap")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.utility")
    requires("org.hiero.consensus.concurrent")
    requires("org.hiero.consensus.gossip")
    requires("org.hiero.consensus.gossip.impl")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.model")
    requires("org.hiero.consensus.reconnect")
    requires("jmh.core")
    requires("org.apache.logging.log4j")
    requiresStatic("com.github.spotbugs.annotations")
    runtimeOnly("com.swirlds.config.impl")
}

fun listProperty(value: String) = objects.listProperty<String>().value(listOf(value))

// ── Benchmark run configurations ─────────────────────────────────────

tasks.register<JMHTask>("jmhCrypto") {
    includes.set(listOf("CryptoBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-crypto.txt"))
}

tasks.register<JMHTask>("jmhVirtualMap") {
    includes.set(listOf("VirtualMapBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-virtualmap.txt"))
}

tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("ReconnectBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}

tasks.register<JMHTask>("jmhDataFileCollection") {
    includes.set(listOf("DataFileCollectionBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-datafilecollection.txt"))
}

tasks.register<JMHTask>("jmhHalfDiskMap") {
    includes.set(listOf("HalfDiskMapBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-halfdiskmap.txt"))
}

tasks.register<JMHTask>("jmhKeyValueStore") {
    includes.set(listOf("KeyValueStoreBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-keyvaluestore.txt"))
}
