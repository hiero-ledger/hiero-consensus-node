# Complete MerkleDB snapshot benchmark results

At one billion leaves, parallel writers, removal of the final LongList force,
and hash-cache flush overlap work well together. With force disabled and
overlap enabled, eight writers per LongList reduced mean snapshot return time
by 20.3-52.0% compared with one writer across all five implementations.

Eight writers capture most of the measured benefit at this size. They are a
supported 1B candidate, not a universal default: the earlier 100M run gained
little beyond two writers, and the forced modes show much weaker scaling.

## What was measured

The 1B campaign ran `MerkleDbSnapshotBenchmark.snapshot()`, which measures
the complete MerkleDB snapshot call. It tested both force settings and both
hash-cache flush schedules:

| Mode | Final LongList `force(true)` | Hash-cache flush |
|---|---|---|
| Forced, serial | Enabled | Finishes before the snapshot tasks start |
| Forced, overlap | Enabled | Runs alongside the four independent snapshot tasks |
| Unforced, serial | Skipped | Finishes before the snapshot tasks start |
| Unforced, overlap | Skipped | Runs alongside the four independent snapshot tasks |

In both overlap modes, the hash-index and hash-store snapshots still wait for
the cache flush to finish.

The 1B settings were:

- **State:** 1,000,000,000 leaves; default provisioned capacity of
  1,000,000,000 keys; 32-byte keys and 128-byte records.
- **Indices:** all five implementations, each used for all three LongList
  indices in its trial. `P={1,2,8,16,32}` is the writer count **per LongList**.
- **Default chunk settings:** 1,048,576 longs per LongList chunk and a
  262,144-long reserved buffer. All 262,144 configured hash-cache chunks were
  loaded before timing.
- **Sampling:** three blocks (A, B, C) that reorder implementations, modes, and
  writer counts. Each configuration had one warmup and three measured snapshots
  per block: **nine measurements per result**, equally for all implementations.

The fixture was generated once, then restored into a disposable source for
each trial. Fixture generation, restore, and cache loading were outside timing.
Generation-time compaction finished before fixture validation; background
compaction was disabled on the measured sources.

After **every invocation**, including warmups, teardown forced the snapshot
files, validated them, and deleted the snapshot. This work was outside the
stopwatch, so the next invocation did not inherit the previous snapshot's
pending file writes.

## Complete 1B results

All times are **mean seconds**, calculated from the nine raw measurements.
Parentheses show the reduction from **P=1 in the same implementation and
column**; a negative percentage means slower. Percentages use unrounded means.

Read down a column to compare writer counts, or across a row to compare
snapshot modes. The percentages describe thread scaling, not force removal
or overlap.

### Segment indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 18.195 | 14.265 | 8.619 | 7.449 |
| `P=2` | 18.281 (-0.5%) | 14.481 (-1.5%) | 7.577 (12.1%) | 6.939 (6.8%) |
| `P=8` | 18.517 (-1.8%) | 14.366 (-0.7%) | 6.775 (21.4%) | 5.800 (22.1%) |
| `P=16` | 16.198 (11.0%) | 14.166 (0.7%) | 6.730 (21.9%) | 5.778 (22.4%) |
| `P=32` | 15.323 (15.8%) | 14.601 (-2.4%) | 6.647 (22.9%) | 5.662 (24.0%) |

