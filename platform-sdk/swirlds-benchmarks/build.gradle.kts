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
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.merkledb")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.virtualmap")
    requires("com.swirlds.virtualmap.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.utility")
    requires("org.hiero.consensus.concurrent")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.model")
    requires("org.hiero.consensus.utility")
    requires("awaitility")
    requires("jmh.core")
    requires("org.apache.logging.log4j")
    requiresStatic("com.github.spotbugs.annotations")

    runtimeOnly("com.swirlds.config.impl")
}

fun listProperty(value: String) = objects.listProperty<String>().value(listOf(value))

// ── Benchmark run configurations ─────────────────────────────────────
// Gradle JMH tasks are intended for regular benchmark runs.
// Keep normal JMH forking for cleaner measurements; pass heap settings to the forked benchmark JVM.

tasks.register<JMHTask>("jmhCrypto") {
    includes.set(listOf("CryptoBench.transferPrefetch"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-crypto.txt"))
}

tasks.register<JMHTask>("jmhVirtualMapRead") {
    includes.set(listOf("VirtualMapReadBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-virtualmap-read.txt"))
}

tasks.register<JMHTask>("jmhVirtualMapEdit") {
    includes.set(listOf("VirtualMapEditBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-virtualmap-edit.txt"))
}

tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("ReconnectBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}
