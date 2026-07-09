# MerkleDb File Monitoring Service — Design Spec

Issue: #26032 · Branch: `26032-merkledb-to-monitor-new-data-files` · Status: DRAFT for discussion

## 1. Goal and background

During a zero-downtime upgrade (ZDU), a new consensus-node process opens a MerkleDb data
source over the live database directory in `swirlds-tmp` (e.g. `swirlds-tmp/merkledb-state/`)
while the old process still owns it and keeps writing: flushes create new data files,
compaction creates and deletes data files, and `table_metadata.pbj` is rewritten on every
flush. The new process runs in **monitoring mode**: it must not serve reads (requests should
fail), and it must keep its in-memory `DataFileCollection` state for all three stores in sync
with the directory until it takes ownership. At takeover, monitoring stops and background
compaction starts.

The builder already anticipates this: if `swirlds-tmp/merkledb-state` exists at startup, "this
is considered a version upgrade, so the data source is created directly from that folder"
(`MerkleDbDataSourceBuilder.java:41-45,112-139`).

**This PR** ships the mechanism in single-process test mode: one node runs normally, and a
shadow monitoring data source follows the same directory in the same JVM, behind a feature
flag. The two-process orchestration comes later.

## 2. On-disk contract (what today's writer produces)

The design must work against an **unmodified old-release writer** — during the first real ZDU,
the old process runs old software, so no new writer-side protocol can be relied on. These are
the signals that already exist:

| Fact | Reference |
|---|---|
| Per-table layout: `table_metadata.pbj`, `idToHashChunk/`, `objectKeyToPath/`, `pathToHashKeyValue/`, index files `*.ll` | `MerkleDbPaths.java:36-47` |
| Data file name: `<store>_<yyyy-MM-dd_HH-mm-ss-SSS>_L<level>_<idx padded to 10>.pbj`; per-collection index is monotonic (`nextFileIndex.getAndIncrement()`) | `DataFileCommon.java:101-116`, `DataFileCollection.java:680` |
| Authoritative copy of index/creation time/level/itemsCount in the protobuf header | `DataFileMetadata.java` |
| Files are created **at their final name** and written in place via overlapping mmap windows; the file is pre-extended (~128 MiB mapping) during writing | `DataFileWriter.java:161-171,389-393` |
| Commit point: `DataFileWriter.close()` rewrites the header in place with the final `itemsCount` (FIXED64 → constant header size, cannot clobber data), then truncates to true size. No rename, no fsync | `DataFileWriter.java:351-376` |
| Directory discovery is name-based only; the stock loader treats every listed file as complete | `DataFileCommon.java:177-183`, `DataFileCollection.java:714-754,818-821` |
| `table_metadata.pbj` (leaf path range, initialCapacity) is rewritten **on every flush**, non-atomically (truncate + streamed write); also rewritten by the constructor on open | `MerkleDbDataSource.java:752,337,1107-1131` |
| `<store>_metadata.pbj` (valid key range) is written only on snapshot/close — absent from a live dir | `DataFileCollection.java:330-331,694-712` |
| `<store>_metadata.hdhm` (12 bytes: version, 0, numOfBuckets) is rewritten on every HDHM `endWriting()` | `HalfDiskHashMap.java:274-283` |
| Compaction sources come only from `getAllCompletedFiles()`; output file index is allocated after sources exist (strictly greater); sources are unlinked only after every index entry was CAS-ed away; empty outputs are created then deleted | `MerkleDbCompactionCoordinator.java:295`, `DataFileCompactor.java:371-393,518-542,583-592` |
| Live indices (`.ll`, bucket index) exist only in the writer's memory; on-disk copies are snapshot artifacts. Index handoff is out of scope here (shared indices / `LongListDiskSegment.takeover()`, #25821) | `MerkleDbDataSource.java:1029-1035` |
| Data locations pack file index (24 bits) + offset (40 bits) — a reused file index corrupts the DB | `DataFileCommon.java:125-130` |

## 3. Alternatives considered

