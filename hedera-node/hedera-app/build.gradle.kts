// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Hedera Application - Implementation"

mainModuleInfo {
    annotationProcessor("dagger.compiler")

    // This is needed to pick up and include the native libraries for the netty epoll transport
    runtimeOnly("io.netty.transport.epoll.linux.x86_64")
    runtimeOnly("io.netty.transport.epoll.linux.aarch_64")
    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
    runtimeOnly("io.helidon.webclient.http2")
    runtimeOnly("com.hedera.pbj.grpc.client.helidon")
    runtimeOnly("com.hedera.pbj.grpc.helidon")
    runtimeOnly("org.hiero.consensus.pcli")
}

testModuleInfo {
    requires("com.fasterxml.jackson.databind")
    requires("com.google.protobuf")
    requires("com.google.common.jimfs")
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.test.fixtures")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("com.swirlds.merkledb")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.base.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
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
    requires("com.hedera.node.config")
    requires("com.hedera.node.hapi")
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.state.api")
    requires("com.hedera.pbj.grpc.helidon")
    requires("com.hedera.pbj.grpc.helidon.config")
    requires("io.helidon.common")
    requires("io.helidon.webserver")
    requires("org.hiero.consensus.model")
    requires("org.hiero.consensus.platformstate")
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

        from(layout.projectDirectory.dir("../configuration/small-memory")) { into("data/config") }
        from(layout.projectDirectory.file("../config.txt"))
        from(layout.projectDirectory.file("../log4j2.xml"))
        from(layout.projectDirectory.file("../configuration/small-memory/settings.txt"))
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
    val jfcFile = rootProject.file("hedera-node/configuration/small-memory/MemoryLowOverhead.jfc")
    val jfrFile = rootProject.file("hedera-node/hedera-app/data/recording-low-memory.jfr")

    jvmArgs =
        listOf(
            "-cp",
            "data/lib/*:data/apps/*",
            "-XX:+UseSerialGC",
//            "-XX:+UseZGC",
//            "-XX:+ZGenerational",
            "-Xms128M",
            "-Xmx512M",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:MaxMetaspaceSize=96M",
            "-XX:CompressedClassSpaceSize=48M",
            "-Xss256K",
            "-XX:-TieredCompilation",
            "-XX:CICompilerCount=1",
            "-XX:ReservedCodeCacheSize=24M",
            "-XX:MaxDirectMemorySize=16M",
            "-XX:NativeMemoryTracking=summary",
            "-XX:StartFlightRecording=dumponexit=true,settings=$jfcFile,filename=$jfrFile",
        )
    mainClass.set("com.hedera.node.app.ServicesMain")

    // Add arguments for the application to run a local node
    args = listOf("-local", "0")
}

// Persistent directory for GraalVM native-image configs (survives ./gradlew assemble)
val persistentNativeConfigDir = layout.projectDirectory.dir("native-image-config")
// Build-time copy inside the node working directory
val nativeImageConfigDir = nodeWorkingDir.map { it.dir("native-image-config") }

// Run with GraalVM tracing agent to auto-generate native-image configs
tasks.register<JavaExec>("runWithTracingAgent") {
    group = "native image"
    description = "Run with GraalVM tracing agent to generate native-image configs."
    dependsOn(tasks.assemble)
    workingDir = nodeWorkingDir.get().asFile

    // Save configs to persistent (source-tree) location so assemble doesn't wipe them
    val configOutDir = persistentNativeConfigDir.asFile.absolutePath
    doFirst { persistentNativeConfigDir.asFile.mkdirs() }

    jvmArgs =
        listOf(
            "-agentlib:native-image-agent=config-output-dir=$configOutDir,config-write-period-secs=5,config-write-initial-delay-secs=0",
            "-cp",
            "data/lib/*:data/apps/*",
            "-XX:+UseSerialGC",
            "-Xms128M",
            "-Xmx512M",
        )
    mainClass.set("com.hedera.node.app.ServicesMain")
    args = listOf("-local", "0")
}

