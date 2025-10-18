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
    id("com.hedera.pbj.pbj-compiler") version "0.12.1"
}

description = "Consensus Otter Test Framework"

dependencies {
    testFixturesImplementation(platform(project(":hiero-dependency-versions")))
    testFixturesImplementation("com.hedera.pbj:pbj-grpc-client-helidon")
    testFixturesImplementation("io.helidon.webclient:helidon-webclient")
    testFixturesImplementation("io.helidon.webclient:helidon-webclient-grpc")
    testFixturesImplementation("io.helidon.webclient:helidon-webclient-http2")
    testFixturesImplementation("io.helidon.common:helidon-common-tls")
}

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
}

testIntegrationModuleInfo {
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.logging")
    requires("org.apache.logging.log4j")
    requires("org.hiero.otter.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("com.github.spotbugs.annotations")
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

// Workaround for PBJ code generation bug with field named 'result'
abstract class FixPbjGeneratedCodeTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fix() {
        val file = inputFile.get().asFile
        if (file.exists()) {
            val content = file.readText()
            val fixed = content
                .replace("if (result != DEFAULT.result)", "if (this.result != DEFAULT.result)")
                .replace("Boolean.hashCode(result)", "Boolean.hashCode(this.result)")
            outputFile.get().asFile.writeText(fixed)
        }
    }
}

tasks.register<FixPbjGeneratedCodeTask>("fixPbjGeneratedCode") {
    val targetFile = layout.buildDirectory.file("generated/source/pbj-proto/testFixtures/java/org/hiero/otter/fixtures/container/proto/TransactionRequestAnswer.java")
    inputFile.set(targetFile)
    outputFile.set(targetFile)
}

tasks.named("compileTestFixturesJava") {
    dependsOn("fixPbjGeneratedCode")
}

tasks.named("generateTestFixturesPbjSource") {
    finalizedBy("fixPbjGeneratedCode")
}
