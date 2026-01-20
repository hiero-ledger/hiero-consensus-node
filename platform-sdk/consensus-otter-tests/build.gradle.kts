// SPDX-License-Identifier: Apache-2.0
import org.gradlex.javamodule.dependencies.dsl.GradleOnlyDirectives

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-integration")
    id("com.hedera.pbj.pbj-compiler")
}

description = "Consensus Otter Test Framework"

dependencies {
    testFixturesImplementation(platform(project(":hiero-dependency-versions")))
    testFixturesImplementation("com.hedera.pbj:pbj-grpc-client-helidon")
    testFixturesRuntimeOnly("io.helidon.webclient:helidon-webclient")
    testFixturesRuntimeOnly("io.helidon.webclient:helidon-webclient-grpc")
    testFixturesRuntimeOnly("io.helidon.webclient:helidon-webclient-http2")
    testFixturesImplementation("io.helidon.common:helidon-common-tls")
}

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
    requires("org.hiero.consensus.metrics")
    requires("org.hiero.consensus.roster")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requiresStatic("com.github.spotbugs.annotations")
}

extensions.getByName<GradleOnlyDirectives>("testOtterModuleInfo").apply {
    runtimeOnly("io.grpc.netty.shaded")
}

extensions.getByName<GradleOnlyDirectives>("testChaosModuleInfo").apply {
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

// Workaround for PBJ code generation bug with field named 'result'
abstract class FixPbjGeneratedCodeTask : DefaultTask() {
    @get:InputFile @get:Optional abstract val inputFile: RegularFileProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fix() {
        val file = inputFile.get().asFile
        if (file.exists()) {
            val content = file.readText()
            val fixed =
                content
                    .replace("if (result != DEFAULT.result)", "if (this.result != DEFAULT.result)")
                    .replace("Boolean.hashCode(result)", "Boolean.hashCode(this.result)")
            outputFile.get().asFile.writeText(fixed)
        }
    }
}

tasks.register<FixPbjGeneratedCodeTask>("fixPbjGeneratedCode") {
    val targetFile =
        layout.buildDirectory.file(
            "generated/source/pbj-proto/testFixtures/java/org/hiero/otter/fixtures/container/proto/TransactionRequestAnswer.java"
        )
    inputFile.set(targetFile)
    outputFile.set(targetFile)
}

tasks.named("compileTestFixturesJava") { dependsOn("fixPbjGeneratedCode") }

tasks.named("generateTestFixturesPbjSource") { finalizedBy("fixPbjGeneratedCode") }

// Ensure explodeCodeSourceTestFixtures runs after fixPbjGeneratedCode
tasks
    .matching { it.name == "explodeCodeSourceTestFixtures" }
    .configureEach { mustRunAfter("fixPbjGeneratedCode") }
