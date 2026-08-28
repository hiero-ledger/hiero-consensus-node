# Making MerkleDb snapshots faster

## Goal

The original task was to write LongList index chunks in parallel. That change
is implemented and tested on this branch. The investigation then continued to
answer a broader question: which other measured changes can reduce
`MerkleDbDataSource.snapshot()` time without adding unjustified complexity?

This report connects the experiments and decisions. Detailed tables, methods,
and raw evidence remain in the result documents linked below.

## 1. Current conclusions

| Area | Current conclusion |
|---|---|
| Parallel LongList writes | Implemented, correctness-tested, and measured with the isolated Linux LongList benchmark. The measurements show real gains, but no single higher thread count is best for every implementation and size. The configuration default remains one writer per LongList. |
| Writing the same bytes more efficiently | The prepared-memory control and follow-up diagnostics did not justify physical preallocation or direct I/O. |
| Removing the final LongList force | Earlier `writeToFile()` return is established. The change is included in this PR. The complete-snapshot effect is not yet measured. |
| Compression | Still worth discussing because it could reduce storage traffic, but it changes the file format and adds CPU work. No experiment starts without team agreement. |
| Hash-cache pre-flush overlap | Investigation in progress. It may shorten total snapshot time by starting independent tasks before the flush finishes. |

The result documents are the source of truth for measurements. This report
keeps only the evidence needed to understand each decision.

## 2. Parallel LongList writes

### Implementation

`MerkleDbDataSource.snapshot()` writes three LongLists. The branch keeps those
three top-level tasks and gives each list a configured number of writer
threads. Each worker owns one contiguous, non-overlapping range and writes it
to a known file offset.

At one writer per LongList, behavior remains sequential. With more writers,
the snapshot uses one bounded pool with at most three times the configured
writer count. Every range finishes before its target is forced or closed, and
every snapshot task finishes before `snapshot()` returns.

The file format is unchanged. The implementation is described in
[`proposal.md`](01-parallel-chunk-writes/proposal.md).

### Performance evidence

The corrected Linux campaign covered all five LongList implementations, four
leaf counts, three chunk sizes, and several writer counts. Every comparative
cell has the same sample count.

At one billion leaves and the default chunk size, the best measured reduction
from one writer was:

| Implementation | Best reduction | Writer count |
|---|---:|---:|
| Heap | 13.1% | 16 |
| OffHeap | 4.0% | 16 |
| Segment | 3.8% | 8 |
| Disk | 8.1% | 8 |
| DiskSegment | 4.8% | 8 |

At five billion leaves, also with the default chunk size, the best reductions
ranged from 0.4% to 7.9%. Disk improved by 7.4%, while Segment improved by
1.0%. Parallel output therefore works, but its benefit depends on the source
implementation, workload, and storage.

The focused `LongListDisk` experiment also showed that it matters whether the
source file is already in the operating system's file cache. At one billion
leaves, two writers improved 4.4% with a cached source and 14.5% after the
source was evicted from that cache. The evicted case is a useful
memory-pressure diagnostic, not the normal periodic-snapshot baseline.

See:

- [Linux parallel-write results](01-parallel-chunk-writes/linux-benchmark-results.md)
- [LongListDisk source-cache diagnostic](01-parallel-chunk-writes/disk-cache-diagnostic.md)

### Branch decision

The configurable parallel writer is a supported branch result. The default
remains one writer per LongList because the measurements do not identify one
higher value that is best across all cases. This preserves the previous
writer-thread and buffer use and provides an immediate parallel-writer rollback
on storage that does not benefit. It does not restore the final force selected
for removal.

## 3. Attempts to make the durable write itself faster

### Prepared-memory FileChannel reference

The control removes LongList traversal and source preparation. Before timing,
it prepares one 8 MiB direct buffer. During the measurement, it repeatedly
writes that data through the same Java `FileChannel`, ext4 filesystem, and
NVMe device used by the LongList benchmark. It writes an 8 GB body and includes
the final `force(true)`.

The best tested result was about 12.62 seconds, or 634 MB/s, at eight and
sixteen writers. More writers made the body calls finish earlier, but much of
that saving moved into a longer final force. Thirty-two writers were slower.

At eight writers, Heap matched the reference. The other implementations were
3.18-4.84% slower in the same campaign. Their final durable difference was
only 0.47-0.68 seconds for an 8 GB write.

