# Making the snapshot's LongList writes faster

**The goal.** `MerkleDbDataSource.snapshot()` spends nearly all of its time
writing LongList index files to disk. The biggest one is the leaf-path index:
8 bytes per leaf, so ~8 GB at 1 billion leaves. Everything else the snapshot
does is cheap by comparison — the actual data files are *hard-linked* (the
filesystem adds a second name pointing at an existing file; no bytes are
copied). So: **make the LongList write faster, and the whole snapshot gets
faster.** This document stays at that level. How and when the application
calls `snapshot()` is out of scope.

There are **three ways** to attack the write:

- **Way 1 — Disk:** keep waiting for the drive, but reach the drive's physical
  sequential-write speed (the best case a drive advertises for storing one
  continuous stream). Measure the true ceiling first, then optimize against it.
- **Way 2 — RAM:** stop waiting for the drive — remove the final "confirm
  everything is on disk" wait, so the write finishes at memory speed and the
  operating system finishes the disk work in the background. Needs research on
  what, if anything, relies on the current wait.
- **Way 3 — combine:** don't wait (Way 2) *and* shrink the total work with the
  Way-1 pieces that still apply. Only some do — see Section 6.

Plus one scheduling fix inside `snapshot()` itself (Section 7), and the
benchmark strategy that decides everything (Section 8).

*(Sizes and speeds are decimal: 1 GB = 10⁹ bytes.)*

---

## 1. What the write actually does today

A LongList is an in-memory array of 8-byte numbers, one per leaf path. Each
number encodes "which data file holds this leaf, and at what position inside
that file."

`writeToFile` (AbstractLongList.java:501-511) does, in order:

1. Creates the file (empty).
2. Writes a 12-byte header.
3. Writes the list contents front to back, one *chunk* at a time (a chunk is
   8 MB by default). One continuous stream — the friendliest pattern for an
   SSD.
4. Calls `force(true)` — "do not return until the drive confirms every byte
   is physically stored" — and closes the file.

Three facts that matter later:

