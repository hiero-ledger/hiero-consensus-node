// SPDX-License-Identifier: Apache-2.0
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.get

plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

description = "Hedera Services YahCli Tool"

mainModuleInfo {
    runtimeOnly("org.junit.jupiter.engine")
    runtimeOnly("org.junit.platform.launcher")
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

    // Tell our launcher to target an embedded network whose mode is set per-class
    systemProperty("hapi.spec.embedded.mode", "per-class")

    // Limit heap and number of processors
    maxHeapSize = "8g"
    jvmArgs("-XX:ActiveProcessorCount=6")
}

// Disable `shadowJar` so it doesn't conflict with `yahCliJar`
tasks.named("shadowJar") { enabled = false }

// Disable unneeded tasks
tasks.matching { it.group == "distribution" }.configureEach { enabled = false }