See
[`filechannel-write-reference.md`](00-filechannel-write-reference/filechannel-write-reference.md).

### Historical hypothesis: physical preallocation

The hypothesis was that growing the target file during writing might serialize
workers. If file growth had explained a material part of the remaining gap,
reserving physical blocks before writing would have been worth testing.

The control grows its target through the same buffered path and is still
faster. The phase and write-path diagnostics did not isolate file growth as a
meaningful cost. The evidence therefore closed this hypothesis without a
prototype.

### Historical hypothesis: direct I/O

The hypothesis was that bypassing the operating-system file cache might reduce
copying or writeback overhead.

The faster control uses the same buffered file-cache path as the LongLists.
The diagnostic did not identify that shared path as the cause of the small
remaining difference. Direct I/O would also require aligned handling for the
12-byte header, body offsets, and final partial range. The evidence therefore
closed this hypothesis without a prototype.

### Why the remaining LongList gap was not pursued

The phase experiment placed the gap before the final force. The follow-up
recorded every target write and found that 99.76-99.93% of the extra non-force
wall time was covered by at least one `FileChannel.write()` call. Only
1.3-4.7 milliseconds remained outside all target writes.

This means there is no material idle period between writes to optimize. The
remaining difference can include source-buffer access, copying into the
operating-system cache, and waits inside the buffered write path. Its final
durable cost is small enough that another production change is not justified
for this branch.

See:

- [Phase breakdown](02-reduce-durable-write-time/phase-breakdown.md)
- [Write-path diagnostic](02-reduce-durable-write-time/write-path-diagnostic.md)

## 4. Remove the final LongList `force(true)`

### What changes

Today, `LongList.writeToFile()` writes the target, calls `force(true)`, and
then returns. Removing the force lets the call return after all Java writes are
finished and the channel is closed. Linux may still have cached bytes to write
to storage afterward.

This does not remove the storage work. Large `FileChannel.write()` calls may
already wait while Linux drains cached data, and the remaining work continues
after the method returns.

### Atomic directory move

`SignedStateFileWriter` builds a snapshot under a temporary directory and
atomically moves that directory to its final name after snapshot creation
finishes.

Removing the LongList force does not make the move race with active LongList
workers: the workers still finish and close their channels before
`snapshot()` returns. Cached bytes remain attached to the same files after
the directory is renamed.

The move prevents readers from seeing a half-published directory. It does not
make every file durable on storage.

### Why the isolated LongList force is not whole-snapshot durability

The current force covers only LongList index files. Other snapshot files are
not all forced through one durability protocol, and the final directory move
is not followed by a directory sync. Waiting for the LongLists therefore does
not make the complete signed-state snapshot durable.

The force does provide two local guarantees: it confirms the LongList files
before publication, and it can report a pending LongList writeback failure to
the caller. Removing it moves the storage wait beyond `writeToFile()`. A
failure reported only by `force(true)` can no longer reach this snapshot call;
it may appear elsewhere later or may not be reported through the snapshot
operation.

### Measurements and decision

The unforced mean was lower in all 280 matching benchmark configurations. At
one billion leaves the reduction was 21.0-58.4%; at five
billion leaves it was 2.7-53.3%.

The focused comparison forced each unforced target immediately after
`writeToFile()` returned. Adding that post-return wait produced totals within
1.0% of the ordinary forced path. This confirms that the faster return comes
from moving storage work past the method boundary, not eliminating it.

The earlier LongList return is strong and reproducible. Its effect on complete
snapshot time still needs measurement because deferred writeback can overlap
other snapshot work. Because the existing force does not provide
whole-snapshot durability, this PR will remove the isolated final wait.

See:

- [Forced versus unforced comparison](03-remove-final-force/remove-final-force.md)
- [Complete unforced Linux results](03-remove-final-force/linux-benchmark-results-without-force.md)

## 5. Compression

Compression remains a possible way to reduce the bytes written to storage.
LongList values contain related file identifiers and offsets, so real index
data may compress well. A smaller snapshot may take less time to write and
load even when storage throughput does not change.

The tradeoff is significant. Compression creates a new LongList file format,
adds CPU work during snapshots, and adds decompression work while loading.
Parallel compression may also compete with other snapshot tasks.