- **No compression exists anywhere in this path — or anywhere in MerkleDb.**
  (Verified: no compression code in the module's production sources.) The
  bytes in memory are exactly the bytes that reach the drive: 8 GB of list =
  8 GB of drive traffic. Compression appears in this document only as a
  *proposal* (Section 4, Step 3).
- **The file is not pre-sized.** It starts at 0 bytes and grows with every
  chunk written (~1,000 growth steps for an 8 GB file).
- **The final wait is unique to index files.** In the entire MerkleDb codebase
  there are exactly four "wait for the drive" calls: the two in the LongList
  snapshot write, one wasted call in `LongListDisk.close()` (it confirms a
  temp file onto the drive and then immediately *deletes* that file —
  LongListDisk.java:501,507), and one in a class production never uses. The
  data files that the index points at are **never** force-confirmed. Section 5
  builds on this.

## 2. What the benchmarks measured, and how to read the numbers

Speeds are computed as *file size ÷ published mean time* from the Linux
campaign (`linux-benchmark-results.md`; each mean covers 6 measured writes on
an AMD EPYC server, ext4 filesystem, single NVMe drive). "Segment" is the
production-default list implementation; 1 thread is the production default.

| Leaves | File size | Time (Segment, 1 thread) | Effective speed |
|---|---:|---:|---:|
| 10 million | 80 MB | 0.180 s | ~444 MB/s |
| 100 million | 800 MB | 1.712 s | ~467 MB/s |
| 1 billion | 8.0 GB | 13.126 s | ~609 MB/s |
| 5 billion (preliminary) | 40 GB | 58.4 s | ~685 MB/s |
| 1 billion — macOS dev machine | 8.0 GB | ~1.42 s | ~5,600 MB/s |

**Three facts the campaign established:**

1. **More threads don't help this write.** At 1 billion leaves, 2–32 writer
   threads changed Segment's time by at most ~2%. Plain reason: all threads
   write into the *same file*, and the operating system makes writers of one
   file take turns; and even without the taking-turns rule, the drive accepts
   bytes at its own fixed rate.
2. **The time is spent pushing bytes into the file, not preparing them.**
   Five list implementations that keep data in completely different places
   (Java arrays, off-heap memory, memory segments, a temp file, a
   memory-mapped file) all land at ~13 s for the same 8 GB output. The only
   step they share is "push bytes into the file and wait."
3. **Bigger files run faster per byte** (444 → 685 MB/s): one-time costs
   (creating the file, the final confirmation) fade as size grows.

**The one thing the campaign did NOT establish: the drive's actual maximum.**
No spec sheet was consulted and no direct speed test was run. From the raw
archive kept on this branch: the benchmark volume is a single **447 GB NVMe
drive** (ext4, nearly empty), and its *model name was never recorded*, so the
spec can't be looked up after the fact.

Why this matters: **drive write speed scales with drive capacity** (bigger
drives contain more flash chips that can be written in parallel). Server
drives in the ~480 GB class are commonly rated at only **500–1,100 MB/s**
sequential write. So the measured 609–685 MB/s might already be this drive's
physical maximum — or there might be real headroom. Unknowable without the
model name or a measurement. Also: the same machine has two 1.7 TB drives
striped together (mounted at `/opt`), likely several times faster — the
campaign ran on the machine's *slowest* volume.

> **Correction to earlier versions of this document:** previous wording said
> the write is "saturated / at the device limit." Overconfident. Proven:
> threads don't move it, and the limiter sits in the shared write-and-wait
> step. Not proven: that ~600 MB/s equals the drive's physical limit.

## 3. Where the time goes — the plain-english physics

When Java writes to a file normally, three things happen:

1. Bytes are **copied into the operating system's memory** (the "page cache")
   at RAM speed — roughly 1–2 s for 8 GB.
2. The OS **streams those bytes to the drive in the background**, at drive
   speed.
3. `force(true)` **waits** for whatever hasn't landed yet, plus the drive's
   final confirmation.

So today's measured ~13 s ≈ mostly step 3's waiting for the drive. That
observation is exactly why the ways are independent: make the drive part
faster (Way 1), or stop waiting for it (Way 2), or both (Way 3).

## 4. Way 1 — Disk: reach the drive's physical limit

### Step 1 — pin the ceiling (about an hour on the Linux host)

- **Get the drive model:** `lsblk -o NAME,MODEL,SIZE` (one command, no root),
  then read its spec-sheet sequential-write number. Add `MODEL` to the
  benchmark's system-check script so future campaigns record it.
- **Measure the practical maximum:** a trivial program that writes 8 GB of
  prepared in-memory junk to one file on the same filesystem as fast as it
  can, waiting for drive confirmation at the end — the same timed window the
  real benchmark uses. That number is "the best any program could achieve
  here." (Also cheap to run on the fast `/opt` volume for comparison.)

Spec sheet = the theoretical target; trivial writer = the truth for this box.
If they disagree, the spec was a burst number or the filesystem adds cost.

### Step 2 — if there's a gap, close it: three one-variable experiments

1. **Pre-size the file** to its final length before writing anything —
   removes ~1,000 rounds of mid-write growth bookkeeping. Cheapest, zero risk.
2. **Flush as you go** — push accumulated bytes to the drive every ~256 MB
   instead of only at the end, keeping the drive continuously busy and the
   final wait short.
3. **Skip the OS middleman ("direct I/O",** `ExtendedOpenOption.DIRECT`**):**
   writes go straight from our buffer to the drive — no copy into OS memory,
   no background pacing. The standard way one thread reaches spec numbers.
   Needs writes aligned to the drive's block size, which the 8 MB chunk layout
   satisfies. If one request at a time can't keep the drive busy, keep 2–4 in
   flight.

Already ruled out by campaign data: write-call size (2/8/32 MB chunks — same
speed) and thread count (fact 1).

### Step 3 — if the limit is real: write fewer bytes (compression — a proposal, nothing like it exists today)

To be explicit, because this caused confusion: **MerkleDb does no compression
anywhere today.** This step proposes adding it to this one file format.

The drive's limit applies to bytes that *physically reach it*, not to the
information they carry. The list's values are extremely repetitive (same data
file, nearby positions, for long runs of neighbors), and repetitive data
compresses well. A quick test with zstd (a fast, standard compressor) on
*synthetic* index data squeezed ~4:1. If that held on real data, the same
drive at the same 600 MB/s would store ~2 GB instead of 8 GB → **~3.5 s
instead of ~13 s**. It is the only lever that keeps working after the
hardware limit is proven, because it shrinks the number the limit multiplies.

Honest costs and unknowns:

- **CPU is not free on a consensus node.** During normal operation the cores
  are busy; compression would compete with consensus work. At the
  shutdown/freeze save the node has stopped processing transactions, so cores
  are freer *there* — but this must be measured, not assumed. (Compression
  does parallelize across cores feeding a single writer, unlike the write
  itself.)
