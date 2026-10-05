// SPDX-License-Identifier: Apache-2.0
import me.champeau.jmh.ConcurrentExecutionControlBuildService
import me.champeau.jmh.JMHTask

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

// Optional properties of jmhOutputQueue:
//   -PjmhProfilers=gc[,perfnorm]           JMH profilers
//   -PjmhForks=3                           number of forks (default 1)
//   -PjmhGroups=producers1,producers16     run only these producer-count groups
//   -PjmhParams=impl=A,B;consumerTokens=0  override benchmark parameters; ';' separates them
val jmhProfilers =
    providers.gradleProperty("jmhProfilers").map { it.split(",") }.orElse(emptyList())
val jmhForks = providers.gradleProperty("jmhForks").orElse("1")
val jmhGroups = providers.gradleProperty("jmhGroups").map { it.split(",") }.orElse(emptyList())
val jmhParams = providers.gradleProperty("jmhParams").map { it.split(";") }.orElse(emptyList())

// Setup shared by the two tasks below. Like jmhSmoke in consensus-hashgraph-impl, they run JMH's
// main class on the benchmark jar instead of using the JMH plugin's task, so that the options above
// can be set from the command line.
fun JavaExec.runsJmh() {
    group = "jmh"
    // the jar alone, so that a run also catches packaging errors
    classpath(tasks.named("jmhJarWithMergedServiceFiles"))
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    // without it, JMH exits successfully when a benchmark throws
    args("-foe", "true")
    // one JMH run at a time across the build; parallel runs fail on JMH's lock file
    usesService(ConcurrentExecutionControlBuildService.restrict(JMHTask::class.java, gradle))
}

// Full measurement run of OutputQueueBench, configured with the properties above.
tasks.register<JavaExec>("jmhOutputQueue") {
    runsJmh()
    description = "Runs OutputQueueBench and writes the results to build/results/jmh."
    val results = layout.buildDirectory.file("results/jmh/results-output-queue.json")
    outputs.file(results)
    // a benchmark run is a measurement, not a build step: never skip it as UP-TO-DATE
    outputs.upToDateWhen { false }
    // JMH include pattern: the whole benchmark, or only the requested producer-count groups
    val groups = jmhGroups.get()
    val include =
        if (groups.isEmpty()) ".*OutputQueueBench.*"
        else ".*OutputQueueBench\\.(" + groups.joinToString("|") + ")$"
    args(include, "-f", jmhForks.get(), "-jvmArgs", "-Xmx4g")
    args("-wi", "3", "-w", "3s", "-i", "5", "-r", "5s")
    jmhParams.get().filter { it.isNotBlank() }.forEach { args("-p", it.trim()) }
    jmhProfilers.get().forEach { args("-prof", it) }
    args("-rf", "json", "-rff", results.get().asFile.absolutePath)
}

// a new commit changes only git.properties in the benchmark jar; keep the smoke run cached
normalization { runtimeClasspath { ignore("git.properties") } }

// Smoke run used by CI ("gradle jmhSmoke" runs it in every module that has it): every benchmark
// once,
// briefly, failing on any error. The token parameters only change the simulated per-message work,
// so
// they are fixed to 0 to keep the run short; all queue implementations and producer counts still
// run.
tasks.register<JavaExec>("jmhSmoke") {
    runsJmh()
    description = "Runs every benchmark once, briefly, and fails on any error."
    args("-f", "1", "-wi", "0", "-i", "1", "-r", "100ms")
    args("-p", "consumerTokens=0", "-p", "producerTokens=0")
    // a relative path keeps the cache key independent of the checkout location
    workingDir = layout.buildDirectory.dir("results/jmh-smoke").get().asFile
    args("-rf", "json", "-rff", "results.json")
    outputs.file(layout.buildDirectory.file("results/jmh-smoke/results.json"))
    outputs.cacheIf("a smoke run only checks that the benchmarks work") { true }
}
