# LongList snapshot-write PR assessment

## Accepted direction

The combined optimization is accepted for this PR:

1. parallel writer threads per LongList, with eight as the measured 1B candidate;
2. removal of the final LongList `force(true)`; and
3. overlap of the hash-cache pre-flush with independent snapshot tasks.

All three paths are implemented and correctness-tested. Complete-snapshot
measurements at 100M and 1B leaves support their combination. The useful writer
count depends on state size; final configuration defaults remain to be agreed.

Compression was discussed with the team and dismissed because the measured
gains are sufficient. The remaining work is to sync with main, remove
experiment-only switches and tooling, simplify tests and benchmarks, and verify
the final production path. Preserve these results for the PR description before
removing the experiment directory from the final diff.

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

At 100M leaves, two writers had the lowest combined-mode mean for Segment and
Disk; eight added no mean improvement. At 1B, eight beat both one and two
writers for all five implementations in every block. Eight captures most of
the 1B gain; thirty-two offers small further savings for four implementations
but regresses Heap. Forced-path thread scaling is much less convincing.

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

The 100M and 1B complete-snapshot campaigns confirmed earlier unforced return
for every tested implementation and writer count, in every block, with either
flush schedule. Post-return forcing still pays for the remaining storage work.

## Hash-cache pre-flush overlap

The previous snapshot order completed the entire hash-cache flush before
starting any of the six snapshot tasks. Four tasks are independent of that
flush and can run concurrently with it; only the hash index and hash store
must wait.

With all 262,144 configured cache chunks populated, unforced overlap won every
tested configuration in every block at both 100M and 1B. At 1B and eight
writers, it reduced mean return time by another 10.0-16.7% across all five
implementations. With force retained, its benefit was less consistent.

## Production setting

The 1B candidate is eight writers per LongList, no final LongList force, and
hash-cache pre-flush overlap. The larger-state confirmation is complete, but
the difference from 100M argues against calling any writer count universally
best. The forced-Segment results also contain a slow first block that must not
be mistaken for a reproducible thread-count gain.

No production defaults were changed by this results update. One writer, force
enabled, and overlap disabled remain the current defaults and individual
rollback settings.

See the [`design record`](01-parallel-chunk-writes/proposal.md),
[`corrected Linux parallel-write results`](01-parallel-chunk-writes/linux-benchmark-results.md),
[`force-removal results`](03-remove-final-force/remove-final-force.md),
[`complete-snapshot results`](04-hash-cache-pre-flush-overlap/hash-cache-pre-flush-overlap.md),
and [`broader snapshot investigation`](snapshot-optimization-report.md).
