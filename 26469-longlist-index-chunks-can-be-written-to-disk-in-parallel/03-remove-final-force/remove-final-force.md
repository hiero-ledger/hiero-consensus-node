# Remove the final LongList force

> **Status:** Focused Linux campaign complete; performance gate passed and
> production-semantics decision pending.

## Question

How much earlier can `LongList.writeToFile()` return when it closes the target
without calling the final `force(true)`?

## Results

Times are means across 15 measurements. `Post-return force` is the teardown
time to reopen, force, and close the unforced target immediately afterward,
outside `writeToFile()`.

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
within 0.96% of its forced reference. Omitting the force therefore does not
remove storage work; it moves approximately 3.2-7.3 seconds of waiting beyond
`writeToFile()`'s return.

The JMH auxiliary-counter summary adds the five iteration values together.
The per-operation post-return values above were recomputed from its JSON
`rawData`, where each value is expressed in nanoseconds.

## Method

| Parameter | Value |
|---|---|
| Implementations | All five LongLists |
| Leaf count | `1,000,000,000` |
| LongList chunk size | `1,048,576` longs |
| Writer threads | `P={1,8}` |
| Force modes | Forced and unforced |
| Sampling | Three reordered blocks; one warmup and five measurements per cell |

The existing public write methods remain forced. The benchmark alone can omit
the final force through a package-private durability switch. After every
unforced measurement, invocation teardown reopens and forces the target before
verification and deletion. Teardown is outside the measured return time, so
pending writes cannot leak into the next invocation.

Each implementation and force mode receives 15 measurements. The forced arm
is the direct reference for the same implementation and writer count; the
prepared-memory FileChannel control is not part of this campaign.

## Decision and next gate

The performance hypothesis is confirmed. The remaining question is whether
returning and publishing the snapshot while this storage work remains pending
is acceptable. Removing the force also moves any delayed writeback failure
beyond the snapshot caller.

The next measurement is a complete unforced LongList baseline using the same
state sizes, chunk sizes, thread counts, and equal-sample implementation matrix
as the forced Linux baseline. It will directly compare every unforced mean
with its matching forced mean and repeat the warm/cold `LongListDisk`
diagnostic. See
[`linux-benchmark-results-without-force.md`](linux-benchmark-results-without-force.md).

This comparison determines how removing the force changes the parallelism and
chunk-size conclusions across network sizes. It remains performance evidence;
the team must still accept the changed durability and error-reporting boundary
before a production implementation proceeds.

## Raw evidence

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
blocks, and 60 JFR recordings. All cells contain five finite measurements; all
JFRs are readable and report no data loss. The campaign used `verify=false`;
the same forced/unforced paths were byte-for-byte verified locally before the
Linux run.
