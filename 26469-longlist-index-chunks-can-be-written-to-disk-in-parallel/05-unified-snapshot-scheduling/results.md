# MerkleDB snapshot scheduling results

## October baseline

The range-based implementation completed the full one-billion-record matrix on October 2–3, 2026.
This is the baseline for comparing the two shared-pool implementations, not a result for either
new implementation. Final index-file forcing is disabled and hash-cache flush overlap is enabled.

The archive contains all 25 configurations in each of three blocks: 75 JMH rows and 225 measured
snapshots, with nine measurements per configuration. All samples are retained.

### Workload and measurement

- One billion records, built in 10,000 batches of 100,000; 32-byte keys and 128-byte records.
- Each selected LongList implementation is used for all three snapshot indices.
- `P` is `threadsPerLongList`: 1, 2, 8, 16 or 32 writers per list, not the total snapshot thread count.
- LongList chunks contain 1,048,576 longs; hash chunk height is 6. Each trial loads 262,144 hash
  chunks into the cache before warmup.
- Each configuration has one JVM fork, one warmup snapshot and three measured snapshots per
  block. JMH uses one benchmark thread and single-shot timing. The separate `numThreads=32`
  fixture parameter is not the JMH thread count.
- Blocks rotate implementation and writer-count order. Exact orders and all raw samples are in
  [the result data](baseline-20261002.json).
- Only the snapshot call is timed. Fixture generation, restoring the source, loading its cache,
  forcing restored files before warmup, forcing output files after return, validation and cleanup
  are outside the measurement. These scores are **snapshot return time, not durable-write latency**.

The machine had an AMD EPYC 9124 (16 cores, 32 logical CPUs), 125.5 GiB RAM, and a Micron 7450 NVMe
drive with ext4. The run used Linux 5.15.0-174-generic, Temurin 25.0.2+10, JMH 1.37, and
`-Xms4g -Xmx32g -XX:MaxDirectMemorySize=16g`.

### Snapshot times

Arithmetic mean of all nine measurements per configuration, in **seconds**. Lower is better.

| Implementation | P=1 | P=2 | P=8 | P=16 | P=32 |
|---|---:|---:|---:|---:|---:|
| SEGMENT | 7.274 | 6.991 | 5.902 | 5.704 | 5.859 |
| DISK | 9.611 | 6.548 | 5.818 | 5.753 | 5.892 |
| HEAP | 11.648 | 5.981 | 5.244 | 5.220 | 5.830 |
| OFF_HEAP | 7.277 | 6.636 | 5.581 | 5.480 | 5.618 |
| DISK_SEGMENT | 9.832 | 8.563 | 7.525 | 7.496 | 7.393 |

The lowest observed mean is at P=16 for four implementations and P=32 for DISK_SEGMENT.
These are observations, not established optimal settings:

- Most of the improvement is already present at P=8. Moving from P=8 to the lowest mean saves
  only 0.5–3.4%, depending on the implementation.
- HEAP at P=32 is 11.7% slower than P=16; more writers do not always help.
- Rankings among P=8, P=16 and P=32 vary between blocks. For example, DISK's fastest setting is
  P=32 in A, P=8 in B and P=16 in C.
- Variation matters: SEGMENT/P=8 block means are 6.459, 5.418 and 5.829 seconds. Small differences
  between settings should not be treated as proven wins. The result data includes block means,
  medians, minimums, maximums and every raw sample.
- P=4, the baseline production default, was not in this matrix.

### Completion and validation

All 25 archived-file checksums and all four frozen-input checksums match. The logs contain no
benchmark failures, timeouts or application errors. The usual JMH Unsafe-deprecation warnings remain.

The completed execution path validates 301 snapshots: one fixture snapshot, 75 warmups and
225 measured snapshots. These checks cover index file sizes and headers, first/chunk-boundary/last
index values, metadata presence and nonempty store directories. They are not an exhaustive value
comparison or a full restore test. All 75 restored sources also passed their fixture checks.

### Runtime and machine-state limits

| Phase | Wall time |
|---|---:|
| Fixture preparation | 9h 00m 30s |
| Block A | 36m 29s |
| Block B | 35m 03s |
| Block C | 35m 19s |
| Three measurement blocks combined | 1h 46m 51s |
| Whole run, including orchestration | 10h 48m 18s |

The fixture occupied about 262 GiB. Preparation dominates the runtime and is excluded from the
snapshot scores. Its JMH score of 0.220 ms is a preparation-only return of the fixture path and is
not included in the results.

