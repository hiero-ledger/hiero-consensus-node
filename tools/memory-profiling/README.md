# HAPI Tests Memory Profiling

Measures peak memory usage of HAPI test suites using Docker cgroup metrics. The goal is to determine the minimum CI runner size needed for each test suite.

## Prerequisites

- Docker Desktop (with at least 18 GB RAM and 10 CPUs allocated to Docker)
- Bash shell

## Directory Structure

```
tools/memory-profiling/
├── Dockerfile                  # Temurin 25 image (matches CI)
├── docker-compose.yml          # Container config with 10 CPU / 18G RAM limit
├── entrypoint.sh               # Container entrypoint (UID remapping, runs once)
├── measure-cgroup-peak.sh      # Reads cgroup memory.peak inside container
├── run-test.sh                 # Core runner: container up -> test -> save result -> container down
├── collect-results.sh          # Aggregates results into a summary table + CSV
├── run-all-suites.sh           # Runs all 8 suite scripts sequentially
├── run-all-subtasks.sh         # Runs all subtask scripts individually
├── results/                    # Output directory (persists across runs, git-ignored)
├── suites/                     # Suite-level scripts (one per HAPI CI job in zxc-execute-hapi-tests.yaml)
│   ├── misc.sh                          # HAPI Tests (Misc)
│   ├── misc-records-crypto.sh           # HAPI Tests (Misc Records, Crypto & Misc Serial)
│   ├── token-time-consuming.sh          # HAPI Tests (Token & Time Consuming)
│   ├── simple-fees-nd-reconnect.sh      # HAPI Tests (Simple Fees & ND Reconnect)
│   ├── smart-contracts-iss.sh           # HAPI Tests (Smart Contracts & ISS)
│   ├── restart.sh                       # HAPI Tests (Restart)
│   ├── atomic-batch.sh                  # HAPI Tests (Atomic Batch)
│   └── state-throttling.sh              # HAPI Tests (State Throttling)
└── subtasks/                   # Individual Gradle task scripts (not kept in sync with CI groupings)
```

## How It Works

Each test runs in a fresh Docker container limited to 10 CPUs and 18 GB RAM (matching the candidate runner config). When the test completes, the script reads the kernel's cgroup `memory.peak` value — the true high-water mark of all memory used by every process in the container (anonymous RSS + file cache + kernel accounting). This is the same metric the OS uses to decide whether to OOM-kill, so it maps directly to runner sizing.

A fresh container is created per test to ensure the cgroup peak counter starts at zero.

## Usage

All commands are run from the `tools/memory-profiling/` directory:

```bash
cd tools/memory-profiling
```

### Run a single suite (chains subtasks like CI does)

```bash
./suites/misc-records-crypto.sh
```

This runs the full `hapiTestMiscRecords && hapiTestMiscRecordsSerial && hapiTestCrypto && hapiTestCryptoEmbedded && hapiTestCryptoSerial && hapiTestMiscSerial` chain in one container, matching how CI executes that job. The peak memory reflects the combined run.

### Run all 8 suites

```bash
./run-all-suites.sh
```

### Run specific suites selectively

```bash
./suites/token-time-consuming.sh
./suites/smart-contracts-iss.sh
./suites/simple-fees-nd-reconnect.sh
```

Results accumulate in `results/` across runs — they are not cleared automatically.

### Testing with different memory limits

To test whether a suite fits within a smaller runner, override the memory limit:

```bash
# Edit docker-compose.yml and change the memory limit
# deploy.resources.limits.memory: 12G

# Or run the container directly with a custom limit
docker compose run --rm --memory=12g --cpus=8 memory-measure \
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

|     Column      |         Description         |
|-----------------|-----------------------------|
| `test_name`     | Test identifier             |
| `peak_mib`      | Peak memory in MiB          |
| `peak_gib`      | Peak memory in GiB          |
| `end_mib`       | Memory at end of test (MiB) |
| `end_gib`       | Memory at end of test (GiB) |
| `duration_secs` | Wall-clock duration         |
| `exit_code`     | 0 = pass, non-zero = fail   |
| `timestamp`     | UTC time of measurement     |
| `git_sha`       | Commit that was tested      |

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

For reference, these are the current CI runner assignments (from `.github/workflows/zxc-execute-hapi-tests.yaml`):

|               Suite                |     CI Runner     |
|------------------------------------|-------------------|
| Misc                               | hl-cn-hapi-lin-lg |
| Misc Records, Crypto & Misc Serial | hl-cn-hapi-lin-lg |
| Token & Time Consuming             | hl-cn-hapi-lin-lg |
| Simple Fees & ND Reconnect         | hl-cn-hapi-lin-lg |
| Smart Contracts & ISS              | hl-cn-hapi-lin-lg |
| Restart                            | hl-cn-hapi-lin-lg |
| Atomic Batch                       | hl-cn-hapi-lin-lg |
| State Throttling                   | hl-cn-hapi-lin-lg |

BN Comms (`hapiTestBlockNodeCommunication`) is excluded — it runs in XTS on a dedicated `hl-cn-hapi-bn-lin-xl` runner and is out of scope for this profiling.

Compare the measured peak memory against the candidate runner spec (10 CPU / 18G RAM) to determine if it fits.

## Clearing Results

To start fresh:

```bash
rm results/*.txt results/SUMMARY.csv
```

## Troubleshooting

**Docker build fails with UID conflict**: The Dockerfile handles existing UID 1000 users in the base image. If you still hit issues, run `docker compose build --no-cache` from the `tools/memory-profiling/` directory.

**Container runs out of memory (OOM-killed)**: Increase the memory limit in `docker-compose.yml` under `deploy.resources.limits.memory`. The default is 18G.

**Tests fail but memory is still recorded**: The measurement script captures peak memory regardless of test exit code. A failed test's memory reading is still valid for runner sizing purposes.
