// SPDX-License-Identifier: Apache-2.0
import groovy.json.JsonSlurper
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import me.champeau.jmh.JMHTask
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import org.gradle.work.DisableCachingByDefault

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
}

description = "Hedera Application - Implementation"

@DisableCachingByDefault(because = "Produces environment-specific benchmark artifacts intended for manual comparison.")
abstract class Secp256k1InteropBenchmarkReportTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resultsJson: RegularFileProperty

    @get:OutputFile
    abstract val summaryFile: RegularFileProperty

    @get:OutputFile
    abstract val metadataFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gitWorkingDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val results = JsonSlurper().parse(resultsJson.get().asFile) as List<Map<String, Any?>>

        fun primaryMetricFor(methodName: String): Map<String, Any?> {
            val benchmarkResult = results.single { result ->
                (result["benchmark"] as String).endsWith(".$methodName")
            }
            @Suppress("UNCHECKED_CAST")
            return benchmarkResult["primaryMetric"] as Map<String, Any?>
        }

        fun scoreFor(methodName: String): Double =
            (primaryMetricFor(methodName)["score"] as Number).toDouble()

        fun scoreErrorFor(methodName: String): Double? =
            (primaryMetricFor(methodName)["scoreError"] as? Number)?.toDouble()

        fun scoreUnitFor(methodName: String): String =
            primaryMetricFor(methodName)["scoreUnit"] as String

        val generatedAt = Instant.now().toString()
        val gitDir = gitWorkingDirectory.get().asFile
        val gitBranch = runCommand(gitDir, "git", "branch", "--show-current")
        val gitCommit = runCommand(gitDir, "git", "rev-parse", "HEAD")
        val gitStatus = runCommand(gitDir, "git", "status", "--short")
        val workingTreeStatus = if (gitStatus.isBlank()) "clean" else "dirty"
        val metadataOutput = metadataFile.get().asFile
        val summaryOutput = summaryFile.get().asFile
        metadataOutput.parentFile.mkdirs()
        summaryOutput.parentFile.mkdirs()

        metadataOutput.writeText(
            buildString {
                appendLine("# Secp256k1 Interop Benchmark Metadata")
                appendLine()
                appendLine("- Generated: `$generatedAt`")
                appendLine("- Git branch: `$gitBranch`")
                appendLine("- Git commit: `$gitCommit`")
                appendLine("- Working tree: `$workingTreeStatus`")
                appendLine("- OS: `${System.getProperty("os.name")} ${System.getProperty("os.version")}`")
                appendLine("- Architecture: `${System.getProperty("os.arch")}`")
                appendLine("- Available processors: `${Runtime.getRuntime().availableProcessors()}`")
                appendLine("- Java vendor: `${System.getProperty("java.vendor")}`")
                appendLine("- Java runtime version: `${System.getProperty("java.runtime.version")}`")
                appendLine("- Java VM: `${System.getProperty("java.vm.name")}`")
                appendLine("- Gradle version: `${GradleVersion.current().version}`")
                appendLine("- Benchmark task: `:app:jmhSecp256k1Interop`")
                appendLine("- Benchmark class: `com.hedera.node.app.signature.impl.Secp256k1InteropBenchmark`")
                appendLine("- Configuration: `2 forks`, `3 x 2s warmup`, `5 x 2s measurement`, `1 thread`, `-prof gc`")
                appendLine("- JVM args: `-Xms2g -Xmx2g`")
                appendLine("- Artifact directory: `${summaryOutput.parentFile.absolutePath}`")
                if (gitStatus.isNotBlank()) {
                    appendLine()
                    appendLine("## Working Tree")
                    appendLine("```text")
                    appendLine(gitStatus)
                    appendLine("```")
                }
            }
        )

        summaryOutput.writeText(
            buildString {
                appendLine("# Secp256k1 Interop Benchmark Summary")
                appendLine()
                appendLine("Generated: `$generatedAt`")
                appendLine()
                appendLine("Mode: `avgt`. Lower is better.")
                appendLine()
                appendLine("| Comparison | Native | BouncyCastle | BC / Native |")
                appendLine("| --- | ---: | ---: | ---: |")
                COMPARISONS.forEach { comparison ->
                    val nativeScore = scoreFor(comparison.nativeMethod)
                    val bcScore = scoreFor(comparison.bcMethod)
                    val nativeError = scoreErrorFor(comparison.nativeMethod)
                    val bcError = scoreErrorFor(comparison.bcMethod)
                    val unit = scoreUnitFor(comparison.nativeMethod)
                    appendLine(
                        "| ${comparison.label} | " +
                            "${formatDecimal(nativeScore)}" +
                            (nativeError?.let { " +/- ${formatDecimal(it)}" } ?: "") +
                            " $unit | " +
                            "${formatDecimal(bcScore)}" +
                            (bcError?.let { " +/- ${formatDecimal(it)}" } ?: "") +
                            " $unit | " +
                            "${formatDecimal(bcScore / nativeScore)}x |"
                    )
                }
                appendLine()
                appendLine("Artifacts:")
                appendLine("- `results.txt`: human-readable JMH output including GC profiler metrics")
                appendLine("- `results.json`: machine-readable JMH output")
                appendLine("- `metadata.md`: environment and git context for the run")
            }
        )
    }

    private fun runCommand(workingDirectory: File, vararg command: String): String {
        val process = ProcessBuilder(*command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("Command timed out: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw GradleException("Command failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output")
        }
        return output
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class BenchmarkComparison(val label: String, val nativeMethod: String, val bcMethod: String)

    companion object {
        private val COMPARISONS = listOf(
            BenchmarkComparison(
                "Decompress compressed key",
                "nativeDecompressCompressedKey",
                "bcDecompressCompressedKey",
            ),
            BenchmarkComparison(
                "Extract Ethereum transaction signatures",
                "nativeExtractEthereumTransactionSignatures",
                "bcExtractEthereumTransactionSignatures",
            ),
            BenchmarkComparison(
                "Recover address from compressed key",
                "nativeRecoverAddressFromCompressedKey",
                "bcRecoverAddressFromCompressedKey",
            ),
            BenchmarkComparison("Verify signature", "nativeVerifySignature", "bcVerifySignature"),
        )
    }
}

val secp256k1InteropResultsJson = layout.buildDirectory.file("reports/benchmarks/secp256k1-interop/results.json")
val secp256k1InteropHumanOutput = layout.buildDirectory.file("reports/benchmarks/secp256k1-interop/results.txt")
val secp256k1InteropSummary = layout.buildDirectory.file("reports/benchmarks/secp256k1-interop/summary.md")
val secp256k1InteropMetadata = layout.buildDirectory.file("reports/benchmarks/secp256k1-interop/metadata.md")

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
    requires("org.bouncycastle.provider")
    requires("jmh.core")
    requires("org.hiero.base.crypto")
}

