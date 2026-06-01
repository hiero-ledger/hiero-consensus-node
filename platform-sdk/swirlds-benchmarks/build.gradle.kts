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

testModuleInfo { requires("org.junit.jupiter.api") }

fun listProperty(value: String) = objects.listProperty<String>().value(listOf(value))

fun jmhParamProperty(name: String, defaultValue: String) =
    objects
        .listProperty<String>()
        .value(listOf(providers.gradleProperty(name).orElse(defaultValue).get()))

tasks.named<Jar>("jmhJarWithMergedServiceFiles") {
    from(sourceSets.main.get().output) { include("com/swirlds/benchmark/reconnect/network/**") }
}

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
    jvmArgs.set(
        listOf(
            "-Xms24g",
            "-Xmx24g",
            "-XX:+AlwaysPreTouch",
            "-Xlog:gc*:file=/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/platform-sdk/swirlds-benchmarks/data/reconnectbench-gc.log:time,uptime,level,tags",
        )
    )
    benchmarkParameters.put("networkProfile", jmhParamProperty("networkProfile", "REALISTIC"))
    benchmarkParameters.put(
        "networkLatencyMicroseconds",
        jmhParamProperty("networkLatencyMicroseconds", "100000"),
    )
    benchmarkParameters.put(
        "networkBandwidthMegabitsPerSecond",
        jmhParamProperty("networkBandwidthMegabitsPerSecond", "1000"),
    )
    benchmarkParameters.put(
        "networkInflightBytesLimit",
        jmhParamProperty("networkInflightBytesLimit", "134217728"),
    )
    benchmarkParameters.put("numFiles", jmhParamProperty("numFiles", "5000"))
    benchmarkParameters.put("numRecords", jmhParamProperty("numRecords", "10000"))
    benchmarkParameters.put("keySize", jmhParamProperty("keySize", "32"))
    benchmarkParameters.put("recordSize", jmhParamProperty("recordSize", "128"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}
