# Representative Linux LongList snapshot benchmark results

> **Status:** Corrected baseline campaign complete.

## Complete broad-matrix means

All times are seconds. Every cell contains six measurements: two in each of
three blocks that reorder implementations, chunk sizes, and thread counts.
`P` is the total number of writer threads used by one LongList. Parentheses
show the reduction in mean time from the same row's `P=1`; negative values are
regressions.

### 10,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=10` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 0.214 | 0.179 (16.4%) | 0.205 (4.3%) | — | 0.180 (15.6%) | 0.170 (20.4%) |
|  | OffHeap | 0.177 | 0.172 (2.9%) | 0.166 (6.2%) | — | 0.176 (0.3%) | 0.172 (2.9%) |
|  | Segment | 0.179 | 0.172 (3.9%) | 0.172 (4.0%) | — | 0.171 (4.5%) | 0.186 (-4.1%) |
|  | Disk | 0.185 | 0.165 (10.8%) | 0.159 (13.6%) | — | 0.163 (11.8%) | 0.168 (8.7%) |
|  | DiskSegment | 0.177 | 0.170 (4.0%) | 0.168 (4.9%) | — | 0.169 (4.5%) | 0.179 (-1.3%) |
| 1,048,576 | Heap | 0.212 | 0.184 (13.6%) | 0.162 (23.8%) | 0.161 (24.2%) | — | — |
|  | OffHeap | 0.180 | 0.169 (6.4%) | 0.177 (1.6%) | 0.178 (1.1%) | — | — |
|  | Segment | 0.183 | 0.172 (6.0%) | 0.175 (4.2%) | 0.181 (1.1%) | — | — |
|  | Disk | 0.198 | 0.171 (13.4%) | 0.165 (16.5%) | 0.165 (16.6%) | — | — |
|  | DiskSegment | 0.180 | 0.173 (3.7%) | 0.178 (1.0%) | 0.189 (-5.3%) | — | — |
| 4,194,304 | Heap | 0.214 | 0.188 (12.2%) | — | — | — | — |
|  | OffHeap | 0.192 | 0.184 (4.5%) | — | — | — | — |
|  | Segment | 0.189 | 0.188 (0.5%) | — | — | — | — |
|  | Disk | 0.198 | 0.180 (9.0%) | — | — | — | — |
|  | DiskSegment | 0.190 | 0.183 (3.7%) | — | — | — | — |

### 100,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=24` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 2.094 | 1.633 (22.0%) | 1.595 (23.8%) | 1.594 (23.9%) | — | 1.601 (23.6%) |
|  | OffHeap | 1.742 | 1.684 (3.4%) | 1.593 (8.5%) | 1.583 (9.2%) | — | 1.595 (8.4%) |
|  | Segment | 1.744 | 1.685 (3.4%) | 1.587 (9.0%) | 1.583 (9.2%) | — | 1.605 (8.0%) |
|  | Disk | 1.808 | 1.621 (10.3%) | 1.552 (14.2%) | 1.566 (13.4%) | — | 1.593 (11.9%) |
|  | DiskSegment | 1.740 | 1.671 (4.0%) | 1.600 (8.1%) | 1.689 (3.0%) | — | 1.587 (8.8%) |
| 1,048,576 | Heap | 2.095 | 1.633 (22.1%) | 1.593 (24.0%) | 1.608 (23.2%) | — | 1.621 (22.6%) |
|  | OffHeap | 1.743 | 1.676 (3.8%) | 1.589 (8.9%) | 1.587 (9.0%) | — | 1.605 (7.9%) |
|  | Segment | 1.743 | 1.681 (3.6%) | 1.593 (8.6%) | 1.594 (8.6%) | — | 1.628 (6.6%) |
|  | Disk | 1.930 | 1.662 (13.9%) | 1.591 (17.5%) | 1.601 (17.0%) | — | 1.612 (16.5%) |
|  | DiskSegment | 1.749 | 1.667 (4.7%) | 1.602 (8.4%) | 1.603 (8.4%) | — | 1.637 (6.4%) |
| 4,194,304 | Heap | 2.099 | 1.642 (21.8%) | 1.684 (19.8%) | 1.603 (23.7%) | 1.679 (20.0%) | — |
|  | OffHeap | 1.866 | 1.798 (3.7%) | 1.696 (9.1%) | 1.681 (10.0%) | 1.626 (12.9%) | — |
|  | Segment | 1.758 | 1.689 (3.9%) | 1.693 (3.7%) | 1.752 (0.3%) | 1.765 (-0.4%) | — |
|  | Disk | 1.942 | 1.667 (14.1%) | 1.617 (16.7%) | 1.607 (17.3%) | 1.684 (13.3%) | — |
|  | DiskSegment | 1.834 | 1.693 (7.7%) | 1.645 (10.3%) | 1.749 (4.6%) | 1.769 (3.5%) | — |

