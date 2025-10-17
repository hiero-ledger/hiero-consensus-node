// SPDX-License-Identifier: Apache-2.0
plugins {
    id("java-library")
    id("jacoco")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.version")
    id("org.hiero.gradle.check.dependencies")
    id("org.hiero.gradle.check.javac-lint")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-java")
    id("org.hiero.gradle.check.spotless-kotlin")
    id("org.hiero.gradle.feature.git-properties-file")
    id("org.hiero.gradle.feature.java-compile")
    id("org.hiero.gradle.feature.java-execute")
    id("org.hiero.gradle.feature.test")
    id("org.hiero.gradle.report.test-logger")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
    id("org.hiero.gradle.feature.protobuf")
}

description = "Consensus Otter Test Framework"

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.otter.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("com.github.spotbugs.annotations")
    requires("com.swirlds.component.framework")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.utility")
    requires("awaitility")
    runtimeOnly("io.grpc.netty.shaded")
}

testIntegrationModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.logging")
    requires("org.apache.logging.log4j")
    requires("org.hiero.otter.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("com.github.spotbugs.annotations")
    runtimeOnly("io.grpc.netty.shaded")
}

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}

// Runs tests against the Turtle environment
tasks.register<Test>("testTurtle") {
    useJUnitPlatform()
    testClassesDirs = sourceSets.testIntegration.get().output.classesDirs
    classpath = sourceSets.testIntegration.get().runtimeClasspath

    // Disable all parallelism
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    // Tell our launcher to target a Turtle network
    systemProperty("otter.env", "turtle")

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

// Runs tests against the Container environment
tasks.register<Test>("testContainer") {
    dependsOn(":consensus-otter-docker-app:copyDockerizedApp")

    useJUnitPlatform()
    testClassesDirs = sourceSets.testIntegration.get().output.classesDirs
    classpath = sourceSets.testIntegration.get().runtimeClasspath

    // Disable all parallelism
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Tell our launcher to target a testcontainer-based network
    systemProperty("otter.env", "container")

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

// Configure the default testIntegration task with proper memory settings
tasks.testIntegration {
    useJUnitPlatform()
    testClassesDirs = sourceSets.testIntegration.get().output.classesDirs
    classpath = sourceSets.testIntegration.get().runtimeClasspath

    // Disable all parallelism
    systemProperty("junit.jupiter.execution.parallel.enabled", false)
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}
