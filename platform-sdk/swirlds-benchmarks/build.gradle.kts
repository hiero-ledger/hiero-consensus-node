// SPDX-License-Identifier: Apache-2.0
import me.champeau.jmh.JMHTask

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.benchmark")
}

val gossipConnectivityExport =
    "--add-exports=org.hiero.consensus.gossip.impl/org.hiero.consensus.gossip.impl.network.connectivity=com.swirlds.benchmarks,ALL-UNNAMED"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.add("-Xlint:-static")
    options.compilerArgs.add(gossipConnectivityExport)
}

tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add("-Xlint:-static")
    options.compilerArgs.add(gossipConnectivityExport)
}

tasks.withType<Test>().configureEach {
    jvmArgs(gossipConnectivityExport)
}

tasks.withType<JMHTask>().configureEach {
    jvmArgs.add(gossipConnectivityExport)
}

jmhModuleInfo {
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.base")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.metrics.api")
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
    requires("org.hiero.consensus.utility")
    requires("jmh.core")
    requires("org.apache.logging.log4j")
    requiresStatic("com.github.spotbugs.annotations")
    runtimeOnly("com.swirlds.config.impl")
    requires("awaitility")
}

testModuleInfo {
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.gossip")
    requires("org.hiero.consensus.gossip.impl")
    requires("org.junit.jupiter.api")
    runtimeOnly("com.swirlds.config.impl")
}

fun listProperty(value: String) = objects.listProperty<String>().value(listOf(value))

fun jmhParamProperty(name: String, defaultValue: String) =
    objects
        .listProperty<String>()
        .value(listOf(providers.gradleProperty(name).orElse(defaultValue).get()))

fun JMHTask.configureReconnectJvmArgs() {
    jvmArgs.set(
        listOf(
            gossipConnectivityExport,
            "-Xms24g",
            "-Xmx24g",
            "-XX:+AlwaysPreTouch",
            "-Xlog:gc*:file=/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/platform-sdk/swirlds-benchmarks/data/reconnectbench-gc.log:time,uptime,level,tags",
        )
    )
}

fun JMHTask.configureReconnectParameters(
    defaultTransport: String,
    defaultProfile: String,
) {
    includes.set(listOf("ReconnectBench"))
    benchmarkParameters.put("networkTransport", jmhParamProperty("networkTransport", defaultTransport))
    benchmarkParameters.put("networkProfile", jmhParamProperty("networkProfile", defaultProfile))
    benchmarkParameters.put(
        "networkLatencyMicroseconds",
        jmhParamProperty("networkLatencyMicroseconds", "75000"),
    )
    benchmarkParameters.put(
        "networkBandwidthMegabitsPerSecond",
        jmhParamProperty("networkBandwidthMegabitsPerSecond", "200"),
    )
    benchmarkParameters.put(
        "networkInflightBytesLimit",
        jmhParamProperty("networkInflightBytesLimit", "16777216"),
    )
    benchmarkParameters.put("randomSeed", jmhParamProperty("randomSeed", "9823452658"))
    benchmarkParameters.put("teacherAddProbability", jmhParamProperty("teacherAddProbability", "0.1"))
    benchmarkParameters.put("teacherRemoveProbability", jmhParamProperty("teacherRemoveProbability", "0.0"))
    benchmarkParameters.put("teacherModifyProbability", jmhParamProperty("teacherModifyProbability", "0.3"))
    benchmarkParameters.put("numFiles", jmhParamProperty("numFiles", "1000"))
    benchmarkParameters.put("numRecords", jmhParamProperty("numRecords", "10000"))
    benchmarkParameters.put("maxKey", jmhParamProperty("maxKey", "10000000"))
    benchmarkParameters.put("keySize", jmhParamProperty("keySize", "32"))
    benchmarkParameters.put("recordSize", jmhParamProperty("recordSize", "128"))
    benchmarkParameters.put("numThreads", jmhParamProperty("numThreads", "32"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}

tasks.named<Jar>("jmhJarWithMergedServiceFiles") {
    from(sourceSets.main.get().output) { include("com/swirlds/benchmark/reconnect/**") }
}

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
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "SIMULATED", defaultProfile = "REALISTIC")
}

tasks.register<JMHTask>("jmhReconnectLoopbackSocket") {
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "LOOPBACK_SOCKET", defaultProfile = "LOOPBACK")
}

tasks.register<JMHTask>("jmhReconnectSimulated") {
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "SIMULATED", defaultProfile = "LOOPBACK")
}
