# Representative Linux LongList snapshot benchmark results

> **Status:** Historical evidence from the first, interrupted Linux campaign.
> The measurements and provenance remain useful, but its execution
> recommendations are superseded. This file will be rewritten after the
> corrected baseline campaign. See
> [`snapshot-optimization-report.md`](../snapshot-optimization-report.md).

## Conclusion

The representative Linux campaign confirms a real, repeatable isolated-write
benefit for `LongListDisk`, but it does not justify a high production thread
count. At one billion leaves and the production-default 1,048,576-long chunk,
`P=2` reduced the Disk mean from 14.007 to 13.163 seconds, or 6.0%. `P=8`
reduced it to 12.941 seconds, or 7.6%; the extra six threads saved only another
222 milliseconds. `P=16` and `P=32` did not improve on `P=8`.

`LongListSegment` showed no material large-state benefit or regression. At the
same one-billion/default-chunk workload its mean moved from 13.126 seconds at
`P=1` to 12.979 at `P=2` and 12.887 at `P=8`, reductions of 1.1% and 1.8%.
The result shows no sign of a material isolated regression, but it is too
small to claim that parallel writing materially improves Segment.

`P=8` is the lowest point estimate in 12 of the 15 one-billion-leaf
implementation/chunk combinations, but this is an isolated one-list
benchmark. A production snapshot may run three LongList writers concurrently,
so `P=2` permits up to six range workers while `P=8` permits up to 24. For the
production-relevant Disk implementation, `P=2` captures 77–82% of the best
measured percentage reduction at the default chunk across 10M, 100M, and 1B.
It therefore remains the better production candidate: most of the measured
benefit for one quarter of `P=8`'s worker ceiling.

The campaign was complete through one billion leaves. The five-billion-leaf
matrix stopped after one block at the smallest chunk because the benchmark's
48 GiB heap was too small to load `LongListHeap` with the default chunk. That
failure is in benchmark sizing, not in parallel snapshot writing. The retained
five-billion result is useful only as a directional observation and cannot
establish repeatability or default/large-chunk behavior.

The next useful measurement is not completion of the full five-billion
Cartesian matrix. It is a counterbalanced production-path `P=1` versus `P=2`
snapshot comparison in the actual Segment and Disk modes. The configuration
default should remain `P=1` until that end-to-end gate confirms the isolated
Disk gain without introducing Segment or three-list contention tails.

## Campaign provenance

- Run ID: `20260805T154851Z-821100`
- Benchmark revision: `9c42d879ee4447083e31571a4cd50fef0c4368ca`
- Current branch revision: `5a43f6577d2479862537a72094cf2c03cef36d09`
- Difference between those revisions: wording-only changes in the system-check
  script; production code, benchmark code, and the runner are unchanged.
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: one NVMe-backed ext4 filesystem, `/dev/nvme1n1p1`, mounted at
  `/home`
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37, single-shot mode, one warmup and two measured writes per trial
- Source fixture: a dense leaf-index body with one non-zero location for every
  leaf
- Benchmark:
  [`LongListSnapshotBenchmark`](../../platform-sdk/swirlds-merkledb/src/jmh/java/com/swirlds/benchmark/LongListSnapshotBenchmark.java)
