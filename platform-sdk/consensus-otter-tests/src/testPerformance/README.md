# Performance Benchmarks

This module contains performance benchmarks for the consensus layer, along with a Grafana + VictoriaMetrics visualization stack for analyzing metrics.

## Running Benchmarks

### Single run via Gradle

```bash
./gradlew :consensus-otter-tests:testPerformance
```

This runs the `ConsensusLayerBenchmark` test which:
- Spins up a 4-node network in containers
- Submits a warm-up phase of 1000 empty transactions
- Submits 1000 benchmark transactions at 20 ops/s
- Measures and reports consensus latency

Metrics are written to:

```
build/container/ConsensusLayerBenchmark/benchmark/node-*/data/stats/metrics.txt
```

### Single run and start Grafana

```bash
./gradlew :consensus-otter-tests:benchmarkAndVisualize
```

This runs the benchmarks first, then automatically starts the Grafana visualization stack with the results.

You can also point to a custom metrics location:

```bash
./gradlew :consensus-otter-tests:benchmarkAndVisualize -PmetricsPath="path/to/metrics.txt"
```

To run only a specific experiment and then visualize, use `-PtestFilter` (note the `=` is required):

```bash
./gradlew :consensus-otter-tests:benchmarkAndVisualize -PtestFilter="*.CombinedOptimizationsExperiment"
```

### Running a single experiment

Use `--tests` to run a specific experiment (or `-PtestFilter` which also works with `benchmarkAndVisualize`):

```bash
# Run only the baseline benchmark
./gradlew :consensus-otter-tests:testPerformance --tests "*.ConsensusLayerBenchmark"

# Run only a specific experiment
./gradlew :consensus-otter-tests:testPerformance --tests "*.CombinedOptimizationsExperiment"

# Equivalent using -PtestFilter (works with both testPerformance and benchmarkAndVisualize)
./gradlew :consensus-otter-tests:testPerformance -PtestFilter="*.MaxOtherParentsExperiment"

# Run a single test method within an experiment
./gradlew :consensus-otter-tests:testPerformance --tests "*.CombinedOptimizationsExperiment.benchmarkCombinedOptimizations"
```

Available experiments:
- `ConsensusLayerBenchmark` — Baseline benchmark (defaults)
- `AntiSelfishnessExperiment` — Anti-selfishness factor variations
- `CreationAttemptRateExperiment` — Event creation attempt rate variations
- `MaxCreationRateExperiment` — Max event creation rate variations
- `MaxOtherParentsExperiment` — Max other parents variations
- `SignatureSchemeExperiment` — Signature scheme comparisons (RSA vs EC)
- `CombinedOptimizationsExperiment` — Combined best settings

### Multiple runs via shell script

The `run-benchmark.sh` script runs experiments multiple times, extracts the average latency from
each run, and saves all artifacts (logs, CSVs, metrics) to `~/benchmark-results/` with timestamped
directories:

```bash
src/testPerformance/run-benchmark.sh [experiment] [num_runs]
```

- `experiment`: Which experiment to run (default: `all`)
  - `benchmark`, `maxotherparents`, `antiselfishness`, `maxcreationrate`, `signature`, `combined`, or `all`
- `num_runs`: Number of times to run the experiment(s) (default: `1`)

Each run produces a directory like `~/benchmark-results/20260216-143022_testName_experiment_avg-42/` containing:
- `swirlds-node-*.log` and `otter-node-*.log` — node logs
- `stats/MainNetStats*.csv` — CSV statistics
- `stats/metrics-node-*.txt` — per-node metrics in Prometheus format

Examples:

```bash
# Run all experiments once
src/testPerformance/scripts/run-benchmark.sh

# Run all experiments 5 times each
src/testPerformance/scripts/run-benchmark.sh all 5

# Run only the combined experiment 3 times
src/testPerformance/scripts/run-benchmark.sh combined 3

# Run only the baseline benchmark once
src/testPerformance/scripts/run-benchmark.sh benchmark
```

## Starting Grafana Independently

### With default metrics location

```bash
./gradlew :consensus-otter-tests:startGrafana
```

This imports metrics from the default path (`build/container/ConsensusLayerBenchmark/benchmark/node-*/data/stats/metrics.txt`).

### With a custom metrics location

```bash
./gradlew :consensus-otter-tests:startGrafana -PmetricsPath="/absolute/path/to/metrics.txt"
```

### Using the shell script directly

```bash
cd platform-sdk/consensus-otter-tests
src/testPerformance/scripts/start-grafana.sh [--keep-data] [paths...]
```

The script accepts files, directories, or glob patterns as arguments. When given a directory, it
automatically finds all `metrics*.txt` files inside it. This makes it easy to load results from
multiple benchmark runs at once — just point it at the results directories.

Examples:

```bash
# Import from a specific file
src/testPerformance/scripts/start-grafana.sh /tmp/my-run/node-0/metrics.txt

# Point to a results directory (finds all metrics*.txt files inside)
src/testPerformance/scripts/start-grafana.sh ~/benchmark-results/20260216-143022_avg-42/stats/

# Load multiple runs at once by passing several directories
src/testPerformance/scripts/start-grafana.sh \
  ~/benchmark-results/20260216-143022_avg-42/stats/ \
  ~/benchmark-results/20260216-150512_avg-38/stats/

# Use a glob to load all saved runs
src/testPerformance/scripts/start-grafana.sh ~/benchmark-results/*/stats/

# Append metrics from a new run without losing previously imported data
src/testPerformance/scripts/start-grafana.sh --keep-data ~/benchmark-results/20260216-160000_avg-40/stats/
```

## Helper scripts

To look for metrics from previous runs and choose which ones to import, use:
```bash
src/testPerformance/scripts/find-metrics.sh
```

To import metrics to an already running Grafana stack without restarting:
```bash
src/testPerformance/scripts/import-metrics.sh
```

To upload the dashboard JSON to Grafana (only needed if you want to modify the dashboard or start from a clean Grafana
without preloaded dashboards):
```bash
src/testPerformance/scripts/upload-dashboards.sh
```

## Accessing the Visualization

Once started, the stack is available at:

- **Grafana Dashboard**: http://localhost:3000/d/consensus-metrics (no login required)
- **VictoriaMetrics**: http://localhost:8428

The dashboard provides:
- A **node** variable to filter by specific nodes
- A **metric** variable to select which metric to visualize

## Stopping the Stack

```bash
src/testPerformance/scripts/stop-grafana.sh
```

This removes both containers, the data volume, and the Docker network.
