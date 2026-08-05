# Parallel LongList snapshot benchmark results

## Conclusion

Parallel LongList writing produces a large measured improvement for
`LongListDisk` in a real MerkleDB snapshot. `P=2`, meaning two writer threads
per LongList, reduced its mean snapshot latency from 404.8 to 279.7 ms, a
30.9% improvement.

The result for production-default `LongListSegment` does not establish an
improvement. The broad matrix initially showed a 13.3% `P=2` mean improvement,
but a higher-repetition confirmation reversed that result: the mean increased
from 250.2 to 267.1 ms, a 6.7% regression. The `P=2` measurements included a
457.6 ms tail. There is therefore no established Segment snapshot win and no
isolated-versus-snapshot contradiction to explain.

`P=2` remains the only setting worth confirming on representative Linux/NVMe
hardware:

- it gives Disk most of the observed parallel-write benefit with the fewest
  additional resources;
- its local Segment regression can be checked directly on representative
  hardware; and
- higher thread counts did not establish a cross-implementation advantage.

The configuration default should remain `P=1` until that representative
confirmation is complete.

## How to read the results

The next two tables use the same format. Every cell gives the independent
block means `A / B / C`, followed by the arithmetic mean of every raw
measurement in those blocks. That overall mean is calculated directly from
raw JMH samples—six per isolated cell and nine per snapshot cell—not from the
rounded block means displayed in the table. It is the sole comparison metric:
each percentage is calculated from the overall means for `P=1` and the given
parallel setting. The block means remain only to expose run-to-run variability.
All times are milliseconds; lower is better.

Compare scaling between the two tables, but do not compare their absolute
times as throughput: the isolated benchmark writes one 7.451 GiB file, whereas
the real snapshot rewrites 767.502 MiB across three LongLists while running the
other snapshot tasks.

## One-billion-long isolated write

Each block mean contains two measured writes after one warmup.

| LongList implementation |                                     `P=1` |                                                       `P=2` |                                                   `P=3` |                                                   `P=6` |                                                   `P=8` |                                                  `P=16` |
|-------------------------|------------------------------------------:|------------------------------------------------------------:|--------------------------------------------------------:|--------------------------------------------------------:|--------------------------------------------------------:|--------------------------------------------------------:|
| Heap                    | 3565.3 / 3649.3 / 3512.0; **mean 3575.5** |     3954.7 / 4109.8 / 3801.2; **mean 3955.2**; 10.6% slower |  3428.9 / 3562.0 / 3403.4; **mean 3464.8**; 3.1% faster | 2941.2 / 3068.8 / 2835.2; **mean 2948.4**; 17.5% faster | 2786.9 / 2914.5 / 2891.1; **mean 2864.2**; 19.9% faster |  2806.8 / 4891.3 / 2852.2; **mean 3516.8**; 1.6% faster |
| OffHeap                 | 1436.3 / 1614.6 / 1414.2; **mean 1488.4** |     1700.8 / 2154.6 / 1601.7; **mean 1819.0**; 22.2% slower |  1534.0 / 1631.7 / 1472.6; **mean 1546.1**; 3.9% slower |  1529.4 / 1549.3 / 1568.3; **mean 1549.0**; 4.1% slower |  1616.7 / 1627.0 / 1543.0; **mean 1595.5**; 7.2% slower |  1623.4 / 1575.7 / 1532.3; **mean 1577.1**; 6.0% slower |
| Segment                 | 1352.0 / 1498.0 / 1399.2; **mean 1416.4** |      1533.7 / 1547.3 / 1505.8; **mean 1528.9**; 7.9% slower |  1549.0 / 1518.1 / 1506.4; **mean 1524.5**; 7.6% slower |  1565.7 / 1581.8 / 1524.5; **mean 1557.3**; 9.9% slower | 1603.2 / 1604.0 / 1820.3; **mean 1675.8**; 18.3% slower |  1551.5 / 1564.8 / 1526.1; **mean 1547.5**; 9.3% slower |
| Disk                    | 2793.6 / 2348.0 / 2991.1; **mean 2710.9** | 1657.4 / 1932.1 / 1601.8; **mean 1730.4**; **36.2% faster** | 1893.4 / 1824.6 / 1986.0; **mean 1901.3**; 29.9% faster | 1896.0 / 2968.5 / 2270.9; **mean 2378.5**; 12.3% faster | 2772.2 / 1871.4 / 2076.2; **mean 2240.0**; 17.4% faster | 1680.3 / 2295.1 / 1868.0; **mean 1947.8**; 28.1% faster |
| DiskSegment             | 1469.3 / 1407.1 / 1760.9; **mean 1545.8** |      1682.3 / 1521.5 / 1532.5; **mean 1578.8**; 2.1% slower |  1581.3 / 1730.3 / 1742.6; **mean 1684.7**; 9.0% slower |  1703.0 / 1740.8 / 1574.4; **mean 1672.7**; 8.2% slower | 1504.3 / 1858.3 / 1742.5; **mean 1701.7**; 10.1% slower | 1567.4 / 1549.8 / 2673.5; **mean 1930.2**; 24.9% slower |

