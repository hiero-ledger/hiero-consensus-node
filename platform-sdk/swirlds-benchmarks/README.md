# Swirlds Benchmarks

This module contains JMH benchmarks that can be run either through Gradle or
from IntelliJ IDEA.

## Configure a Run

Edit `settings.txt` in this directory before starting the benchmark. Relative
paths in that file, such as `data`, are resolved from the benchmark process
working directory.

## Run with Gradle

Run Gradle tasks with the full project path. For example, from the repository
root:

```shell
./gradlew :swirlds-benchmarks:jmhReconnect
```

Do not run only `jmhReconnect`. Gradle can match that task name in multiple
projects, which can start more than one reconnect benchmark. The full task path
runs only the benchmark from this module.

Available benchmark tasks:

```shell
./gradlew :swirlds-benchmarks:jmhCrypto
./gradlew :swirlds-benchmarks:jmhVirtualMapRead
./gradlew :swirlds-benchmarks:jmhVirtualMapEdit
./gradlew :swirlds-benchmarks:jmhReconnect
```

The configured JMH result files are written under this module's
`build/results/jmh` directory.

## Run with IntelliJ IDEA

Benchmark classes have a `main` method intended for local IDE runs and
profiling. To run one benchmark from IntelliJ:

1. Open the benchmark class, for example `ReconnectBench`.
2. Run the class's `main` method.
3. Edit the generated run configuration.
4. Set **Working directory** to:

   ```text
   $PROJECT_DIR$/platform-sdk/swirlds-benchmarks
   ```

The working directory is required. If IntelliJ uses another default working
directory, the benchmark will not load this module's `settings.txt`; default
settings may be used instead, and relative directories such as `data` and
`output` may be created outside `platform-sdk/swirlds-benchmarks`.

After the run starts, check `settingsUsed.txt` in this directory to verify that
the intended settings were loaded.
