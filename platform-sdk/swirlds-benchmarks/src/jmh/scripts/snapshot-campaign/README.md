# MerkleDB snapshot campaign

Measures full MerkleDB snapshots of a 1-billion-leaf fixture with three builds and all five LongList implementations. It runs on the Linux benchmark machine, generates the fixture once, and never changes the checkout it is started from.

| Build   | Source                                         | `longListWriteThreads` means                     |
|---------|------------------------------------------------|--------------------------------------------------|
| `main`  | `main` at `32784e212f`                         | ignored                                          |
| `range` | the same `main` plus `range.patch`             | threads per LongList file, three files in parallel |
| `pool`  | this branch at `1bc9b32001` (A merged with that `main`) | threads in the one pool shared by the snapshot |

`range.patch` is the range approach of the earlier version of the PR (`9db9258015`) moved onto the same `main`: each LongList file is split into contiguous ranges, one per thread.

Every build also gets the following, so the three differ only in their snapshot code:

- `selector.patch`: a benchmark-only switch that makes all three indices use the LongList implementation named by the benchmark parameter (Segment, Disk, Heap, OffHeap or DiskSegment). Production code only creates Segment or Disk lists.
- `MerkleDbSnapshotBenchmark.java`: the campaign version of the benchmark. It can generate the fixture without measuring, fails instead of generating a missing one during measurement, checks that every index uses the selected implementation, and validates each snapshot after the timed call.
- `SnapshotFailureCheckBenchmark.java`: a microbenchmark of the shared pool's per-chunk failure check.

## Run

From the repository root, with `JAVA_HOME` pointing to JDK 25.0.2. The first time, let the runner generate the fixture:

```bash
nohup bash platform-sdk/swirlds-benchmarks/src/jmh/scripts/snapshot-campaign/runner.sh --generate-fixture > snapshot-campaign.log 2>&1 &
tail -f snapshot-campaign.log
```

Later campaigns reuse the saved fixture: run the same command without `--generate-fixture`. With the flag, the runner refuses to start while a saved fixture exists, and without it, while none does.

Requirements: Bash, Git, JDK 25.0.2 (`java` and `jfr`), Gradle's build prerequisites, `jq`, procps (`top`, `vmstat`), and standard Linux tools including `flock`, `setsid` and `stdbuf`. Generating the fixture needs at least 360 GiB free; measuring needs 60 GiB. Keep the machine to yourself for the whole run. `--dry-run` prints the plan without running anything.

The runner first checks the tools, the JDK, the fixture, free space and that the machine is quiet. It builds the three jars from exact commits in temporary `git archive` copies. With `--generate-fixture`, the `main` jar then generates the fixture, outside any measurement, and the runner saves it with the settings, workload key and file list it was made with; every later run checks the fixture against them. Then the runner checks the machine again and measures.

Generating the October 1B fixture took 2 to 9 hours. The campaign itself takes about 5.5 hours.

## What runs

- **Timing:** 3 blocks × 14 settings × 5 implementations = 210 JMH forks. Each fork loads the fixture, takes 1 warmup and 3 measured snapshots, and validates every snapshot. The settings are `main`, `range` with 1, 2, 4 and 8 threads per file, and `pool` with 1, 2, 3, 4, 6, 8, 12, 16 and 24 threads. Each block runs them in a different order.
- **Profiling:** 15 forks with Java Flight Recorder on: `range` with 4 threads per file (12 in total), and `pool` with 12 and with 24 threads, for each implementation. The recording slows the run, so these forks are kept out of the timing results.
- **Microbenchmark:** the failure check with 1 and 24 threads.
- **Sampling:** `top`, `vmstat`, `/proc/diskstats` and `/proc/meminfo` (dirty and writeback pages) throughout the measurement, with cell markers.

The timed call is the snapshot only. Loading the fixture, filling the hash chunk cache, forcing the snapshot files to disk, validation and cleanup are outside it.

## Results

Results go to `platform-sdk/swirlds-benchmarks/build/results/jmh/merkledb-snapshot-1b-campaign/snapshot-campaign/<RUN_ID>`. At the end the runner prints the path of one `.tar.gz` archive and the `scp` command that copies it to your computer. It also creates the archive after a failure or an interruption. The archive never contains the fixture.

To continue an interrupted measurement, run the command the runner printed:

```bash
bash platform-sdk/swirlds-benchmarks/src/jmh/scripts/snapshot-campaign/runner.sh --resume RUN_ID
```

Resume reuses the frozen jars and plan, skips cells whose completed results still validate, and reruns the rest. A failed build or fixture generation is not resumable: start a new run.

The saved fixture lives under `platform-sdk/swirlds-benchmarks/build/tmp/merkledb-snapshot-1b-campaign/saved-fixture`, so do not run `gradle clean` in this clone.
