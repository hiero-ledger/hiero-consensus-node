// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Hedera Application - Implementation"

mainModuleInfo {
    annotationProcessor("dagger.compiler")
    annotationProcessor("com.google.auto.service.processor")

    // This is needed to pick up and include the native libraries for the netty epoll transport
    runtimeOnly("io.netty.transport.epoll.linux.x86_64")
    runtimeOnly("io.netty.transport.epoll.linux.aarch_64")
    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
}

testModuleInfo {
    requires("com.fasterxml.jackson.databind")
    requires("com.google.protobuf")
    requires("com.google.common.jimfs")
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.test.fixtures")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("com.esaulpaugh.headlong")
    requires("org.assertj.core")
    requires("org.bouncycastle.provider")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    requires("tuweni.bytes")
    requires("uk.org.webcompere.systemstubs.core")
    requires("uk.org.webcompere.systemstubs.jupiter")

    exportsTo("org.hiero.base.utility") // access package "utils" (maybe rename to "util")
    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
    opensTo("com.swirlds.common") // instantiation via reflection
}

jmhModuleInfo {
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.hapi.utils")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.app.test.fixtures")
    requires("com.hedera.node.hapi")
    requires("com.hedera.pbj.runtime")
    requires("jmh.core")
    requires("org.hiero.base.crypto")
}

// Add all the libs dependencies into the jar manifest!
tasks.jar {
    inputs.files(configurations.runtimeClasspath)
    manifest { attributes("Main-Class" to "com.hedera.node.app.ServicesMain") }
    doFirst {
        manifest.attributes(
            "Class-Path" to
                inputs.files
                    .filter { it.extension == "jar" }
                    .map { "../../data/lib/" + it.name }
                    .sorted()
                    .joinToString(separator = " ")
        )
    }
}

// Copy dependencies into `data/lib`
val copyLib =
    tasks.register<Sync>("copyLib") {
        from(project.configurations.getByName("runtimeClasspath"))
        into(layout.projectDirectory.dir("../data/lib"))
    }

// Copy built jar into `data/apps` and rename HederaNode.jar
val copyApp =
    tasks.register<Sync>("copyApp") {
        from(tasks.jar)
        into(layout.projectDirectory.dir("../data/apps"))
        rename { "HederaNode.jar" }
        shouldRunAfter(tasks.named("copyLib"))
    }

// Working directory for 'run' tasks
val nodeWorkingDir = layout.buildDirectory.dir("node")

val copyNodeData =
    tasks.register<Sync>("copyNodeDataAndConfig") {
        into(nodeWorkingDir)

        // Copy things from hedera-node/data
        into("data/lib") { from(copyLib) }
        into("data/apps") { from(copyApp) }
        into("data/onboard") { from(layout.projectDirectory.dir("../data/onboard")) }
        into("data/keys") { from(layout.projectDirectory.dir("../data/keys")) }

        // Copy hedera-node/configuration/dev as hedera-node/hedera-app/build/node/data/config  }
        from(layout.projectDirectory.dir("../configuration/dev")) { into("data/config") }
        from(layout.projectDirectory.file("../config.txt"))
        from(layout.projectDirectory.file("../log4j2.xml"))
        from(layout.projectDirectory.file("../configuration/dev/settings.txt"))
    }

tasks.assemble {
    dependsOn(copyLib)
    dependsOn(copyApp)
    dependsOn(copyNodeData)
}

// Create the "run" task for running a Hedera consensus node
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run a Hedera consensus node instance."
    dependsOn(tasks.assemble)
    workingDir = nodeWorkingDir.get().asFile
    jvmArgs = listOf("-cp", "data/lib/*:data/apps/*")
    mainClass.set("com.hedera.node.app.ServicesMain")

    // Add arguments for the application to run a local node
    args = listOf("-local", "0")
}

val cleanRun =
    tasks.register<Delete>("cleanRun") {
        val prjDir = layout.projectDirectory.dir("..")
        delete(prjDir.dir("database"))
        delete(prjDir.dir("output"))
        delete(prjDir.dir("settingsUsed.txt"))
        delete(prjDir.dir("swirlds.jar"))
        delete(prjDir.asFileTree.matching { include("MainNetStats*") })
        val dataDir = prjDir.dir("data")
        delete(dataDir.dir("accountBalances"))
        delete(dataDir.dir("apps"))
        delete(dataDir.dir("lib"))
        delete(dataDir.dir("recordstreams"))
        delete(dataDir.dir("saved"))
    }

tasks.clean { dependsOn(cleanRun) }

tasks.register("showHapiVersion") {
    inputs.property("version", project.version)
    doLast { println(inputs.properties["version"]) }
}

var updateDockerEnvTask =
    tasks.register<Exec>("updateDockerEnv") {
        description =
            "Creates the .env file in the docker folder that contains environment variables for docker"
        group = "docker"

        workingDir(layout.projectDirectory.dir("../docker"))
        commandLine("./update-env.sh", project.version)
    }

tasks.register<Exec>("createDockerImage") {
    description = "Creates the docker image of the services based on the current version"
    group = "docker"

    dependsOn(updateDockerEnvTask, tasks.assemble)
    workingDir(layout.projectDirectory.dir("../docker"))
    commandLine("./docker-build.sh", project.version, layout.projectDirectory.dir("..").asFile)
}

tasks.register<Exec>("startDockerContainers") {
    description = "Starts docker containers of the services based on the current version"
    group = "docker"

    dependsOn(updateDockerEnvTask)
    workingDir(layout.projectDirectory.dir("../docker"))
    commandLine("docker-compose", "up")
}

tasks.register<Exec>("stopDockerContainers") {
    description = "Stops running docker containers of the services"
    group = "docker"

    dependsOn(updateDockerEnvTask)
    workingDir(layout.projectDirectory.dir("../docker"))
    commandLine("docker-compose", "stop")
}