- **A file-format change** — the loader must decompress; version bump; the
  startup path then also *reads* ~4× fewer bytes, which speeds boot.
- **The ratio is unverified on real data.** First action costs minutes and no
  code: run zstd over a real large index file from a node and read the ratio.

## 5. Way 2 — RAM: stop waiting for the drive (remove `force(true)`)

The idea (raised by the team, and the code evidence backs it): delete the
`force(true)` line from the LongList write. The write then returns after the
OS has the bytes in memory (~1–2 s for 8 GB instead of ~13 s), and the OS
finishes storing them in the background. This is not exotic — it is how
*almost every other write in MerkleDb already works*.

**The inconsistent-guarantee argument, verified in code:** in the entire
module, only the LongList index files get the "confirmed on drive" treatment.
The data files — the hashes, leaves, and key buckets that the index points at
— are written *without any confirmation, ever* (zero force calls in the whole
`files/` package). So a finished snapshot directory today holds
drive-confirmed indexes pointing at unconfirmed data. If power is lost at the
wrong moment, the snapshot can already be torn regardless of the index's
guarantee. The index's `force(true)` therefore buys **no end-to-end
guarantee** — it only makes the snapshot *slower*. Removing it makes the
guarantee consistent (uniformly "the OS has it") instead of inconsistently
partial.

**What must be researched before doing it** (the "will anything break" list):

1. **Process crash vs power loss — different animals.** If the Java process
   crashes right after a save, nothing is lost: the OS survives and finishes
   writing in the background. Bytes are only lost if the *machine* loses power
   or the kernel panics within the writeback window (seconds to ~half a
   minute) after the save. For the shutdown/upgrade flow, the process exits
   but the machine keeps running — the OS finishes the writes; a clean machine
   shutdown also flushes everything. The exposure is sudden power loss only.
2. **The saved-state protocol renames the finished snapshot into place.**
   Renaming makes the directory *appear* complete. After a power loss, a
   complete-looking state could contain a torn index file. This is *already
   true today for the data files* — the research question is whether removing
   the index's guarantee meaningfully widens an exposure that already exists,
   or just aligns it.
3. **What happens if a torn state is loaded?** Does state loading detect
   corruption (size checks, hash validation) and fall back loudly to an older
   state, or could it proceed silently? This determines the blast radius of
   the power-loss window and is the main thing to pin down. (The LongList
   loader derives the list's size from the file's length and reads the header
   — but there is no checksum on the body.) If the answer is bad, the likely
   mitigation is still simple: no-force *plus* a cheap end-of-file marker or
   checksum so a torn index is detected loudly at load.
4. **Where the write is consumed immediately** (some snapshot outputs are
   read back by the same process minutes later and then deleted), removing
   the wait is trivially safe — the OS serves the read from its own memory.

**Benchmark note for Way 2:** with the wait removed, the benchmark clock
measures RAM copying, and iteration N can end up paying for iteration N−1's
background flushing. The benchmark must then either watch the system's
"dirty bytes still to be written" figure, or keep one forced arm to account
for the true device work.

## 6. Way 3 — combine: don't wait, and shrink the work

Way 3 = Way 2 for latency + the Way-1 pieces that reduce *total* work. Not
everything composes; sort the Way-1 items into two piles:

**Compose with Way 2 (do these in Way 3):**

- **Pre-sizing** — less filesystem bookkeeping helps the background writer
  exactly as it helps a waiting writer. Free to keep.
- **Compression** — the strongest pairing. Visible time shrinks further (only
  ~2 GB to copy into OS memory → ~0.3–0.5 s instead of 1–2 s), the background
  drain shrinks 4×, the power-loss exposure window shrinks with it, and the
  next startup reads 4× fewer bytes.

**Conflict with Way 2 (Way-1-only techniques):**

- **Flush-as-you-go** — it *adds* waits; Way 2 removes them. Mutually
  exclusive by definition.
- **Direct I/O** — each direct write completes only when the drive has it;
  that's waiting, just spread out. It belongs to the world where the wait must
  stay.

**Two system-level truths about Way 3:**

1. **Way 2 depends on free memory — which is why Way 1's ceiling still gets
   measured.** The OS absorbs unwritten bytes in memory only up to a limit;
   past it, the OS slows the writer down to drive speed anyway. On a host with
   little free RAM, Way 2 quietly degrades into Way 1 — so the drive ceiling
   (Way 1, Step 1) still predicts the worst case and sizes the background
   drain. Measure it regardless of which way wins.
