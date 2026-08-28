# LongList snapshot-write PR assessment

## Recommendation

Proceed with both changes in this PR:

1. the configurable parallel LongList writer; and
2. removal of the final LongList `force(true)`.

The parallel writer is implemented and correctness-tested. The no-force
LongList earlier-return evidence is complete, but the production path still
needs to be updated before the PR is complete.

## Parallel writer

The implementation preserves the existing file format and sequential behavior
at one writer per LongList. It bounds the added LongList range-writer pool to
at most three times the configured per-list writer count, waits for every
worker before closing a target, and propagates snapshot failures to the caller.
Tests cover all five LongList implementations, byte-identical sequential and
parallel output, restoration, worker failure and completion, and top-level
snapshot failure and interruption.

The isolated LongList benchmark on the representative Linux host shows that
parallel writing can reduce LongList write time, but the benefit depends on the
implementation, list size, storage, and whether a Disk source is already in
the operating system's file cache. At one billion leaves and the default chunk
size, the best measured reductions ranged from 3.8% for Segment to 13.1% for
Heap; Disk improved by 8.1%. At five billion leaves, also with the default
chunk size, the gains were smaller for several implementations, while Disk
still improved by 7.4%.

## Final LongList force

The unforced mean was lower in all 280 matching Linux benchmark
configurations. The skipped storage wait reappeared when the
benchmark forced the target immediately after return, so this change moves
work beyond `writeToFile()` rather than eliminating it.

The current force covers only LongList index files. It does not make the whole
signed-state snapshot durable because the other files and final directory move
are not part of one force-and-sync protocol. Removing this isolated wait does
not affect worker completion, channel close, or atomic directory publication.
It does mean that an error reported only by `force(true)` can no longer reach
the snapshot call.

The complete-snapshot time effect has not yet been measured. Deferred
writeback may overlap other snapshot work, so the LongList earlier-return
percentages must not be presented as complete-snapshot improvements.

## Production setting

Keep the default at one writer per LongList. This retains the previous writer
thread and buffer use unless an operator deliberately enables parallel writing.
It does not restore the final force selected for removal. No higher count was
consistently best in the LongList campaign, and a higher default has not been
validated through complete Linux MerkleDb snapshots.

The one-thread configuration remains the immediate parallel-writer rollback if
a storage environment does not benefit.

See the [`design record`](01-parallel-chunk-writes/proposal.md), the
[`corrected Linux parallel-write results`](01-parallel-chunk-writes/linux-benchmark-results.md),
the [`force-removal results`](03-remove-final-force/remove-final-force.md), and
the [`broader snapshot investigation`](snapshot-optimization-report.md).
