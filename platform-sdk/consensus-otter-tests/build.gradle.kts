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
    requires("com.swirlds.base")
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
    requires("org.apache.logging.log4j")
}

testing.suites {
    val testOtter by
        registering(JvmTestSuite::class) {
            useJUnitJupiter()

            dependencies {
                implementation(project())
                implementation(project.dependencies.testFixtures(project()))
                implementation(project(":swirlds-common"))
                implementation(project(":swirlds-platform-core"))
                implementation(project(":base-crypto"))
                implementation("org.junit.jupiter:junit-jupiter-params")
                implementation("com.github.spotbugs:spotbugs-annotations")
                runtimeOnly("io.grpc:grpc-netty-shaded")
            }

            targets {
                all {
                    testTask.configure {
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
                }
            }
        }
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

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}

// Runs tests against the Turtle environment
tasks.register<Test>("testTurtle") {
    useJUnitPlatform()
    testClassesDirs = sourceSets.named("testOtter").get().output.classesDirs
    classpath = sourceSets.named("testOtter").get().runtimeClasspath

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
    beforeTest(
        KotlinClosure2({ descriptor: TestDescriptor, details: Any ->
            println("Starting test: ${descriptor.displayName}")
        })
    )

    dependsOn(":consensus-otter-docker-app:copyDockerizedApp")

    useJUnitPlatform()
    testClassesDirs = sourceSets.named("testOtter").get().output.classesDirs
    classpath = sourceSets.named("testOtter").get().runtimeClasspath

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

    afterTest(
        KotlinClosure2({ descriptor: TestDescriptor, result: TestResult ->
            try {
                // Grab all output from the
                // {root}/platform-sdk/consensus-otter-tests/build/container directory
                // Ensure the containerized app has spooled down completely
                // Copy all output from the above directory into an aggregate directory:
                //  {root}/platform-sdk/consensus-otter-tests/build/aggregateData
                // The copy should use the copy action from gradle API to ensure proper handling of
                // file locks, etc.
                val aggregateDir = layout.buildDirectory.dir("aggregateTestContainer").get().asFile
                val testName = descriptor.className?.substringAfterLast('.') + "_" + descriptor.name
                val targetDir = File(aggregateDir, "${testName}")
                val containerDir = layout.buildDirectory.asFile.get().resolve("container")
                if (containerDir.exists()) {
                    // Ensure the container has fully stopped before copying logs
                    // This is a best-effort attempt; if the container is still running, the copy
                    // may fail
                    Thread.sleep(2000)

                    val targetContainerDir = File(targetDir, "container")
                    copy {
                        from(containerDir)
                        into(targetContainerDir)
                    }
                } else {
                    println("No container logs found to copy.")
                }
            } catch (e: Exception) {
                println("Error while copying container logs: ${e.message}")
            }
        })
    )
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