### 1,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 14.476 | 12.805 (11.5%) | 12.642 (12.7%) | 12.671 (12.5%) | 12.881 (11.0%) |
|  | OffHeap | 13.698 | 13.542 (1.1%) | 13.321 (2.8%) | 13.485 (1.6%) | 13.403 (2.2%) |
|  | Segment | 13.701 | 13.470 (1.7%) | 13.218 (3.5%) | 13.395 (2.2%) | 13.540 (1.2%) |
|  | Disk | 14.453 | 13.598 (5.9%) | 13.292 (8.0%) | 13.373 (7.5%) | 13.596 (5.9%) |
|  | DiskSegment | 13.891 | 13.641 (1.8%) | 13.316 (4.1%) | 13.370 (3.8%) | 13.760 (0.9%) |
| 1,048,576 | Heap | 14.497 | 12.792 (11.8%) | 12.637 (12.8%) | 12.601 (13.1%) | 12.963 (10.6%) |
|  | OffHeap | 13.753 | 13.439 (2.3%) | 13.282 (3.4%) | 13.205 (4.0%) | 13.396 (2.6%) |
|  | Segment | 13.731 | 13.468 (1.9%) | 13.204 (3.8%) | 13.406 (2.4%) | 13.477 (1.8%) |
|  | Disk | 14.471 | 13.850 (4.3%) | 13.301 (8.1%) | 13.348 (7.8%) | 13.653 (5.7%) |
|  | DiskSegment | 13.958 | 13.642 (2.3%) | 13.286 (4.8%) | 13.414 (3.9%) | 13.601 (2.6%) |
| 4,194,304 | Heap | 14.530 | 12.800 (11.9%) | 12.652 (12.9%) | 12.623 (13.1%) | 12.843 (11.6%) |
|  | OffHeap | 13.708 | 13.498 (1.5%) | 13.212 (3.6%) | 13.313 (2.9%) | 13.429 (2.0%) |
|  | Segment | 13.684 | 13.494 (1.4%) | 13.305 (2.8%) | 13.400 (2.1%) | 13.505 (1.3%) |
|  | Disk | 14.693 | 13.721 (6.6%) | 13.349 (9.1%) | 13.419 (8.7%) | 13.546 (7.8%) |
|  | DiskSegment | 13.997 | 13.643 (2.5%) | 13.346 (4.7%) | 13.553 (3.2%) | 13.573 (3.0%) |

### 5,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 62.234 | 57.826 (7.1%) | 57.588 (7.5%) | 57.864 (7.0%) | 58.190 (6.5%) |
|  | OffHeap | 59.255 | 58.090 (2.0%) | 57.681 (2.7%) | 57.988 (2.1%) | 57.584 (2.8%) |
|  | Segment | 58.114 | 58.063 (0.1%) | 57.770 (0.6%) | 57.532 (1.0%) | 58.077 (0.1%) |
|  | Disk | 61.787 | 60.899 (1.4%) | 60.940 (1.4%) | 60.670 (1.8%) | 61.235 (0.9%) |
|  | DiskSegment | 59.262 | 59.163 (0.2%) | 58.655 (1.0%) | 58.680 (1.0%) | 58.446 (1.4%) |
| 1,048,576 | Heap | 62.202 | 57.640 (7.3%) | 57.262 (7.9%) | 57.972 (6.8%) | 57.696 (7.2%) |
|  | OffHeap | 58.233 | 58.051 (0.3%) | 57.995 (0.4%) | 58.159 (0.1%) | 58.017 (0.4%) |
|  | Segment | 58.567 | 58.225 (0.6%) | 58.189 (0.6%) | 58.088 (0.8%) | 57.984 (1.0%) |
|  | Disk | 63.693 | 59.394 (6.8%) | 59.033 (7.3%) | 58.983 (7.4%) | 59.497 (6.6%) |
|  | DiskSegment | 59.662 | 59.154 (0.9%) | 58.846 (1.4%) | 58.920 (1.2%) | 58.595 (1.8%) |
| 4,194,304 | Heap | 61.529 | 57.620 (6.4%) | 57.737 (6.2%) | 57.860 (6.0%) | 57.575 (6.4%) |
|  | OffHeap | 58.412 | 58.435 (0.0%) | 58.181 (0.4%) | 58.197 (0.4%) | 58.089 (0.6%) |
|  | Segment | 58.443 | 58.436 (0.0%) | 58.140 (0.5%) | 58.185 (0.4%) | 57.979 (0.8%) |
|  | Disk | 64.518 | 59.265 (8.1%) | 58.751 (8.9%) | 58.987 (8.6%) | 60.296 (6.5%) |
|  | DiskSegment | 59.044 | 59.126 (-0.1%) | 58.774 (0.5%) | 58.657 (0.7%) | 58.557 (0.8%) |