Three designs were developed in detail and adversarially reviewed (crash-consistency lens and
engineering-pragmatics lens). Full documents in `wf-results/` (workflow artifacts, not part of
the repo).

### 3.1 WatchService + marker files + FileLock (the original sketch) — rejected

- **WatchService adds risk, no signal.** Data is written via mmap, which produces **no**
  inotify modify events; the only disambiguating event (`IN_CLOSE_WRITE`) is not exposed by
  the JDK (requested since 2010); `ENTRY_CREATE` fires when the *empty* file is created — and
  MerkleDb never renames, so CREATE always means "incomplete". On macOS, WatchService is a
  2-second poller even in JDK 25 (FSEvents PR abandoned; `SensitivityWatchEventModifier` is a
  silent no-op since JDK 21). The JDK Linux implementation has a documented event-
  misattribution bug, and one kernel-side overflow poisons every watch key. Correct OVERFLOW
  recovery *is* a full rescan — so the rescan path must be built and trusted anyway, and the
  monitor has no latency requirement (it serves no reads; only the takeover rescan must be
  complete, and it is synchronous). Battle-tested spool-directory ingesters (Flume, Kafka
  Connect spooldir, Logstash) all poll; RocksDB's secondary instance is also pull-based.
- **Per-file markers have no bootstrap story.** Markers would exist only for files written by
  a new-release writer session. Everything loaded from a restored snapshot (i.e. most of every
  real node's directory) and everything written by an old-release writer is complete but
  unmarked — the marker invariant is false at session start, and the fallback needed to cover
  that gap (header heuristics) makes markers redundant. Marker emission would also sit on the
  flush hot path and inside the compaction/snapshot lock.
- **FileLock on `table_metadata.pbj` is a trap.** POSIX locks are advisory, bind to the inode
  (useless the moment anyone atomizes the rewrite with a rename), and are dropped by *any*
  `close()` of that file anywhere in the JVM. It also requires a writer-side change on the hot
  flush path — unavailable on old-release writers. Validation-based torn-read detection (§6)
  achieves the same guarantee reader-side. FileLock survives in the one role it is good at: a
  LevelDB-style `owner.lock` liveness latch on a dedicated file whose inode never changes (§8).

### 3.2 Append-only manifest/journal, RocksDB MANIFEST-style — right end-state, wrong first PR

The writer appends file-set edit records (`FILE_ADDED`/`FILES_DELETED`/`FLUSH_COMMIT`/
`OWNER_CLOSED`); the monitor tails one file. Strongest steady-state consistency (atomic
multi-file edits, explicit deletions, per-flush barriers) and by far the best operational
debuggability — an ordered on-disk history of everything the writer did. But: the monitor is
blind against a writer that doesn't journal (release sequencing — unusable for the first real
ZDU, or any N→N+2 jump); it puts a shared append lock + I/O on three hot writer threads; and
its correctness depends on every current and future `DataFileCollection` mutation path
notifying the journal — an invariant MerkleDb's code structure doesn't naturally enforce.
**Planned as the PR2+ layer** on top of this design (journal drives when present, rescan
verifies; rescan drives when absent).

### 3.3 Periodic poll-rescan ("directory follower") — SELECTED

Zero mandatory writer-side changes; consumes only the signals in §2; identical code path on
macOS and Linux; stateless across monitor restarts (every tick is the recovery procedure);
every enumerated crash interleaving ends fail-stop, never in silent corruption. Both
adversarial reviews independently ranked it first for PR1. Details below.

## 4. Architecture

All new code lives in `platform-sdk/swirlds-merkledb`. One new thread per monitored data
source (production has one table), built with the house pattern
(`ThreadConfiguration(getStaticThreadManager())…buildFactory()`, cf.
`MerkleDbDataSource.java:281-311`), named `MerkleDb-<table>: file monitor`.

