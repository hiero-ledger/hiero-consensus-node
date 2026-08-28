# Remove the final LongList force

> **Status:** Focused and full-matrix Linux campaigns complete. Earlier
> `writeToFile()` return is confirmed, and the investigation decision is to
> include this change in the current PR. The effect on complete snapshot time
> has not yet been measured, and the production path still needs to be updated.

## Question

How much earlier can `LongList.writeToFile()` return when it closes the target
without calling the final `force(true)`?

## Complete broad-matrix comparison

All times are seconds. Every cell contains six measurements from the forced
baseline and six from the matching unforced baseline. Each cell shows the
unforced mean followed by its reduction from the matching forced mean. The
lowest observed unforced mean in each implementation/chunk row is bold; close
thread-count rankings are not treated as default-selection evidence.

### 10,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=10` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 0.103 (51.7%) | 0.069 (61.3%) | 0.096 (53.1%) | — | 0.078 (56.7%) | **0.058 (66.1%)** |
|  | OffHeap | 0.068 (61.4%) | 0.061 (64.2%) | **0.056 (66.3%)** | — | 0.059 (66.3%) | 0.067 (61.2%) |
|  | Segment | 0.070 (60.8%) | 0.062 (63.8%) | **0.059 (65.6%)** | — | 0.061 (64.2%) | 0.079 (57.5%) |
|  | Disk | 0.078 (57.5%) | 0.055 (66.4%) | **0.054 (66.0%)** | — | 0.055 (66.3%) | 0.063 (62.5%) |
|  | DiskSegment | 0.070 (60.5%) | **0.062 (63.4%)** | 0.063 (62.7%) | — | 0.065 (61.8%) | 0.072 (59.9%) |
| 1,048,576 | Heap | 0.104 (51.1%) | 0.073 (60.3%) | 0.052 (67.9%) | **0.051 (68.2%)** | — | — |
|  | OffHeap | 0.069 (61.6%) | **0.062 (63.1%)** | 0.065 (63.1%) | 0.068 (61.8%) | — | — |
|  | Segment | 0.073 (60.3%) | 0.064 (62.6%) | **0.062 (64.3%)** | 0.075 (58.3%) | — | — |
|  | Disk | 0.088 (55.4%) | 0.062 (63.6%) | 0.056 (65.9%) | **0.056 (65.9%)** | — | — |
|  | DiskSegment | 0.073 (59.6%) | 0.064 (63.2%) | **0.064 (64.4%)** | 0.072 (61.9%) | — | — |
| 4,194,304 | Heap | 0.110 (48.6%) | **0.080 (57.5%)** | — | — | — | — |
|  | OffHeap | 0.082 (57.4%) | **0.075 (59.2%)** | — | — | — | — |
|  | Segment | 0.080 (57.6%) | **0.075 (60.1%)** | — | — | — | — |
|  | Disk | 0.094 (52.6%) | **0.068 (62.0%)** | — | — | — | — |
|  | DiskSegment | 0.080 (57.8%) | **0.075 (59.1%)** | — | — | — | — |

### 100,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=24` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 1.006 (52.0%) | 0.536 (67.2%) | 0.511 (68.0%) | **0.498 (68.7%)** | — | 0.521 (67.5%) |
|  | OffHeap | 0.648 (62.8%) | 0.593 (64.8%) | 0.485 (69.5%) | **0.480 (69.7%)** | — | 0.498 (68.8%) |
|  | Segment | 0.650 (62.7%) | 0.576 (65.8%) | 0.491 (69.0%) | **0.483 (69.5%)** | — | 0.502 (68.7%) |
|  | Disk | 0.741 (59.0%) | 0.541 (66.6%) | **0.474 (69.4%)** | 0.483 (69.2%) | — | 0.522 (67.2%) |
|  | DiskSegment | 0.647 (62.8%) | 0.590 (64.7%) | 0.500 (68.7%) | **0.488 (71.1%)** | — | 0.517 (67.4%) |
| 1,048,576 | Heap | 1.001 (52.2%) | 0.542 (66.8%) | 0.501 (68.6%) | **0.494 (69.3%)** | — | 0.530 (67.3%) |
|  | OffHeap | 0.649 (62.8%) | 0.572 (65.9%) | 0.490 (69.1%) | **0.489 (69.2%)** | — | 0.491 (69.4%) |
|  | Segment | 0.650 (62.7%) | 0.587 (65.1%) | **0.498 (68.8%)** | 0.498 (68.8%) | — | 0.514 (68.4%) |
|  | Disk | 0.847 (56.1%) | 0.582 (65.0%) | 0.510 (68.0%) | **0.505 (68.4%)** | — | 0.514 (68.1%) |
|  | DiskSegment | 0.650 (62.8%) | 0.575 (65.5%) | 0.500 (68.8%) | **0.495 (69.1%)** | — | 0.535 (67.3%) |
| 4,194,304 | Heap | 1.000 (52.4%) | 0.537 (67.3%) | 0.493 (70.7%) | **0.489 (69.5%)** | 0.519 (69.1%) | — |
|  | OffHeap | 0.660 (64.7%) | 0.583 (67.6%) | 0.523 (69.1%) | 0.520 (69.1%) | **0.512 (68.5%)** | — |
|  | Segment | 0.659 (62.5%) | **0.596 (64.7%)** | 0.621 (63.3%) | 0.629 (64.1%) | 0.751 (57.5%) | — |
|  | Disk | 0.864 (55.5%) | 0.577 (65.4%) | 0.529 (67.3%) | **0.510 (68.3%)** | 0.538 (68.1%) | — |
|  | DiskSegment | 0.659 (64.1%) | 0.595 (64.8%) | **0.580 (64.7%)** | 0.638 (63.5%) | 0.614 (65.3%) | — |

