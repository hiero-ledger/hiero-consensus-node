# Benchmark Output and Analysis Guide

This guide explains the output of the `BlockStreamingBenchmark` and how to analyze the resulting Java Flight Recorder (JFR) data.

## Output

When you run the benchmark, you will see two primary outputs:

1. **Console Output:** The benchmark results (throughput, latency) and GC allocation rates are printed directly to the console.
2. **JFR Recording:** A binary recording file saved in:

   ```
   hedera-node/hedera-app/src/jmh/java/com/hedera/node/app/blocks/jfr/
   ```

### JFR Filename Format

Files are named based on key parameters:

```
bench-lat<latency>-bw<bandwidth>-buf<maxBlocks>-http<windowSize>-grpc<bufferSize>.jfr
```

Example: `bench-lat20-bw1000-buf150-http65535-grpc512.jfr`

## How to Convert JFR Files to JSON

The binary `.jfr` files are not human-readable. Use the `jfr` tool (bundled with your JDK) to convert them.

### Convert All JFR Files to JSON

In the JFR directory run:

```bash
for f in *.jfr; do
  jfr print --events "jdk.SocketRead,jdk.SocketWrite,jdk.GarbageCollection,jdk.JavaMonitorEnter" \
    --json "$f" > "${f%.jfr}.json"
done
```

This creates a JSON file for each JFR file with the same name.
