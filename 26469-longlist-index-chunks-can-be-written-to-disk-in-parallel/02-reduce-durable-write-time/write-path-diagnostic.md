# LongList pre-force write-path diagnostic

> **Status:** Linux campaign complete.

## Results

All values are arithmetic means of 15 measurements: five in each of three
reordered blocks. Non-force time is JMH total minus the final force; it includes
the small create, header, and close overhead. Target-write wall time is the
time covered by at least one target `FileChannel.write()` call; overlapping
calls from parallel workers are counted once.

| Workload | Total | Non-force | Target-write wall time | Outside target writes | Final force | Total gap from FileChannel |
|---|---:|---:|---:|---:|---:|---:|
| FileChannel | 12.839 s | 5.595 s | 5.595 s | 0.5 ms | 7.244 s | — |
| Heap | 12.610 s | 5.391 s | 5.370 s | 21.8 ms | 7.218 s | 1.79% faster |
| OffHeap | 13.316 s | 7.613 s | 7.611 s | 1.9 ms | 5.703 s | 3.71% slower |
| Segment | 13.313 s | 7.574 s | 7.568 s | 5.3 ms | 5.739 s | 3.69% slower |
| Disk | 13.517 s | 7.627 s | 7.624 s | 3.0 ms | 5.890 s | 5.28% slower |
| DiskSegment | 13.418 s | 7.526 s | 7.522 s | 4.2 ms | 5.892 s | 4.51% slower |

For the four slower LongLists, 99.76–99.93% of their additional non-force wall
time relative to FileChannel is covered by at least one target write call.
Only 1.3–4.7 milliseconds of their approximately 1.93–2.03-second non-force
gap is outside all target writes.

## Answer

The data does not show a material serial gap in which the LongList prepares
data while no target write is active. During virtually the entire remaining
gap, at least one worker is inside a target `FileChannel.write()` call. Those
calls include copying bytes from the supplied source buffer into the operating
system's file cache and any wait caused by the buffered write path.

This wall-clock overlap does not prove that source access and preparation are
free. With eight workers, one worker can prepare or read data while another is
inside `write()`. The experiment rules out idle time between target writes as
the explanation; JFR does not identify the kernel-, buffer-, or source-level
cause inside the recorded intervals.

The control repeatedly submits one already-hot 8 MiB direct buffer without
refilling it. OffHeap, Segment, and DiskSegment submit successive chunks of
their source memory; Disk refills a per-worker transfer buffer from its backing
file. This experiment locates the difference inside the write calls, but does
not distinguish the lower-level effects of source-buffer type, source-page
residency, memory copying, and operating-system write throttling.

Disk read exactly 8 GB from its source file in every invocation. Its source
reads covered 1.243 seconds of wall time on average, of which 1.240 seconds
overlapped target writes from other workers. The reads are therefore not a
separate 1.243-second addition to the total.

The difference from FileChannel is small in final durable time—0.474–0.678
seconds for the four slower implementations—and is not evidence for physical
preallocation or direct I/O. File growth and the buffered target path are also
present in the faster control. Further buffer- and kernel-level investigation
belongs in a separate follow-up rather than the parallel-chunk PR.

## Validation and raw evidence

The focused totals differ from the preceding phase campaign by no more than
0.65%, so recording every file event did not materially change the workload.
All three blocks reproduced the same ordering for the four slower LongLists.

The campaign produced 18 complete recordings and 90 measured benchmark
invocations. Every invocation wrote exactly 8,000,000,012 target bytes and
recorded one final force. No sample was excluded.

- Run ID: `20260826T171953Z-88872`
- Git revision: `a323906ae51781cc9f56d5fa81058838db6f624d`
- Host: AMD EPYC 9124, 16 physical cores and 32 hardware threads, 125 GiB RAM
- Storage: Micron 7450 `MTFDKBA480TFR`; `/home` on ext4
- JVM: Eclipse Temurin 25.0.2+10
- Raw archive:
  [`20260826T171953Z-88872.tar.gz`](raw/20260826T171953Z-88872.tar.gz)
- SHA-256:
  `ea4691d354421047e0d3c1f88a16a5c855e18a931287c2ecfced311141d1fadb`

The archive contains the exact runner and JFR configuration, environment and
build logs, six JMH JSON files, readable logs, and all 18 recordings. The
build and all cells completed without a benchmark failure.
