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

fun jmhParamProperty(name: String, defaultValue: String) =
    objects
        .listProperty<String>()
        .value(listOf(providers.gradleProperty(name).orElse(defaultValue).get()))

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

// Defaults are based on the large-state local calibration profile documented in
// docs/ReconnectBench.md.
tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("ReconnectBench"))
    benchmarkParameters.put("networkProfile", jmhParamProperty("networkProfile", "REALISTIC"))
    benchmarkParameters.put(
        "networkLatencyMicroseconds",
        jmhParamProperty("networkLatencyMicroseconds", "270"),
    )
    benchmarkParameters.put(
        "networkBandwidthMegabitsPerSecond",
        jmhParamProperty("networkBandwidthMegabitsPerSecond", "200"),
    )
    benchmarkParameters.put(
        "networkInflightBytesLimit",
        jmhParamProperty("networkInflightBytesLimit", "134217728"),
    )
    benchmarkParameters.put("randomSeed", jmhParamProperty("randomSeed", "9823452658"))
    benchmarkParameters.put(
        "teacherAddProbability",
        jmhParamProperty("teacherAddProbability", "0.09"),
    )
    benchmarkParameters.put(
        "teacherRemoveProbability",
        jmhParamProperty("teacherRemoveProbability", "0.0"),
    )
    benchmarkParameters.put(
        "teacherModifyProbability",
        jmhParamProperty("teacherModifyProbability", "0.40"),
    )
    benchmarkParameters.put("numFiles", jmhParamProperty("numFiles", "7500"))
    benchmarkParameters.put("numRecords", jmhParamProperty("numRecords", "10000"))
    benchmarkParameters.put("maxKey", jmhParamProperty("maxKey", "10000000"))
    benchmarkParameters.put("keySize", jmhParamProperty("keySize", "32"))
    benchmarkParameters.put("recordSize", jmhParamProperty("recordSize", "128"))
    benchmarkParameters.put("numThreads", jmhParamProperty("numThreads", "32"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
    jvmArgs.set(listOf("-Xms24g", "-Xmx24g", "-XX:+AlwaysPreTouch"))
}