2. **The disk work doesn't disappear — it moves.** After an unforced write
   returns, the drive spends the next ~13 s (or ~3.5 s compressed) storing in
   the background. For the shutdown flow this lands in the upgrade window —
   dead time, ideal. But if the node restarts *quickly*, leftover background
   writing competes with startup's reads. Worth one benchmark arm:
   write-unforced, then immediately time a cold start.

## 7. In-scope scheduling fix inside `snapshot()`: overlap the hash-cache pre-flush

*(Benefits all three ways; relatively more important under Ways 2/3 — see
below.)*

**What the pre-flush is.** MerkleDb keeps the most frequently updated hash
data — the top of the hash tree, touched every round — in an in-memory cache
instead of rewriting it to disk constantly. A snapshot must contain
everything, so `snapshot()` first empties that cache into the hash store: up
to ~262,000 entries, worst case ~0.8 GB, written entry by entry **on one
thread, before any of the six snapshot tasks starts**
(MerkleDbDataSource.java:886-894).

**The fact that makes it fixable:** only two of the six tasks consume what
the flush produces — the hash store's file list and the hash index. The other
four — including the 8 GB leaf-path index, the giant — touch completely
different data (verified by code reading: the flush mutates only the hash
store and its index).

**The fix, as a timeline** (illustrative numbers at 1-billion-leaf scale):

```
Today:   [flush ~0.5s][ leaf index ~13s ............. ]   total ≈ 13.5s
                      [ hash index ][ links ][ meta ]

Fixed:   [ leaf index ~13s ............. ]                total ≈ 13s
         [flush ~0.5s][ hash index ]
         [ links ][ meta ]
```

Start the four independent tasks immediately, run the flush alongside them,
release the two hash tasks when the flush finishes. New total =
`max(leaf index, flush + hash tasks)` instead of `flush + max(everything)` —
**never worse**, and the flush time vanishes whenever the leaf index is the
long pole (always, at scale).

**Why it matters more under Ways 2/3:** under Way 1 this hides ~0.5–1 s
behind a 13 s write — a few percent. Under Way 2 the leaf write shrinks to
~1–2 s of memory copying, so an unhidden serial flush would suddenly be a
*third* of the whole snapshot. (The flush itself is already unforced — its
cost is per-entry processing, not drive waiting — so it hides well behind
either kind of leaf write.) If under Way 3 the flush itself becomes the long
pole, its own follow-up exists: the store writer explicitly supports several
threads storing entries at once, so the flush loop can be parallelized.

**Three conditions found during verification (all implementable):**

1. The hash store's snapshot must not begin until the flush's file is fully
   finished — starting early would silently produce a snapshot whose index
   points at a file that isn't in it (broken snapshot, no error at write
   time).
2. If the flush fails, the two waiting tasks must still be released, or the
   snapshot waits forever.
3. The error must be re-thrown as the same exception type callers expect.

**How to measure it:** this one is invisible to the isolated list benchmark —
it needs the *whole-snapshot* benchmark (`MerkleDbSnapshotBenchmark`), and a
timer around the flush should land first (it has never been measured;
estimated 0.3–1 s worst case, bounded by a config constant regardless of
state size).

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

### Fixes to the harness before re-baselining

1. **The 5-billion out-of-memory failure.** The recorded cause: the benchmark
   tried to load the *diagnostic* `LongListHeap` implementation (plain Java
   arrays on the heap) at 5B into a 48 GB heap. Simplest fix: at 5B, run only
   the two production implementations (Segment, Disk) — the diagnostic ones
   proved their point at smaller sizes; this also roughly halves campaign
   time. *(Details of the failure to be provided; fold them in here.)*
2. **Cache state becomes an explicit axis: warm vs cold.** The suspected
   reason the disk-backed list's improvement looks small on Linux: 125 GB of
   RAM meant its 8 GB source file sat entirely in OS memory — source reads
   were nearly free, so overlapping them saved nothing. Production's
   interesting cases (restart, memory pressure) have *cold* sources. Every
   configuration that reads from disk gets two arms: warm (as today) and cold
   (drop the OS cache before each measured write, or use a source larger than
   RAM). Record cache state in the results. This may correct *all*
   implementations' numbers, not just Disk's.
3. **More data per cell** — more measured writes per configuration and enough
   repetitions to report variance, since some effects being hunted are
   single-digit percentages.
4. **Way-2 arms need different accounting.** Without the final wait, the
   stopwatch measures RAM copying and iteration N can pay for iteration
   N−1's background flushing. Those arms must also record the system's
   "dirty bytes still to be written" before/after, and keep one forced arm as
   the device-work reference.