## Supplemental `P=1`/`P=2` stability check — Segment and Disk only

This separate check used 15 measurements per cell, compared with six in every
broad-matrix cell. It tests repeatability only for the cases shown below and
must not be used to compare implementations. All cross-implementation
comparisons use the equal-sample broad matrix above.

CV is the observed sample standard deviation divided by the mean.

| Leaves | Implementation | `P=1` mean (CV) | `P=2` mean (CV) | Mean reduction | A/B/C blocks faster |
|---:|---|---:|---:|---:|---:|
| 1B | Segment | 13.770 s (1.01%) | 13.511 s (1.23%) | 1.9% | 3/3 |
| 1B | Disk | 14.529 s (1.02%) | 13.716 s (0.82%) | **5.6%** | 3/3 |
| 5B | Segment | 58.252 s (0.76%) | 58.136 s (0.96%) | 0.2% | 3/3 |
| 5B | Disk | 62.412 s (1.13%) | 58.822 s (0.40%) | **5.8%** | 3/3 |

## Notes needed to read the tables

- `P=10` and `P=24` are the even near-maximum settings for 11 and 25 active
  chunks. Settings above the active chunk count were omitted because they
  would repeat the same effective concurrency.
- The 10M results are noisy: cell CV reaches 23.71%, so exact thread rankings
  at that size are not decision evidence.
- At 100M with 4,194,304-long chunks, block A was systematically slower for
  OffHeap and DiskSegment. Their aggregate high-thread gains are overstated.
- At 5B with 262,144-long chunks, Disk's parallel cells contain a recorded
  timing anomaly. Do not use that row to infer scaling.
- Every measured `LongListDisk` invocation in the broad matrix followed a
  complete warmup snapshot, so its source-file cache was warm-like. The
  explicit warm/cold diagnostic is recorded in
  [`disk-cache-diagnostic.md`](disk-cache-diagnostic.md).

## Method and raw evidence

- Run ID: `20260825T103909Z-3524645`
- Git revision: `b20f397ef734e04c5d9cf66038a5fa197e396dc4`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`; `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37, single-shot mode, one fork and one JMH thread per parameter row
- Benchmark:
  [`LongListSnapshotBenchmark`](../../platform-sdk/swirlds-merkledb/src/jmh/java/com/swirlds/benchmark/LongListSnapshotBenchmark.java)
- Runner:
  [`run-long-list-snapshot-benchmark.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-long-list-snapshot-benchmark.sh)

The dense fixture contains `N` longs for `N` leaves: `8N` body bytes plus the
12-byte LongList header. It is created once per leaf count and reused outside
the measured operation. The complete campaign finished all 45 planned JMH
launches without an OOM, timeout, exception, disk-space failure, or incomplete
fork.

- Corrected campaign archive:
  [`20260825T103909Z-3524645.tar.gz`](raw/20260825T103909Z-3524645.tar.gz)
- SHA-256:
  `7e424c6f0c2c66b93ca30ed3ea562af7d25290540feb3eb4d3a1a6d04020341e`
- The archive contains the exact runner, environment, build log, JSON results,
  and readable JMH logs.

The earlier interrupted archive remains under `raw/` as historical evidence.