```
swirlds-tmp/<db-dir>/                        MerkleDbFileMonitor (1 thread, poll loop)
├── table_metadata.pbj            ◄────────── TableMetadataFollower   (re-read + validate, rule M1)
├── owner.lock                    ◄────────── OwnerLock               (liveness latch, §8)
├── idToHashChunk/                ◄────────── DataFileCollectionFollower  (rules C1/C2)
├── pathToHashKeyValue/           ◄────────── DataFileCollectionFollower
└── objectKeyToPath/              ◄────────── DataFileCollectionFollower
    └── <t>_objectkeytopath_metadata.hdhm ◄── HdhmMetadataFollower    (rule M2)
```

| Component | Package | Role |
|---|---|---|
| `MerkleDbFileMonitor` (new) | `com.swirlds.merkledb` | Poll thread; drives followers; `start()`, `takeOwnership()`, `stop()`; writes the observation log (§9) |
| `DataFileCollectionFollower` (new) | `com.swirlds.merkledb.files` | Per-store rescan/diff; injects completed readers, detaches deleted ones, tracks PENDING files and max seen index (package-local for package-private collection APIs) |
| `TableMetadataFollower` (new) | `com.swirlds.merkledb` | Rule-M1 re-reads of `table_metadata.pbj`; holds last-good leaf path range |
| `HdhmMetadataFollower` (new) | `com.swirlds.merkledb.files.hashmap` | Rule-M2 re-reads of `_metadata.hdhm`; applies bucket-count growth |
| `OwnerLock` (new) | `com.swirlds.merkledb` | FileLock wrapper on `owner.lock`, single long-lived channel, PID payload |
| Monitoring construction mode (modified) | `MerkleDbDataSource`, `DataFileCollection`, `HalfDiskHashMap` | Side-effect-free open + read gates (§7) |

Loop: `sleep(pollIntervalMs)` → scan 3 store dirs → resolve PENDING files → apply detachments
→ re-read 2 metadata files. All follower state is thread-confined. Collection mutations go
through the existing copy-on-write `AtomicReference<ImmutableIndexedObjectList<DataFileReader>>`
(`DataFileCollection.java:137`), already safe for concurrent publication.

Per-tick cost: one `readdir` per store + one ≤64-byte header pread per PENDING file.
Sub-millisecond at the default 500 ms interval.

## 5. File-set following

### Completeness rules

> **C1**: header `itemsCount > 0` ⇒ file is complete-for-reading-by-location. (The header is
> rewritten only by `DataFileWriter.close()` after all item bytes are visible via the page
> cache; FIXED64 keeps the header size constant.)
>
> **C2**: header `itemsCount == 0` ⇒ PENDING indefinitely; resolve only at takeover, where
> quiescence disambiguates "in progress" from "legitimately empty completed file". Deferral is
> free: a zero-item file can never be referenced by any index entry.

No size/mtime/stability heuristics: in-progress files are instantly ~128 MiB due to mmap
pre-extension, and mmap writes update neither size nor mtime reliably.

### Rescan algorithm (per store, per tick)

State: `Map<fileName, TrackedFile{index, PENDING|COMPLETE, reader?}>` + `maxSeenFileIndex`.

1. `Files.list(storeDir)` filtered with `isFullyWrittenDataFile(storeName, path)` — the exact
   filter the stock loader uses, so follower and future normal opens agree on membership.
2. **New names**: `DataFileMetadata.readFromFile(path)` (public API).
   - Throws (header not yet written, or file deleted between list and read): skip, retry next tick.
   - `itemsCount > 0` ⇒ COMPLETE: open `DataFileReader`, `collection.addNewDataFileReader(path,
     metadata)` (`DataFileCollection.java:638-651`), `reader.setFileCompleted()`. On promotion,
     re-read the header once and keep the second value (torn-count belt-and-braces).
   - `itemsCount == 0` ⇒ PENDING: track; no reader is opened (bounds fd usage).
   - Update `maxSeenFileIndex` from the header (cross-check filename; on mismatch trust header, log).
