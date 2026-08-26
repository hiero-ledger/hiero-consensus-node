# Prepared-memory FileChannel write reference

> **Status:** Control campaign and corrected LongList comparison complete.

## What this benchmark measures

The control asks how quickly the same Java `FileChannel` → ext4 → NVMe path
can make an 8 GB LongList-shaped file durable after LongList traversal, source
reads, and value preparation have been removed.

It is the practical target-write reference for this host and file size. It is
not an absolute SSD limit and does not select the production LongList thread
count.

## Method in plain English

Before timing starts, the benchmark creates one 8 MiB direct buffer containing
dense deterministic pseudo-random bytes. It reuses that buffer while still
writing every byte of the 8,000,000,000-byte target body; it does not allocate
or read an 8 GB source.

Each measured invocation then:

1. Creates a new target file and writes the 12-byte LongList header.
2. Writes the 8 GB body. With multiple writers, the body is split into
   balanced, contiguous, non-overlapping ranges of one shared `FileChannel`.
3. Calls `force(true)` so the file is durable, then closes it.
4. Deletes the target after the timed operation.

The benchmark records the body-write phase and final `force(true)` separately.
JMH's total includes file creation, header, body writes, force, and close.

The campaign tested `writerThreads={1,2,8,16,32}` in three reordered blocks.
Each setting had one warmup and two measured writes per block, giving six
measured writes per setting. Every number below is the arithmetic mean of
those six writes.

## Results

| Writers | Body mean | `force(true)` mean | Total mean | Durable throughput | Change from `P=1` |
|---:|---:|---:|---:|---:|---:|
| 1 | 7.389 s | 5.677 s | 13.066 s | 612.3 MB/s | — |
| 2 | 6.431 s | 6.567 s | 12.999 s | 615.4 MB/s | 0.5% faster |
| 8 | 5.546 s | 7.082 s | 12.628 s | 633.5 MB/s | 3.4% faster |
| 16 | 5.538 s | 7.077 s | **12.616 s** | **634.1 MB/s** | **3.4% faster** |
| 32 | 5.645 s | 7.119 s | 12.764 s | 626.8 MB/s | 2.3% faster |

## Where the tested plateau is

`P=8` and `P=16` differ by only 13 milliseconds, or 0.1%, and each was faster
than its same-block `P=1` in all three blocks. `P=32` was slower. The practical
tested plateau is therefore approximately **12.62 seconds, or 634 MB/s**: it
is first reached at `P=8`, is not improved by `P=16`, and regresses at `P=32`.

Additional writers make the Java body-write calls finish sooner, but they
leave more outstanding work for `force(true)`. From `P=1` to `P=16`, body time
fell by 1.85 seconds while force time grew by 1.40 seconds, so durable total
time improved by only 0.45 seconds.

## Initial cross-campaign comparison with all five LongLists

The corrected one-billion-leaf/default-chunk benchmark writes the same
8,000,000,012-byte file shape. The values below come from the equal-sample
broad matrix: six measurements for every implementation and thread setting.

| Implementation | Fastest setting | Mean | Gap from 12.616-second reference |
|---|---:|---:|---:|
| Heap | `P=16` | 12.601 s | -0.1%; effectively identical |
| OffHeap | `P=16` | 13.205 s | 4.7% slower |
| Segment | `P=8` | 13.204 s | 4.7% slower |
| Disk | `P=8` | 13.301 s | 5.4% slower |
| DiskSegment | `P=8` | 13.286 s | 5.3% slower |

Heap shows that LongList output can reach the prepared-memory reference. The
other four implementations remain 4.7–5.4% slower, but this end-to-end gap
does not identify whether the cause is source access, copying, buffer shape,
file growth, or another phase.

The comparison applies only to the 8 GB one-billion-leaf workload. The 40 GB
five-billion-leaf workload needs a same-size control before this number can be
treated as its practical reference.

A later direct comparison ran the control and all five LongLists in the same
three blocks with 15 measurements per cell. At `P=8`, Heap was 1.56% faster
than the control and the other four implementations were 3.18–4.84% slower.
That gap occurs before the final force. See
[`phase-breakdown.md`](../02-reduce-durable-write-time/phase-breakdown.md).

## Environment and raw evidence

- Run ID: `20260824T155243Z-3103148`
- Git revision: `a6fc3a5eac470a4877d04c911cf72b478d9fa805`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`; `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37, single-shot mode
- Benchmark:
  [`FileChannelWriteBenchmark`](../../platform-sdk/swirlds-merkledb/src/jmh/java/com/swirlds/benchmark/FileChannelWriteBenchmark.java)
- Runner:
  [`run-filechannel-write-reference.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-filechannel-write-reference.sh)
- Raw archive:
  [`20260824T155243Z-3103148.tar.gz`](20260824T155243Z-3103148.tar.gz)
- SHA-256:
  `62c0999ec46f860f97913b5e212b60bd39358ec32afd4ca1831292d24a535a1a`

All 30 measured writes completed. The logs contain no benchmark failure or
incomplete setting, and the measured revision had a clean Git worktree.
