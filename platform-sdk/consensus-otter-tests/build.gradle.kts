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
        targets.configureEach {
            // Forwards e.g. -Dfalcon.repetitions=10 to the test JVM
            testTask { systemProperties(providers.systemPropertiesPrefixedBy("falcon.").get()) }
        }

        targets.named("testOtter") {
            testTask { dependsOn(":consensus-otter-docker-app:assemble") }
        }

        // Runs tests against the Container environment
        targets.register("testContainer") {
            testTask {
                systemProperty("otter.env", "container")
                useJUnitPlatform { excludeTags("falcon") }
                dependsOn(":consensus-otter-docker-app:assemble")
            }
        }

        // Runs tests against the Turtle environment
        targets.register("testTurtle") {
            testTask {
                systemProperty("otter.env", "turtle")
                useJUnitPlatform { excludeTags("falcon") }
            }
        }

        // Runs tests against the Falcon environment
        targets.register("testFalcon") { testTask { useJUnitPlatform { includeTags("falcon") } } }
    }

    suites.register<JvmTestSuite>("testChaos") {
        targets.configureEach { testTask { dependsOn(":consensus-otter-docker-app:assemble") } }
    }
}

testModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.metrics.api")
    requires("org.hiero.consensus.event.stream")
    requires("org.hiero.consensus.fakes")
    requires("org.hiero.consensus.roster")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility")
    requires("org.hiero.consensus.wiring.framework")
    requires("org.apache.logging.log4j")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requiresStatic("com.github.spotbugs.annotations")

    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
}

testIntegrationModuleInfo { //
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testOtterModuleInfo").apply {
    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testChaosModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

// This is applied to all Test tasks to work across all execution methods (local, CI, etc.)
tasks.withType<Test>().configureEach { maxHeapSize = "8g" }

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}

// Task to generate saved states for otter tests
tasks.register<JavaExec>("generateSavedState") {
    group = "otter"
    description = "Generate a saved state for use in otter tests"
    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass = "org.hiero.otter.fixtures.tools.GenerateStateTool"
}

// Task to generate the post-freeze base fixture consumed by
// PostFreezeStakeThresholdFlipTest. Produces state + full PCES; the symmetric filter step
// below then trims online-creator events past the checkpoint round.
tasks.register<JavaExec>("generatePostFreezeSavedState") {
    group = "otter"
    description = "Generate the post-freeze base saved state for PostFreezeStakeThresholdFlipTest"
    modularity.inferModulePath = true
    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainModule = "org.hiero.otter.fixtures"
    mainClass = "org.hiero.otter.fixtures.tools.GeneratePostFreezeStateTool"
}

// Symmetric filter: single fixture, online-creator events past R dropped. All online nodes
// load the same PCES; each recreates its own post-R events live at restart. Consumed by
// testNoSpuriousFlipWithSymmetricFilteredFixture.
tasks.register<JavaExec>("partitionPostFreezeFixtureSymmetric") {
    group = "otter"
    description = "Produce a symmetric filtered fixture (online creators dropped past R)"
    modularity.inferModulePath = true
    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainModule = "org.hiero.otter.fixtures"
    mainClass = "org.hiero.otter.fixtures.tools.PartitionPostFreezeFixtureSymmetric"
}
