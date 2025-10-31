// SPDX-License-Identifier: Apache-2.0
import org.gradlex.javamodule.dependencies.dsl.GradleOnlyDirectives

// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
}

description = "Consensus Otter Test Framework"

@Suppress("UnstableApiUsage")
testing.suites.register<JvmTestSuite>("testOtter") {
    // Runs tests against the Container environment
    targets.register("testContainer") {
        testTask {
            dependsOn(":consensus-otter-docker-app:copyDockerizedApp")
            systemProperty("otter.env", "container")
        }
    }

    // Runs tests against the Turtle environment
    targets.register("testTurtle") { testTask { systemProperty("otter.env", "turtle") } }
}

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("com.github.spotbugs.annotations")
    requires("com.swirlds.component.framework")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.utility")
    requires("org.apache.logging.log4j")
}

testIntegrationModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.logging")
    requires("org.hiero.otter.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("com.github.spotbugs.annotations")
    requires("org.apache.logging.log4j")
    requires("awaitility")
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testOtterModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

@Suppress("UnstableApiUsage")
javaModuleTesting {
    whitebox(testing.suites["test"]) { sourcesUnderTest = sourceSets.testFixtures.get() }
    whitebox(testing.suites["testIntegration"]) { sourcesUnderTest = sourceSets.testFixtures.get() }
}

configurations {
    testCompileOnly { extendsFrom(configurations.testFixturesCompileOnly.get()) }
    testImplementation { extendsFrom(configurations.testFixturesImplementation.get()) }
    testRuntimeOnly { extendsFrom(configurations.testFixturesRuntimeOnly.get()) }

    testIntegrationCompileOnly { extendsFrom(configurations.testFixturesCompileOnly.get()) }
    testIntegrationImplementation { extendsFrom(configurations.testFixturesImplementation.get()) }
    testIntegrationRuntimeOnly { extendsFrom(configurations.testFixturesRuntimeOnly.get()) }
}

tasks.withType<Test>().configureEach { maxHeapSize = "8g" }

// https://github.com/hiero-ledger/hiero-gradle-conventions/issues/341
configurations {
    testFixturesApi {
        withDependencies { this.removeIf { it is ProjectDependency && it.name == project.name } }
    }
}

// https://github.com/hiero-ledger/hiero-gradle-conventions/issues/340
apply(plugin = "org.hiero.gradle.feature.protobuf")

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}
