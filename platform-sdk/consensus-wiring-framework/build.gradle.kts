// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.benchmark-tracking")
}

description = "Wiring Framework"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add(
        "-Xlint:-exports,-lossy-conversions,-overloads,-dep-ann,-text-blocks,-varargs",
    )
}

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.base.concurrent.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

jmhModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.consensus.benchmark.tools")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.model")
    requires("org.hiero.consensus.wiring.framework")
    requires("jmh.core")
}

jmh {
    // the benchmarks that are tracked; -PjmhTests=<pattern> runs others instead
    includes.convention(listOf("WiringHopBenchmark"))
}

benchmarkTracking {
    // a pass at 3 items per second takes over a minute; the faster rate is enough for the smoke run
    smokeArgs = listOf("-p", "rate=460")
}
