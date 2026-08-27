# Representative Linux LongList snapshot results without final force

> **Status:** Prepared; Linux campaign pending.

## Purpose

Measure the same state sizes, chunk sizes, thread counts, and five LongList
implementations as the forced Linux baseline when `writeToFile()` returns
without calling its final `force(true)`.

The result tables will directly compare every unforced mean with the matching
cell in the forced
[`linux-benchmark-results.md`](../01-parallel-chunk-writes/linux-benchmark-results.md).
The focused forced/unforced campaign remains an additional same-campaign
confirmation at 1B/default/`P={1,8}`.

## Campaign

| Parameter | Value |
|---|---|
| Leaf counts | 10M, 100M, 1B, and 5B |
| LongList chunk sizes | `262,144`, `1,048,576`, and `4,194,304` longs |
| Writer threads | Same non-duplicate selections as the forced baseline |
| Implementations | All five LongLists |
| Sampling | Three reordered blocks; one warmup and two measurements per cell |
| Final force | Omitted from measured `writeToFile()`; performed in invocation teardown |

The campaign also repeats the 1B/default `LongListDisk` warm/cold source-cache
diagnostic at `P={1,2,8}`. It does not repeat the forced campaign's
Segment/Disk-only supplemental check; every broad-matrix implementation keeps
the same sample count.

Invocation teardown reopens and forces every unforced target before deleting
it. This work is outside the reported return time and prevents pending target
writes from affecting the next measurement.

The runner writes approximately 33.412 TB of target snapshot data. The same
39 launches in the forced campaign took 18 hours 52 minutes on the Linux host,
so this campaign is expected to take about 19 hours; reserve 20 hours.

Runner:
[`run-long-list-snapshot-without-force-benchmark.sh`](../../platform-sdk/swirlds-merkledb/src/jmh/scripts/run-long-list-snapshot-without-force-benchmark.sh)

## Results

Pending the Linux campaign.

## Raw evidence

Pending the Linux campaign.