### 1,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 11.167 (22.9%) | 6.148 (52.0%) | 5.419 (57.1%) | **5.270 (58.4%)** | 5.893 (54.2%) |
|  | OffHeap | 9.896 (27.8%) | 8.278 (38.9%) | 7.658 (42.5%) | **7.506 (44.3%)** | 7.689 (42.6%) |
|  | Segment | 9.890 (27.8%) | 8.608 (36.1%) | 7.652 (42.1%) | **7.425 (44.6%)** | 7.644 (43.5%) |
|  | Disk | 10.745 (25.7%) | 8.566 (37.0%) | **7.436 (44.1%)** | 7.549 (43.6%) | 7.778 (42.8%) |
|  | DiskSegment | 9.843 (29.1%) | 8.675 (36.4%) | 7.636 (42.7%) | **7.533 (43.7%)** | 7.694 (44.1%) |
| 1,048,576 | Heap | 11.258 (22.3%) | 6.208 (51.5%) | **5.262 (58.4%)** | 5.424 (57.0%) | 5.882 (54.6%) |
|  | OffHeap | 9.974 (27.5%) | 8.433 (37.2%) | **7.416 (44.2%)** | 7.424 (43.8%) | 7.467 (44.3%) |
|  | Segment | 9.654 (29.7%) | 8.635 (35.9%) | 7.601 (42.4%) | **7.383 (44.9%)** | 7.436 (44.8%) |
|  | Disk | 11.433 (21.0%) | 8.545 (38.3%) | **7.627 (42.7%)** | 7.634 (42.8%) | 7.640 (44.0%) |
|  | DiskSegment | 9.425 (32.5%) | 8.684 (36.3%) | 7.574 (43.0%) | **7.400 (44.8%)** | 7.567 (44.4%) |
| 4,194,304 | Heap | 11.228 (22.7%) | 6.192 (51.6%) | **5.308 (58.0%)** | 5.315 (57.9%) | 5.790 (54.9%) |
|  | OffHeap | 10.014 (26.9%) | 8.566 (36.5%) | 7.560 (42.8%) | **7.495 (43.7%)** | 7.513 (44.1%) |
|  | Segment | 9.975 (27.1%) | 8.587 (36.4%) | 7.600 (42.9%) | 7.542 (43.7%) | **7.499 (44.5%)** |
|  | Disk | 11.538 (21.5%) | 8.613 (37.2%) | 7.675 (42.5%) | **7.502 (44.1%)** | 7.618 (43.8%) |
|  | DiskSegment | 9.815 (29.9%) | 8.625 (36.8%) | 7.584 (43.2%) | 7.522 (44.5%) | **7.519 (44.6%)** |

