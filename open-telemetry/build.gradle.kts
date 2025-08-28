// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("com.hedera.pbj.pbj-compiler") version "0.11.13"
}

description = "Open Telemetry Converter"

application.mainClass = "org.hiero.telemetryconverter.TelemetryConverter"

mainModuleInfo {
    runtimeOnly("com.swirlds.config.impl")
}

pbj {
    generateTestClasses = false
}

tasks.named<JavaExec>("run") {
    // Copy all system properties, but avoid ones Gradle/JDK uses internally
    val forwarded = System.getProperties()
        .entries
        .asSequence()
        .filter { (k, _) ->
            val key = k.toString()
            !key.startsWith("org.gradle.") &&
                    !key.startsWith("java.") &&
                    !key.startsWith("sun.") &&
                    !key.startsWith("user.")
        }
        .associate { it.key.toString() to it.value }

    systemProperties(forwarded)
}

tasks.register<Delete>("cleanData") {
    description = "Clean up docker containers data dictory"
    group = "docker"
    delete(layout.projectDirectory.dir("docker/tmp"), layout.projectDirectory.dir("out"))
}

tasks.register<Exec>("cleanAndRestartTempo") {
    group = "docker"
    commandLine("bash", "-c", "rm -rf docker/tmp/tempo-data && docker restart docker-tempo-1")
}

tasks.register<Exec>("startDockerContainers") {
    description = "Starts docker containers with Prometheus, Grafana, etc"
    group = "docker"

    workingDir(layout.projectDirectory.dir("docker"))
    commandLine("/usr/local/bin/docker", "compose", "up")
}

tasks.register<Exec>("stopDockerContainers") {
    description = "Stops running docker containers with Prometheus, Grafana, etc"
    group = "docker"

    workingDir(layout.projectDirectory.dir("docker"))
    commandLine("/usr/local/bin/docker", "compose", "stop")
}