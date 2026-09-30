// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Consensus Benchmark Tools"

extraJavaModuleInfo.module("org.openjdk.jmh:jmh-core", "jmh.core") {
    exportAllPackages()
    requireAllDefinedDependencies()
    requires("java.logging")
    requires("java.management")
    requires("jdk.unsupported")
}

extraJavaModuleInfo.module("net.sf.jopt-simple:jopt-simple", "jopt.simple")

extraJavaModuleInfo.module("org.apache.commons:commons-math3", "commons.math3")

dependencies.constraints { compileOnly("org.openjdk.jmh:jmh-core:${jmh.jmhVersion.get()}") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    runtimeOnly("jmh.core")
}

jmhModuleInfo {
    requires("org.hiero.consensus.benchmark.tools")
}
