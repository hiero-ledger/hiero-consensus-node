# Making the snapshot's LongList writes faster

**Goal:** test the plausible ways to reduce the LongList contribution to
`MerkleDbDataSource.snapshot()` latency, measure them on representative
Linux/NVMe hardware, and select the best production optimization supported by
the evidence. The outcome may also be that none of the tested changes is worth
shipping. End-to-end snapshot time, rather than an isolated LongList result,
is the final measure of success.

The plan tests three approaches:

- **Way 1 — durable write:** retain the current durability boundary, but reach
  the practical storage ceiling or reduce the bytes that must reach it.
- **Way 2 — early return:** test omitting the final `force(true)`. This may
  shorten the call, but it changes when a snapshot is considered durable, and
  large writes may still be throttled by the OS. Establish the required
  crash-durability and recovery contract before treating this as a production
  option.
- **Way 3 — combine:** combine early return with the Way-1 changes that still
  reduce total work.

Detailed outputs belong in the experiment-specific result files described
later in this document. This document connects the hypotheses, experiments,
and decisions.

---

## 1. Decision-relevant evidence already established

The representative Linux campaign used an AMD EPYC host, ext4, and a 480 GB
[Micron 7450 PRO M.2](https://www.micron.com/products/storage/ssd/data-center-ssd/7450-ssd/part-catalog)
(`MTFDKBA480TFR`). Micron rates this model at 700 MB/s for sequential writes.

| Workload | Implementation | `P=1` | `P=2` | `P=8` |
|---|---|---:|---:|---:|
| 1B leaves, 8.0 GB, default chunk | Segment | 13.126 s | 12.979 s (-1.1%) | 12.887 s (-1.8%) |
| 1B leaves, 8.0 GB, default chunk | Disk | 14.007 s | 13.163 s (-6.0%) | 12.941 s (-7.6%) |
| 5B leaves, 40 GB, small chunk | Segment | 58.383 s | 58.400 s (+0.0%) | 58.160 s (-0.4%) |

The complete 1B means contain six measured writes. The 5B result is
preliminary: one block with two measured writes and a 262,144-long chunk.

The 1B Segment result is 609.5 MB/s, or 87% of the published 700 MB/s. The
preliminary 5B result is 685.1 MB/s, or 98%. Micron obtained its number with a
different tool and write pattern, so 700 MB/s is not an exact ceiling for our
Java/ext4 workload. The same-protocol control subsequently established a
12.62-second, 634 MB/s durable-write plateau for an 8 GB file. Its provisional
comparison with the historical LongList campaign must be revisited after the
corrected baseline.

The campaign also established that:

- Additional Segment writers provide no material large-state improvement on
  this host.
- Disk benefits from parallelism: `P=2` saved 6.0% at 1B and won in every
  corresponding A/B/C block. Whether this comes from overlapping its source
  work or from target-file concurrency remains to be established.
- At 1B/default/`P=1`, all five implementations fall between 13.126 and
  14.007 seconds. This suggests that their shared target-file path dominates
  at large size, but it does not separate body-write time from force time.

It did **not** establish the same-protocol practical ceiling, the time split
between body writes and `force(true)`, or the end-to-end effect on a
representative Linux MerkleDb snapshot.

## 2. Questions the experiments must answer

1. **How much headroom remains in the current durable file-writing path?**
   Run one prepared-memory control using the same Java, filesystem, and
   durability boundary as LongList. Compare that reference with the existing
   results for all five implementations. A stable gap identifies something to
   investigate; the control alone does not explain its cause.
2. **Does the current parallel-writing change improve a complete snapshot?**
   The isolated benchmark shows a repeatable improvement for `LongListDisk`,
   but not for `LongListSegment`. Compare complete snapshots using those two
   implementations currently selected by `MerkleDbDataSource` at `P=1` and
   `P=2`. Keep the change only if its end-to-end result justifies it without a
   material regression in the other mode.
3. **How much time could removing `force(true)` actually save?**
   Time the body writes and `force(true)` separately, then compare forced and
   unforced runs without making one trial pay for another trial's pending disk
   work. Consider removing the force only if the saving is material and the
   saved-state durability and recovery contract allows it.
4. **Would writing fewer bytes justify a file-format change?**
   This is a team-discussion idea, not a scheduled experiment. If the team
   agrees to investigate it, measure compression ratio, CPU cost, and read cost
   on representative index files before considering a prototype.

## 3. Where the measured time can go

When Java writes a large LongList file normally, these stages overlap:

1. `FileChannel.write()` copies bytes from the LongList's buffer into the
   operating system's file cache. If that cache fills faster than the drive
   drains it, the write call itself slows down or waits.
2. The operating system writes cached bytes to the drive in the background
   while Java continues issuing writes.
3. After all write calls finish, `force(true)` waits for any remaining file
   content and required metadata to become durable.

The benchmark currently measures all of this as one number:

```text
total = create/header + body writes + force + close
```

It does not show how the time is divided. The body writes may already include
most of the wait for the drive, or a substantial wait may remain in
`force(true)`. Time the two phases separately before predicting how much Way 2
can save.

## 4. Way 1 — reduce durable-write time

### Step 1 — establish one practical reference

The Linux host's `/home` filesystem is on a 480 GB Micron 7450 PRO M.2
(`MTFDKBA480TFR`), rated at 700 MB/s for sequential writes. That published
number is useful context, but it was measured with a different workload and is
not a pass/fail threshold for our Java/FileChannel/ext4 workload. The next
experiment establishes the more relevant reference using the same software and
storage path as `LongList.writeToFile()`.

```text
LongList: read/prepare its data -> FileChannel/ext4 -> SSD -> force
Control:  prepared memory       -> FileChannel/ext4 -> SSD -> force
```

Add a dedicated `FileChannelWriteBenchmark`; it does not construct or read a
LongList. The benchmark prepares deterministic, densely populated
pseudo-random data before timing, then writes an 8 GB body through one shared
`FileChannel` using 8 MiB requests and contiguous, non-overlapping worker
ranges. It uses the same `/home` filesystem and the same create, write,
`force(true)`, and close boundary as the LongList writer. Record body-write
time, force time, and total time, and delete the output after every invocation.

Sweep `writerThreads={1,2,8,16,32}` and use the lowest stable mean as the
practical best-case reference for the current Java/FileChannel/ext4 protocol
on this host. Start with six measured writes per setting across three reordered
blocks. Confirm only the fastest settings, and only if their uncertainty
prevents establishing the practical plateau.

**Result:** `P=8` and `P=16` established the same approximately 12.62-second
plateau. Parallel writers shortened the body phase by about 25%, but the final
force became longer, leaving a 3.4% end-to-end reduction. See
[`filechannel-write-reference.md`](00-filechannel-write-reference/filechannel-write-reference.md).

Compare the resulting reference with the isolated results for all five
LongList implementations:

- A LongList result close to the control has little demonstrated same-path
  headroom.
- A LongList result materially slower than the control has a gap worth
  profiling. The gap does not by itself say whether the cause is source access,
  copying, buffer shape, file growth, or something else.
- If all implementations are close to the control, stop experimenting with
  ways to write the same bytes and move to ideas that reduce the output.

Keep results for all five LongList implementations. Highlight Segment and Disk
because they are currently selected by `MerkleDbDataSource`, but do not treat
them as the only results that matter.

### Step 2 — only if Step 1 exposes a gap

First time and profile the slower phase. Then test only the cause that the
measurement identifies:

1. **File growth is expensive:** reserve the file's physical disk blocks before
   writing and compare total durable-write time. Merely setting the logical file
   length is not physical preallocation.
2. **The buffered page-cache path is expensive:** consider a benchmark-only
   direct-I/O prototype. Direct I/O avoids the page cache, but it may be slower
   and is not a small change here: the 12-byte header, body offsets, and final
   partial range are not block-aligned.

Periodic `force()` calls are not a planned experiment. Each call waits; it does
not tell the operating system to start asynchronous writeback.

### Idea to discuss with the team — write fewer bytes with compression

If the control is already close to the storage limit, the remaining durable
time is largely determined by how many bytes must reach the drive. Compression
could still help because a smaller file requires less storage traffic even
when the drive's bytes-per-second rate does not change.

LongList values may be compressible because they contain repeated data-file
identifiers and related offsets. An earlier synthetic index-shaped sample
compressed by about 4:1, which makes the idea worth discussing, but synthetic
data can be much more regular than a real production index. Treat that result
as motivation, not as a production prediction.

This would be a new LongList file format; MerkleDb does not compress these
files today. Snapshot creation would spend CPU on compression, and loading
would spend CPU on decompression. Loading could also become faster because it
would read fewer bytes, but that is not guaranteed. Chunk compression could be
parallelized, but its CPU impact must be measured in the real snapshot context.

Do not run or implement this idea without team agreement. If the team wants to
investigate it, start with representative real index files and measure the
compression ratio, compression time, and decompression/load cost. Build a
prototype only if those measurements predict a worthwhile end-to-end gain.

## 5. Way 2 — remove the final LongList `force(true)`

The idea raised by the team is to let `LongList.writeToFile()` return after its
write calls and channel close, without the final `force(true)`. Linux would
continue storing any cached bytes in the background.

This does not imply that an 8 GB write will fall from about 13 seconds to RAM
copying time. The body write calls may already slow down while Linux drains
dirty pages to the drive. The phase timing from Section 3 must show how much
time actually remains in `force(true)` before predicting the gain.

### What happens to the atomic directory move

`SignedStateFileWriter` first builds the complete signed state under a
temporary directory. MerkleDb waits for every snapshot task and LongList worker
to finish, and every LongList file channel is closed before snapshot creation
returns. Only then does `executeAndRename()` atomically move the temporary
directory to its final name.

Removing `force(true)` does not introduce a race with that move. All Java writes
are finished before the rename. Bytes that Linux has not yet stored remain
attached to the same underlying files in the operating system's cache. Renaming
the directory changes their path, not their identity, so Linux continues
writing them afterward. Under normal operation, an immediate reader can also
read those cached bytes through the final path.

The atomic move guarantees that another process or thread does not see the
final directory halfway through its construction. It does not wait for every
file in that directory to reach durable storage.

### What the current LongList force provides

The current `force(true)` waits only for the LongList index files. It does not
make the signed-state snapshot durable as a whole: the data files and
supplemental files are not all forced under one protocol, and the final
directory move is not followed by a directory sync.

Removing the call therefore does not turn a fully durable snapshot into a
non-durable one. It removes an isolated LongList-only wait. That wait still has
two local effects today: it confirms those index files before the snapshot is
published, and it can report a pending LongList writeback failure to the
caller. The question is whether those partial effects have enough practical
value when they cannot make the complete snapshot durable by themselves.

### How to measure Way 2

Compare forced and unforced writes while recording body-write, force, and total
time. An unforced iteration must not leave dirty data for the following
iteration to inherit; drain or account for its pending writeback outside the
timed interval. Keep the forced result as the reference for the device work
that still occurs after an unforced call returns.

## 6. Way 3 — combine only measured wins

Way 3 is not a separate optimization. It combines removing the final LongList
force with a Way-1 change only after that change proves beneficial on its own.

### Possible combinations

- **Physical block preallocation:** combine it with Way 2 only if its
  experiment reduces total snapshot time after including the cost of reserving
  the blocks. It is not assumed to be free.
- **Compression:** consider the combination only after team approval and
  favorable measurements on real index files. Fewer bytes could reduce both
  the visible write work and the background drain, but compression CPU may
  offset either benefit.

Periodic forces remain excluded. Direct I/O is a separate strategy rather than
a useful Way-2 pairing: bypassing the page cache leaves blocking writes tied to
storage progress and removes most of the expected early-return benefit. Direct
I/O also does not provide synchronized durability by itself.

### Limits and measurements

Removing the final force can return early only while Linux accepts dirty data
faster than the drive stores it. The available dirty-page budget depends on the
host's memory and writeback state. Once that budget is reached,
`FileChannel.write()` is throttled toward storage speed, so the Way-2 benefit
shrinks. The phase measurements must determine how much work actually remains
after an unforced call returns.

## 7. Scheduling candidate inside `snapshot()`: overlap the hash-cache pre-flush

This candidate can benefit all three ways described above. Its actual benefit
must be measured because the overlapping work may compete for CPU, memory, and
storage resources. As with compression, discuss this candidate with the team
before adding instrumentation, implementing it, or running the experiment. If
the team approves it, measure the existing pre-flush first and implement the
overlap only if that duration is significant.

**What the pre-flush is.** MerkleDb keeps the most frequently updated hash
data — the top of the hash tree, touched every round — in an in-memory cache
instead of rewriting it to disk constantly. A snapshot must contain
everything, so `snapshot()` first empties that cache into the hash store: up
to 262,144 entries and, under the current default hash-chunk configuration,
worst case approximately 0.8 GB, written entry by entry **on one thread,
before any of the six snapshot tasks starts**
(MerkleDbDataSource.java:886-894).

**The fact that makes it fixable:** only two of the six tasks consume what
the flush produces — the hash store's file list and the hash index. The other
four — including the leaf-path index — touch different data (verified by code
reading: the flush mutates only the hash store and its index).

**The fix, as a timeline:**

```text
Today:   [ flush ][ independent snapshot tasks ........ ]
                 [ hash index ][ hash store ]

Fixed:   [ independent snapshot tasks .................. ]
         [ flush ][ hash index ][ hash store ]
```

Start the four independent tasks immediately, run the flush alongside them,
then start the two hash tasks after the flush succeeds. This shortens the
dependency path from `flush + max(all snapshot tasks)` to
`max(independent tasks, flush + hash tasks)`. It does not guarantee a faster
snapshot: the newly overlapping work may contend for shared resources.

**Why it may matter more under Ways 2/3:** if another optimization makes the
LongList writes substantially faster, the serial pre-flush becomes a larger
fraction of total snapshot time. The flush itself does not call `force()`, but
it still performs serialization, index updates, memory copies, mappings, and
filesystem-backed writes, so its duration must be measured rather than
assumed.

**Three conditions found during verification (all implementable):**

1. The hash store and hash index snapshots must begin only after the flush
   succeeds, so the copied files and their recorded locations remain
   consistent.
2. A failed flush must prevent both dependent tasks from running, complete
   their dependency without hanging the snapshot, and propagate the failure
   to the caller.
3. The existing snapshot exception behavior must be preserved.

**How to measure it:** this one is invisible to the isolated list benchmark —
it needs the *whole-snapshot* benchmark (`MerkleDbSnapshotBenchmark`), and a
timer around the flush should land first. The cache entry count is bounded by
configuration regardless of state size, but the flush duration has not yet
been measured.

**Team-discussion idea:** if measurements show that the flush itself becomes a
significant bottleneck, discuss parallelizing it with the team before adding
that experiment. `DataFileWriter` supports concurrent writes and `LongList`
supports concurrent updates, but the complete `writeHashes()` path would still
need correctness analysis and measurement.

## 8. Benchmark strategy

### Policy: Linux only

All decision numbers come from the Linux machine. Reasons: production runs
Linux/ext4/NVMe; the macOS numbers already misled twice (a ~9× faster drive,
and a 25–35% result that shrank to ~6% on Linux); and benchmarking must not
block a developer's laptop. The macbook is used only to smoke-test that
benchmark code runs (tiny sizes), never for numbers. One cheap insurance
before committing a final default: validate the *winning* way once on a
second device — the host's faster striped `/opt` volume counts — so
conclusions aren't married to one 447 GB drive.

### Harness fixes and focused confirmation

1. **Fix the 5-billion out-of-memory failure.** The runner currently gives
   every 5B case a 48 GiB heap, although `LongListHeap` retains different
   amounts of memory at different chunk sizes. Keep all five implementations
   and make heap sizing depend on both leaf count and chunk size. At 5B, use
   `-Xmx48g` for 262,144-long chunks, `-Xmx64g` for 1,048,576-long chunks, and
   `-Xmx96g` for 4,194,304-long chunks. These limits follow the retained G1
   region footprints calculated from the failed campaign.
2. **Test the disk-cache hypothesis narrowly.** `LongListDisk` copies the
   fixture into its temporary backing file during setup, which warms that
   source in the OS cache. Linux's 125 GiB RAM makes it likely that the whole
   8 GiB source remains cached, but this does not prove that cache state caused
   the Linux/macOS result difference. Do not add warm/cold state to the full
   matrix. Run one focused warm-versus-cold comparison for `LongListDisk` at
   1B leaves, the default chunk size, and `P={1,2,8}`. Use only a method that
   can reliably evict the benchmark source; otherwise do not label the run
   cold.
3. **Collect more samples only for decision-making cells.** The broad campaign
   has two measured writes in each of three parameter-order blocks, giving six
   samples per cell. Keep broad exploratory runs bounded, then give shortlisted
   comparisons five measured writes in each of the three counterbalanced
   blocks, giving 15 samples per cell. Preserve the individual measurements
   and report their mean and observed variability; the different block orders
   reduce bias from machine drift during a long run.
4. **Complete the automatic environment record.** The existing Linux host is
   already documented as a Micron 7450 NVMe using ext4 with
   `rw,relatime,stripe=16` and 125 GiB RAM. For future result archives, add the
   exact filesystem and mount options from `findmnt` and explicit drive model,
   size, rotational status, and transport columns from `lsblk` to
   `environment.txt`. The runner already records the remaining required
   source, JVM, operating-system, CPU, memory, and capacity information.

### Directory structure for the branch folder

Everything lives under `26469-longlist-index-chunks-can-be-written-to-disk-in-parallel/`:

```text
26469-longlist-index-chunks-can-be-written-to-disk-in-parallel/
│
├── README.md
│   # Index of every document and experiment, with its current status and
│   # one-line verdict.
│
├── snapshot-optimization-report.md
│   # Master hypothesis and execution plan. Updated with cross-experiment
│   # conclusions as evidence arrives.
│
├── assessment-go-no-go.md
│   # Placeholder for the final concise recommendation after all approved
│   # experiments finish.
│
├── 00-filechannel-write-reference/
│   └── filechannel-write-reference.md
│       # Prepared-memory -> FileChannel -> ext4 -> force(true) control, with
│       # its method, measurements, and conclusion.
│
├── 01-parallel-chunk-writes/
│   ├── proposal.md
│   │   # Existing production-code design for parallel LongList chunk writing.
│   ├── macos-benchmark-results.md
│   │   # Existing development-machine evidence, retained for history.
│   ├── linux-benchmark-results.md
│   │   # Rewritten after the corrected complete Linux baseline campaign.
│   ├── disk-cache-diagnostic.md
│   │   # Planned focused LongListDisk warm/cold comparison.
│   └── raw/
│       └── long-list-snapshot-partial.tar.gz
│           # Raw results from the interrupted Linux campaign already completed.
│
├── 02-reduce-durable-write-time/
│   ├── physical-block-preallocation.md
│   │   # Created if profiling identifies file growth as meaningful overhead.
│   ├── direct-io.md
│   │   # Created if profiling identifies the buffered page-cache path as the gap.
│   └── compression.md
│       # Created only after the team agrees to investigate compression. Start
│       # with ratio and load-cost measurements; prototype only if gated in.
│
├── 03-remove-final-force/
│   └── remove-final-force.md
│       # Implementation, isolated and whole-snapshot measurements, and
│       # conclusion. Detailed correctness reasoning remains in this report.
│
├── 04-combine-measured-wins/
│   ├── no-force-plus-preallocation.md
│   │   # Created only if preallocation and removing force independently win.
│   └── no-force-plus-compression.md
│       # Created only with team approval and independent favorable results.
│
└── 05-overlap-hash-cache-flush/
    └── overlap-hash-cache-flush.md
        # Created only after team approval. Contains pre-flush timing and, if
        # that timing gates it in, the scheduling change and snapshot results.
```

Each experiment document starts with its conclusion and records the tested Git
revision, environment summary, comparison baseline, raw archive name and
checksum, method, and measurements. Large future archives may remain outside
Git; the existing 123 KiB partial campaign archive is already tracked and stays
with the parallel-write evidence.

### Execution ladder

For every experiment, preserve its raw output first. Before interpreting those
results or creating or editing its Markdown document, agree on a document
structure tailored to that experiment. Then analyze the data, write the
document, and verify its calculations and conclusions.

1. ~~**Prepare the branch directory.** Create the agreed semantic directories;
   move and rename the existing proposal and result documents; move the partial
   Linux archive under `01-parallel-chunk-writes/raw/`; keep this report and
   `assessment-go-no-go.md` at the root; and repair every affected relative
   link. Do not create documents for conditional experiments yet.~~
2. ~~**Run the FileChannel write reference on Linux.** Implement and smoke-test
   the dedicated control on the MacBook for correctness only, then run the
   writer-count sweep on Linux. Agree on `filechannel-write-reference.md`
   before processing its raw results.~~
3. **Re-establish the corrected parallel-chunk baseline.** Apply the 5B heap,
   environment-capture, and sampling changes; compile and run a tiny local
   correctness smoke; then start the real Linux campaign directly. Its early
   10M work provides the operational check before the campaign reaches larger
   states. Complete all five LongList implementations, including 5B; use the
   larger sample count for shortlisted comparisons; and run the planned
   `LongListDisk` warm/cold diagnostic as part of resolving the baseline. Agree
   on the structures of `linux-benchmark-results.md` and
   `disk-cache-diagnostic.md` before processing their raw results.
4. **Compare the FileChannel reference with the corrected baseline.** Decide
   which implementations have material same-path headroom, which measured
   phase is responsible, and which durable-write experiment, if any, that
   evidence justifies.
5. **Run only the resulting measurement-gated durable-write experiments.**
   Test physical block preallocation only if file growth is implicated, and
   direct I/O only if the buffered path is implicated. Preserve and document
   each experiment independently.
6. **Test removing the final force independently.** This experiment runs even
   if Step 5 produces no candidate. If Step 5 does run an experiment, finish
   and preserve that benchmark first, then start the no-force campaign while
   processing and documenting the completed Step-5 results. Never overlap two
   benchmark campaigns on the Linux machine.
7. **Take compression and hash-cache pre-flush overlap to the team.** Neither
   experiment proceeds without approval. If approved, each retains its own
   measurement gate: representative ratio and load-cost evidence before a
   compression prototype, and existing pre-flush timing before the scheduling
   change.
8. **Combine only independently measured wins.** Give every tested combination
   its own document so its result can be compared with each individual change.
9. **Run the final production-shaped gate.** Compare the surviving candidate
   with the current baseline using total snapshot latency and tails, then run
   the winner once on the host's second `/opt` device before selecting the code
   and configuration recommendation.
10. **Finalize the documents.** Update the cross-experiment conclusions in
    this report and rewrite `assessment-go-no-go.md` with the final
    recommendation.
