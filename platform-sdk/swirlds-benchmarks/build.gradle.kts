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
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.config.api")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.fchashmap")
    requires("com.swirlds.merkledb")
    requires("com.swirlds.virtualmap")
    requires("com.swirlds.platform.core")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.utility")
    requires("org.hiero.consensus.model")
    requires("jmh.core")
    requires("org.apache.logging.log4j")
    requires("org.hiero.consensus.model")
    requiresStatic("com.github.spotbugs.annotations")
    runtimeOnly("com.swirlds.config.impl")
}

jmh {
    jvmArgs.set(listOf("-Xmx8g"))
    includes.set(listOf("transfer"))
    benchmarkParameters.put("numFiles", listProperty("10"))
    benchmarkParameters.put("keySize", listProperty("16"))
    benchmarkParameters.put("recordSize", listProperty("128"))
}

fun listProperty(value: String) = objects.listProperty<String>().value(listOf(value))

tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("Reconnect.*"))
    jvmArgs.set(
        listOf(
            "-Xmx16g",
            "-Xms16g",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseZGC",
            "-XX:MaxDirectMemorySize=48g",
        )
    )

    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))

    benchmarkParameters.put("numRecords", listProperty("1000"))
    benchmarkParameters.put("numFiles", listProperty("100"))
    benchmarkParameters.put("delayStorageMicroseconds", listProperty("100"))
    benchmarkParameters.put("delayNetworkMicroseconds", listProperty("50"))
    benchmarkParameters.put("teacherAddProbability", listProperty("0.01"))
    benchmarkParameters.put("teacherRemoveProbability", listProperty("0.01"))
    benchmarkParameters.put("teacherModifyProbability", listProperty("0.01"))
}
