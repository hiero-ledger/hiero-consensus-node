# Representative Linux LongList snapshot results without final force

> **Status:** Complete Linux campaign.

## Complete broad-matrix means

All times are seconds. Every cell contains six measurements: two in each of
three blocks that reorder implementations, chunk sizes, and thread counts.
`P` is the total number of writer threads used by one LongList. Parentheses
show the reduction in mean time from the same row's unforced `P=1`; negative
values are regressions.

### 10,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=10` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 0.103 | 0.069 (33.0%) | 0.096 (7.1%) | — | 0.078 (24.3%) | 0.058 (44.3%) |
|  | OffHeap | 0.068 | 0.061 (10.0%) | 0.056 (18.0%) | — | 0.059 (13.0%) | 0.067 (2.4%) |
|  | Segment | 0.070 | 0.062 (11.1%) | 0.059 (15.8%) | — | 0.061 (12.7%) | 0.079 (-12.9%) |
|  | Disk | 0.078 | 0.055 (29.5%) | 0.054 (30.9%) | — | 0.055 (30.1%) | 0.063 (19.4%) |
|  | DiskSegment | 0.070 | 0.062 (11.1%) | 0.063 (10.1%) | — | 0.065 (7.7%) | 0.072 (-2.8%) |
| 1,048,576 | Heap | 0.104 | 0.073 (29.8%) | 0.052 (50.1%) | 0.051 (50.8%) | — | — |
|  | OffHeap | 0.069 | 0.062 (9.9%) | 0.065 (5.4%) | 0.068 (1.7%) | — | — |
|  | Segment | 0.073 | 0.064 (11.6%) | 0.062 (14.0%) | 0.075 (-3.7%) | — | — |
|  | Disk | 0.088 | 0.062 (29.4%) | 0.056 (36.1%) | 0.056 (36.2%) | — | — |
|  | DiskSegment | 0.073 | 0.064 (12.3%) | 0.064 (12.7%) | 0.072 (0.9%) | — | — |
| 4,194,304 | Heap | 0.110 | 0.080 (27.2%) | — | — | — | — |
|  | OffHeap | 0.082 | 0.075 (8.6%) | — | — | — | — |
|  | Segment | 0.080 | 0.075 (6.3%) | — | — | — | — |
|  | Disk | 0.094 | 0.068 (27.0%) | — | — | — | — |
|  | DiskSegment | 0.080 | 0.075 (6.7%) | — | — | — | — |

### 100,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=24` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|---:|
| 262,144 | Heap | 1.006 | 0.536 (46.7%) | 0.511 (49.2%) | 0.498 (50.5%) | — | 0.521 (48.2%) |
|  | OffHeap | 0.648 | 0.593 (8.4%) | 0.485 (25.1%) | 0.480 (25.8%) | — | 0.498 (23.2%) |
|  | Segment | 0.650 | 0.576 (11.4%) | 0.491 (24.4%) | 0.483 (25.7%) | — | 0.502 (22.7%) |
|  | Disk | 0.741 | 0.541 (27.0%) | 0.474 (36.0%) | 0.483 (34.9%) | — | 0.522 (29.5%) |
|  | DiskSegment | 0.647 | 0.590 (8.8%) | 0.500 (22.7%) | 0.488 (24.6%) | — | 0.517 (20.1%) |
| 1,048,576 | Heap | 1.001 | 0.542 (45.8%) | 0.501 (50.0%) | 0.494 (50.7%) | — | 0.530 (47.0%) |
|  | OffHeap | 0.649 | 0.572 (11.8%) | 0.490 (24.4%) | 0.489 (24.7%) | — | 0.491 (24.3%) |
|  | Segment | 0.650 | 0.587 (9.7%) | 0.498 (23.5%) | 0.498 (23.4%) | — | 0.514 (21.0%) |
|  | Disk | 0.847 | 0.582 (31.3%) | 0.510 (39.9%) | 0.505 (40.4%) | — | 0.514 (39.4%) |
|  | DiskSegment | 0.650 | 0.575 (11.6%) | 0.500 (23.0%) | 0.495 (23.9%) | — | 0.535 (17.8%) |
| 4,194,304 | Heap | 1.000 | 0.537 (46.3%) | 0.493 (50.7%) | 0.489 (51.1%) | 0.519 (48.1%) | — |
|  | OffHeap | 0.660 | 0.583 (11.6%) | 0.523 (20.7%) | 0.520 (21.2%) | 0.512 (22.4%) | — |
|  | Segment | 0.659 | 0.596 (9.6%) | 0.621 (5.8%) | 0.629 (4.6%) | 0.751 (-13.9%) | — |
|  | Disk | 0.864 | 0.577 (33.3%) | 0.529 (38.8%) | 0.510 (41.0%) | 0.538 (37.8%) | — |
|  | DiskSegment | 0.659 | 0.595 (9.6%) | 0.580 (12.0%) | 0.638 (3.2%) | 0.614 (6.7%) | — |