### 5,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 59.813 (3.9%) | 32.847 (43.2%) | 27.772 (51.8%) | **27.344 (52.7%)** | 30.660 (47.3%) |
|  | OffHeap | 44.577 (24.8%) | 40.067 (31.0%) | 33.199 (42.4%) | **32.485 (44.0%)** | 36.215 (37.1%) |
|  | Segment | 44.354 (23.7%) | 42.876 (26.2%) | 35.795 (38.0%) | **33.288 (42.1%)** | 33.974 (41.5%) |
|  | Disk | 56.840 (8.0%) | 46.441 (23.7%) | 39.568 (35.1%) | **39.337 (35.2%)** | 41.630 (32.0%) |
|  | DiskSegment | 50.961 (14.0%) | 46.920 (20.7%) | **39.775 (32.2%)** | 40.357 (31.2%) | 40.922 (30.0%) |
| 1,048,576 | Heap | 59.769 (3.9%) | 33.144 (42.5%) | **27.571 (51.9%)** | 27.713 (52.2%) | 30.729 (46.7%) |
|  | OffHeap | 50.724 (12.9%) | 46.995 (19.0%) | 40.467 (30.2%) | **39.084 (32.8%)** | 39.822 (31.4%) |
|  | Segment | 50.429 (13.9%) | 47.001 (19.3%) | 40.307 (30.7%) | **39.746 (31.6%)** | 40.353 (30.4%) |
|  | Disk | 59.289 (6.9%) | 47.038 (20.8%) | 40.526 (31.4%) | **39.774 (32.6%)** | 40.155 (32.5%) |
|  | DiskSegment | 51.006 (14.5%) | 46.787 (20.9%) | 40.256 (31.6%) | **39.012 (33.8%)** | 39.439 (32.7%) |
| 4,194,304 | Heap | 59.850 (2.7%) | 32.652 (43.3%) | 28.104 (51.3%) | **27.029 (53.3%)** | 30.773 (46.6%) |
|  | OffHeap | 50.636 (13.3%) | 47.103 (19.4%) | 39.654 (31.8%) | 39.505 (32.1%) | **39.142 (32.6%)** |
|  | Segment | 51.099 (12.6%) | 46.625 (20.2%) | 40.716 (30.0%) | **38.978 (33.0%)** | 39.403 (32.0%) |
|  | Disk | 60.878 (5.6%) | 46.100 (22.2%) | 40.659 (30.8%) | 39.637 (32.8%) | **39.486 (34.5%)** |
|  | DiskSegment | 51.021 (13.6%) | 46.452 (21.4%) | 40.169 (31.7%) | **39.137 (33.3%)** | 39.234 (33.0%) |

## LongListDisk source-cache comparison

`Earlier return` is the unforced reduction from the forced return in the same
row. The final column adds the post-return force to the unforced return; its
percentage is the resulting difference from the forced return, and a leading
`+` means slower.

| Source cache | `P` | Forced return | Unforced return | Earlier return | Post-return force | Unforced + post-return force |
|---|---:|---:|---:|---:|---:|---:|
| Warm | 1 | 14.389 s | 10.927 s | 3.462 s (24.1%) | 3.751 s | 14.678 s (+2.0%) |
| Warm | 2 | 13.760 s | 9.010 s | 4.751 s (34.5%) | 4.856 s | 13.866 s (+0.8%) |
| Warm | 8 | 13.396 s | 7.620 s | 5.775 s (43.1%) | 5.906 s | 13.526 s (+1.0%) |
| Cold | 1 | 19.609 s | 14.711 s | 4.898 s (25.0%) | 5.012 s | 19.723 s (+0.6%) |
| Cold | 2 | 16.757 s | 7.965 s | 8.793 s (52.5%) | 8.838 s | 16.803 s (+0.3%) |
| Cold | 8 | 16.261 s | 7.267 s | 8.994 s (55.3%) | 9.243 s | 16.510 s (+1.5%) |

## Higher-repetition same-campaign confirmation

The comparison above uses six measurements per cell from each complete
baseline. The focused campaign below included forced and unforced modes in
each reordered block and used 15 measurements per cell for one billion leaves,
the default chunk size, and `P={1,8}`. `Post-return force` is the teardown time
to reopen, force, and close the unforced target immediately afterward, outside
`writeToFile()`.

### One writer

| Implementation | Forced return | Unforced return | Earlier return | Post-return force | Unforced + post-return force |
|---|---:|---:|---:|---:|---:|
| Heap | 14.511 s | 11.287 s | 3.223 s (22.21%) | 3.167 s | 14.454 s (-0.39%) |
| OffHeap | 13.877 s | 9.851 s | 4.026 s (29.01%) | 4.062 s | 13.913 s (+0.26%) |
| Segment | 13.892 s | 9.873 s | 4.020 s (28.94%) | 3.938 s | 13.810 s (-0.59%) |
| Disk | 14.668 s | 11.294 s | 3.374 s (23.00%) | 3.344 s | 14.639 s (-0.20%) |
| DiskSegment | 14.013 s | 9.867 s | 4.146 s (29.59%) | 4.281 s | 14.148 s (+0.96%) |

### Eight writers

