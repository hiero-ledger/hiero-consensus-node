// SPDX-License-Identifier: Apache-2.0
import org.gradlex.javamodule.dependencies.dsl.GradleOnlyDirectives

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
    id("org.hiero.gradle.feature.protobuf")
}

description = "Consensus Otter Test Framework"

@Suppress("UnstableApiUsage")
testing {
    suites.named<JvmTestSuite>("test") {
        javaModuleTesting.whitebox(this) { sourcesUnderTest = sourceSets.testFixtures }
    }

    suites.named<JvmTestSuite>("testIntegration") {
        targets.configureEach { testTask { dependsOn(":consensus-otter-docker-app:assemble") } }
    }

    suites.register<JvmTestSuite>("testOtter") {
        // Runs tests against the Container environment
        targets.register("testContainer") { testTask { systemProperty("otter.env", "container") } }

        // Runs tests against the Turtle environment
        targets.register("testTurtle") { testTask { systemProperty("otter.env", "turtle") } }

        targets.configureEach { testTask { dependsOn(":consensus-otter-docker-app:assemble") } }
    }

    suites.register<JvmTestSuite>("testChaos") {
        targets.configureEach { testTask { dependsOn(":consensus-otter-docker-app:assemble") } }
    }

    suites.register<JvmTestSuite>("testPerformance") {
        // Runs performance benchmarks against the container environment
        targets.configureEach {
            testTask {
                systemProperty("otter.env", "container")
                dependsOn(":consensus-otter-docker-app:assemble")
            }
        }
    }
}

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.component.framework")
    requires("com.swirlds.metrics.api")
    requires("org.apache.logging.log4j")
    requires("org.assertj.core")
    requires("org.hiero.consensus.utility")
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.roster")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requiresStatic("com.github.spotbugs.annotations")
}

testIntegrationModuleInfo { //
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testOtterModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testChaosModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testPerformanceModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

// Fix testcontainers module system access to commons libraries
// testcontainers 2.0.2 is a named module but doesn't declare its module-info dependencies
// We need to grant it access to the commons modules via JVM arguments
// Note: automatic modules are named from their package names (org.apache.commons.io for commons-io
// JAR)
// This is applied to all Test tasks to work across all execution methods (local, CI, etc.)
tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
    jvmArgs(
        "--add-reads=org.testcontainers=org.apache.commons.lang3",
        "--add-reads=org.testcontainers=org.apache.commons.compress",
        "--add-reads=org.testcontainers=org.apache.commons.io",
        "--add-reads=org.testcontainers=org.apache.commons.codec",
    )
}

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}
