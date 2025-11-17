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
}

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.component.framework")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.apache.logging.log4j")
    requires("org.assertj.core")
    requires("org.hiero.consensus.utility")
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

tasks.withType<Test>().configureEach { maxHeapSize = "8g" }

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}
