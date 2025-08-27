// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.get

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Execution YahCli Tool"

mainModuleInfo {
    runtimeOnly("org.junit.jupiter.engine")
    runtimeOnly("org.junit.platform.launcher")
}

testModuleInfo {
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("org.junit.platform.launcher")
    opensTo("org.junit.platform.commons")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }

tasks.compileJava { dependsOn(":test-clients:assemble") }

val yahCliJar =
    tasks.register<ShadowJar>("yahCliJar") {
        archiveClassifier.set("shadow")
        configurations = listOf(project.configurations.getByName("runtimeClasspath"))

        manifest { attributes("Main-Class" to "com.hedera.services.yahcli.Yahcli") }

        // Include all classes and resources from the main source set
        from(sourceSets["main"].output)

        // Also include all service files (except signature-related) in META-INF
        mergeServiceFiles()
        exclude(listOf("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/INDEX.LIST"))

        // Allow shadow Jar files to have more than 64k entries
        isZip64 = true

        dependsOn(tasks.compileJava, tasks.classes, tasks.processResources)
    }

tasks.assemble { dependsOn(yahCliJar) }

tasks.register<Copy>("copyYahCli") {
    group = "copy"
    from(yahCliJar)
    into(project.projectDir)
    rename { "yahcli.jar" }

    dependsOn(yahCliJar)
    mustRunAfter(tasks.jar, yahCliJar, tasks.javadoc)
}

tasks.named("compileTestJava") { mustRunAfter(tasks.named("copyYahCli")) }

tasks.test {
    useJUnitPlatform {}

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

tasks.register<Test>("testSubprocess") {
    useJUnitPlatform { includeTags("REGRESSION") }

    systemProperty("hapi.spec.initial.port", 25000)
    systemProperty("hapi.spec.default.shard", 11)
    systemProperty("hapi.spec.default.realm", 12)
    systemProperty("hapi.spec.network.size", 4)
    systemProperty("hapi.spec.quiet.mode", "false")
    systemProperty("junit.jupiter.execution.parallel.enabled", true)
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Surprisingly, the Gradle JUnitPlatformTestExecutionListener fails to gather result
    // correctly if test classes run in parallel (concurrent execution WITHIN a test class
    // is fine). So we need to force the test classes to run in the same thread. Luckily this
    // is not a huge limitation, as our test classes generally have enough non-leaky tests to
    // get a material speed up. See https://github.com/gradle/gradle/issues/6453.
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
    maxParallelForks = 1
}

// Disable `shadowJar` so it doesn't conflict with `yahCliJar`
tasks.named("shadowJar") { enabled = false }

// Disable unneeded tasks
tasks.matching { it.group == "distribution" }.configureEach { enabled = false }
