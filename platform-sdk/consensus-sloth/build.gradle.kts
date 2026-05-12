// SPDX-License-Identifier: Apache-2.0
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradlex.javamodule.dependencies.dsl.GradleOnlyDirectives

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
    id("org.hiero.gradle.feature.protobuf")
}

description = "Consensus Performance Framework"

testFixturesModuleInfo {
    runtimeOnly("io.netty.transport.epoll.linux.x86_64")
    runtimeOnly("io.netty.transport.epoll.linux.aarch_64")
    runtimeOnly("org.hiero.consensus.event.intake.concurrent")
    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
    runtimeOnly("io.grpc.netty.shaded")
}

tasks.testFixturesJar {
    inputs.files(configurations.testFixturesRuntimeClasspath)
    manifest { attributes("Main-Class" to "org.hiero.sloth.fixtures.container.docker.DockerMain") }
    doFirst {
        manifest.attributes(
            "Class-Path" to
                inputs.files
                    .filter { it.extension == "jar" }
                    .map { "../lib/" + it.name }
                    .sorted()
                    .joinToString(separator = " ")
        )
    }
}

tasks.register<Sync>("copyDockerizedApp") {
    into(layout.buildDirectory.dir("data"))
    from(layout.projectDirectory.file("src/testFixtures/docker/Dockerfile"))
    into("apps") {
        from(tasks.testFixturesJar)
        rename { "DockerApp.jar" }
    }
    into("lib") { from(configurations.testFixturesRuntimeClasspath) }
}

tasks.assemble { dependsOn("copyDockerizedApp") }

@Suppress("UnstableApiUsage")
testing {
    suites.register<JvmTestSuite>("testPerformance") {
        // Runs performance benchmarks against the container environment
        targets.configureEach {
            testTask {
                systemProperty("sloth.env", "container")
                dependsOn("copyDockerizedApp")
            }
        }
    }
}

testModuleInfo { requiresStatic("com.github.spotbugs.annotations") }

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

// Disable Jacoco (code coverage) for performance tests to avoid overhead
// Also ensure performance tests always run (never cached/skipped)
tasks.named<Test>("testPerformance") {
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    outputs.upToDateWhen { false } // Always run, never consider up-to-date

    // Allow filtering experiments via -PtestFilter="*.SomeExperiment"
    providers.gradleProperty("testFilter").orNull?.let { filter ->
        this.filter.includeTestsMatching(filter)
    }

    // Forward sloth.* project properties from the Gradle command line to the test JVM.
    // Allows overriding BenchmarkParameters from the command line, e.g.:
    //   ./gradlew :consensus-sloth:testPerformance -Psloth.tps=100 -Psloth.benchmarkTime=60s
    // Note: project properties (-P) are used instead of system properties (-D) because -D flags
    // are set on the Gradle client JVM and are not reliably forwarded to the daemon's
    // System.getProperties().
    project.properties
        .filterKeys { it.startsWith("sloth.") }
        .forEach { (key, value) -> systemProperty(key, value.toString()) }

    // Allow running @Disabled tests for manual remote runs, e.g.:
    //   ./gradlew :consensus-sloth:testPerformance -PincludeDisabled
    if (project.hasProperty("includeDisabled")) {
        systemProperty("junit.jupiter.conditions.deactivate", "org.junit.*Disabled*")
    }
}