// Build a GraalVM native image from the assembled node JARs
tasks.register<Exec>("nativeCompile") {
    group = "native image"
    description = "Build a GraalVM native image of the Hedera node."
    dependsOn(tasks.assemble)
    workingDir = nodeWorkingDir.get().asFile

    val persistentConfigDir = persistentNativeConfigDir.asFile
    val buildConfigDir = nativeImageConfigDir.get().asFile
    val classpath = "data/lib/*:data/apps/*"

    doFirst {
        // Copy persistent configs into build directory
        if (persistentConfigDir.exists() &&
            (persistentConfigDir.listFiles()?.any { it.extension == "json" } == true)
        ) {
            buildConfigDir.mkdirs()
            persistentConfigDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                file.copyTo(buildConfigDir.resolve(file.name), overwrite = true)
            }
        } else {
            buildConfigDir.mkdirs()
            // No tracing agent configs - native-image will rely on auto-detection
        }

        // Strip Netty's internal native-image.properties to avoid conflicting init declarations
        val libDir = file("${nodeWorkingDir.get().asFile}/data/lib")
        libDir.listFiles()?.filter { it.name.startsWith("netty-") || it.name.startsWith("grpc-netty") }
            ?.forEach { jar ->
                val pb = ProcessBuilder("zip", "-d", jar.absolutePath, "META-INF/native-image/*")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                proc.inputStream.readBytes() // consume output
                proc.waitFor() // ignore exit code - some JARs may not have the entry
            }
    }

    // Packages that get pulled in during build-time analysis and must be allowed at build time.
    // io.netty is NOT included - too many classes need native memory/network at init time.
    // io.grpc is NOT included - gRPC netty triggers Epoll checks at static init.
    // Netty and gRPC default to runtime init (GraalVM 25 default).
    val buildTimePackages = listOf(
        "org.bouncycastle",
        "org.slf4j",
        "com.fasterxml.jackson",
        // com.google.protobuf is NOT at build time - it uses Unsafe.objectFieldOffset()
        // in MessageSchema/UnsafeUtil during class init; offsets computed at build time
        // don't match native-image runtime object layout, causing data corruption in
        // protobuf serialization (e.g. ThrottleDefinitions gets corrupted bytes).
        "com.google.common",
        "org.apache.commons",
        "com.google.gson",
        "com.google.errorprone",
        "javax.annotation",
        "io.perfmark",
        "org.yaml.snakeyaml",
    ).joinToString(",")
    // Log4j (org.apache.logging) is NOT at build time - it starts timer threads during class init

    // Packages that must be initialized at run time to avoid Unsafe field offset
    // mismatches between build-time JVM and native-image runtime object layout.
    // org.hiero.base.utility - MemoryUtils caches Buffer.address field offset
    // com.hedera.pbj.runtime.io - UnsafeUtils caches the same offset
    val runTimePackages = listOf(
        "com.sun.jna",
        "io.netty",
        "org.hiero.base.utility.MemoryUtils",
        "com.hedera.pbj.runtime.io.UnsafeUtils",
    ).joinToString(",")

    commandLine(
        "native-image",
        "-cp", classpath,
        "-H:ConfigurationFileDirectories=${buildConfigDir.absolutePath}",
        "-o", "hedera-node",
        "--no-fallback",
        "--initialize-at-build-time=$buildTimePackages",
        "--initialize-at-run-time=$runTimePackages",
        "-J-Xmx8g",
        "com.hedera.node.app.ServicesMain",
    )
}

// Run the native image binary
tasks.register<Exec>("runNative") {
    group = "native image"
    description = "Run the native-image compiled Hedera node."
    dependsOn("nativeCompile")
    workingDir = nodeWorkingDir.get().asFile
    commandLine("./hedera-node", "-local", "0")
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

dependencies { api(project(":config")) }

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
