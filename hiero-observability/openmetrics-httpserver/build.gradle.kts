// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
}

description = "Openmetrics HTTP Server for Hiero Metrics"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    requires("java.net.http")
    runtimeOnly("com.swirlds.config.impl")
}

jmhModuleInfo {
    requires("org.hiero.metrics")
    requires("com.swirlds.config.api")
    requires("io.prometheus.metrics.model")
    requires("io.prometheus.metrics.core")
    requires("io.prometheus.metrics.exporter.httpserver")
    requires("java.net.http")
    runtimeOnly("org.hiero.metrics.openmetrics.httpserver")
    runtimeOnly("com.swirlds.config.impl")
}

// versions and module name mappings for prometheus-metrics modules only required for JMH
javaModuleDependencies {
    moduleNameToGA.put("io.prometheus.metrics.core", "io.prometheus:prometheus-metrics-core")
    moduleNameToGA.put(
        "io.prometheus.metrics.exporter.httpserver",
        "io.prometheus:prometheus-metrics-exporter-httpserver",
    )
    moduleNameToGA.put("io.prometheus.metrics.model", "io.prometheus:prometheus-metrics-model")
}

dependencies { api(platform("io.prometheus:prometheus-metrics-bom:1.4.3")) }
