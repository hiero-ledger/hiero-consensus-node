# Swirlds Benchmarks

## Running benchmarks via Gradle

Run benchmarks using the named Gradle tasks from the terminal:

```bash
./gradlew :platform-sdk:swirlds-benchmarks:jmhCrypto
./gradlew :platform-sdk:swirlds-benchmarks:jmhVirtualMap
./gradlew :platform-sdk:swirlds-benchmarks:jmhReconnect
./gradlew :platform-sdk:swirlds-benchmarks:jmhDataFileCollection
./gradlew :platform-sdk:swirlds-benchmarks:jmhHalfDiskMap
./gradlew :platform-sdk:swirlds-benchmarks:jmhKeyValueStore
```

Each task's parameters and JVM args are configured in `build.gradle.kts`. Parameters not explicitly set in a task fall back to `@Param` annotation defaults in the benchmark source.

Results are written to `build/results/jmh/`.

> **Note:** Do not use the IntelliJ gutter run icon — it runs the plugin's default `jmh` task, which may pick up benchmarks from other modules.
