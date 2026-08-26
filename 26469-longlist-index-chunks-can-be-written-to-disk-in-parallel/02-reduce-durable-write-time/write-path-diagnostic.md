# LongList pre-force write-path diagnostic

> **Status:** Linux campaign planned.

## Question

For the remaining `P=8` gap, is the extra pre-force time spent inside target
`FileChannel.write()` calls or while the LongList supplies data between those
calls?

## Campaign

| Parameter | Value |
|---|---|
| Workloads | Prepared-memory FileChannel control and all five LongList implementations |
| Writer threads | `P=8` |
| Leaf count | `1,000,000,000` |
| LongList chunk size | `1,048,576` longs |
| Sampling | Three reordered blocks; one warmup and five measurements per cell |
| Recording | Every `jdk.FileWrite`, `jdk.FileRead`, and `jdk.FileForce` event, without the default JFR threshold or throttle |

The analysis will compare total pre-force time with the target-write call
intervals. It will also report source-file reads for Disk separately. All five
LongList implementations receive the same number of measurements. Overlapping
write calls from different workers will be merged for wall-clock accounting,
not added together as if they ran sequentially.

## Decision

- Interpret the split only if total times remain consistent with the previous
  phase campaign; otherwise the unthrottled recording perturbed the workload.
- If target-write calls explain the gap, identify the specific write behavior
  before proposing a separate I/O experiment.
- If time outside target-write calls explains it, classify that as LongList
  source/preparation work outside the parallel-chunk PR.
- If the split is ambiguous or the gap is no longer stable, close this
  investigation without selecting another optimization.

## Results

Pending the Linux campaign.