3. **PENDING files**: re-read header; promote when `itemsCount > 0`.
4. **Missing names** (deleted by the old process's compactor):
   - COMPLETE ⇒ `collection.detachFileReaders(...)` — new package-private API: remove from list
     + close reader, **no** `Files.delete` (the existing `deleteFiles`, `DataFileCollection.java:659-670`,
     unlinks and would throw on an already-deleted path). Immediate detach is safe: the monitor
     serves no reads, and the writer unlinks a source only after CAS-ing every index entry away
     from it (`DataFileCompactor.java:518-542`).
   - PENDING ⇒ forget (create-then-delete blip of an empty compaction output).
   - Never observed at all ⇒ provably irrelevant (§10, index-reseed invariant).
5. Every scan is a full reconciliation — no event stream, no cursor, no OVERFLOW state.
   A monitor restart rebuilds the entire view from disk.

Multiple PENDING files per store per tick are normal (one flush file + one output per
compaction level in flight).

## 6. Metadata following

### `table_metadata.pbj` — rule M1

Fields are emitted in ascending order; min/max leaf path (fields 1,2) are conditional-on-nonzero,
`initialCapacity` (field 3) is always written (`MerkleDbDataSource.java:1107-1131`).

> **M1**: accept iff the parse consumes the whole buffer without error AND field 3 is present
> AND equals the value captured at monitoring open. Otherwise keep last-good and retry next tick.

This rejects every torn-read prefix of the non-atomic truncate+write: empty file, mid-varint
truncation, the dangerous "parses clean but only fields 1-2 present" case, and garbage that
parses. Plus a bounds sanity check against `maxNumOfKeys * 2`. The range is not monotonic
(shrinks on deletion), so no monotonic check.

Fragility, accepted and guarded: M1 is coupled to `saveMetadata`'s field order and
field-3-always-written behavior. A **pinning test placed next to the writer** (§13.3) guards
the invariant; §11's atomic-rename hardening bounds the coupling's lifetime to one release.

### `<store>_metadata.hdhm` — rule M2

> **M2**: accept iff size == 12 AND version == 1 AND `numOfBuckets` is a power of two AND ≥ the
> last accepted value (bucket count only doubles). On change: update HDHM `numOfBuckets` and
> `fileCollection.updateValidKeyRange(0, n-1)` via a new package-private setter.

Known lag, safe: `resizeIfNeeded` runs after `endWriting` wrote the metadata, so the on-disk
count trails an in-memory resize until the next keyToPath flush — but no bucket is written
with the post-resize mask before that flush, so the on-disk `(count, files)` pair is always
self-consistent; the new owner just resizes again on demand. Targeted test required (§13.3).

### `<store>_metadata.pbj` (valid key range)

Not present in a live dir (written on snapshot/close only). Store key ranges are derived at
takeover from `table_metadata.pbj` + HDHM bucket count, the same way `saveRecords` does
(`MerkleDbDataSource.java:1251-1253,1288-1290`).

## 7. Monitoring-mode data source

A new construction path (`MerkleDbDataSource.openForMonitoring(...)`), implemented as a **mode
object consulted at each side-effect site** — not scattered booleans — so every suppression is
greppable. Suppressed side effects of the stock constructor:

| Suppressed | Location |
|---|---|
| `saveMetadata(dbPaths)` rewrite of `table_metadata.pbj` on open | `MerkleDbDataSource.java:337` |
| Legacy hash-store migration (`rebuildHashChunks`) — presence of legacy stores ⇒ abort loudly | `:366-380` |
| Index-rebuild `LoadedDataCallback`s (iterating live files crashes on mmap zero-padding: `"Unknown data file field: 0"`, `DataFileIterator.java:112-127`) — indices stay empty until takeover | `:385-399,428-455`, `HalfDiskHashMap.java:228-243` |
| Stock `tryLoadFromExistingStore` (opens possibly-in-progress files as complete; deletes legacy metadata) — the follower's first rescan populates the collection instead | `DataFileCollection.java:714-754,782-784` |
| HDHM legacy-metadata deletion / metadata creation | `HalfDiskHashMap.java:214-216,258` |
| `keyToPath.repair(...)` | `MerkleDbDataSource.java:468-476` |
| `Files.createDirectories(storeDir)` in store constructors | `MemoryIndexDiskKeyValueStore.java:73` |
| Background compaction | already a ctor flag (`compactionEnabled=false`) |

**Read/write gates**: `owned = false` until takeover; `loadLeafRecord(Bytes)`,
`loadLeafRecord(long)`, `findKey`, `loadHashChunk`, `saveRecords`, `snapshot`, and the three
`run*Compaction` methods throw `IllegalStateException("data source is in monitoring mode")`.
Precedent for the blocked-until-owned contract: `LongListDiskSegment.checkBackingFileOwned`.

**Non-destructive close**: monitor `stop()` closes readers and the data source **without
writing or deleting anything** — stock `close()` writes `<store>_metadata.pbj`
(`DataFileCollection.java:330-331`) and `close(false)` deletes the whole storage dir
(`MerkleDbDataSource.java:977-980`). Both need no-write/keep-data variants.

## 8. `owner.lock` — ownership/liveness latch

The one legitimate FileLock use. Flag-gated, ~20 lines:

- Writer side: in the data source constructor (before `saveMetadata`), acquire an exclusive
  `FileChannel.tryLock()` on a dedicated `storageDir/owner.lock` via a single long-lived
  channel held for the data source's lifetime; write PID + start time into it (diagnostics).
  Released by the OS at process death or explicitly at handoff.
- Monitor side: `tryLock()` succeeding is a **necessary precondition** for takeover ("the old
  process is gone"); failure ⇒ old process still alive ⇒ abort takeover. In-JVM
  `OverlappingFileLockException` (single-process test mode) is handled as "owner alive".
- The inode of `owner.lock` never changes (never renamed/recreated), avoiding the
  lock-across-rename trap.
- Honest limitation: an old-release writer never holds the lock, so for the first real ZDU the
  latch is vacant — takeover then rests on T1/T2 + orchestration confirmation. Fail-safe
  (abort), not corruption.

## 9. Monitor-side observation log

Append-only log in the **monitor's own scratch directory** (zero writer impact): one record
per adopt/promote/detach/reject/metadata-accept/metadata-reject decision, with the evidence
(header values, sizes, tick timestamps). This recovers most of the journal design's
debuggability at zero writer cost — when a soak-test divergence fires, the follower's
decisions are auditable after the fact.

## 10. Takeover / stop

Precondition (out of band; orchestration- or test-controlled): the writer has **quiesced** —
no flush in flight, compaction stopped with `stopAndDisableBackgroundCompaction()` **and**
`awaitForCurrentCompactionsToComplete()` confirmed successful (the stop alone can time out at
60 s leaving stragglers that may still unlink files — `MerkleDbCompactionCoordinator.java:191-221`),
and a final hash-chunk-cache flush has happened (a `snapshot()` does this into the live dir,
`MerkleDbDataSource.java:1019-1026`; without it, low-ID hash chunks do not exist on disk and no
monitor can conjure them).

`takeOwnership()` runs on the monitor thread:

- **T0 — liveness**: acquire `owner.lock` (§8). Fail ⇒ abort.
- **T1 — final rescan**, resolving every file:
  - PENDING, header now non-zero ⇒ promote.
  - PENDING, still zero, size == header size ⇒ complete empty file: inject + complete.
  - PENDING, still zero, size inflated ⇒ writer did not quiesce ⇒ **abort loudly**.
  - COMPLETE files: validate absence of trailing mmap padding (crash between header rewrite
    and truncate leaves `itemsCount>0` + zero padding; reads-by-location would work but any
    whole-file iteration — compaction, index rebuild — would explode later). Padding found ⇒
    abort.
- **T2 — stability check**: one more listing after a grace tick must equal T1's (no
  creations/deletions) — quiescence proof from the monitor's own vantage point.
- **T3 — reseed `nextFileIndex`** per collection = max live index + 1, via new package-private
  `reseedNextFileIndex()` (same rule as the stock loader, `DataFileCollection.java:805`).
  Correctness invariant (verified against the compactor): a non-empty deleted file's surviving
  compaction output always has a strictly higher index, so max-live+1 is safe even for files
  the monitor never observed; only referentially-harmless empty outputs can reuse an index.
  Assert against `maxSeenFileIndex`.
- **T4 — metadata**: re-read both metadata files; both must pass M1/M2 on the first try
  (quiesced writer). Push `validLeafPathRange` and store key ranges; assert the hash-chunk
  store is not missing chunk 0 while leaves exist (detects a skipped drain flush).
- **T5 — indices** (this PR): rebuild by scanning data files with the existing
  `LoadedDataCallback` machinery — safe now because every file is verifiably complete and
  truncated. Replay order MUST be creationDate-then-index (`DataFileReader.compareTo`), the
  same order a fresh open uses — index order would resurrect stale item copies that compaction
  outputs can legitimately contain. O(all data); the production ZDU replaces this with
  `LongListDiskSegment.takeover()` (#25821) for O(1) index handoff — orthogonal component,
  same monitor.
- **T6 — assume ownership**: stop the poll thread; write `table_metadata.pbj` once (we own it
  now); flip `owned = true` (gates open); `enableBackgroundCompaction()`. From here the data
  source is indistinguishable from a normally opened one.

Plain `stop()` (ZDU aborted): stop thread, close readers, non-destructive close (§7).

**Correctness oracle** (used by tests): after T1–T5 the data source must be equivalent to a
fresh normal open of the same quiesced directory with `indexRebuildingEnforced=true`.

## 11. Writer-side hardening (the only writer change, flag-independent)

Convert `MerkleDbDataSource.saveMetadata` (`:1107-1131`) and `HalfDiskHashMap.writeMetadata`
(`:274-283`) to write-temp-file + `Files.move(..., ATOMIC_MOVE)`. **No fsync** — same-host
page-cache visibility needs none, and `force(true)` would put an F_FULLFSYNC on the flush hot
path for nothing.

This is independently a pre-existing crash-bug fix: today, a crash mid-rewrite leaves a
truncated `table_metadata.pbj`, and any later open of that directory hard-fails
(`MerkleDbDataSource.java:318-327`). The monitor does NOT rely on this hardening (M1 handles
old-release writers); it bounds M1's schema-coupling lifetime to one release.

## 12. Feature flag and single-process test mode

```java
// MerkleDbConfig (record, pattern: indexRebuildingEnforced at config/MerkleDbConfig.java:115)
@ConfigProperty(defaultValue = "false") boolean fileMonitoringEnabled,
@ConfigProperty(defaultValue = "500")   int     fileMonitorPollIntervalMs,
@ConfigProperty(defaultValue = "false") boolean ownerLockEnabled,
```

With `merkleDb.fileMonitoringEnabled=true`, `MerkleDbDataSourceBuilder` additionally
constructs a **shadow monitoring data source** over the same storage dir when building the
live one, and starts its `MerkleDbFileMonitor`. Same JVM, two data source instances: the
writer behaves exactly as today; the shadow exercises the full monitoring mechanism against
real flush/compaction/snapshot traffic. The shadow exposes test hooks (per-store
`{fileIndex → (size, itemsCount, level, completed)}`, last-good metadata, `takeOwnership()`
for drills) and closes non-destructively before the writer.

Flag off ⇒ zero behavioral change except §11 (safe standalone). Same-JVM statics are harmless:
the shadow never uses the shared compaction executor or HDHM flush pool.

Verification with `./gradlew :app:run`: observe the live dir under
`hedera-node/hedera-app/build/node/data/saved/swirlds-tmp/`, shadow-monitor log lines, and the
observation log.

## 13. Testing strategy

1. **Follower unit tests** (real writer + follower over one temp dir, one JVM): flush loops,
   concurrent compaction, create-then-delete empty outputs; randomized interleavings of
   flush/compact/snapshot-pause/stop; after quiesce, follower state ≡ fresh-scan oracle.
2. **Completeness-rule tests**: hand-built files — header-only, header+padding (crash
   pre-truncate), finalized empty, finalized non-empty — assert C1/C2 classification and the
   T1 padding validator.
3. **Torn-metadata tests**: byte-truncate a valid `table_metadata.pbj` at every prefix length;
   M1 must accept only the full image. This test lives next to `saveMetadata` and pins its
   field-order/field-3 invariant (the guard against silent schema drift). Same for
   `_metadata.hdhm` / M2, including the resize-lag scenario.
4. **Takeover equivalence**: realistic workload → quiesce → `takeOwnership()`; compare every
   leaf/hash chunk against a control data source opened normally over a hardlink copy with
   `indexRebuildingEnforced=true`; then `saveRecords` on the new owner and assert no file-index
   collisions.
5. **Crash-point matrix** (fault injection): kill mid-data-file / post-header-pre-truncate /
   mid-table-metadata / mid-hdhm ⇒ monitor state per §14; takeover aborts in all four.
6. **Flag-on integration**: node run with shadow monitor active for the whole test; periodic
   invariant: shadow completed-file set ⊆ writer `getAllCompletedFiles()`, equal at quiesce
   points modulo PENDING empties; fd/memory leak checks.
7. **Platform**: identical code path on Linux and macOS CI — no platform-conditional behavior
   anywhere.

## 14. Failure modes (summary)

| Failure | Monitor behavior | Outcome |
|---|---|---|
| Writer crashes mid-data-file | File stays PENDING | Takeover T1 aborts; ZDU falls back to snapshot restart |
| Writer crashes between header rewrite and truncate | Promoted COMPLETE (padded) | Caught by T1 padding validation ⇒ abort |
| Writer crashes mid-metadata rewrite | M1/M2 reject; last-good retained | Takeover aborts (also breaks plain restart today → §11) |
| Empty compaction output created+deleted between ticks | Never seen | Provably irrelevant (T3 invariant) |
| File deleted between list and header read | Exception → skip | Reconciled next tick |
| Writer snapshot mid-monitoring | One extra hash-store data file; compaction pause/resume | Ordinary events, no special handling |
| Monitor crash/restart | — | Stateless; first rescan rebuilds everything |
| Old process shuts down without handoff (`close(false)` deletes dir) | All files vanish | Monitor parks in terminal FAILED state; writes nothing |
| Compaction straggler after takeover | — | Excluded by precondition (`awaitForCurrentCompactionsToComplete`) + T0 owner.lock + T2 |

Every path is fail-stop; none is silent corruption.

## 15. Out of scope / prerequisites tracked for the real (two-process) ZDU

1. `FileSystemManager` **wipes `swirlds-tmp` on construction** (`FileSystemManager.java:74-77`)
   — currently makes the documented `defaultDbFolderName` upgrade path unreachable for a
   second process.
2. Old process must exit without `close(false)` (deletes the storage dir).
3. After restore-from-snapshot the live dir is timestamped, not `merkledb-state`
   (`VirtualMapStateLifecycleManager` builds without `defaultDbFolderName`) — dir discovery
   needs plumbing.
4. Index handoff: wire `LongListDiskSegment.takeover()` (#25821); until then T5 is O(all data).
5. PR2+: the append-only file-set journal (§3.2) layered on this monitor as accelerator and
   consistency layer — journal drives when present, rescan verifies; rescan drives when absent.
   Known fixes to incorporate: explicit generation-selection rule, heartbeat record,
   per-store watermarks in `FLUSH_COMMIT`, appends decoupled from flush threads.

## 16. Open questions

1. Shadow-instance test mode doubles off-heap index allocation in one JVM (empty in monitoring
   mode, but `LongList` reservations may not be zero-cost) — measure; may need a smaller
   `maxNumOfKeys` for the shadow or lazy index creation.
2. `openForMonitoring` as ctor-mode-object vs. a separate lean `MonitoringDataSource` class
   that builds a real data source only at takeover — this spec picks the mode object for the
   in-place T6 flip; revisit if constructor surgery reviews badly.
3. Poll interval default (500 ms) and observability (metrics for adopted/pending/detached
   counts, last metadata accept time?) — decide with the first benchmark run.
4. Sequencing with #25821 (`LongListDiskSegment`) — does T5 rebuild-from-files need to ship at
   all if index takeover lands first?
