# Benchmark Output and Analysis Guide

This guide explains the output of the `BlockStreamingBenchmark` and how to analyze the resulting Java Flight Recorder (JFR) data.

## Output

When you run the benchmark, you will see two primary outputs:

1. **Console Output:** The benchmark results (throughput, latency) and GC allocation rates are printed directly to the console.
2. **JFR Recording:** A binary recording file will be saved in a specific directory structure created by JMH to avoid overwriting results from different parameter combinations.
   * **Location:** The file will be deeply nested in a folder named after the benchmark parameters, e.g.:
     `./com.hedera.node.app.blocks.BlockStreamingBenchmark.streamBlocks-SingleShotTime-.../profile.jfr`

## 3. How to Generate JSON Analysis

The binary `.jfr` file is not human-readable. You must use the `jfr` tool (bundled with your JDK) to extract relevant events (Network IO, GC, Locks) into a readable JSON format.

### Command

Run the following command in your terminal. You can use a wildcard (`*`) to locate the JFR file without typing the full directory path:

```bash
jfr print --events "jdk.SocketRead,jdk.GarbageCollection,jdk.JavaMonitorEnter" --json **/profile.jfr > analysis.json
```
