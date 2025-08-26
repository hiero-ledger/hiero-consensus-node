// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.get

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Services YahCli Tool"

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }

tasks.compileJava { dependsOn(":test-clients:assemble") }

testModuleInfo {
    requires("org.junit.jupiter.params")
    requires("org.assertj.core")

    runtimeOnly("com.hedera.common.nativesupport")
    runtimeOnly("com.hedera.node.app.hapi.utils")
    runtimeOnly("com.hedera.node.hapi")
    runtimeOnly("com.hedera.node.test.clients")
    runtimeOnly("com.swirlds.config.api")
    runtimeOnly("info.picocli")
    runtimeOnly("io.github.classgraph")
    runtimeOnly("io.helidon.common.buffers")
    runtimeOnly("io.helidon.common.resumable")
    runtimeOnly("io.helidon.common")
    runtimeOnly("jakarta.inject")
    runtimeOnly("javax.inject")
    runtimeOnly("org.apache.logging.log4j")
    runtimeOnly("org.eclipse.collections.api")
    runtimeOnly("org.jspecify")
    runtimeOnly("org.junit.jupiter.api")
    runtimeOnly("org.yaml.snakeyaml")
    runtimeOnly("simpleclient")
}

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