| Implementation | Forced return | Unforced return | Earlier return | Post-return force | Unforced + post-return force |
|---|---:|---:|---:|---:|---:|
| Heap | 12.662 s | 5.316 s | 7.345 s (58.01%) | 7.308 s | 12.625 s (-0.29%) |
| OffHeap | 13.227 s | 7.496 s | 5.731 s (43.33%) | 5.831 s | 13.326 s (+0.75%) |
| Segment | 13.383 s | 7.502 s | 5.882 s (43.95%) | 5.820 s | 13.322 s (-0.46%) |
| Disk | 13.465 s | 7.588 s | 5.878 s (43.65%) | 5.869 s | 13.456 s (-0.07%) |
| DiskSegment | 13.416 s | 7.483 s | 5.934 s (44.23%) | 5.928 s | 13.410 s (-0.04%) |

The improvement reproduced in every block: 21.16-30.02% at `P=1` and
42.67-58.56% at `P=8`. Adding the post-return force back leaves every mean
within 1.0% of its forced reference. Omitting the force therefore does not
remove storage work; it moves approximately 3.2-7.3 seconds of waiting beyond
`writeToFile()`'s return.

The JMH auxiliary-counter summary adds the five iteration values together.
The per-operation post-return values above were recomputed from its JSON
`rawData`, where each value is expressed in nanoseconds.

## Higher-repetition method

| Parameter | Value |
|---|---|
| Implementations | All five LongLists |
| Leaf count | `1,000,000,000` |
| LongList chunk size | `1,048,576` longs |
| Writer threads | `P={1,8}` |
| Force modes | Forced and unforced |
| Sampling | Three reordered blocks; one warmup and five measurements per cell |

At the time of this campaign, the public write methods remained forced. The
benchmark alone could omit the final force through a package-private durability
switch. After every
unforced measurement, invocation teardown reopens and forces the target before
verification and deletion. Teardown is outside the measured return time, so
pending writes cannot leak into the next invocation.

Each implementation and force mode receives 15 measurements. The forced arm
is the direct reference for the same implementation and writer count; the
prepared-memory FileChannel control is not part of this campaign.

## Decision

Remove the isolated final LongList force in this PR. The earlier-return
hypothesis is confirmed, and the current
force does not make the signed-state snapshot durable as a whole. Removing it
moves the remaining storage wait beyond `writeToFile()`'s return; it does not
eliminate that work. A failure reported only by `force(true)` can no longer
reach this snapshot call and may surface elsewhere later, or may not be
reported through the snapshot operation.

The unforced mean was lower in all 280 matching broad-matrix cells. At one
billion leaves the reduction was 21.0-58.4%; at five billion
leaves it was 2.7-53.3%. The warm/cold `LongListDisk` comparison reached the
same conclusion. Unforced parallel scaling relative to its own `P=1` is kept
separately in
[`linux-benchmark-results-without-force.md`](linux-benchmark-results-without-force.md).

No additional LongList microbenchmark is needed to establish the earlier
return. Production implementation should keep worker completion and channel
close before publication, while documenting the changed error-reporting
boundary. Its effect on complete snapshot time belongs in the final
production-path comparison.

## Raw evidence

### Complete baselines

- Forced baseline archive:
  [`20260825T103909Z-3524645.tar.gz`](../01-parallel-chunk-writes/raw/20260825T103909Z-3524645.tar.gz)
- Forced archive SHA-256:
  `7e424c6f0c2c66b93ca30ed3ea562af7d25290540feb3eb4d3a1a6d04020341e`
- Unforced baseline archive:
  [`20260827T120713Z-523569.tar.gz`](raw/20260827T120713Z-523569.tar.gz)
- Unforced archive SHA-256:
  `4d2e92cbd3d4b8e09644ffe54d86b4e183cc1e6ea0bd23953ee5010aa8b3f931`

### Higher-repetition confirmation

- Git revision: `5291163b12c2f3e5a5cff29aaa2e485e20014875`
- Archive:
  [`20260827T075601Z-418584.tar.gz`](raw/20260827T075601Z-418584.tar.gz)
- Archive SHA-256:
  `616ee14d707131e37484bbad86694d7e0e18c905014e094fd2d072546292b01c`
- Console log:
  [`20260827T075601Z-418584-console.log`](raw/20260827T075601Z-418584-console.log)
- Console SHA-256:
  `54021efc2c2406962c0376a9fa80186e62cea0e8a3d851260dd4c6677be8d095`
- Environment: Temurin 25.0.2, AMD EPYC 9124, 125 GiB RAM, ext4 on a
  Micron 7450 NVMe.

The archive contains the exact runner, environment and build logs, three JSON
blocks, and 60 JFR recordings. Every per-block JSON cell contains five finite
measurements, giving 15 after the three blocks are combined. All JFRs are
readable and report no data loss. The campaign used `verify=false`; the same
forced/unforced paths were byte-for-byte verified locally before the Linux
run.