The retry reused the same JAR as the failed October 1 attempt and increased the preparation timeout
from six to twelve hours. Preparation finished after nine hours. The larger timeout allowed this run
to complete; it does not explain why preparation was much slower than the previous successful run.

The five-second preflight sample showed 122.8 GiB available RAM, no swap activity and almost no disk
activity, but warned about a background system-management process using CPU. There was no continuous
resource recording or second check immediately before the measurement blocks, so the archive cannot
prove that the machine was otherwise idle throughout the run.

Output-file forcing took about 9.7 seconds on average after each snapshot, outside the timed call.
This reinforces the distinction between snapshot return time and storage completion. Benchmark CSV
output was disabled; platform metrics output remained enabled.

### Comparison with the previous large run

The matching August 31 measurements are the `UNFORCED_OVERLAP` configurations from
`20260831T085212Z-2675542`, recorded at revision `6417ab06b7`. Both sets contain nine measurements
for each implementation/P pair.

Change in mean snapshot time: `(October / August - 1) × 100`.
Positive means slower; negative means faster.

| Implementation | P=1 | P=2 | P=8 | P=16 | P=32 |
|---|---:|---:|---:|---:|---:|
| SEGMENT | -2.35% | +0.75% | +1.76% | -1.29% | +3.49% |
| DISK | -3.16% | -7.60% | +0.56% | -1.35% | +3.36% |
| HEAP | +2.62% | -2.62% | -3.66% | -2.25% | -4.13% |
| OFF_HEAP | -2.58% | -2.13% | -6.30% | -8.14% | -1.05% |
| DISK_SEGMENT | -1.03% | +2.21% | +0.12% | -1.34% | +0.56% |

Snapshot times remain broadly in the same range. This is historical context, not a controlled
comparison of the new scheduling implementations:

- Main-branch changes, including parallel fixture flushing, are present in October.
- The fixture grew from about 254 to 262 GiB. Its hash/leaf/bucket file counts changed from
  159/172/25 to 159/171/27.
- October forces restored source files before warmup; August did not. Both force output files
  after the timed snapshot call.
- August interleaved four force/overlap modes; October measures only force-off/overlap-on.
- Fixture preparation took about 2h 03m in August versus 9h 00m in October. The cause of that
  preparation slowdown is not established by these archives.

Historical cell and block means are included in [the result data](baseline-20261002.json).
The [earlier report](https://github.com/hiero-ledger/hiero-consensus-node/blob/83480682be94163cffcca728c6c7ecd414950e3a/26469-longlist-index-chunks-can-be-written-to-disk-in-parallel/04-hash-cache-pre-flush-overlap/hash-cache-pre-flush-overlap.md)
remains the source for the separate force-removal and flush-overlap experiments. This new run
does not remeasure those individual effects.

### Comparing the new implementations

Use this October baseline, the same workload, and force-off/overlap-on for both candidates.
Compare each implementation across the full matrix, not just its best observed setting.

For an approximate writer-capacity comparison, the baseline's three lists at P=1/2/8/16/32 map to
shared-pool budgets of 3/6/24/48/96. This is not exact resource equivalence: the baseline's top-level
snapshot work runs outside its writer pool, P=1 writes on the calling threads, and a list can use
fewer than P writers when it has fewer chunks. The new pool also runs hash flushing and other
snapshot operations.

Neither candidate has Linux performance results in this report yet. Close results should be judged
alongside block variation and code simplicity, not used to claim a narrow performance win.

### Artifact identity

- Run: `baseline-20261002T135748Z-532316`, completed with exit code 0.
- Recorded checkout: `bdfd70497908f02f46d754d556a01008332ac7e1`.
- The JAR was reused from the earlier failed baseline attempts. The supplied archives record the
  checkout revision but do not contain its original build log, so that revision is not independently
  verified as the JAR's build revision. Its bytecode contains the range writer and per-LongList
  writer parameter, not the new shared-pool implementation.
- Archive SHA-256:
  `017c52ffa652d1e2f3a7ed7844e9a550b0023c91cf1c985eb0174473a9be4a8c`.
- JAR SHA-256:
  `11080a9a688df3f177eb78a84d2ac26fbb85c8ea095757a08c417bf0730ccc17`.
- [Sanitized result data](baseline-20261002.json) preserves all 225 raw measurements, summaries,
  settings and frozen-input hashes. The original archive stays local; machine/user identifiers
  and operational paths are not copied into the result artifacts.
