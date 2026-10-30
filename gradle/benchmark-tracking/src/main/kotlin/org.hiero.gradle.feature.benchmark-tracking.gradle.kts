// SPDX-License-Identifier: Apache-2.0
import me.champeau.jmh.ConcurrentExecutionControlBuildService
import me.champeau.jmh.JMHTask

/*
 * Tracks the benchmarks of a module: they are smoke-run on every pull request, run nightly on the benchmark machine,
 * and published to Grafana. A module opts in by applying this plugin after org.hiero.gradle.feature.benchmark and
 * names its tracked benchmarks with `jmh { includes.convention(listOf("...")) }`. The run configuration (forks, warmup,
 * measurement, heap) comes from the benchmarks' annotations; see docs/consensus-layer/benchmarks/ for the conventions.
 *
 * - `jmh` runs the tracked benchmarks with JSON output and the latency profiler; -PjmhTests=<pattern> runs others.
 * - `jmhSmoke` runs every benchmark of the module once, briefly, from the packaged jar, and fails on any error. It is
 *   cacheable by the jar's content, so a pull request that leaves the jar unchanged costs nothing.
 * - `jmhTracked` builds the benchmark jar and writes build/jmh/tracked.properties, which the nightly job reads.
 */
plugins { id("me.champeau.jmh") }

check(plugins.hasPlugin("org.hiero.gradle.feature.benchmark")) {
    "Apply org.hiero.gradle.feature.benchmark before org.hiero.gradle.feature.benchmark-tracking"
}

interface BenchmarkTrackingExtension {
    /**
     * JVM arguments of the smoke run's forked JVMs. They replace the benchmarks' own `jvmArgsAppend`, so one value
     * caps the memory of every benchmark in the jar (default: a 2 GB heap).
     */
    val smokeJvmArgs: Property<String>

    /**
     * Further JMH arguments of the smoke run, for example "-p", "numNodes=4" to run one representative parameter set
     * of a benchmark with an expensive setup.
     */
    val smokeArgs: ListProperty<String>
}

val benchmarkTracking = extensions.create<BenchmarkTrackingExtension>("benchmarkTracking")

benchmarkTracking.smokeJvmArgs.convention("-Xms2g -Xmx2g")

jmh {
    resultFormat = "JSON"
    // tracked benchmarks report their latency through this profiler and fail without it
    profilers.add("org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler")
    // without it, JMH exits successfully when a benchmark throws; no effect until the JMH plugin
    // passes "-foe true", not "-foe 1" (https://github.com/melix/jmh-gradle-plugin/issues/255)
    failOnError = true
}

// a new commit changes only git.properties in the benchmark jar; keep the smoke run cached
normalization { runtimeClasspath { ignore("git.properties") } }

val jmhJar = tasks.named<Jar>("jmhJarWithMergedServiceFiles")

tasks.register<JavaExec>("jmhSmoke") {
    group = "jmh"
    description = "Runs every benchmark once, briefly, and fails on any error."
    // the jar alone, so that the smoke run also catches packaging errors
    classpath(jmhJar)
    mainClass = "org.openjdk.jmh.Main"
    args("-f", "1", "-wi", "0", "-i", "1", "-r", "100ms")
    // without it, JMH exits successfully when a benchmark throws
    args("-foe", "true")
    // locals, not script members: the configuration cache cannot serialize a lambda that captures the script
    val profilers = jmh.profilers
    val smokeJvmArgs = benchmarkTracking.smokeJvmArgs
    val smokeArgs = benchmarkTracking.smokeArgs
    argumentProviders.add(
        CommandLineArgumentProvider {
            profilers.get().flatMap { listOf("-prof", it) } +
                listOf("-jvmArgsAppend", smokeJvmArgs.get()) +
                smokeArgs.get()
        }
    )
    // a relative path keeps the cache key independent of the checkout location
    workingDir = layout.buildDirectory.dir("results/jmh-smoke").get().asFile
    args("-rf", "json", "-rff", "results.json")
    outputs.file(layout.buildDirectory.file("results/jmh-smoke/results.json"))
    outputs.cacheIf("a smoke run only checks that the benchmarks work") { true }
    // one JMH run at a time across the build, shared with the jmh tasks; smoke runs of several
    // modules would otherwise run in parallel and fail on JMH's lock file
    usesService(ConcurrentExecutionControlBuildService.restrict(JMHTask::class.java, gradle))
}

tasks.register("jmhTracked") {
    group = "jmh"
    description = "Builds the benchmark jar and writes the manifest of the tracked benchmarks for the nightly job."
    val jarFile = jmhJar.flatMap { it.archiveFile }
    val includes = jmh.includes
    val profilers = jmh.profilers
    val moduleName = project.name
    val manifest = layout.buildDirectory.file("jmh/tracked.properties")
    inputs.file(jarFile)
    inputs.property("includes", includes)
    inputs.property("profilers", profilers)
    outputs.file(manifest)
    doLast {
        check(includes.get().isNotEmpty()) {
            "$moduleName tracks no benchmark: name them with jmh { includes.convention(listOf(...)) }"
        }
        manifest
            .get()
            .asFile
            .writeText(
                """
                module=$moduleName
                jar=${jarFile.get().asFile.absolutePath}
                includes=${includes.get().joinToString(",")}
                profilers=${profilers.get().joinToString(",")}
                """
                    .trimIndent() + "\n"
            )
    }
}