5. **Record the environment properly**: drive model (`lsblk -o NAME,MODEL`),
   filesystem, mount options, free RAM — into `environment.txt` via the
   system-check script.

### Directory structure for the branch folder

Everything lives under `26469-longlist-index-chunks-can-be-written-to-disk-in-parallel/`:

```
26469-longlist-.../
  README.md                        — index: every experiment, one-line verdict, links
  docs/
    plan.md                        — this document
    way2-safety-research.md        — the force(true)-removal research answers
    results-and-recommendation.md  — final deliverable (step 3 of the plan)
  benchmarks/
    00-baseline/                   — re-run of current code after harness fixes
    01-storage-ceiling/            — drive model + spec + trivial-writer numbers
    02-way1-presize/
    03-way1-flush-as-you-go/       — only if 01 shows a gap
    04-way1-direct-io/             — only if 01 shows a gap
    05-way2-no-force/
    06-way3-no-force-plus-presize/
    07-compression-ratio-probe/    — zstd on a real index file; gates 08
    08-way3-compression/           — only if 07's ratio is good
    09-preflush-overlap/           — whole-snapshot benchmark, needs the timer
    10-disk-cold-cache-rerun/      — settles the reopened parallel question
```

Each experiment folder contains: `results.md` (conclusion first), the exact
commit hash the numbers came from, `environment.txt`, and raw data (or the
archive's checksum if raw is kept elsewhere). Every experiment states which
baseline it compares against. Numbers prefix = execution order.

### Suggested execution order (cheapest and most decisive first)

1. **Harness fixes + 00 re-baseline + 01 ceiling.** An hour of machine time;
   01 informs every later verdict.
2. **05 no-force** — a one-line change with the largest expected
   ratio-of-win-to-effort; start `way2-safety-research.md` in parallel (the
   torn-state-on-load question is the one that could demand a mitigation).
3. **02 pre-size** — cheap; also tells us whether Way 1 has any soft overhead
   to reclaim at all.
4. **07 compression ratio probe** — minutes, no code; a bad ratio kills the
   whole compression branch early, a good one justifies building 08.
5. **03/04 (flush-as-you-go, direct I/O)** — only if 01 showed the current
   writer below the drive's true ceiling.
6. **09 pre-flush overlap** — timer first, then the reorder, measured on the
   whole-snapshot benchmark.
7. **10 cold-cache re-run** — resolves whether parallel disk-list writes were
   under-credited by a warm cache.
8. **Write `results-and-recommendation.md`** — every way measured, one
   recommendation with numbers.

### Which earlier "dead ends" stay dead

- **Parallel writers for the memory-backed (production default) list**: flat
  (±2%) on Linux at every thread count, worse on macOS; the source is RAM, so
  there is no second stage to hide behind the write. Stays dead on evidence
  from both platforms.
- **Tuning write-call sizes**: 2/8/32 MB measured identical.
- **Skipping zero regions**: production indexes are dense, and the loader
  derives list size from file length — end-of-file holes corrupt loading.
- **Parallel writers for the disk-backed list**: *reopened* — see harness fix
  2; verdict deferred to experiment 10.

## Sources

- `26469-longlist-index-chunks-can-be-written-to-disk-in-parallel/linux-benchmark-results.md`
  — all Linux times (means of 6 measured writes per cell); speeds = size ÷ time.
- Raw campaign archive (`long-list-snapshot-partial.tar.gz` →
  `environment.txt`) — host facts: EPYC 9124, 125 GB RAM, benchmark volume =
  447 GB NVMe / ext4 / 3% full, model name absent; faster striped volume at
  `/opt`.
- `AbstractLongList.writeToFile` (AbstractLongList.java:501-511) — write
  sequence, no pre-size, `force(true)`.
- Force-call inventory (verified by search over all production sources):
  AbstractLongList.java:509,526; LongListDisk.java:501 (immediately before
  file deletion at :507); LongListDiskSegment.java:322 (not used in
  production); zero in the `files/` package (data files never confirmed).
- Compression: verified absent from all MerkleDb production sources; the 4:1
  figure is from a quick zstd test on synthetic index-shaped data, not from
  the codebase.
- Pre-flush overlap: MerkleDbDataSource.java:886-894 (the serial batch),
  :1157-1191 (what it touches), verified against each of the six tasks;
  multi-thread store writes supported per DataFileWriter.java:40-56.
- macOS numbers: `benchmark-results.md` on the same branch.
