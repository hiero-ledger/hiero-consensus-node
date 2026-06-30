# swirlds-benchmarks

This module contains JMH benchmarks for platform SDK virtual map and reconnect
workloads. They cover crypto-transfer-like operations over virtual maps, virtual
map read and edit cycles, and reconnect synchronization between learner and
teacher map states.

## Available Benchmarks

### CryptoBench

Exercises crypto-transfer-like workloads over a virtual map. Each transfer reads
account-like records, updates balances, and copies the map across iterations.

Benchmarks:

- `CryptoBench.transferSerial`: performs the transfer flow on a single thread.
- `CryptoBench.transferPrefetch`: prefetches records before applying ordered
  transfer updates.
- `CryptoBench.transferParallel`: uses parallel tasks for the transfer flow.

### VirtualMapReadBench

Builds a virtual map with a configured key population and measures parallel
random reads from it.

Benchmarks:

- `VirtualMapReadBench.read`: reads random records from the pre-created map.

### VirtualMapEditBench

Measures virtual map edit cycles. The benchmark copies the map between
iterations to exercise the copy and flush behavior used by the platform.

Benchmarks:

- `VirtualMapEditBench.update`: reads, updates, creates, and sometimes removes
  records.
- `VirtualMapEditBench.create`: creates or replaces records.
- `VirtualMapEditBench.delete`: updates records while removing expired entries.

### ReconnectBench

Builds learner and teacher virtual map states with configurable differences,
then measures reconnect synchronization between them. It can also emulate
storage and network delays.

Benchmarks:

- `ReconnectBench.reconnect`: runs reconnect synchronization from the learner
  state to the teacher state.

## Run with Gradle

Run Gradle tasks with the full project path. For example, from the repository
root:

```shell
./gradlew :swirlds-benchmarks:jmhCrypto
```

Use the full project path for every benchmark task to avoid matching tasks with
the same name in other Gradle projects. For example, run
`./gradlew :swirlds-benchmarks:jmhReconnect`, not `./gradlew jmhReconnect`;
the short task name can also match other `jmhReconnect` tasks and start
additional benchmarks.

Available benchmark tasks:

```shell
./gradlew :swirlds-benchmarks:jmhCrypto
./gradlew :swirlds-benchmarks:jmhVirtualMapRead
./gradlew :swirlds-benchmarks:jmhVirtualMapEdit
./gradlew :swirlds-benchmarks:jmhReconnect
```

The Gradle tasks are curated run configurations. For example,
`:swirlds-benchmarks:jmhCrypto` currently runs `CryptoBench.transferPrefetch`.
Use the JMH JAR include patterns, or adjust the Gradle task configuration, to
run `CryptoBench.transferSerial` or `CryptoBench.transferParallel`.

These tasks keep normal JMH forking enabled and pass `-Xmx16g` to the forked
benchmark JVM. Their JMH result files are written under this module's
`build/results/jmh` directory.

## Run from an IDE

Benchmark classes have a `main` method intended for local IDE runs and
profiling. To run one benchmark from an IDE:

1. Open the benchmark class, for example `ReconnectBench`.
2. Run the class's `main` method.
3. Edit the generated run configuration.
4. Set the working directory to:

   ```text
   <repo>/platform-sdk/swirlds-benchmarks
   ```

The working directory is required. The benchmark loads `settings.txt` from the
process working directory. If the IDE uses another default working directory,
this module's `settings.txt` will not be loaded; default settings may be used
instead, and relative directories such as `data` and `output` may be created
outside `platform-sdk/swirlds-benchmarks`.

IDE main-method runs use `.forks(0)`, so the benchmark runs in the IDE process.
This is useful for profiling because the profiler attaches directly to the
benchmark workload. It is not identical to the Gradle tasks, which fork a JMH
worker process.

## Run from the JMH JAR

Build the JMH uber JAR from the repository root:

```shell
./gradlew :swirlds-benchmarks:jmhJar
```

Then run it from this module directory so `settings.txt` and relative output
paths are resolved correctly. Pass a JMH include pattern to choose the
benchmark:

```shell
cd platform-sdk/swirlds-benchmarks
java -jar build/libs/*-jmh.jar CryptoBench.transferSerial
java -jar build/libs/*-jmh.jar CryptoBench.transferParallel
java -jar build/libs/*-jmh.jar VirtualMapReadBench.read
java -jar build/libs/*-jmh.jar ReconnectBench
```

The JMH JAR uses JMH CLI behavior. By default this is closer to the Gradle
tasks than to the IDE main methods because JMH can fork worker JVMs. Use JMH CLI
options, such as `-f`, `-p`, warmup, and measurement options, when a run needs a
different JMH configuration.

## Configure Settings

Edit `settings.txt` in this directory before starting the benchmark. The
benchmark reads this file from the process working directory, so the working
directory must be `platform-sdk/swirlds-benchmarks` for local runs that should
use this module's settings.

The file can contain both benchmark settings and platform settings used by the
loaded config types, including `virtualMap.*`, `merkleDb.*`, `metrics.*`, and
`crypto.*`.

The benchmark-specific settings are:

|              Setting               |                                                                                                                    Description                                                                                                                     |
|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `benchmark.benchmarkData`          | Base directory for benchmark state. If this setting is empty or omitted, each run uses a temporary directory. If set, the benchmark uses `<value>/<BenchmarkName>`.                                                                                |
| `benchmark.saveDataDirectory`      | Keeps benchmark state after the run. If `false`, the benchmark data directory is deleted during teardown.                                                                                                                                          |
| `benchmark.verifyResult`           | Enables benchmark result verification where supported.                                                                                                                                                                                             |
| `benchmark.enableSnapshots`        | Enables periodic virtual map snapshots for benchmarks that support snapshots.                                                                                                                                                                      |
| `benchmark.printHistogram`         | Prints a class histogram during invocation teardown.                                                                                                                                                                                               |
| `benchmark.csvOutputFolder`        | Directory for CSV metric output. If empty, CSV files are written into the active benchmark data directory, which is either `<benchmark.benchmarkData>/<BenchmarkName>` or a temporary benchmark directory when `benchmark.benchmarkData` is empty. |
| `benchmark.csvMetricsFileName`     | CSV file name for metric samples. Default is `BenchmarkMetrics.csv`.                                                                                                                                                                               |
| `benchmark.csvMetricNamesFileName` | CSV file name for metric names. Default is `BenchmarkMetricNames.csv`.                                                                                                                                                                             |
| `benchmark.csvWriteFrequency`      | Metric write frequency in milliseconds. `0` disables periodic metric writes.                                                                                                                                                                       |
| `benchmark.csvAppend`              | Appends to an existing metrics CSV instead of replacing it.                                                                                                                                                                                        |
| `benchmark.deviceName`             | Linux block device name used for disk metrics. Set it to the device that hosts the benchmark data, for example `sda` for `/sys/block/sda`.                                                                                                         |

JMH `@Param` values such as `numFiles`, `numRecords`, `maxKey`, `numThreads`,
and the reconnect delay probabilities are JMH parameters, not `settings.txt`
properties. Change them with JMH options such as `-p` when using the JMH JAR, or
by adjusting the Gradle or IDE JMH configuration.

## After a Run

Expect these artifacts relative to the benchmark working directory unless an
absolute path is configured:

|            Artifact             |                                                   Description                                                   |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `settingsUsed.txt`              | Export of the resolved configuration. Check this first when a run does not appear to use the expected settings. |
| `data/`                         | Default location used by this module's `settings.txt` for benchmark state and CSV metrics.                      |
| `data/<BenchmarkName>/`         | Benchmark state directory when `benchmark.benchmarkData` is `data`.                                             |
| `data/BenchmarkMetrics.csv`     | Metric samples when `benchmark.csvOutputFolder` is `data`.                                                      |
| `data/BenchmarkMetricNames.csv` | Metric name lookup file when `benchmark.csvOutputFolder` is `data`.                                             |
| `build/results/jmh/`            | JMH text result files from the curated Gradle tasks.                                                            |
| `output/`                       | Log files from the benchmark `log4j2.xml`, such as `hgcaa.log`, `swirlds.log`, and `swirlds-vmap.log`.          |

If `settingsUsed.txt`, `data`, or `output` appear elsewhere, the benchmark was
started with a different working directory.
