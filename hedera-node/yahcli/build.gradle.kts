// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    id("org.hiero.gradle.module.application")
    id("org.gradlex.java-module-packaging")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Execution YahCli Tool"

mainModuleInfo {
    runtimeOnly("org.junit.jupiter.engine")
    runtimeOnly("org.junit.platform.launcher")
}

testModuleInfo {
    requires("org.hiero.base.crypto")
    requires("com.fasterxml.jackson.databind")
    requires("org.apache.commons.lang3")
    requires("org.assertj.core")
    requires("org.junit.jupiter.params")
    requires("org.junit.platform.launcher")

    opensTo("org.junit.platform.commons")
}

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-exports") }

application.mainClass = "com.hedera.services.yahcli.Yahcli"

// Besu and Log4j core both provide plugin caches; merge them so shadow packaging preserves both.
tasks.withType<ShadowJar>().configureEach {
    filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(Log4j2PluginsCacheFileTransformer::class.java)
}

tasks.register<Copy>("copyYahCli") {
    group = "copy"
    from(tasks.shadowJar)
    into(project.projectDir)
    rename { "yahcli.jar" }
}

tasks.test {
    // Keep default `test` fast and deterministic; subprocess HAPI-style suites run under
    // `testSubprocess`.
    useJUnitPlatform { excludeTags("REGRESSION") }

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

tasks.register<Test>("testSubprocess") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

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