tasks.register<JMHTask>("jmhSecp256k1Interop") {
    group = "jmh"
    description = "Runs the secp256k1 native-vs-BouncyCastle benchmark with shareable output artifacts."

    includes.set(listOf("Secp256k1InteropBenchmark"))
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("us")
    threads.set(1)
    synchronizeIterations.set(true)
    fork.set(2)
    warmupIterations.set(3)
    warmup.set("2s")
    iterations.set(5)
    timeOnIteration.set("2s")
    failOnError.set(true)
    jvmArgs.set(listOf("-Xms2g", "-Xmx2g"))
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.convention(secp256k1InteropResultsJson)
    humanOutputFile.convention(secp256k1InteropHumanOutput)

    outputs.upToDateWhen { false }
}

tasks.register<Secp256k1InteropBenchmarkReportTask>("secp256k1InteropBenchmarkReport") {
    group = "jmh"
    description = "Runs the secp256k1 interop benchmark and writes a shareable report bundle."

    dependsOn("jmhSecp256k1Interop")
    resultsJson.set(secp256k1InteropResultsJson)
    summaryFile.set(secp256k1InteropSummary)
    metadataFile.set(secp256k1InteropMetadata)
    gitWorkingDirectory.set(rootProject.layout.projectDirectory)
    outputs.upToDateWhen { false }
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