## Real 50-million-leaf MerkleDB snapshot

Each block mean contains three measured snapshots after one warmup.

| LongList implementation  |                                 `P=1` |                                                   `P=2` |                                               `P=3` |                                               `P=6` |                                               `P=8` |                                              `P=16` |
|--------------------------|--------------------------------------:|--------------------------------------------------------:|----------------------------------------------------:|----------------------------------------------------:|----------------------------------------------------:|----------------------------------------------------:|
| Heap (diagnostic)        | 542.7 / 429.6 / 415.3; **mean 462.5** |      445.5 / 427.9 / 476.3; **mean 449.9**; 2.7% faster |  416.0 / 443.8 / 421.5; **mean 427.1**; 7.7% faster |  418.6 / 501.0 / 443.6; **mean 454.4**; 1.7% faster | 439.7 / 618.7 / 486.8; **mean 515.1**; 11.4% slower |  431.5 / 473.0 / 447.6; **mean 450.7**; 2.6% faster |
| OffHeap (diagnostic)     | 253.3 / 248.8 / 216.9; **mean 239.6** |      243.2 / 223.0 / 300.8; **mean 255.7**; 6.7% slower |  201.9 / 233.7 / 214.6; **mean 216.7**; 9.6% faster | 270.4 / 301.2 / 234.4; **mean 268.7**; 12.1% slower |  233.4 / 230.1 / 271.4; **mean 244.9**; 2.2% slower |  269.0 / 223.3 / 260.3; **mean 250.9**; 4.7% slower |
| Segment (production)     | 284.8 / 271.1 / 249.9; **mean 268.6** |   236.8 / 236.7 / 224.7; **mean 232.7**; 13.3% faster\* | 289.6 / 279.4 / 396.6; **mean 321.9**; 19.8% slower |  264.2 / 260.3 / 248.4; **mean 257.7**; 4.1% faster |  223.7 / 303.0 / 315.7; **mean 280.8**; 4.5% slower |  289.9 / 256.7 / 338.7; **mean 295.1**; 9.9% slower |
| Disk (production)        | 405.0 / 432.3 / 377.2; **mean 404.8** | 267.5 / 289.6 / 282.1; **mean 279.7**; **30.9% faster** | 300.7 / 268.9 / 244.0; **mean 271.2**; 33.0% faster | 251.7 / 465.4 / 247.2; **mean 321.4**; 20.6% faster | 248.0 / 274.5 / 257.9; **mean 260.1**; 35.7% faster | 278.2 / 288.9 / 270.0; **mean 279.0**; 31.1% faster |
| DiskSegment (diagnostic) | 270.2 / 266.2 / 240.9; **mean 259.1** |     267.0 / 248.9 / 402.8; **mean 306.2**; 18.2% slower | 264.6 / 278.3 / 446.6; **mean 329.8**; 27.3% slower |  250.0 / 276.2 / 250.0; **mean 258.7**; 0.1% faster | 243.9 / 291.1 / 341.0; **mean 292.0**; 12.7% slower |  282.6 / 299.5 / 227.7; **mean 270.0**; 4.2% slower |

\* The focused Segment confirmation supersedes this broad-matrix mean result.

| Focused Segment setting |    Block means A / B / C | Mean of all measurements | Mean change from `P=1` | Raw measured range |
|-------------------------|-------------------------:|-------------------------:|-----------------------:|-------------------:|
| `P=1`                   | 256.7 / 246.2 / 247.8 ms |                 250.2 ms |                      — |     200.7–349.6 ms |
| `P=2`                   | 261.6 / 242.8 / 296.8 ms |                 267.1 ms |            6.7% slower |     199.9–457.6 ms |

`P=2` was 6.7% slower by the mean of all 15 measurements per setting. Its
457.6 ms tail contributes to that mean, and the result reverses the broad
matrix's apparent `P=2` Segment improvement.

The all-five run used the real
`MerkleDbDataSourceBuilder.snapshot(target, source)` orchestration. Current
production construction exposes only Segment and Disk. For this diagnostic
campaign, one common fixture was restored through a temporary setup-only
loader so that all three indices used the selected implementation. The loader
did not execute in the timed operation and was removed after measurement.
Heap, OffHeap, and DiskSegment must therefore not be described as supported
MerkleDB production modes.

### Workload

The reusable source contained 50 million populated and fully hashed leaves in
a database provisioned with the production-default one-billion initial
capacity. This is the shape produced by the current production builder:

- leaf-path index: 400,000,012 bytes (381.470 MiB);
- bucket index: 268,435,468 bytes (256.000 MiB);
- hash-chunk index: 136,348,180 bytes (130.032 MiB); and
- total LongList output: 804,783,660 bytes (767.502 MiB).