The forced, serial column includes an unusually slow first block; see
[Important caveats](#important-caveats). All measurements are retained.

### Disk indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 18.658 | 18.312 | 11.283 | 9.925 |
| `P=2` | 19.158 (-2.7%) | 18.401 (-0.5%) | 7.744 (31.4%) | 7.087 (28.6%) |
| `P=8` | 20.657 (-10.7%) | 20.215 (-10.4%) | 6.948 (38.4%) | 5.786 (41.7%) |
| `P=16` | 20.274 (-8.7%) | 19.577 (-6.9%) | 6.969 (38.2%) | 5.831 (41.2%) |
| `P=32` | 18.548 (0.6%) | 19.391 (-5.9%) | 6.868 (39.1%) | 5.700 (42.6%) |

### Heap indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 16.103 | 14.831 | 12.282 | 11.350 |
| `P=2` | 15.199 (5.6%) | 14.090 (5.0%) | 7.161 (41.7%) | 6.141 (45.9%) |
| `P=8` | 15.065 (6.4%) | 14.223 (4.1%) | 6.303 (48.7%) | 5.443 (52.0%) |
| `P=16` | 15.011 (6.8%) | 14.209 (4.2%) | 6.504 (47.0%) | 5.340 (53.0%) |
| `P=32` | 14.981 (7.0%) | 14.307 (3.5%) | 6.869 (44.1%) | 6.081 (46.4%) |

### OffHeap indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 15.139 | 14.516 | 8.249 | 7.469 |
| `P=2` | 14.992 (1.0%) | 14.563 (-0.3%) | 7.711 (6.5%) | 6.780 (9.2%) |
| `P=8` | 14.995 (1.0%) | 14.370 (1.0%) | 6.697 (18.8%) | 5.956 (20.3%) |
| `P=16` | 15.110 (0.2%) | 14.340 (1.2%) | 6.792 (17.7%) | 5.966 (20.1%) |
| `P=32` | 15.169 (-0.2%) | 14.548 (-0.2%) | 6.579 (20.2%) | 5.678 (24.0%) |

### DiskSegment indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 15.557 | 14.695 | 10.739 | 9.934 |
| `P=2` | 15.483 (0.5%) | 14.667 (0.2%) | 10.039 (6.5%) | 8.378 (15.7%) |
| `P=8` | 15.385 (1.1%) | 14.646 (0.3%) | 8.353 (22.2%) | 7.516 (24.3%) |
| `P=16` | 15.323 (1.5%) | 14.517 (1.2%) | 8.324 (22.5%) | 7.598 (23.5%) |
| `P=32` | 15.185 (2.4%) | 14.791 (-0.7%) | 8.486 (21.0%) | 7.352 (26.0%) |

## What the results mean

### Additional writers help, especially without the final force

With force disabled and overlap enabled, P=8 beat both P=1 and P=2 for every
implementation in every block. The reduction from P=1 was 20.3-52.0%;
the additional reduction from P=2 was 10.3-18.4%.

Going from P=8 to P=32 saved another 1.5-4.7% for four implementations, but
made Heap 11.7% slower. P=8 therefore captures most of the useful gain without
assuming the largest thread count is always best.

With the final force retained, improvements were generally much smaller or
absent. In particular, the apparent larger forced-Segment gain needs the
caveat below.

### Removing the final force shortens snapshot return

Unforced snapshots returned earlier for every implementation and writer count,
in every block, with either flush schedule. For example, Segment at P=8 with
overlap fell from 14.366 seconds with force to 5.800 seconds without it.

This is a reduction in return time, not the elimination of storage work.
The remaining wait is discussed below.

### Overlap provides an additional improvement

With force already disabled, overlap improved every implementation and writer
count in every block. At P=8, the reduction was 10.0-16.7% across all five
implementations. For Segment, the mean fell from 6.775 seconds with serial
flushing to 5.800 seconds with overlap, a 14.4% reduction.

Overlap was less consistently beneficial with force retained. The unforced
result should not be generalized to every forced configuration.

### Why threads helped more at 1B than at 100M

The leaf-index body grew from approximately 800 MB at 100M leaves to 8 GB at
1B leaves. The configured hash cache stayed at 262,144 chunks.

In the earlier 100M unforced-overlap run, the hash-cache flush occupied about
90-92% of the snapshot's duration at P=8. Finishing the index writes sooner
could therefore save little once the flush was the remaining wait. P=8 added
no mean improvement over P=2 for either tested implementation.

At 1B, the cache flush still took roughly 1.0-1.4 seconds at P=8, while the
complete unforced-overlap snapshot took 5.4-7.5 seconds. These measurements
point to the larger index writes, rather than the cache flush, now keeping the
snapshot open for longer. Additional writers can therefore shorten much more
of the wait. The campaign did not separately time each index writer.

## Important caveats

**The first forced-Segment block was unusually slow.** At P=1 its mean was
24.964 seconds in block A, versus 14.734 and 14.887 seconds in B and C.
In A, times fell sharply later in that first sequence. The apparent 15.8%
P=32 improvement in the pooled table was not reproduced in B or C; it is not
reliable evidence of forced-path thread scaling. The logs do not establish
why conditions changed. No measurements were removed.

**Unforced return leaves storage work pending.** For Segment at P=8 with
overlap, mean return time was 5.800 seconds, but teardown then spent another
8.815 seconds on average forcing the snapshot files. The benchmark measures
the earlier return. It does not show that Linux finished writing all files in
5.800 seconds, or measure the effect of that background work on later node
activity.

## Earlier 100M results

The earlier campaign tested **100,000,000 leaves**, Segment and Disk indices,
and `P={1,2,8}`. It used the same four modes, default capacity and chunk
settings, full hash-cache population, and nine measurements per result.

These tables retain the earlier measurements, with the same mean-seconds and
P=1 percentage convention as the 1B tables.

### Segment indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 2.853 | 2.046 | 1.580 | 1.054 |
| `P=2` | 2.958 (-3.7%) | 1.989 (2.8%) | 1.511 (4.4%) | 1.037 (1.6%) |
| `P=8` | 3.250 (-13.9%) | 1.906 (6.9%) | 1.434 (9.3%) | 1.042 (1.1%) |

### Disk indices

| Writers per LongList | Forced, serial | Forced, overlap | Unforced, serial | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 3.510 | 2.483 | 2.128 | 1.439 |
| `P=2` | 3.822 (-8.9%) | 1.974 (20.5%) | 1.891 (11.1%) | 1.394 (3.2%) |
| `P=8` | 3.600 (-2.6%) | 1.997 (19.6%) | 1.844 (13.4%) | 1.404 (2.5%) |

Two writers had the lowest unforced-overlap mean for both implementations at
100M. The larger 1B campaign shows why that was a size-specific result, not a
reason to stop testing higher writer counts.

## Raw evidence

Both runs used Temurin 25.0.2+10 on the AMD EPYC 9124 host with 125 GiB RAM,
ext4 on `/home`, and the Micron 7450 NVMe. Each archive contains the exact
runner, environment, settings, build log, benchmark logs, and A/B/C JSON
results.

### 1B campaign — 31 August 2026

- Revision: `6417ab06b7ee424481de4789b6126ce65d4d094e`.
- Verified: 100 configurations, 300 JSON rows, and 900 finite measurements;
  nine per configuration. Validation remained enabled and no benchmark
  failures were found.
- Total runtime: 8h 52m 55s, including 2h 02m 51s of fixture preparation.
  The fixture occupied approximately 254 GiB. Waiting for pending generation
  compaction took 23.475 seconds before fixture snapshot and validation.
- [Results archive](raw/20260831T085212Z-2675542.tar.gz);
  SHA-256: `2718b54a5ddff81bfe34f7a44eae33e820b057f747c762315839b5e69aff9fa9`.
- [Console log](raw/20260831T085212Z-2675542-console.log);
  SHA-256: `3a3d1d7723942842b6bafa7031e58b7711231cb4d4a9b7327009f3fb50c53b70`.

### 100M campaign — 28 August 2026

- Revision: `bebb2190892744d91350ee12917d20344438f727`.
- Verified: 24 configurations, 72 JSON rows, and 216 finite measurements;
  nine per configuration.
- [Results archive](raw/20260828T113202Z-1105376.tar.gz);
  SHA-256: `2aa1c8dbfa521e02cb9853f2ef89e7acc862e3129024d4d05f292114822f7980`.
- [Console log](raw/20260828T113202Z-1105376-console.log);
  SHA-256: `607624d0e32661d9fc7c50e429d222ab99ecd9fd11fbc6dbd9bf7fffed58a0cf`.
