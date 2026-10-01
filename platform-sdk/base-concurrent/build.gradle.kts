// SPDX-License-Identifier: Apache-2.0
import java.io.ByteArrayOutputStream
import java.io.OutputStream

plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
    id("org.hiero.gradle.feature.benchmark")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.test-timing-sensitive")
}

description = "Base Concurrent"

mainModuleInfo { annotationProcessor("com.swirlds.config.processor") }

jmhModuleInfo { requires("org.hiero.base.concurrent") }

testModuleInfo {
    requires("com.swirlds.base.test.fixtures")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.concurrent.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

timingSensitiveModuleInfo {
    requires("com.swirlds.base")
    requires("com.swirlds.logging")
    requires("com.swirlds.logging.test.fixtures")
    requires("org.hiero.base.concurrent")
    requires("org.hiero.base.concurrent.test.fixtures")
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}

// JMH prints UTF-8 symbols (approx-equal and plus-minus signs) regardless of the JVM settings,
// which are garbled
// by consoles that are not UTF-8. This stream keeps the console output ASCII-only.
class AsciiOutputStream(private val delegate: OutputStream) : OutputStream() {
    private val line = ByteArrayOutputStream()
    private val superscriptDigits = "\u2070\u00b9\u00b2\u00b3\u2074\u2075\u2076\u2077\u2078\u2079"

    override fun write(b: Int) {
        line.write(b)
        if (b == '\n'.code) flush()
    }

    override fun flush() = emit(force = false)

    override fun close() {
        emit(force = true)
        delegate.close()
    }

    // An incomplete UTF-8 sequence at the end (split across two writes) is kept until the rest
    // arrives,
    // unless force is set.
    private fun emit(force: Boolean) {
        val bytes = line.toByteArray()
        val end = if (force) bytes.size else completeUtf8Length(bytes)
        if (end > 0) {
            val ascii = StringBuilder()
            for (c in String(bytes, 0, end, Charsets.UTF_8)) {
                when {
                    c.code < 128 -> ascii.append(c)
                    c == '\u2248' -> ascii.append('~')
                    c == '\u00b1' -> ascii.append("+/-")
                    c == '\u207b' -> ascii.append('-')
                    c in superscriptDigits -> ascii.append('0' + superscriptDigits.indexOf(c))
                    else -> ascii.append('?')
                }
            }
            delegate.write(ascii.toString().toByteArray(Charsets.US_ASCII))
            line.reset()
            line.write(bytes, end, bytes.size - end)
        }
        delegate.flush()
    }

    // Length of the prefix of bytes that ends on a complete UTF-8 sequence.
    private fun completeUtf8Length(bytes: ByteArray): Int {
        val n = bytes.size
        for (k in 1..minOf(3, n)) {
            val b = bytes[n - k].toInt() and 0xFF
            if ((b and 0xC0) == 0x80) continue // continuation byte: keep looking for the lead byte
            if (b < 0x80) return n // ASCII: everything before is complete
            val length = if (b >= 0xF0) 4 else if (b >= 0xE0) 3 else 2
            return if (k < length) n - k else n
        }
        return n
    }
}

// Queue benchmarks. Optional properties:
//   -PjmhSmoke                               very short smoke run
//   -PjmhProfilers=gc[,perfnorm]             JMH profilers
//   -PjmhForks=3                             number of forks (default 1)
//   -PjmhGroups=producers1,producers16       run only these producer-count groups
//   -PjmhParams=impl=A,B;consumerTokens=0    override benchmark parameters (';' separates
// parameters)
val jmhSmoke = providers.gradleProperty("jmhSmoke").isPresent
val jmhProfilers =
    providers.gradleProperty("jmhProfilers").map { it.split(",") }.orElse(emptyList())
val jmhForks = providers.gradleProperty("jmhForks").orElse("1")
val jmhGroups = providers.gradleProperty("jmhGroups").map { it.split(",") }.orElse(emptyList())
val jmhParams = providers.gradleProperty("jmhParams").map { it.split(";") }.orElse(emptyList())

tasks.register<JavaExec>("jmhOutputQueue") {
    group = "jmh"
    description = "Runs OutputQueueBench"
    val jmhJar = tasks.named<AbstractArchiveTask>("jmhJarWithMergedServiceFiles")
    classpath(jmhJar.flatMap { it.archiveFile })
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    val results = layout.buildDirectory.file("results/jmh/results-output-queue.json")
    outputs.file(results)
    // A benchmark run is a measurement, not a build step: never skip it as UP-TO-DATE
    outputs.upToDateWhen { false }
    val groups = jmhGroups.get()
    val include =
        if (groups.isEmpty()) ".*OutputQueueBench.*"
        else ".*OutputQueueBench\\.(" + groups.joinToString("|") + ")$"
    args(listOf(include, "-f", jmhForks.get(), "-jvmArgs", "-Xmx4g", "-rf", "json"))
    jmhParams.get().filter { it.isNotBlank() }.forEach { args("-p", it.trim()) }
    args("-rff", results.get().asFile.absolutePath)
    if (jmhSmoke) {
        args("-wi", "0", "-i", "1", "-r", "200ms")
    } else {
        args("-wi", "3", "-w", "3s", "-i", "5", "-r", "5s")
    }
    jmhProfilers.get().forEach { args("-prof", it) }
    doFirst {
        results.get().asFile.parentFile.mkdirs()
        standardOutput = AsciiOutputStream(System.out)
    }
}