The leaf index contains one location per live leaf. Internal and leaf hashes
are stored in `VirtualHashChunk`s; the hash LongList contains one disk location
per hash chunk rather than one long per tree node. The bucket index is
pre-sized from the configured initial capacity. These sizes are therefore
correct for a 50-million-live-leaf production-default database.

The timed operation includes the six production snapshot tasks, which together
write three LongLists, snapshot three stores, and write metadata. Store
snapshots primarily create hard links; the LongList files contain the rewritten
index data. Target-path selection, validation, and deletion are outside the
timed method, while target creation, writes, `force`, and close remain timed.

### Protocol

The broad matrix covered all five implementations and
`P={1,2,3,6,8,16}` in three counterbalanced blocks. Every cell used one warmup
and three measured snapshots:

- 90 JMH trials;
- 270 measured snapshots;
- 90 warmup snapshots; and
- approximately 269.825 GiB of logical index output.

The conditional Segment confirmation used three counterbalanced `P={1,2}`
blocks with two warmups and five measurements per cell:

- 6 JMH trials;
- 30 measured snapshots;
- 12 warmup snapshots; and
- approximately 31.480 GiB of logical index output.

All 96 result entries contain the expected finite measurements. Every output
was validated, every invocation target was deleted, Time Machine remained idle,
the machine remained on AC power, and swap-ins and swap-outs stayed at zero.
No result was discarded as an outlier.

## Resolution of the Segment question

The isolated one-billion-long benchmark found Segment `P=2` 7.9% slower by
mean. The first broad real-snapshot matrix appeared to show the opposite
result, with a 13.3% mean improvement.

The higher-repetition confirmation established that the apparent snapshot win
was not stable. Across its 15 measurements per setting:

- `P=1` mean: 250.204 ms;
- `P=2` mean: 267.065 ms; and
- mean change: `P=2` was 6.7% slower.

The correct conclusion is not that topology makes Segment faster. The
direction changed in the higher-repetition run, so the broad result is not
reliable. Since the win did not reproduce, no separate causal explanation
document or topology diagnostic is justified. The isolated large-file Segment
regression remains a warning for representative-host testing.

## What the absolute times show

- Disk is the only strong result in both benchmarks. At `P=2`, its isolated
  mean falls from 2710.9 to 1730.4 ms, a 36.2% improvement, and its real
  snapshot mean falls from 404.8 to 279.7 ms, a 30.9% improvement.
- Every tested parallel setting increased Segment's isolated mean. Its broad
  real-snapshot `P=2` mean improvement failed the higher-repetition
  confirmation, which instead measured a 6.7% regression.
- Several apparent rankings are noisy. Isolated Heap `P=16` has a 4891 ms
  block between two approximately 2.8-second blocks; isolated Disk `P=6` has
  a 2969 ms block; real-snapshot Disk `P=6` has a 465 ms block whose raw
  samples reach 805 ms; and real-snapshot DiskSegment `P=2` rises to 403 ms in
  block C.

The isolated benchmark remains useful for implementation-level behavior but
must not select the global MerkleDB setting by itself. The real snapshot is the
decision workload, and its remaining candidate still requires representative
Linux/NVMe confirmation.

## Decision and next gate

No additional scheduler, striped-range, pre-extension, buffer, or channel
experiment is justified by these results.

The next performance step is narrow:

1. compare only `P=1` and `P=2` on a representative Linux/NVMe host;
2. use both production Segment and Disk modes with the largest practical
   production-shaped snapshot;
3. confirm total latency, tail behavior, CPU, storage saturation, and memory;
4. change the default to `P=2` only if Disk retains a material mean improvement
   and Segment has no material mean regression.

The two retained benchmarks have separate roles:

- `LongListSnapshotBenchmark` diagnoses all five implementations directly.
- `MerkleDbSnapshotBenchmark` is the unmodified production-path gate for the
  currently supported Segment and Disk modes.

## Reproducibility and cleanup

- Branch:
  `26469-longlist-index-chunks-can-be-written-to-disk-in-parallel`
- Working-tree base revision:
  `9e12639b37e82b1cb6acf61e0f66a50661f38a75`
- Host: Apple M3 Max, 48 GiB RAM, macOS, APFS, AC power
- JVM: Eclipse Temurin 25.0.2+10
- JMH: 1.37
- Campaign date: 2026-07-27
- One-billion-long raw results:
  `platform-sdk/swirlds-merkledb/build/results/jmh/longlist-1b-20260724/`
- All-five real-snapshot raw results:
  `platform-sdk/swirlds-benchmarks/build/results/jmh/merkledb-snapshot-all-five-50m-20260727/`

Raw JSON and logs remain in ignored Gradle build directories. Per-invocation
targets were removed during teardown. The reusable fixture is removed after
the campaign.

`P=1` is the branch-local sequential baseline. The generalized implementation
uses positional body writes on that path, so these campaigns do not measure an
exact byte-for-byte performance comparison with `main`.