### 1,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 11.167 | 6.148 (44.9%) | 5.419 (51.5%) | 5.270 (52.8%) | 5.893 (47.2%) |
|  | OffHeap | 9.896 | 8.278 (16.3%) | 7.658 (22.6%) | 7.506 (24.1%) | 7.689 (22.3%) |
|  | Segment | 9.890 | 8.608 (13.0%) | 7.652 (22.6%) | 7.425 (24.9%) | 7.644 (22.7%) |
|  | Disk | 10.745 | 8.566 (20.3%) | 7.436 (30.8%) | 7.549 (29.8%) | 7.778 (27.6%) |
|  | DiskSegment | 9.843 | 8.675 (11.9%) | 7.636 (22.4%) | 7.533 (23.5%) | 7.694 (21.8%) |
| 1,048,576 | Heap | 11.258 | 6.208 (44.9%) | 5.262 (53.3%) | 5.424 (51.8%) | 5.882 (47.8%) |
|  | OffHeap | 9.974 | 8.433 (15.4%) | 7.416 (25.6%) | 7.424 (25.6%) | 7.467 (25.1%) |
|  | Segment | 9.654 | 8.635 (10.6%) | 7.601 (21.3%) | 7.383 (23.5%) | 7.436 (23.0%) |
|  | Disk | 11.433 | 8.545 (25.3%) | 7.627 (33.3%) | 7.634 (33.2%) | 7.640 (33.2%) |
|  | DiskSegment | 9.425 | 8.684 (7.9%) | 7.574 (19.6%) | 7.400 (21.5%) | 7.567 (19.7%) |
| 4,194,304 | Heap | 11.228 | 6.192 (44.9%) | 5.308 (52.7%) | 5.315 (52.7%) | 5.790 (48.4%) |
|  | OffHeap | 10.014 | 8.566 (14.5%) | 7.560 (24.5%) | 7.495 (25.2%) | 7.513 (25.0%) |
|  | Segment | 9.975 | 8.587 (13.9%) | 7.600 (23.8%) | 7.542 (24.4%) | 7.499 (24.8%) |
|  | Disk | 11.538 | 8.613 (25.4%) | 7.675 (33.5%) | 7.502 (35.0%) | 7.618 (34.0%) |
|  | DiskSegment | 9.815 | 8.625 (12.1%) | 7.584 (22.7%) | 7.522 (23.4%) | 7.519 (23.4%) |

