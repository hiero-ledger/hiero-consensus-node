# Prepared-memory FileChannel write reference

> **Status:** Control campaign complete. The durable-write reference is
> established; revisit the LongList comparison after the corrected baseline
> campaign.

## Conclusion and decision

The practical durable-write reference for an 8 GB file on the representative
Linux host is approximately **12.62 seconds, or 634 MB/s**. `P=8` and `P=16`
reached the same plateau; their means differed by 13 milliseconds, or 0.1%.
`P=32` was slower.

Additional writers substantially shortened the body-write phase, but most of
that time moved into the final `force(true)`. Compared with `P=1`, `P=16`
finished its write calls 1.85 seconds earlier and then spent 1.40 seconds
longer in `force(true)`, leaving a durable end-to-end saving of 0.45 seconds,
or 3.4%.

No additional `P=8` versus `P=16` control run is needed. This experiment must
identify the practical write plateau, not select the production LongList
thread count, and both settings establish the same reference. The corrected
LongList baseline is the next gate.

## Environment and method

- Run ID: `20260824T155243Z-3103148`
- Git revision: `a6fc3a5eac470a4877d04c911cf72b478d9fa805`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`, `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37, single-shot mode
- Benchmark:
  [`FileChannelWriteBenchmark`](../../platform-sdk/swirlds-merkledb/src/jmh/java/com/swirlds/benchmark/FileChannelWriteBenchmark.java)
- Runner:
  [`run-filechannel-write-reference.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-filechannel-write-reference.sh)

The benchmark prepared one immutable direct 8 MiB buffer containing dense,
deterministic pseudo-random data before timing. Each invocation created a file,
wrote a 12-byte LongList-shaped header and an 8,000,000,000-byte body through
one shared `FileChannel`, called `force(true)`, and closed and deleted the file.
Workers owned balanced, contiguous, non-overlapping ranges.

The campaign tested `writerThreads={1,2,8,16,32}` in three reordered blocks.
Each setting performed one warmup followed by two measured writes per block,
giving six measured writes per setting and 30 measured writes in total. Every
number below is the arithmetic mean of those six raw measurements. The runner
performed approximately 360 GB of cumulative output including warmups while
retaining at most one 8 GB target.

The body and force means were recomputed from each JSON `rawData` sample. JMH's
displayed auxiliary `score` sums the two measurements within a block, so that
summary value is not a per-write mean.

## Results

| Writers | Body mean | `force(true)` mean | Total mean | Durable throughput | Change from `P=1` |
|---:|---:|---:|---:|---:|---:|
| 1 | 7.389 s | 5.677 s | 13.066 s | 612.3 MB/s | — |
| 2 | 6.431 s | 6.567 s | 12.999 s | 615.4 MB/s | 0.5% faster |
| 8 | 5.546 s | 7.082 s | 12.628 s | 633.5 MB/s | 3.4% faster |
| 16 | 5.538 s | 7.077 s | **12.616 s** | **634.1 MB/s** | **3.4% faster** |
| 32 | 5.645 s | 7.119 s | 12.764 s | 626.8 MB/s | 2.3% faster |

`P=8` and `P=16` beat their same-block `P=1` mean in A, B, and C. Their
relative ranking was mixed across the three blocks, so the 0.1% aggregate
difference is not meaningful. `P=16` had the lowest variability: its six total
times ranged from 12.535 to 12.667 seconds, with a 0.36% coefficient of
variation. `P=2` improved in two blocks and regressed in one; its 0.5% aggregate
change is not a material result.

## Interpretation

Parallel writers make the Java write calls finish sooner, but they leave more
of the durability wait until `force(true)`. The body phase fell by about 25%
from `P=1` to the `P=8`/`P=16` plateau, while total durable time fell by only
3.4%. The prepared-memory path therefore does not have a large parallel-write
gain hidden behind single-threaded `FileChannel.write()` calls on this host.

The 12.62-second mean corresponds to 634 MB/s, about 91% of the drive's
published 700 MB/s sequential-write rate. The published rate uses a different
workload and is not an exact ceiling for Java and ext4, but the control confirms
that the current path is already operating near the storage device's reported
range.

## Provisional LongList comparison

The historical one-billion-leaf/default-chunk campaign reported best means of
12.394 seconds for Heap, 12.868 for OffHeap, 12.887 for Segment, 12.941 for
Disk, and 12.914 for DiskSegment. These range from 1.8% faster to 2.6% slower
than the 12.616-second control.

Those measurements came from a separate campaign and revision. Heap appearing
1.8% faster than the prepared-memory control demonstrates that differences of
this size cannot be treated as precise source overhead across campaigns. The
useful provisional observation is that the five implementations converged to
within roughly 3% of the same durable-write plateau. Recompute this comparison
after the corrected LongList baseline; only that result can decide whether an
implementation has material same-path headroom worth profiling.

## Verification and raw evidence

- Raw archive:
  [`20260824T155243Z-3103148.tar.gz`](20260824T155243Z-3103148.tar.gz)
- SHA-256:
  `62c0999ec46f860f97913b5e212b60bd39358ec32afd4ca1831292d24a535a1a`
- All three JSON files contain five settings and ten measured writes.
- All 30 total-time samples equal their corresponding body and force samples
  within approximately 0.3 milliseconds of header and close overhead.
- The logs contain no benchmark failure or incomplete setting.
- The benchmark revision had a clean Git worktree.

## Next gate

Run the corrected LongList baseline for all five implementations. Then revisit
this document, replace the provisional cross-campaign comparison, and decide
whether any implementation has a material gap from the prepared-memory
reference. Do not start physical-preallocation or direct-I/O experiments based
on this control alone.