This idea requires team agreement before an experiment. The first measurement
should use representative index files and report compression ratio,
compression time, and decompression/load time. A production prototype is
justified only if those measurements predict an end-to-end benefit.

## 6. Hash-cache pre-flush overlap

This investigation is in progress.

MerkleDb keeps frequently updated hashes in memory. Before starting its six
snapshot tasks, `snapshot()` writes that cache to the hash store. The flush is
currently serial with all snapshot tasks.

Only the hash store and hash-index tasks depend on the flushed data. The other
four tasks can start independently:

```text
Current:  [ hash-cache flush ][ all snapshot tasks ................ ]

Proposed: [ independent snapshot tasks ............................ ]
          [ hash-cache flush ]--+--[ hash-index snapshot .......... ]
                                +--[ hash-store snapshot .......... ]
```

The proposed schedule shortens total snapshot time only when the flush is
significant and overlapping it does not create more contention than it saves.
The experiment therefore starts by measuring the existing flush in a complete
MerkleDb snapshot.

The implementation must preserve three conditions:

1. Hash-store and hash-index snapshots start only after the flush succeeds.
2. A failed flush prevents both dependent tasks from starting and cannot leave
   the snapshot waiting forever.
3. The caller receives the same snapshot failure behavior as today.

The result belongs in its own experiment document when the in-progress work
produces measurements.

## 7. How the changes fit together

Parallel LongList writing is the base branch change. The no-force campaigns
already measured parallel writing and early return together across all writer
counts.

If pre-flush overlap succeeds, the complete-snapshot candidate becomes:

```text
writer count selected for the target storage
    + no final LongList force
    + hash-cache pre-flush overlap
```

That combination needs a complete-snapshot measurement because the operations
may compete for CPU, memory bandwidth, file-cache capacity, and storage.

Compression is not part of the current candidate. If the team approves it and
its own measurements show a benefit, it can later be added to the same
complete-snapshot comparison. There is no separate reason to combine no-force
with preallocation or direct I/O because those hypotheses were closed.

## 8. Benchmark rules

- Decision measurements run on Linux/ext4/NVMe.
- Comparative LongList campaigns use the same configurations and sample counts
  for all five implementations.
- Narrow implementation-specific diagnostics are labeled separately and are
  not used as equal cross-implementation comparisons.
- Every result document records the tested Git revision, environment,
  comparison baseline, method, raw archive, and checksum.
- The final candidate is measured through the complete MerkleDb snapshot path.
  Compare it on the same revision and fixture with the forced one-writer
  baseline and current serial pre-flush schedule. Record the mean and slowest
  observed snapshot times. A second Linux storage device provides a final
  check against tuning to one drive.

## 9. Execution record and remaining steps

The original execution order is retained below because it explains how the
current conclusions were reached.

1. **Branch documentation prepared — complete.** The proposal, result
   documents, and raw Linux evidence were organized under this directory.
2. **Prepared-memory FileChannel reference — complete.** The Linux control
   established the practical 8 GB durable-write reference and showed that more
   writers mainly move time from body calls into the final force.
3. **Corrected parallel-write baseline — complete.** All five implementations
   completed the equal-sample Linux matrix through five billion leaves. The
   focused Disk cache experiment measured warm and cold source behavior.
4. **Phase comparison — complete.** A same-campaign FileChannel and LongList
   comparison confirmed the small remaining gap and placed it before the final
   force.
5. **Write-path diagnostic — complete.** The follow-up found no material idle
   gap between target writes. Physical preallocation and direct I/O were not
   supported by the evidence.
6. **No-force evaluation — complete.** Focused and complete Linux campaigns
   showed that omitting the final LongList force returns earlier while moving
   the remaining storage wait past `writeToFile()`.
7. **No-force production change — included in this PR.** Update the production
   LongList path while preserving worker completion and channel close before
   snapshot publication.
8. **Hash-cache pre-flush overlap — in progress.** Measure the existing flush
   in a complete snapshot, then test the dependency-aware schedule only if the
   measurement supports it.
9. **Compression — team discussion.** Measure representative compression and
   load cost only if the team chooses to pursue the file-format change.
10. **Final complete-snapshot comparison — after the in-progress work.** Compare
   the selected candidate with the forced one-writer and serial-pre-flush
   baseline on the same revision and fixture. Record the mean and slowest
   observed snapshot times, then confirm the result on the second Linux device.
