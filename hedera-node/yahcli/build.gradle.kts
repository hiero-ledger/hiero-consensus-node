// SPDX-License-Identifier: Apache-2.0
<<<<<<< HEAD
=======
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer
import org.gradle.kotlin.dsl.get

>>>>>>> 354c2e2 (fix: bump Besu  EVM version and remove Native Libs patch (#571))
plugins {
    id("org.hiero.gradle.module.application")
    id("org.gradlex.java-module-packaging")
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

<<<<<<< HEAD
application.mainClass = "com.hedera.services.yahcli.Yahcli"
=======
tasks.compileJava { dependsOn(":test-clients:assemble") }

// besu-util ships its own Log4j2Plugins.dat; merge it with log4j-core's instead of letting
// the duplicates strategy keep whichever jar comes first on the classpath.
tasks.withType<ShadowJar>().configureEach {
    // The convention plugin fails on unexpected duplicates; let both copies of the log4j
    // plugin cache through so the transformer below can merge them into one.
    filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    transform(Log4j2PluginsCacheFileTransformer::class.java)
}

val yahCliJar =
    tasks.register<ShadowJar>("yahCliJar") {
        archiveClassifier = "shadow"
        configurations = listOf(project.configurations["runtimeClasspath"])

        manifest {
            attributes(
                "Main-Class" to "com.hedera.services.yahcli.Yahcli",
                // Declares JNI usage (netty's NativeLibraryUtil) so the JDK does not print a
                // restricted-method warning for callers in the unnamed module of this JAR.
                "Enable-Native-Access" to "ALL-UNNAMED",
            )
        }

        // Strip log4j2 configs contributed by transitive deps so they do not shadow
        // yahcli's own config or mislead operators inspecting the jar. yahcli's own
        // log config uses a distinct filename (src/main/resources/log4j2-yahcli.xml,
        // pinned via src/main/resources/log4j2.component.properties), so this
        // exclude does not affect it.
        exclude("log4j2.xml", "log4j2-test-client.xml", "log4j2-JRS.xml", "regression-log4j2.xml")

        // Include all classes and resources from the main source set
        from(sourceSets["main"].output)
    }
>>>>>>> 354c2e2 (fix: bump Besu  EVM version and remove Native Libs patch (#571))

tasks.register<Copy>("copyYahCli") {
    group = "copy"
    from(tasks.fatModuleJar)
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
