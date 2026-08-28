# LongList snapshot phase breakdown

> **Status:** Linux campaign complete.

## Results

All values are seconds and are arithmetic means of 15 measurements: five in
each of three reordered blocks. Non-force time is JMH total time minus the
duration of the final `force(true)` recorded by JFR.

### One writer

| Workload | Total | Non-force | Final force | Total gap from FileChannel |
|---|---:|---:|---:|---:|
| FileChannel | 13.311 | 7.167 | 6.144 | — |
| Heap | 14.406 | 11.295 | 3.111 | 8.23% slower |
| OffHeap | 13.903 | 9.875 | 4.028 | 4.45% slower |
| Segment | 13.939 | 9.903 | 4.037 | 4.72% slower |
| Disk | 14.620 | 11.198 | 3.421 | 9.83% slower |
| DiskSegment | 14.022 | 9.726 | 4.296 | 5.34% slower |

### Eight writers

| Workload | Total | Non-force | Final force | Total gap from FileChannel | Reduction from `P=1` |
|---|---:|---:|---:|---:|---:|
| FileChannel | 12.892 | 5.760 | 7.133 | — | 3.15% |
| Heap | 12.691 | 5.384 | 7.307 | 1.56% faster | 11.91% |
| OffHeap | 13.333 | 7.531 | 5.803 | 3.42% slower | 4.09% |
| Segment | 13.302 | 7.530 | 5.772 | 3.18% slower | 4.57% |
| Disk | 13.516 | 7.631 | 5.885 | 4.84% slower | 7.55% |
| DiskSegment | 13.405 | 7.524 | 5.881 | 3.98% slower | 4.40% |

The four slower `P=8` LongLists remained slower than the same-block control in
all three blocks:

| Implementation | Smallest block gap | Largest block gap |
|---|---:|---:|
| OffHeap | 2.65% | 4.39% |
| Segment | 2.70% | 3.74% |
| Disk | 4.57% | 5.29% |
| DiskSegment | 3.58% | 4.41% |

The largest total-time coefficient of variation (standard deviation divided
by the mean) among all cells was 1.78%.

## Conclusion

- The same-campaign comparison confirms a `P=8` gap for OffHeap, Segment,
  Disk, and DiskSegment. Its mean is 3.18–4.84%, slightly smaller than the
  earlier 4.7–5.4% cross-campaign comparison, and it is not benchmark noise.
- The remaining gap occurs before the final force. The four implementations
  spend 1.764–1.872 seconds more than FileChannel outside the force, while
  their final force is 1.247–1.361 seconds shorter. The shorter force offsets
  most of the earlier-phase difference; it does not cause the total gap.
- `P=8` improves total time for the control and every LongList implementation.
  The non-force phase finishes sooner, but a longer final force absorbs part
  of that saving.

Non-force time still combines source access, data preparation, and target
write calls. The completed follow-up found that 99.76–99.93% of the four
slower implementations' additional non-force wall time is covered by at least
one target write call, rather than forming a serial gap between calls. See
[`write-path-diagnostic.md`](write-path-diagnostic.md). That result does not
support physical preallocation or direct I/O. The separate force-removal
campaigns are complete: omitting the force returns earlier while moving the
remaining storage wait beyond `writeToFile()`. See
[`remove-final-force.md`](../03-remove-final-force/remove-final-force.md).

## Method and validation

| Parameter | Value |
|---|---|
| Workloads | Prepared-memory FileChannel control and all five LongList implementations |
| Writer threads | `P={1,8}` |
| Leaf count | `1,000,000,000` |
| LongList chunk size | `1,048,576` longs |
| Sampling | Three reordered blocks; one warmup and five measurements per cell |
| Profiler | JFR `jdk.FileForce`, recorded during measured iterations |

The campaign produced 36 complete JFR recordings and 180 measured writes.
Every measured write has exactly one target-file `jdk.FileForce` event. For
the FileChannel control, JFR force durations match the benchmark's explicit
force counter within 0.107 milliseconds; total minus its body and force
counters leaves a mean 0.215 milliseconds for file creation, header, and
close. No sample was excluded.

## Raw evidence

- Run ID: `20260826T153220Z-44134`
- Git revision: `9c3050a67c2f37affc5ed68f760144bde40f76ba`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`; `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- Raw archive:
  [`20260826T153220Z-44134.tar.gz`](raw/20260826T153220Z-44134.tar.gz)
- SHA-256:
  `cdf41c2eb9c66f7cff53bc2c49a8132173a97b5ff097615b29d892ae69f531dc`

The archive contains the exact runner, environment, build log, six JMH JSON
files, readable logs, and all 36 JFR recordings. The build and all benchmark
cells completed without an exception, timeout, out-of-memory error, or
storage-space failure.