- Runner:
  [`run-long-list-snapshot-benchmark.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-long-list-snapshot-benchmark.sh)
- Raw input: `raw/long-list-snapshot-partial.tar.gz`
- Raw archive SHA-256:
  `4a437f94643b9ee6d04ba82969e869b61048ed95b439f8d9989c76cb26d1a9eb`

## Campaign completeness

The complete three-block matrices for 10 million, 100 million, and 1 billion
leaves are available. For 5 billion leaves, only block A with a 262,144-long
chunk completed.

The next launch, block A with the production-default 1,048,576-long chunk,
failed while loading `LongListHeap` at `P=1`. It produced no measured sample.
Seven later launches were never started.

| Leaf count | Active body | Complete launches | Result rows | Measured writes | Status |
|---:|---:|---:|---:|---:|---|
| 10M | 76.29 MiB | 9/9 | 165 | 330 | Complete A/B/C |
| 100M | 762.94 MiB | 9/9 | 225 | 450 | Complete A/B/C |
| 1B | 7.45 GiB | 9/9 | 225 | 450 | Complete A/B/C |
| 5B | 37.25 GiB | 1/9 | 25 | 50 | Small chunk, block A only |
| **Total** | — | **28/36** | **640/840** | **1,280** | Partial campaign |

Every completed result row contains two measured writes from one fork. The
completed campaign also performed one unmeasured warmup per row, for about
8.98 TB of logical output including warmups. The benchmark fixture and
snapshots were deleted by the runner; only logs and result data were retained.

## Method

Each complete cell below combines blocks A, B, and C. Every block used a
separate JMH launch and one fork with one warmup followed by two measured
writes. The displayed time is the arithmetic mean of all six measured writes.
Since every block has two measurements, this is also exactly the arithmetic
mean of the three block means.

The percentage in parentheses is the change in mean time from the `P=1` mean
in the same leaf-count, chunk, and implementation cell:

```text
(parallel mean / P=1 mean - 1) * 100
```

A negative percentage is lower and therefore faster. No measurement was
discarded. Block-level paired comparisons are used to assess consistency;
the six writes are not treated as six independent experimental replicates,
and JMH cannot calculate a confidence interval from one fork.

The runner changed parameter order across A/B/C to expose order drift. It also
avoided thread settings above the active chunk count. Consequently, the 10M
default-chunk case uses the even near-maximum `P=10`, the 10M largest-chunk
case stops at `P=2`, and the 100M largest-chunk case uses `P=24`. These special
values avoid duplicate effective concurrency; they are not additional global
thread candidates.

## Results

### One-billion-leaf production-default chunk

This is the most decision-relevant completed isolated workload. Times are
seconds; percentages are mean-time changes from `P=1`.

| Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---|---:|---:|---:|---:|---:|
| Heap | 13.915 | 12.493 (-10.2%) | 12.394 (-10.9%) | 12.619 (-9.3%) | 12.615 (-9.3%) |
| OffHeap | 13.148 | 13.026 (-0.9%) | 12.907 (-1.8%) | 12.868 (-2.1%) | 13.011 (-1.0%) |
| Segment | 13.126 | 12.979 (-1.1%) | 12.887 (-1.8%) | 13.161 (+0.3%) | 13.085 (-0.3%) |
| Disk | 14.007 | 13.163 (-6.0%) | 12.941 (-7.6%) | 13.039 (-6.9%) | 13.285 (-5.2%) |
| DiskSegment | 13.505 | 13.164 (-2.5%) | 12.914 (-4.4%) | 13.151 (-2.6%) | 13.029 (-3.5%) |

Disk `P=2` and `P=8` beat their same-block `P=1` baseline in A, B, and C.
The respective block means were 13.182/13.091/13.217 seconds for `P=2` and
13.012/12.800/13.012 seconds for `P=8`, versus
14.142/13.855/14.023 seconds for `P=1`.

### Stability

The block-mean variability falls as the workload grows:

| Leaf count | Median block-mean CV | Maximum block-mean CV | `P=1` median | `P=1` maximum |
|---:|---:|---:|---:|---:|
| 10M | 3.93% | 10.55% | 3.55% | 5.81% |
| 100M | 1.07% | 4.99% | 0.95% | 1.67% |
| 1B | 0.86% | 3.21% | 0.64% | 1.73% |

At 100M, the A/B/C `P=1` block means averaged 0.11% below, 0.21% above,
and 0.10% below their cell means. At 1B they averaged 0.14% above, 0.33%
below, and 0.20% above. There is no broad timing drift or evidence of major
external-host interference in those results. The campaign did not record
runtime CPU or storage telemetry, so brief or constant external load cannot be
excluded.

The 10M measurements are not suitable for fine ranking. For example, Heap
with 262,144-long chunks had a systematic first-versus-second measurement
effect in all three blocks: `P=8` first writes were 242.7–258.2 ms while
second writes were 141.1–152.3 ms; `P=16` first writes were 274.9–337.7 ms
while second writes were 159.0–159.8 ms. The means below accurately record the
executed protocol, but one warmup did not remove the short-run transient.

### Complete mean tables

All times below are seconds. A negative percentage is lower than the same
row's `P=1` mean.

#### 10 million leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=10` | `P=16` | `P=32` |
|---|---:|---:|---:|---:|---:|---:|---:|
| 262,144 (`2^18`) | Heap | 0.209 | 0.174 (-17.1%) | 0.199 (-4.9%) | — | 0.232 (+10.8%) | 0.162 (-22.7%) |
| | OffHeap | 0.176 | 0.162 (-8.0%) | 0.165 (-6.4%) | — | 0.160 (-9.3%) | 0.175 (-0.8%) |
| | Segment | 0.171 | 0.162 (-5.3%) | 0.164 (-4.1%) | — | 0.164 (-4.2%) | 0.186 (+9.0%) |
| | Disk | 0.176 | 0.161 (-9.0%) | 0.165 (-6.4%) | — | 0.155 (-12.2%) | 0.165 (-6.8%) |
| | DiskSegment | 0.175 | 0.166 (-5.1%) | 0.153 (-12.2%) | — | 0.182 (+3.9%) | 0.178 (+2.0%) |
| 1,048,576 (`2^20`, default) | Heap | 0.208 | 0.176 (-15.4%) | 0.156 (-24.8%) | 0.163 (-21.7%) | — | — |
| | OffHeap | 0.172 | 0.166 (-3.2%) | 0.180 (+4.5%) | 0.175 (+1.8%) | — | — |
| | Segment | 0.180 | 0.163 (-9.4%) | 0.171 (-4.8%) | 0.175 (-2.3%) | — | — |
| | Disk | 0.192 | 0.165 (-14.1%) | 0.159 (-17.2%) | 0.160 (-16.8%) | — | — |
| | DiskSegment | 0.169 | 0.167 (-1.1%) | 0.171 (+1.5%) | 0.186 (+10.3%) | — | — |
| 4,194,304 (`2^22`) | Heap | 0.209 | 0.189 (-9.4%) | — | — | — | — |
| | OffHeap | 0.187 | 0.181 (-3.4%) | — | — | — | — |
| | Segment | 0.186 | 0.181 (-2.4%) | — | — | — | — |
| | Disk | 0.191 | 0.177 (-7.5%) | — | — | — | — |
| | DiskSegment | 0.185 | 0.176 (-4.8%) | — | — | — | — |

#### 100 million leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=24` | `P=32` |
|---|---:|---:|---:|---:|---:|---:|---:|
| 262,144 (`2^18`) | Heap | 2.079 | 1.600 (-23.0%) | 1.563 (-24.8%) | 1.565 (-24.8%) | — | 1.604 (-22.9%) |
| | OffHeap | 1.715 | 1.662 (-3.1%) | 1.568 (-8.5%) | 1.551 (-9.6%) | — | 1.568 (-8.5%) |
| | Segment | 1.710 | 1.638 (-4.2%) | 1.570 (-8.2%) | 1.559 (-8.8%) | — | 1.579 (-7.7%) |
| | Disk | 1.804 | 1.608 (-10.9%) | 1.526 (-15.4%) | 1.531 (-15.1%) | — | 1.576 (-12.6%) |
| | DiskSegment | 1.696 | 1.644 (-3.1%) | 1.536 (-9.4%) | 1.537 (-9.4%) | — | 1.574 (-7.2%) |
| 1,048,576 (`2^20`, default) | Heap | 2.069 | 1.592 (-23.1%) | 1.560 (-24.6%) | 1.565 (-24.4%) | — | 1.600 (-22.7%) |
| | OffHeap | 1.714 | 1.651 (-3.7%) | 1.554 (-9.3%) | 1.563 (-8.8%) | — | 1.564 (-8.8%) |
| | Segment | 1.712 | 1.635 (-4.5%) | 1.554 (-9.2%) | 1.549 (-9.5%) | — | 1.589 (-7.2%) |
| | Disk | 1.889 | 1.633 (-13.6%) | 1.556 (-17.6%) | 1.569 (-16.9%) | — | 1.599 (-15.3%) |
| | DiskSegment | 1.736 | 1.662 (-4.3%) | 1.583 (-8.9%) | 1.599 (-7.9%) | — | 1.603 (-7.7%) |
| 4,194,304 (`2^22`) | Heap | 2.041 | 1.606 (-21.3%) | 1.577 (-22.7%) | 1.558 (-23.7%) | 1.581 (-22.5%) | — |
| | OffHeap | 1.721 | 1.660 (-3.5%) | 1.571 (-8.7%) | 1.562 (-9.3%) | 1.584 (-7.9%) | — |
| | Segment | 1.724 | 1.643 (-4.7%) | 1.639 (-4.9%) | 1.661 (-3.6%) | 1.805 (+4.7%) | — |
| | Disk | 1.905 | 1.636 (-14.1%) | 1.586 (-16.7%) | 1.559 (-18.1%) | 1.583 (-16.9%) | — |
| | DiskSegment | 1.712 | 1.640 (-4.2%) | 1.649 (-3.7%) | 1.692 (-1.2%) | 1.791 (+4.6%) | — |

#### 1 billion leaves

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---|---:|---:|---:|---:|---:|---:|
| 262,144 (`2^18`) | Heap | 13.826 | 12.501 (-9.6%) | 12.325 (-10.9%) | 12.488 (-9.7%) | 12.777 (-7.6%) |
| | OffHeap | 13.208 | 13.018 (-1.4%) | 12.872 (-2.5%) | 12.895 (-2.4%) | 13.063 (-1.1%) |
| | Segment | 13.148 | 13.118 (-0.2%) | 12.936 (-1.6%) | 12.956 (-1.5%) | 13.129 (-0.1%) |
| | Disk | 13.921 | 13.165 (-5.4%) | 13.062 (-6.2%) | 13.010 (-6.5%) | 13.125 (-5.7%) |
| | DiskSegment | 13.345 | 13.153 (-1.4%) | 12.936 (-3.1%) | 13.051 (-2.2%) | 13.243 (-0.8%) |
| 1,048,576 (`2^20`, default) | Heap | 13.915 | 12.493 (-10.2%) | 12.394 (-10.9%) | 12.619 (-9.3%) | 12.615 (-9.3%) |
| | OffHeap | 13.148 | 13.026 (-0.9%) | 12.907 (-1.8%) | 12.868 (-2.1%) | 13.011 (-1.0%) |
| | Segment | 13.126 | 12.979 (-1.1%) | 12.887 (-1.8%) | 13.161 (+0.3%) | 13.085 (-0.3%) |
| | Disk | 14.007 | 13.163 (-6.0%) | 12.941 (-7.6%) | 13.039 (-6.9%) | 13.285 (-5.2%) |
| | DiskSegment | 13.505 | 13.164 (-2.5%) | 12.914 (-4.4%) | 13.151 (-2.6%) | 13.029 (-3.5%) |
| 4,194,304 (`2^22`) | Heap | 13.912 | 12.523 (-10.0%) | 12.422 (-10.7%) | 12.340 (-11.3%) | 12.631 (-9.2%) |
| | OffHeap | 13.203 | 12.998 (-1.6%) | 12.848 (-2.7%) | 13.012 (-1.4%) | 13.221 (+0.1%) |
| | Segment | 13.180 | 13.053 (-1.0%) | 12.865 (-2.4%) | 13.029 (-1.1%) | 13.159 (-0.2%) |
| | Disk | 14.002 | 13.144 (-6.1%) | 12.968 (-7.4%) | 13.076 (-6.6%) | 13.162 (-6.0%) |
| | DiskSegment | 13.373 | 13.167 (-1.5%) | 13.001 (-2.8%) | 13.238 (-1.0%) | 13.418 (+0.3%) |

#### 5 billion leaves: preliminary block A only

Only the smallest chunk completed. These cells average two writes from one
fork and have no B/C replication, so the table is not comparable in evidential
strength to the complete tables above.

| Chunk (longs) | Implementation | `P=1` | `P=2` | `P=8` | `P=16` | `P=32` |
|---|---:|---:|---:|---:|---:|---:|
| 262,144 (`2^18`) | Heap | 61.003 | 59.103 (-3.1%) | 58.774 (-3.7%) | 57.519 (-5.7%) | 57.574 (-5.6%) |
| | OffHeap | 58.074 | 58.375 (+0.5%) | 58.057 (-0.0%) | 57.671 (-0.7%) | 57.683 (-0.7%) |
| | Segment | 58.383 | 58.400 (+0.0%) | 58.160 (-0.4%) | 57.554 (-1.4%) | 57.629 (-1.3%) |
| | Disk | 60.323 | 58.781 (-2.6%) | 58.863 (-2.4%) | 58.580 (-2.9%) | 58.718 (-2.7%) |
| | DiskSegment | 59.266 | 58.967 (-0.5%) | 59.102 (-0.3%) | 58.484 (-1.3%) | 58.554 (-1.2%) |

## Interpretation

### Disk is the production beneficiary

Only Segment and Disk are selected by `MerkleDbDataSource`; Heap, OffHeap, and
DiskSegment are diagnostic implementations. Disk is the only production
implementation with a material, repeatable isolated benefit in the large
completed workload.

At `P=2`, Disk beat `P=1` in every A/B/C block for every complete
leaf-count/chunk combination: 27 of 27 paired blocks. At the default chunk its
mean-time reduction was 14.1% at 10M, 13.6% at 100M, and 6.0% at 1B. The
absolute saving increased from 27 to 256 to 844 milliseconds even as the
percentage fell. The preliminary 5B/small-chunk block saved 1.54 seconds, or
2.6%.

`P=8` reduced the same default-chunk Disk means by 17.2%, 17.6%, and 7.6%.
Thus `P=2` captured 82%, 77%, and 79% of the best measured percentage
reduction at those sizes. At 1B the difference between `P=2` and `P=8` was
222 milliseconds, while the three-list production worker ceiling changes from
six to 24. No `P=16` or `P=32` default-chunk result improved on `P=8`.

The source implementations converge as the output becomes large. At 1B and
the default chunk, Disk is 14.007 seconds at `P=1`, versus 13.126 for Segment.
At `P=8`, Disk is 12.941 seconds, while Segment, OffHeap, and DiskSegment are
12.887, 12.907, and 12.914. This is consistent with parallel workers hiding
Disk's backing-file read/copy stage until the common target write becomes the
limiter. The benchmark did not profile CPU, page cache, or device queues, so
that mechanism remains an inference from code and timing, not a direct
measurement.

### Segment is effectively neutral at large scale

Segment `P=2` reductions at 1B were 0.2%, 1.1%, and 1.0% for the small,
default, and large chunks. Its default-chunk reduction declined from 9.4% at
10M to 4.5% at 100M and 1.1% at 1B; the preliminary 5B/small-chunk result was
neutral. This supports no material isolated Segment gain or regression at
large scale.

Heap retains a clear source-side benefit: `P=2` reduced its means by about 23%
at 100M and 10% at 1B. OffHeap and DiskSegment gains at 1B were generally only
1–4%. Those implementations help explain the mechanism but do not select a
production setting.

### The useful plateau starts before the highest thread counts

At 100M, the lowest point estimate was `P=8` in eight of 15
implementation/chunk cells, `P=16` in six, and `P=2` in one. At 1B, `P=8` was
lowest in 12 of 15 and `P=16` in three. Exact winners are under-resolved:
across the 40 cells with at least two parallel settings, the two lowest
parallel means differ by at most 1% in 23 cells and by at most 2% in 33.

`P=8` is therefore the strongest isolated throughput knee, not proof that
eight threads per list should be the production default. A real snapshot can
create `3P` range workers: 24 at `P=8`, 48 at `P=16`, and 96 at `P=32` on this
32-hardware-thread host. The isolated benchmark measures only `P` workers and
cannot expose that three-list contention. `P=2` is the lower-resource setting
that retains most of Disk's observed plateau.

Near-one-worker-per-active-chunk settings provide no reason to go higher. At
100M with 25 large chunks, `P=24` made Segment 4.7% and DiskSegment 4.6%
slower than `P=1`; Disk was already on the same plateau reached at `P=8`.

### Chunk size does not require tuning for this feature

At 1B, Disk `P=2` reductions were 5.4%, 6.0%, and 6.1% across the three chunk
sizes; `P=8` reductions were 6.2%, 7.6%, and 7.4%. Segment `P=2` remained
between 0.2% and 1.1%. The parallel-write decision is therefore insensitive
to the tested chunk-size range at the largest complete state. These results do
not support changing the production chunk default or adding chunk-specific
parallel behavior.

### Scope of the result

The fixture faithfully represents a dense leaf-index body: `N` non-zero
locations for paths `N-1` through `2N-2`, producing `8N + 12` bytes. The timed
method includes target creation, body writing, force, and close. Fixture
creation/loading and target deletion are outside the timed operation.

It is nevertheless not a complete MerkleDB snapshot. It writes one LongList,
not three lists plus the data-store and metadata tasks, and it does not model
all production source-cache states. `verify=false` was intentional after the
benchmark smoke validation, so this campaign supplies performance evidence,
not new correctness evidence. Finally, `P=1` is the branch-local control using
the generalized positional writer; these numbers are not a branch-versus-main
regression comparison.

## Failure analysis and remaining evidence

The failed launch was deterministic benchmark heap exhaustion:

```text
leafCount             = 5,000,000,000
longListChunkSize     = 1,048,576
listImpl              = LongListHeap
threadsPerLongList    = 1
failure               = java.lang.OutOfMemoryError: Java heap space
phase                 = setup, while loading the source fixture
configured heap       = -Xmx48g
```

It happened before warmup and before any sequential or parallel snapshot
write. Other activity on the host could affect timings, but it does not explain
this stop: external memory exhaustion would normally kill the process, while
this JVM explicitly exhausted its own bounded Java heap.

Five billion active longs contain 40,000,000,000 payload bytes, or 37.25 GiB.
That raw size misses whole-array and G1-region allocation effects. With the
32 MiB G1 regions selected by this Temurin/Xmx combination:

- a 262,144-long array is 2 MiB plus its header; 15 fit per region;
- a 1,048,576-long array is 8 MiB plus its header; only three fit per region,
  because four exceed 32 MiB by their headers; and
- a 4,194,304-long array is 32 MiB plus its header and consumes two regions as
  a humongous object.

| Chunk (longs) | Active arrays | Minimum G1-region footprint | Outcome under `-Xmx48g` |
|---:|---:|---:|---|
| 262,144 | 19,074 | 39.75 GiB | Completed |
| 1,048,576 | 4,769 | 49.69 GiB | Failed before warmup |
| 4,194,304 | 1,193 | 74.56 GiB | Would not fit |

The runner sized heap from `N * 8` and therefore underestimated Heap's
chunk-dependent retained footprint. A future full 5B retry would need roughly
64 GiB for the default chunk and 96 GiB for the largest chunk. No production
change is implied.

Completing the eight missing launches would answer the original Cartesian
matrix, but would regenerate the 37.25 GiB fixture and spend most of the
campaign's I/O on high-thread and diagnostic combinations that the completed
results already place on a plateau. It is not the recommended next step.

The decision-oriented next gate is:

1. Compare only `P=1` and `P=2` through the production snapshot path.
2. Exercise the actual Segment and Disk modes.
3. Counterbalance and repeat the cases at the largest practical
   production-shaped state.
4. Capture total latency and tails together with CPU, memory, and device-queue
   telemetry so three-list contention is visible.

If the production-path fixture is unavailable, the narrower fallback is a
three-block 5B/default-chunk run for Segment and Disk at `P={1,2}`. It resolves
the missing large-state production question without rerunning all five
implementations and high-thread plateaus.

## Verification

The result handoff and this report were checked as follows:

- the gzip/tar stream is valid, all paths are relative, and there are no
  duplicate members;
- the archive contains 29 logs, 29 JSON files, and three provenance files;
- 28 JSON files parse and the failed launch's JSON is the only zero-byte file;
- all 640 result entries have the expected filename parameters, unique
  implementation/thread combinations, and exactly two finite positive raw
  measurements;
- every JMH score equals the arithmetic mean of its two raw measurements;
- independent parsers reproduced all 230 aggregated cells, percentages,
  block-variability statistics, and best-thread counts used here;
- selected aggregates were reconciled against the human-readable JMH summaries;
  and
- the benchmark revision differs from the current branch only in two lines of
  wording in the system-check script. Production code, benchmark code, and the
  runner are identical.

No raw measurement was removed or replaced. The incomplete 5B launch is used
only for failure diagnosis, never as a timing sample.
