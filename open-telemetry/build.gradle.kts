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

tasks.register<JavaExec>("runOnHapiTestOutput") {
    group = "application"
    description = "Run the OpenTelemetry converter on HAPI test results"
    dependsOn("assemble")

    mainModule.set("org.hiero.telemetryconverter")
    mainClass.set("org.hiero.telemetryconverter.TelemetryConverter")
    modularity.inferModulePath.set(true)
    classpath = sourceSets["main"].runtimeClasspath

    val jfrDirectory = layout.projectDirectory.dir("../hedera-node/test-clients/build/hapi-test").asFile.absolutePath
    val blockStreamsDirectory = layout.projectDirectory.dir("../hedera-node/test-clients/build/hapi-test/node0/data/blockStreams").asFile.absolutePath

    systemProperties(
        mapOf(
            "tracing.converter.jfrDirectory" to jfrDirectory,
            "tracing.converter.blockStreamsDirectory" to blockStreamsDirectory
        )
    )
}

tasks.register("re-runOnHapiTestOutput") {
    group = "application"
    description = "Rerun the OpenTelemetry converter on HAPI test results cleaning data and restarting Docker"

    dependsOn("restartDockerContainers", "runOnHapiTestOutput")
    tasks.getByName("runOnHapiTestOutput").dependsOn("restartDockerContainers")

    // Ensure this task is never up-to-date
    outputs.upToDateWhen { false }
}

tasks.register<Delete>("cleanData") {
    description = "Clean up docker containers data dictory"
    group = "docker"
    delete(layout.projectDirectory.dir("docker/tmp"), layout.projectDirectory.dir("out"))
}

tasks.register<Exec>("startDockerContainers") {
    description = "Starts docker containers with Prometheus, Grafana, etc"
    group = "docker"

    workingDir(layout.projectDirectory.dir("docker"))
    commandLine("/usr/local/bin/docker", "compose", "up", "-d")

    doLast {
        println("Started docker containers, waiting a bit to allow them to fully start...")
        Thread.sleep(2000) // 2 second pause
    }
}

tasks.register<Exec>("stopDockerContainers") {
    description = "Stops running docker containers with Prometheus, Grafana, etc"
    group = "docker"

    workingDir(layout.projectDirectory.dir("docker"))
    commandLine("/usr/local/bin/docker", "compose", "stop")
}

tasks.register("restartDockerContainers") {
    description = "Stops docker, cleans data and starts docker containers"
    group = "docker"

    dependsOn("stopDockerContainers", "cleanData", "startDockerContainers")

    tasks.getByName("cleanData").dependsOn("stopDockerContainers")
    tasks.getByName("startDockerContainers").dependsOn("cleanData")
}