# Remove the final LongList force

> **Status:** Focused Linux campaign ready to run.

## Question

How much earlier can `LongList.writeToFile()` return when it closes the target
without calling the final `force(true)`?

## First campaign

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

## Decision

- Stop if the measured early-return benefit is small or unstable.
- If the benefit is material, separately decide whether losing the current
  LongList-only durability wait and synchronous writeback-error reporting is
  acceptable.
- Do not choose the size-confirmation matrix yet. Revise it from this result
  before running a larger campaign.

## Raw evidence

Pending the Linux campaign.
