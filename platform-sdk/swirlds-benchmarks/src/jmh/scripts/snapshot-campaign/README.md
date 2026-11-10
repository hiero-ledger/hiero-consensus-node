# MerkleDB snapshot campaign: pre-sizing A/B

Measures full MerkleDB snapshots of a 1-billion-leaf fixture to find out whether pre-sizing LongList index files still pays off with one writer per file. It compares three builds of the same `main` for the two LongList implementations production uses. It runs on the Linux benchmark machine, reuses the saved fixture (or generates it once), and never changes the checkout it is started from.

| Build        | Source                                                  |
|--------------|---------------------------------------------------------|
| `main`       | `main` at `4f374d9604`                                  |
| `pr`         | the same `main` plus `pr.patch`                         |
| `pr-presize` | the same `main` plus `pr.patch` and `presize.patch`     |

- `pr.patch`: the snapshot changes of PR #26557 without parallel writes. `AbstractLongList.writeToFile()` no longer ends with `force(true)`, and `MerkleDbDataSource.snapshot()` flushes the hash chunk cache while the snapshot tasks that don't need it run.
- `presize.patch`: `writeToFile()` also sets each index file to its final length before writing it.

In every build each index file has one writer, and the three index files are written in parallel, as on `main`.

Every build also gets the following, so the three differ only in their snapshot code:

- `selector.patch`: a benchmark-only switch that makes all three indices use the LongList implementation named by the benchmark parameter. This run uses Segment and Disk, the two that production creates.
- `MerkleDbSnapshotBenchmark.java`: the campaign version of the benchmark. It can generate the fixture without measuring, fails instead of generating a missing one during measurement, checks that every index uses the selected implementation, and validates each snapshot after the timed call.

The October campaign (`main`, the range approach and the shared pool, with all five implementations) is this folder at commit `ca5ab5b7c5`.

## Run

From the repository root, with `JAVA_HOME` pointing to JDK 25.0.2, after fetching `main`:

```bash
nohup bash platform-sdk/swirlds-benchmarks/src/jmh/scripts/snapshot-campaign/runner.sh > snapshot-campaign.log 2>&1 &
tail -f snapshot-campaign.log
```

This reuses the saved fixture. If there is none, add `--generate-fixture`. With the flag, the runner refuses to start while a saved fixture exists, and without it, while none does.

Requirements: Bash, Git, JDK 25.0.2 (`java` and `jfr`), Gradle's build prerequisites, `jq`, procps (`top`, `vmstat`), and standard Linux tools including `flock`, `setsid` and `stdbuf`. Generating the fixture needs at least 360 GiB free; measuring needs 60 GiB. Keep the machine to yourself for the whole run. `--dry-run` prints the plan without running anything.

The runner first checks the tools, the JDK, the fixture, free space and that the machine is quiet. It builds the three jars from the exact `main` commit in temporary `git archive` copies. With `--generate-fixture`, the `main` jar then generates the fixture, outside any measurement, and the runner saves it with the settings, workload key and file list it was made with; every later run checks the fixture against them. Then the runner checks the machine again and measures.

Generating the October 1B fixture took 2 to 9 hours. This run takes about 1.5 hours: about 30 minutes of builds, then 48 cells of 1.2 to 1.6 minutes each.

## What runs

- **Timing:** 8 blocks × 3 builds × 2 implementations = 48 JMH forks. Each fork loads the fixture, takes 1 warmup and 3 measured snapshots, and validates every snapshot. The blocks swap which implementation goes first, and use all six orders of the three builds plus two that keep `pr` and `pr-presize` symmetric: each of the two runs first, second and last equally often, and before the other in half of the blocks.
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
