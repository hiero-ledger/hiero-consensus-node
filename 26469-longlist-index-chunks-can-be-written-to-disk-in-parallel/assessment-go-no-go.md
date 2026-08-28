# LongList snapshot-write PR assessment

## Recommendation

Proceed with the combined candidate in this PR:

1. two writer threads per LongList;
2. removal of the final LongList `force(true)`; and
3. overlap of the hash-cache pre-flush with independent snapshot tasks.

All three paths are implemented and correctness-tested. The 100-million-leaf
complete-snapshot gate strongly supports their combination. Run the focused
larger-state confirmation before selecting the final configuration defaults.

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

In the complete-snapshot combined mode, two writers had the lowest mean for
both Segment and Disk indices and beat one writer in every reordered block.
Eight writers gave no further benefit. Two is therefore the supported
candidate; higher counts are not justified for this workload.

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

The complete-snapshot effect is now measured. Without hash-cache overlap,
removing the force reduced mean return time by 44.6-55.9% for Segment and
39.4-50.5% for Disk. With overlap already enabled, it still reduced the mean
by 45.3-48.5% for Segment and 29.4-42.0% for Disk.

## Hash-cache pre-flush overlap

The previous snapshot order completed the entire hash-cache flush before
starting any of the six snapshot tasks. Four tasks are independent of that
flush and can run concurrently with it; only the hash index and hash store
must wait.

With all 262,144 configured cache chunks populated, overlap reduced the
100-million-leaf snapshot mean by 28.3-48.3% with the final LongList force and
23.9-33.3% without it. It won every tested index mode, writer count, and
reordered block. This is decisive evidence to retain the dependency-aware
schedule.

## Production setting

The benchmark-selected candidate is two writers per LongList, no final
LongList force, and hash-cache pre-flush overlap. Against the current forced,
serial-flush, one-writer baseline, it reduced the mean by 63.7% for Segment and
60.3% for Disk at 100 million leaves.

Do not finalize those defaults from this fixture alone. At a larger state the
leaf index grows while the hash-cache threshold remains fixed, so the relative
benefit and writer-count ranking can change. The next confirmation only needs
the forced one-writer baseline and unforced overlap at one and two writers for
both index modes. One writer, force enabled, and overlap disabled remain the
individual rollback settings.

See the [`design record`](01-parallel-chunk-writes/proposal.md),
[`corrected Linux parallel-write results`](01-parallel-chunk-writes/linux-benchmark-results.md),
[`force-removal results`](03-remove-final-force/remove-final-force.md),
[`complete-snapshot overlap results`](04-hash-cache-pre-flush-overlap/hash-cache-pre-flush-overlap.md),
and [`broader snapshot investigation`](snapshot-optimization-report.md).
