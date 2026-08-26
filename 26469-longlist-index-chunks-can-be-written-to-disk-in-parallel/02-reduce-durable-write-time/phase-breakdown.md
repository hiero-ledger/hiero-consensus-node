# LongList snapshot phase breakdown

> **Status:** Linux campaign planned; no result yet.

## Question

At one billion leaves with the production-default chunk size, is the measured
non-Heap `P=8` gap reproducible, and does it occur inside or outside the final
`force(true)` call?

## Campaign

| Parameter | Values |
|---|---|
| Workloads | Prepared-memory FileChannel control and all five LongList implementations |
| Writer threads | `P={1,8}` |
| Leaf count | `1,000,000,000` |
| LongList chunk size | `1,048,576` longs |
| Sampling | Three reordered blocks; one warmup and five measurements per cell |
| Profiler | JFR `jdk.FileForce`, recorded only during measured iterations |

JMH provides total operation time. JFR provides the duration of the final
force. Their difference is the non-force time, which includes source access,
data preparation, target writes, and small file-open/header/close costs. The
FileChannel benchmark's explicit phase counters cross-check the JFR timings.

## Decision gates

1. Interpret phases only if the non-Heap total-time gap is reproduced across
   the three blocks. Otherwise classify the earlier gap as unstable and stop.
2. If the extra time is outside the final force, investigate the identified
   source, copying, or target-write work instead of selecting an unrelated I/O
   experiment.
3. If the extra time is in the final force, investigate the target-file and
   storage behavior before selecting an experiment.
4. If the phase difference remains ambiguous, do not select another
   optimization from this evidence.

## Results

Pending the Linux campaign.

## Raw evidence

Pending the Linux campaign archive.
