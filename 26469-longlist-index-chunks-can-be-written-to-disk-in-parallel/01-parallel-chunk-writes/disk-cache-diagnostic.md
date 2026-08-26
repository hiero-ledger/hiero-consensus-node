# LongListDisk source-cache diagnostic

> **Status:** Focused warm-versus-cold campaign complete.

This is an implementation-specific diagnostic, not part of the equal-sample
comparison across all five LongList implementations.

## Results

Each cell contains six measurements: two from each of three reordered blocks.
CV is the observed sample standard deviation divided by the mean.

| Source cache | `P` | Mean | CV | Observed range | Reduction from `P=1` |
|---|---:|---:|---:|---:|---:|
| Warm | 1 | 14.389 s | 0.64% | 14.242–14.508 s | — |
| Warm | 2 | 13.760 s | 0.40% | 13.713–13.865 s | 4.4% |
| Warm | 8 | 13.396 s | 1.02% | 13.245–13.540 s | 6.9% |
| Cold | 1 | 19.609 s | 0.60% | 19.485–19.774 s | — |
| Cold | 2 | 16.757 s | 1.12% | 16.423–16.954 s | **14.5%** |
| Cold | 8 | 16.261 s | 0.63% | 16.144–16.431 s | **17.1%** |

Source residency therefore changes both absolute `LongListDisk` snapshot time
and the measured benefit from parallel writers.

## Method

- Workload: `LongListDisk`, one billion leaves, 1,048,576 longs per chunk,
  and `P={1,2,8}`.
- The source was forced to storage once during trial setup so both cache arms
  started from the same durable file.
- Before every warmup and measured write, the warm arm read the complete
  source; `fincore` verified 8,005,656,576 resident bytes.
- Before every cold write, `posix_fadvise(POSIX_FADV_DONTNEED)` evicted the
  source; `fincore` verified zero resident bytes.
- Only source residency changed. Target creation, body writing, `force(true)`,
  close, and deletion used the normal benchmark path.

All 27 warm and all 27 cold preparation checks passed.

## Production relevance and benchmark decision

A complete cold-baseline rerun is not justified by this result:

- With the default `merkleDb.useDiskIndices=false`, normal periodic snapshots
  use `LongListSegment`, so a `LongListDisk` source cache is not involved.
- When Disk mode is enabled, loading an index copies its complete body into a
  new temporary backing file. Runtime `get()` and `put()` operations access
  that file, and enabled garbage scans may traverse the complete active index.
  The code never deliberately evicts it.
- The default save period is 900 seconds. At 8–10K TPS, 7.2–9.0 million
  transactions occur between periodic snapshots. Transaction type, cache hit
  rate, and modified records prevent converting TPS into an exact number of
  LongList page accesses, but steady activity favors a warm or partly warm
  source rather than a deliberately cold one.
- Linux may still evict pages under memory pressure, so Java code alone cannot
  guarantee how much of a large source remains resident.

The ordinary broad-matrix Disk measurements are warm-like because every
measured invocation followed a complete warmup snapshot. The cold arm above is
a sensitivity and memory-pressure case, not a replacement baseline.

If stronger production grounding becomes necessary, measure actual
`LongListDisk` backing-file residency immediately before an end-to-end
high-TPS snapshot. Only then run targeted `P={1,2,8}` measurements for the
observed cache state; do not rerun the full Cartesian matrix cold by default.

## Raw evidence

- Campaign archive:
  [`20260825T103909Z-3524645.tar.gz`](raw/20260825T103909Z-3524645.tar.gz)
- Relevant files:
  `disk-cache-leaves-1000000000-chunk-1048576-block-{A,B,C}.{json,log}`
- SHA-256:
  `7e424c6f0c2c66b93ca30ed3ea562af7d25290540feb3eb4d3a1a6d04020341e`
