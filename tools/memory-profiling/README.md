# HAPI Tests Memory Profiling

Measures peak memory usage of HAPI test suites using Docker cgroup metrics. The goal is to determine the minimum CI runner size needed for each test suite.

## Prerequisites

- Docker Desktop (with at least 16 GB allocated to Docker)
- Bash shell

## Directory Structure

```
tools/memory-profiling/
├── Dockerfile                  # Temurin 25 image (matches CI)
├── docker-compose.yml          # Container config with 16G limit
├── entrypoint.sh               # Container entrypoint (UID remapping, runs once)
├── measure-cgroup-peak.sh      # Reads cgroup memory.peak inside container
├── run-test.sh                 # Core runner: container up -> test -> save result -> container down
├── collect-results.sh          # Aggregates results into a summary table + CSV
├── run-all-suites.sh           # Runs all 9 suite scripts sequentially
├── run-all-subtasks.sh         # Runs all 22 subtask scripts individually
├── results/                    # Output directory (persists across runs, git-ignored)
├── suites/                     # Suite-level scripts (match CI jobs exactly)
│   ├── crypto.sh
│   ├── misc.sh
│   ├── misc-records.sh
│   ├── token.sh
│   ├── time-consuming.sh
│   ├── state-throttling.sh
│   ├── simple-fees.sh
│   ├── smart-contracts.sh
│   └── atomic-batch.sh
└── subtasks/                   # Individual Gradle task scripts
    ├── crypto/                 # hapiTestCrypto, hapiTestCryptoEmbedded, hapiTestCryptoSerial
    ├── misc/                   # hapiTestMisc, hapiTestMiscEmbedded, hapiTestMiscRepeatable, hapiTestMiscSerial
    ├── misc-records/           # hapiTestMiscRecords, hapiTestMiscRecordsSerial
    ├── token/                  # hapiTestToken, hapiTestTokenSerial
    ├── time-consuming/         # hapiTestTimeConsuming, hapiTestTimeConsumingSerial
    ├── state-throttling/       # hapiTestStateThrottling
    ├── simple-fees/            # hapiTestSimpleFees, hapiEmbeddedSimpleFees, hapiTestSimpleFeesSerial
    ├── smart-contracts/        # hapiTestSmartContract, hapiTestSmartContractSerial
    └── atomic-batch/           # hapiTestAtomicBatch, hapiTestAtomicBatchSerial, hapiTestAtomicBatchEmbedded
```

## How It Works

Each test runs in a fresh Docker container with a 16 GB memory limit. When the test completes, the script reads the kernel's cgroup `memory.peak` value — the true high-water mark of all memory used by every process in the container. This is the same metric the OS uses to decide whether to OOM-kill, so it maps directly to runner sizing.

A fresh container is created per test to ensure the cgroup peak counter starts at zero.

## Usage

All commands are run from the `tools/memory-profiling/` directory:

```bash
cd tools/memory-profiling
```

### Run a single suite (chains subtasks like CI does)

```bash
./suites/crypto.sh
```

This runs `hapiTestCrypto && hapiTestCryptoEmbedded && hapiTestCryptoSerial` sequentially in one container, matching how CI executes the Crypto job. The peak memory reflects the combined run.

### Run a single subtask in isolation

```bash
./subtasks/crypto/hapiTestCrypto.sh
```

This runs only `hapiTestCrypto` in its own fresh container, so you can see how much memory that specific Gradle task uses on its own.

### Run all 9 suites

```bash
./run-all-suites.sh
```

### Run all 22 subtasks individually

```bash
./run-all-subtasks.sh
```

### Run specific suites selectively

```bash
./suites/crypto.sh
./suites/token.sh
./suites/smart-contracts.sh
```

Results accumulate in `results/` across runs — they are not cleared automatically.

### Testing with different memory limits

To test whether a suite fits within a smaller runner, override the memory limit:

```bash
# Edit docker-compose.yml and change the memory limit
# deploy.resources.limits.memory: 8G

# Or run the container directly with a custom limit
docker compose run --rm --memory=8g memory-measure \
  /usr/local/bin/measure-cgroup-peak.sh bash -lc './gradlew hapiTestCrypto --no-daemon'
```

If the container gets OOM-killed, the suite needs more than that amount of RAM.

## Viewing Results

### Summary table

```bash
./collect-results.sh
```

Prints a formatted table to the terminal:

```
================================================================
  MEMORY PROFILING RESULTS SUMMARY
================================================================
TEST                                          PEAK(MiB) PEAK(GiB)    TIME(s)   EXIT
----                                          ---------  ---------   -------   ----
crypto-full                                       8192       8.0        1234      0
crypto-hapiTestCrypto                             5120       5.0         456      0
crypto-hapiTestCryptoEmbedded                     3072       3.0         321      0
...
================================================================
```

And writes `results/SUMMARY.csv` which you can import into a spreadsheet.

### CSV output

Open `results/SUMMARY.csv` directly in Excel, Google Sheets, or any spreadsheet tool. Columns:

|     Column      |        Description        |
|-----------------|---------------------------|
| `test_name`     | Test identifier           |
| `peak_mib`      | Peak memory in MiB        |
| `peak_gib`      | Peak memory in GiB        |
| `end_mib`       | Memory at end of test (MiB) |
| `end_gib`       | Memory at end of test (GiB) |
| `duration_secs` | Wall-clock duration       |
| `exit_code`     | 0 = pass, non-zero = fail |
| `timestamp`     | UTC time of measurement   |
| `git_sha`       | Commit that was tested    |

### Individual result files

Each test writes a key=value file to `results/<test_name>.txt`:

```
test_name=crypto-hapiTestCrypto
timestamp=2026-03-30T14:22:01Z
git_sha=6d7b67f
cgroup_version=v2
peak_bytes=8589934592
peak_mib=8192
peak_gib=8.0
end_mib=4096
end_gib=4.0
duration_secs=1234
exit_code=0
```

## CI Runner Mapping

For reference, these are the current CI runner assignments:

|      Suite       |     CI Runner     |    Size     |
|------------------|-------------------|-------------|
| Crypto           | hl-cn-hapi-lin-lg | Large       |
| Misc             | hl-cn-hapi-lin-md | Medium      |
| Misc Records     | hl-cn-hapi-lin-xl | Extra Large |
| Token            | hl-cn-hapi-lin-xl | Extra Large |
| Time Consuming   | hl-cn-hapi-lin-lg | Large       |
| State Throttling | hl-cn-hapi-lin-md | Medium      |
| Simple Fees      | hl-cn-hapi-lin-md | Medium      |
| Smart Contracts  | hl-cn-hapi-lin-lg | Large       |
| Atomic Batch     | hl-cn-hapi-lin-lg | Large       |

Compare the measured peak memory against each runner's available RAM to determine if a runner is oversized or undersized.

## Clearing Results

To start fresh:

```bash
rm results/*.txt results/SUMMARY.csv
```

## Troubleshooting

**Docker build fails with UID conflict**: The Dockerfile handles existing UID 1000 users in the base image. If you still hit issues, run `docker compose build --no-cache` from the `tools/memory-profiling/` directory.

**Container runs out of memory (OOM-killed)**: Increase the memory limit in `docker-compose.yml` under `deploy.resources.limits.memory`. The default is 16G.

**Tests fail but memory is still recorded**: The measurement script captures peak memory regardless of test exit code. A failed test's memory reading is still valid for runner sizing purposes.