### 5,000,000,000 leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---:|---|---:|---:|---:|---:|---:|
| 262,144 | Heap | 59.813 | 32.847 (45.1%) | 27.772 (53.6%) | 27.344 (54.3%) | 30.660 (48.7%) |
|  | OffHeap | 44.577 | 40.067 (10.1%) | 33.199 (25.5%) | 32.485 (27.1%) | 36.215 (18.8%) |
|  | Segment | 44.354 | 42.876 (3.3%) | 35.795 (19.3%) | 33.288 (25.0%) | 33.974 (23.4%) |
|  | Disk | 56.840 | 46.441 (18.3%) | 39.568 (30.4%) | 39.337 (30.8%) | 41.630 (26.8%) |
|  | DiskSegment | 50.961 | 46.920 (7.9%) | 39.775 (21.9%) | 40.357 (20.8%) | 40.922 (19.7%) |
| 1,048,576 | Heap | 59.769 | 33.144 (44.5%) | 27.571 (53.9%) | 27.713 (53.6%) | 30.729 (48.6%) |
|  | OffHeap | 50.724 | 46.995 (7.4%) | 40.467 (20.2%) | 39.084 (22.9%) | 39.822 (21.5%) |
|  | Segment | 50.429 | 47.001 (6.8%) | 40.307 (20.1%) | 39.746 (21.2%) | 40.353 (20.0%) |
|  | Disk | 59.289 | 47.038 (20.7%) | 40.526 (31.6%) | 39.774 (32.9%) | 40.155 (32.3%) |
|  | DiskSegment | 51.006 | 46.787 (8.3%) | 40.256 (21.1%) | 39.012 (23.5%) | 39.439 (22.7%) |
| 4,194,304 | Heap | 59.850 | 32.652 (45.4%) | 28.104 (53.0%) | 27.029 (54.8%) | 30.773 (48.6%) |
|  | OffHeap | 50.636 | 47.103 (7.0%) | 39.654 (21.7%) | 39.505 (22.0%) | 39.142 (22.7%) |
|  | Segment | 51.099 | 46.625 (8.8%) | 40.716 (20.3%) | 38.978 (23.7%) | 39.403 (22.9%) |
|  | Disk | 60.878 | 46.100 (24.3%) | 40.659 (33.2%) | 39.637 (34.9%) | 39.486 (35.1%) |
|  | DiskSegment | 51.021 | 46.452 (9.0%) | 40.169 (21.3%) | 39.137 (23.3%) | 39.234 (23.1%) |

## LongListDisk source-cache diagnostic

The percentage in the last column is measured from `P=1` in the same source
cache state.

| Source cache | `P` | Unforced mean | Reduction from `P=1` |
|---|---:|---:|---:|
| Warm | 1 | 10.927 s | — |
| Warm | 2 | 9.010 s | 17.5% |
| Warm | 8 | 7.620 s | 30.3% |
| Cold | 1 | 14.711 s | — |
| Cold | 2 | 7.965 s | 45.9% |
| Cold | 8 | 7.267 s | 50.6% |

## Notes needed to read the tables

- `P=10` and `P=24` are the even near-maximum settings for 11 and 25 active
  chunks. Larger settings are omitted where they would use the same effective
  number of workers.
- The 10M measurements are too noisy for exact thread-count rankings. Several
  100M and 5B cells are also close or variable, so an observed minimum is not
  by itself a default-selection result.
- The complete forced-versus-unforced comparison belongs in
  [`remove-final-force.md`](remove-final-force.md).

## Method and raw evidence

- Run ID: `20260827T120713Z-523569`
- Git revision: `fe4dc4bdcd80da795a91a8519a42a90d1faed1e4`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`; `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37, single-shot mode, one fork and one JMH thread per parameter row
- Sampling: one warmup and two measurements in each of three reordered blocks
- Final force: omitted from measured `writeToFile()` and performed in
  invocation teardown before the target was deleted
- Elapsed campaign time: approximately 18 hours 54 minutes

The campaign completed all 39 planned JMH launches: 36 broad-matrix launches
and three cache-diagnostic launches. The broad matrix contains six finite raw
measurements for each of its 280 cells; the cache diagnostic contains six for
each of its six cells. No OOM, timeout, exception, or storage failure occurred.

- Campaign archive:
  [`20260827T120713Z-523569.tar.gz`](raw/20260827T120713Z-523569.tar.gz)
- Archive SHA-256:
  `4d2e92cbd3d4b8e09644ffe54d86b4e183cc1e6ea0bd23953ee5010aa8b3f931`
- Console log:
  [`20260827T120713Z-523569-console.log`](raw/20260827T120713Z-523569-console.log)
- Console SHA-256:
  `7609598ad309417c1292126008d94d032c6db639e5b9c15855176c1975613e7f`
- Runner:
  [`run-long-list-snapshot-without-force-benchmark.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-long-list-snapshot-without-force-benchmark.sh)
