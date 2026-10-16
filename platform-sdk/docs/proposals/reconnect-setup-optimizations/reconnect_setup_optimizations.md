# Faster reconnect setup at 3B+ leaves: proposals for review

## 1. Problem

A node that falls behind under 10K TPS load reconnects successfully, but never converges. Every reconnect
completes, the node spends 3–4 s in `RECONNECT_COMPLETE`, then falls `BEHIND` again and starts the next
reconnect.

Measured on the 1B-account network (state of about 3.25B leaves, leaf paths about 3.25B..6.49B):

- **Network speed:** about 9.9 rounds per second.
- **Event retention:** peers keep events for about 1,500 rounds (the expired threshold is 1,501 rounds behind
  the latest round). That's about 150 s.
- **What converging requires:** the whole reconnect cycle must fit into those roughly 150 s, so that when it
  finishes the node can catch up through gossip.
- **What a cycle takes now:** 4–5 minutes. When a reconnect finishes, the node is 2,300–2,900 rounds behind.

One cycle measured on both sides:

| Phase | Where | Duration |
|---|---|---|
| Teacher: MerkleDB snapshot of its state | teacher, pipeline paused | 29.1 s |
| Teacher: data source rebuilt from that snapshot | teacher, pipeline still paused | 51.2 s |
| Teacher: rest of setup, then sends signatures | teacher | about 4 s |
| Learner: MerkleDB snapshot of its own state | learner, pipeline paused | 32.0 s |
| Learner: data source rebuilt from that snapshot | learner, pipeline still paused | 34.7 s |
| Tree transfer | both | 90–160 s (varies per cycle) |

Across seven reconnect cycles the teacher's part (until the learner receives the signatures) took 41–94 s. The learner's
part (from the signatures to `learner start synchronizing`) took 52–93 s. These parts run **one after the other**,
because the learner only starts copying after it has received the signatures: in all seven cycles its snapshot
started 4–12 ms after the signatures arrived. Together they take about 140–180 s, which is more than the whole
150 s budget, before any data is transferred.

Measured times for the copies' snapshots are 21–32 s; seven learner cycles gave 20.9, 31.3, 31.2, 28.8, 31.3, 31.0
and 32.0 s. Their rebuilds took 20–53 s on the learner and 51 s on the teacher. The slower 46–50 s snapshots and
the 60 s rebuild in the logs belong to the state write after a reconnect, which uses disk-based indexes (see
section 2). They aren't caused by two snapshots competing for the disk: the learner snapshot that overlapped the
49.9 s one took 32.0 s, about 3 s over its usual time.

## 2. How a data source copy works today

All three operations below copy a MerkleDB data source the same way: **snapshot to disk, then rebuild from that
snapshot**.

- **Teacher:**
    - `TeachingSynchronizer` calls `teacherMap.detach()` in its constructor
      ([`TeachingSynchronizer.java:56`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/sync/TeachingSynchronizer.java#L56)).
    - `ReconnectStateTeacher` creates the synchronizer before it calls `sendSignatures()`
      ([`ReconnectStateTeacher.java:100`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacher.java#L100),
      [`ReconnectStateTeacher.java:161`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacher.java#L161)).
    - The copy uses in-memory indexes (`build(..., compactionEnabled = false, offlineUse = false)`).
- **Learner:**
    - `VirtualMapLearner`'s constructor calls `originalMap.detachAsDataSourceCopy()`
      ([`VirtualMapLearner.java:140`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMapLearner.java#L140)).
    - `LearningSynchronizer.buildLearnerExchanger()` creates that learner
      ([`LearningSynchronizer.java:77`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/sync/LearningSynchronizer.java#L77)),
      and only after `ReconnectStateLearner.receiveSignatures()` returns
      ([`ReconnectStateLearner.java:165`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java#L165),
      [`ReconnectStateLearner.java:202`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java#L202)).
    - The copy uses in-memory indexes (`build(..., compactionEnabled = true, offlineUse = false)`).
- **Writing a reconnected state to disk:**
    - `VirtualMap.createSnapshot()`
      ([`VirtualMap.java:1376`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1376))
      runs four steps:
        1. It snapshots the data source, inside the pipeline pause.
        2. It rebuilds a copy from that snapshot.
        3. It flushes the cache snapshot into the copy.
        4. It snapshots the copy into the target directory.
      - The rebuild uses **disk-based indexes**: it calls `build(LABEL, snapshotPath, false, offlineUse = true)`
        ([`VirtualMap.java:1394`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1394)).
        `offlineUse` becomes the constructor's `diskBasedIndices`
        ([`MerkleDbDataSource.java:305`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L305)),
        so the copy's indexes are `LongListDisk`. 
  
      As a result:
      - The rebuild copies the three index files, 37 GB in total, into `LongListDisk` temp files
             ([`LongListDisk.java:230`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDisk.java#L230)).
             That's 37 GB read and 37 GB written: 60 s in the measured cycle. 42.6 s of it is the stage holding the 26 GB
             leaf index ("Where the time goes" below).
      - The final snapshot reads the 37 GB back from the temp files and writes it to the target: 46–50 s.
      - The measured total is about 2 min 20 s. It's off the critical path, but it overlaps the
        next cycle in two ways:
          - Its first snapshot holds the learner's **pipeline pause** for about 28 s. Pauses on
            one pipeline run one after the other: `pausePipelineAndExecute`
            ([`VirtualPipeline.java:331`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/internal/pipeline/VirtualPipeline.java#L331))
            parks a task on the pipeline's single-threaded executor. So nothing else on that pipeline can pause, flush
            or merge until this snapshot is done.
          - Its rebuild and final snapshot compete for the disk with the next cycle's copies.
    - Only states saved for reasons other than a periodic snapshot (reconnect, freeze and so on) take this path.
      Periodic states with `state.saveStateAsync` use `createSnapshotAsync()`, which takes one data source snapshot
      on the lifecycle thread right after the copy is flushed
      ([`SignedStateFileWriter.java:187`](../../../../platform-sdk/consensus-state/src/main/java/org/hiero/consensus/state/SignedStateFileWriter.java#L187),
      [`VirtualMap.java:971`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L971)).

**What each side does:**

**Snapshot.**
- `MerkleDbDataSourceBuilder.snapshotDataSource()` runs `MerkleDbDataSource.snapshot()` with compaction paused
  (`pauseCompactionAndRun`,
  [`MerkleDbDataSourceBuilder.java:162`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSourceBuilder.java#L162)).
- `MerkleDbDataSource.snapshot()`
  ([`MerkleDbDataSource.java:943`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L943))
  first flushes the hash chunk cache. Then it runs six tasks **in parallel** on its snapshot executor
  ([`MerkleDbDataSource.java:960`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L960)):
    - write `idToDiskLocationHashChunks` to a file with `LongList.writeToFile()`
      ([`MerkleDbDataSource.java:963`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L963));
    - write `pathToDiskLocationLeafNodes` to a file the same way
      ([`MerkleDbDataSource.java:967`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L967));
    - snapshot each of the three stores: write the HalfDiskHashMap bucket index, and hard-link every completed data
      file (`DataFileCollection.snapshot()`,
      [`DataFileCollection.java:588`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java#L588));
    - save metadata.
- So the snapshot's wall time is that of its slowest task.

**Rebuild.** `MerkleDbDataSourceBuilder.restoreDataSource()`
([`MerkleDbDataSourceBuilder.java:210`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSourceBuilder.java#L210))
runs these steps **one after another**:
- hard-link the whole snapshot tree again into a new temp directory (`FileUtils.hardLinkTree`,
  [`MerkleDbDataSourceBuilder.java:228`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSourceBuilder.java#L228));
- construct a `MerkleDbDataSource` there, which loads both indexes back from files
  ([`MerkleDbDataSource.java:376`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L376),
  [`MerkleDbDataSource.java:421`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L421);
  `LongListSegment.readChunkData()`,
  [`LongListSegment.java:275`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java#L275));
- load the HalfDiskHashMap bucket index;
- open a `DataFileReader` for every data file, reading each file's header first.

**Sizes at this scale**:

| Part | Entries | Size |
|---|---|---|
| `pathToDiskLocationLeafNodes` | 3,260,006,174 (paths 3,260,006,173..6,520,012,346) | 26.08 GB (24.29 GiB) |
| `idToDiskLocationHashChunks` | 1,090,785,345 (chunk IDs 0..1,090,785,344) | 8.73 GB (8.13 GiB) |
| HalfDiskHashMap bucket index | 268,435,456 = 2^28 buckets (12.1 keys per bucket) | 2.15 GB (2.00 GiB) |
| **Indexes per data source** | | **36.96 GB (34.4 GiB)** |

- **The leaf index** is written to disk and read back in full. `writeLongsData` starts at the first chunk with
  data
  ([`LongListSegment.java:298`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java#L298)).
- **The hash chunk index is larger than the leaf count suggests.**
    - The chunk grid (height 6) is anchored at the root, so the rank-30 chunks hold ranks 31–36.
    - The tree's bottom ranks (31–33) therefore span all 2^30 rank-30 chunk IDs.
    - `VirtualHashChunk.lastChunkIdForPaths()`
      ([`VirtualHashChunk.java:394`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/datasource/VirtualHashChunk.java#L394))
      for the last leaf path gives exactly 1,090,785,344.
- **Off-heap memory:** these indexes are what MerkleDB keeps off-heap. Off-heap memory consumption on the measured node was 69.4 GiB while
  it held two data sources: 2 × 34.4 GiB plus about 0.5 GiB.
- **Data files** in the measured cycle, from the rebuild's log lines:
    - 1,823 in `idToHashChunk`, 35,810 in `pathToHashKeyValue` and 5,802 in `objectKeyToPath`: 43,435 in total.
    - A saved state of the same network, written later, had 39,296 files and 894 GiB in total.
    - Most are level-0 files left by reconnect flushes, which are never compacted; that's a separate issue. For
      example, 31,855 of the 32,301 `pathToHashKeyValue` files in that saved state were at L0, 313 of its 393 GiB.
    - Each file is hard-linked twice, and opened once with its header read.

**Where the time goes:**
- **Snapshot:** the index writes, 37 GB in total, in parallel tasks. The largest is the 26 GB leaf index. 28–32 s
  is about 1.2 GB/s in aggregate.
    - The disk is an ext4 RAID volume (`/dev/md0`, 7 TB) over NVMe drives, so this is most likely well below the
      disk's sequential bandwidth.
    - Suspected cause, not measured: buffered writes into the page cache while the cgroup is at its memory limit.
      `memory.peak` reached `memory.max` (256 GiB), and the page cache fills the remainder. Each 26 GB of new page
      cache first has to be reclaimed.
- **Rebuild:** measured per stage on the learner, from the constructor's INFO lines. In each stage, the reader
  opens happen before the stage's "Loading existing set" line, so the time between lines covers the whole stage.

  | Stage | What it does | Clean cycle | Under <br> 10K TPS |
  |---|---|---|---|
  | 1 | hard links (43.4K files), 8.7 GB hash chunk index, 1.8K reader opens | 7.8 s | 9.5 s |
  | 2 | 26.1 GB leaf index, 35.8K reader opens | 23.5 s | 27.1 s |
  | 3 | 2.1 GB bucket index, 5.8K reader opens | 3.3 s | 2.6 s |
  | **Total** || **34.7 s** | **39.2 s** |


  Stage 2 is two thirds of the rebuild. The logs can't split it between the index load and the reader opens:
    - The clean cycle fits about 4 GB/s for index loads and 0.48 ms per reader open. That gives about 6 s of index
      load and 17 s of opens in stage 2.
    - The other cycle fits 1.2 GB/s and 0.14 ms. That gives about 22 s of index load and 5 s of opens.
    - Either way, the reader opens aren't negligible: 5–17 s per rebuild. Opening a reader reads the file's header
      (`DataFileMetadata.readFromFile()`,
      [`DataFileMetadata.java:86`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileMetadata.java#L86)),
      which is cold I/O for most of the 35.8K files.
    - The disk test in section 7 measures both rates directly.
- **The state write's rebuild into disk-based indexes**, measured the same way: stage 1 took 13.8 s, stage 2
  42.6 s and stage 3 3.9 s.

## 3. Step 1: copy the data source without the disk round trip

### Idea

Add a direct copy that never writes the indexes to a file and reads them back. It replaces snapshot + rebuild in
all three users: `detach()`, `detachAsDataSourceCopy()` and `createSnapshot()`.

With the pipeline paused (no flushes) and compaction paused (as for a snapshot today):

1. **Flush the hash chunk cache,** as `snapshot()` does today (`flushHashChunkCache()`,
   [`MerkleDbDataSource.java:1392`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L1392)).
2. **Hard-link the data files once** into the copy's own directory: every completed file of
   `hashChunkStore`, `keyValueStore` and `keyToPath`. That's one link pass instead of two (snapshot, then
   `hardLinkTree`).
3. **Copy the indexes:** `idToDiskLocationHashChunks`, `pathToDiskLocationLeafNodes` and the HalfDiskHashMap bucket
   index.
    - `LongListSegment` holds its data in chunks of `merkleDb.longListChunkSize` longs (8 MB by default). Each chunk
      is a `MemorySegment` with its own shared `Arena` (`LongListSegment.createChunk()`,
      [`LongListSegment.java:140`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java#L140)).
    - An in-memory copy allocates a new chunk for each non-null source chunk and runs `MemorySegment.copy()`. Then
      it copies `size`, `minValidIndex` and `maxValidIndex` exactly. That's more faithful than today's round trip,
      which sets `maxValidIndex` to `size - 1` on load
      ([`AbstractLongList.java:227`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java#L227)).
    - Chunks are independent, so they can be copied in parallel. The 37 GB of indexes take a few seconds at memory
      bandwidth, including zeroing the new chunks. Today the snapshot takes 28–32 s to write them, and the rebuild
      takes about 34 s to read them back and open the readers.
4. **Build the new `MerkleDbDataSource` from these pieces:** a new constructor, or a factory that takes the copied
   index lists and the linked directory instead of loading files.
    - Reuse the source readers' `DataFileMetadata` instead of reading 43K file headers again. This matters more
      than the first draft assumed: the reader opens take an estimated 5–17 s of each rebuild (section 2).
      Opening a reader with known metadata still opens one file channel, but it reads nothing.
    - Write the new directory's metadata files, as the constructor does today, so the copy's directory is a
      consistent set of files on disk.

Possible API:
- `VirtualDataSourceBuilder.copy(VirtualDataSource source, String label, boolean compactionEnabled, boolean
  offlineUse)`
  ([`VirtualDataSourceBuilder.java:17`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/datasource/VirtualDataSourceBuilder.java#L17)).
- Its default implementation would be today's `snapshot()` + `build()` + delete the snapshot directory, so other
  implementations (for example the in-memory test data source) keep working.
- `MerkleDbDataSourceBuilder` overrides it. If the source uses disk-based indexes (`merkleDb.useDiskIndices = true`),
  the override also falls back to the default.

### What the copy must carry over

- **Data source:** `validLeafPathRange`, `initialCapacity`, `hashChunkHeight` and the loaded hash digest type.
  Carry the digest type as is: if it's different from the default, the constructor would delete the hash files.
  Don't copy the leaf record cache; the copy starts with an empty one. The copy's compaction coordinator and
  garbage scanners start fresh, as after a rebuild today.
- **Each `DataFileCollection`:**
    - `validKeyRange`. That's all `saveMetadata()` persists
      ([`DataFileCollection.java:700`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java#L700)).
    - The list of completed files.
    - `nextFileIndex` = the highest file index + 1, which is what the rebuild does today
      ([`DataFileCollection.java:803`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java#L803)).
      Data locations in the indexes encode the file index, not the path. So linked files keep their names, and the
      copied indexes stay valid.
    - Each file's compaction level is in its own `DataFileMetadata`. Every linked file is completed: the rebuild marks
      all loaded files completed today
      ([`DataFileCollection.java:818`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java#L818)).
- **Reusing `DataFileMetadata`** is safe:
    - It's immutable: all its fields are final.
    - `DataFileReader` already has a package-private constructor that takes it
      ([`DataFileReader.java:131`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileReader.java#L131)),
      used today for files created by flushes and compaction.
    - The copy needs its own readers, though. A reader owns file channels, which the source closes when it compacts
      or closes.
- **HalfDiskHashMap:**
    - Its state is the bucket index, `numOfBuckets` (the bucket mask is derived from it) and its file collection,
      whose valid key range is [0, numOfBuckets − 1].
    - That's exactly what `HalfDiskHashMap.snapshot()` writes
      ([`HalfDiskHashMap.java:375`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/hashmap/HalfDiskHashMap.java#L375)):
      the index, the files, and metadata holding `numOfBuckets`.
    - It only resizes during a flush
      ([`HalfDiskHashMap.java:783`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/hashmap/HalfDiskHashMap.java#L783),
      called from `saveRecords` at
      [`MerkleDbDataSource.java:1384`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L1384)),
      which the pause excludes.

### Why the copy is as consistent as `snapshot()`

The copy reads the same in-memory state that `snapshot()` writes to disk, under the same conditions:

- **No flush runs:** the virtual pipeline is paused.
- **Compaction can't change files or indexes:**
    - A compactor copies each item, updates the index and deletes old files only while it holds its
      `snapshotCompactionLock` (`DataFileCompactor.compactSingleItem()`,
      [`DataFileCompactor.java:518`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCompactor.java#L518);
      [`DataFileCompactor.java:374`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCompactor.java#L374)).
    - `pauseCompaction()`
      ([`DataFileCompactor.java:619`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCompactor.java#L619))
      takes that lock and finishes the output file in progress, marking it completed.
    - A compaction task that starts during the pause can't register its compactor: registering needs the
      coordinator's monitor
      ([`MerkleDbCompactionCoordinator.java:663`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbCompactionCoordinator.java#L663)),
      which `pauseCompactionAndRun()` holds for the whole action
      ([`MerkleDbCompactionCoordinator.java:164`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbCompactionCoordinator.java#L164)).
    - Garbage scanners only read.
- **No partly written files are included:**
    - The only files being written are flush files (excluded by the pause) and compaction output (finished by
      `pauseCompaction()`).
    - `getAllCompletedFiles()`
      ([`DataFileCollection.java:301`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java#L301))
      filters out anything else.
    - The copy links both the partly compacted source files and the new output file, so every index entry points to
      a file that exists.
- **No pending HalfDiskHashMap state:** its only state outside the bucket index and files is
  `oneTransactionsData`, which exists only inside a flush session and is committed in `endWriting()`
  ([`HalfDiskHashMap.java:500`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/hashmap/HalfDiskHashMap.java#L500)).

Two requirements follow:
- `copy()` must run inside the pipeline pause, like the snapshots it replaces.
- It must set `snapshotInProgress`
  ([`MerkleDbDataSource.java:945`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/internal/MerkleDbDataSource.java#L945)),
  so it can't run at the same time as a snapshot of the same data source.

### `createSnapshot()` needs a different copy

`createSnapshot()` builds its copy with disk-based indexes on purpose:
- The `offlineUse` flag was added in [#16090](https://github.com/hiero-ledger/hiero-consensus-node/pull/16090) for
  exactly this copy.
- Its javadoc
  ([`VirtualDataSourceBuilder.java:28`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/datasource/VirtualDataSourceBuilder.java#L28))
  says that such copies "should use as little resources as possible", because they aren't used for
  performance-critical tasks.
- [#21123](https://github.com/hiero-ledger/hiero-consensus-node/pull/21123) later moved most of this work out of the
  pipeline pause, and kept the flag.

An in-memory copy there would add 34.4 GiB of off-heap memory on the learner: 37 GB of indexes, measured. That would
happen exactly when the learner already holds the reconnect data source and, from the next cycle, its copy. So
there are two options for `createSnapshot()`:

- **Option A, memory-neutral:** inside the pause, write the source's index chunks straight into the copy's
  `LongListDisk` temp files.
    - That's one 37 GB write instead of three passes: write the snapshot files, then read them and write them into
      the temp files.
    - The pause stays about as long as today's first snapshot, about 30 s. As shown in section 2, that delays the
      learner's copy in the next cycle.
- **Option B, short pause:** inside the pause, make the in-memory copy (a few seconds). Outside the pause, write it
  into the `LongListDisk` temp files and free it.
    - This holds 34.4 GiB of extra off-heap memory for about 30 s.
    - This goes against the `offlineUse` intent for about 30 s, in exchange for a pause of a few seconds instead of
      about 30 s.
    - Memory on the learner today (its cgroup, measured while it held two data sources):
        - the ZGC heap counts as `shmem`: 112.4 GiB;
        - `anon` was 73.7 GiB, including NMT "Other" 69.4 GiB;
        - the page cache was about 63 GiB and filled the rest of the 256 GiB limit. `memory.peak` reached
          `memory.max`.
    - Option B adds a third set of indexes, which raises the memory that can't be reclaimed from about 186 GiB to
      about 220 GiB. The page cache would shrink to about 36 GiB during that window, which would likely slow the
      disk-heavy steps running at the same time. That's feasible, but it should be measured before choosing this
      option.

### Expected effect (estimate)

| Copy | Today | With step 1 |
|---|---|---|
| Teacher (`detach`) | 80–84 s | roughly 10–20 s: in-memory index copy, 43K links (about 5 s today, see stage 1 in section 2), and 43K reader opens without header reads |
| Learner (`detachAsDataSourceCopy`) | 52–93 s | roughly 10–20 s |
| State write after reconnect (`createSnapshot`) | about 140 s, 28 s of it in the pipeline pause | option A: about 80 s, about 30 s paused; option B: about 80 s, a few seconds paused |

**Peak off-heap memory doesn't change for `detach()` and `detachAsDataSourceCopy()`.** Today their rebuilds
allocate a second set of in-memory indexes; the in-memory copy allocates the same amount, just without the disk
round trip. For `createSnapshot()`, option A keeps memory unchanged and option B adds 34.4 GiB for about 30 s.

## 4. Step 2: keep the pipeline paused only for the snapshot

### Today

- `detach()`
  ([`VirtualMap.java:1207`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1207))
  runs the snapshot, the rebuild and the cache snapshot all inside `pipeline.pausePipelineAndExecute(...)`.
- `detachAsDataSourceCopy()`
  ([`VirtualMap.java:1182`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1182))
  runs the snapshot, the rebuild and the flush of the cache snapshot inside it too.
- Deleting the snapshot directory also happens inside the pause. That includes freeing the last link to the
  26 GB index file.
- So the lifecycle thread can't merge or flush newer copies for the entire 50–85 s.
- `createSnapshot()`
  ([`VirtualMap.java:1376`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1376))
  already does it the better way, since
  [#21123](https://github.com/hiero-ledger/hiero-consensus-node/pull/21123):
    - It takes the cache snapshot and the data source snapshot inside the pause, and runs the rebuild, flush and
      target snapshot outside.
    - Its comment explains why: a long pause makes unhandled copies pile up in the pipeline, which triggers size
      backpressure. #21123 fixed exactly that problem for large states, where the pause made the pipeline and the
      family size backpressure grow.

### Change

Restructure `detach()` and `detachAsDataSourceCopy()` the same way:

```java
final ValueReference<VirtualNodeCache> cacheSnapshot = new ValueReference<>();
final Path snapshotPath = pipeline.pausePipelineAndExecute("detach", () -> {
    cacheSnapshot.setValue(cache.snapshot());
    return dataSourceBuilder.snapshot(null, dataSource);
});
try {
    final VirtualDataSource copy = dataSourceBuilder.build(getLabel(), snapshotPath, /* as today */ ...);
    // detach(): return new RecordAccessor(metadata.copy(), hashChunkHeight, cacheSnapshot.getValue(), copy);
    // detachAsDataSourceCopy(): flush(cacheSnapshot.getValue(), copy); return copy;
} finally {
    FileUtils.deleteDirectory(snapshotPath);
}
```

**Why this is safe:**
- Once taken, the snapshot directory is self-contained: index files plus hard links, so later compaction of the
  source can't change it.
- The cache snapshot is an independent copy.
- `createSnapshot()` relies on exactly these two properties today.
- Nothing downstream depends on the source after the pause:
    - `RecordAccessor` holds only the metadata copy, the hash chunk height, the cache snapshot and the data source
      copy. It has no reference to the source data source.
    - On an immutable copy, `metadata` and the hash chunk height can't change.
    - On the learner, the pipeline may flush older copies into the original data source after the pause. That
      doesn't affect the copy. The learner already reads hashes from the original map during reconnect
      (`originalRecords`,
      [`VirtualMapLearner.java:133`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMapLearner.java#L133)),
      and step 2 doesn't change that.
- `snapshot()` still runs inside the pause, so snapshots of one data source still can't overlap.

**Effect:**
- The teacher's pause shrinks from about 80 s to the snapshot only (about 29 s today); with step 1, to the
  in-memory copy only (a few seconds).
- This doesn't shorten the reconnect itself, but it protects the teacher's handling under load. That makes
  it a prerequisite for using busy nodes as teachers. It's independent of step 1 and small.

## 5. Step 3: start the learner's copy while waiting for the teacher

### Today

`ReconnectStateLearner.execute()`
([`ReconnectStateLearner.java:161`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java#L161))
does three things in order:
1. `receiveSignatures()`, which blocks until the teacher has finished its own copy (41–94 s);
2. `reconnect()` → `synchronizer.synchronize(currentState.getRoot(), …)`;
3. inside it, `new VirtualMapLearner(originalMap)`, which copies the learner's data source (52–93 s).

The learner's copy depends only on its own state, not on anything the teacher sends:
- `currentState` is `stateLifecycleManager.getMutableState()`, captured before `execute()` starts
  ([`ReconnectStatePeerProtocol.java:291`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStatePeerProtocol.java#L291)).
- Gossip is already stopped and the queues are cleared by then, so that state doesn't change while the learner
  waits.

### Change

Start building the `VirtualMapLearner` (or just its data source copy) asynchronously at the beginning of
`execute()`, before `receiveSignatures()`, and hand the result to `LearningSynchronizer.synchronize()` when the
transfer starts.

Points to get right:
- **No leaks on failure.** If receiving the signatures fails, the transfer fails, or the reconnect is interrupted,
  the prepared learner must be closed (`abortOnException()`). Otherwise a full data source copy, 34.4 GiB
  off-heap here, leaks.
- **No wasted copies.**
    - The teacher rejects a request in `ReconnectStatePeerProtocol.shouldAccept()`
      ([`ReconnectStatePeerProtocol.java:176`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStatePeerProtocol.java#L176))
      before either side runs the protocol.
    - Reasons include no fully signed state, not being `ACTIVE`, having fallen behind, and the throttle.
    - `ReconnectStateLearner` doesn't exist yet at that point, so starting the copy in `execute()` doesn't waste
      copies on rejected requests.
- **The previous state write blocks the copy.** The copy needs the learner's pipeline pause, and the previous
  cycle's `createSnapshot()` holds that pause for its first snapshot (section 2). In the measured cycle, the
  copy would start when the cycle starts and wait about 24 s for that snapshot to finish. It would then compete
  for the disk with that state write's rebuild and final snapshot.

### Effect

- **Step 3 alone:** in the measured cycle, the copy would run from about 24 s into the cycle and finish 93–113 s
  into it, depending on disk contention. The signatures arrived 85 s into the cycle, and the transfer started
  153 s into it. So step 3 saves about 40–60 s in that cycle, not the full 69 s the learner's copy took.
- **With steps 1 and 2:** the learner's copy takes 10–20 s. With option B in `createSnapshot()`, the state
  write's pause takes a few seconds, and the learner's copy is fully hidden behind the teacher's. With option A,
  the learner's copy finishes about 40–50 s into the cycle, behind the teacher's (about 15–25 s).

## 6. Combined estimate and what's left

| | Today | Steps 1 + 2 + 3 (estimate) |
|---|---|---|
| Waiting for the teacher | 41–94 s | about 15–25 s |
| Learner copy (afterwards) | 52–93 s | hidden with option B; adds about 20–30 s with option A |
| Transfer | 90–160 s | unchanged |
| **Cycle** | **4–5 min** | **about 1.8–3.1 min (option B) or 2.2–3.5 min (option A)** |

That is close to the roughly 150 s budget, but not safely under it. The transfer is the next target. In short
cycles the learner's in-flight leaf count is mostly 0, so the transfer is limited by round trips while walking the
internal nodes top-down, not by bandwidth, hashing or disk. Until that improves, a larger `consensus.roundsExpired`
on all nodes would give the margin needed to converge, at the cost of keeping more events.

## 7. Before implementing: measurements

These need no review input, only data from the test host. [`measure_merkledb.sh`](measure_merkledb.sh) collects all
of them.

**Done** (on the learner node):
- index sizes, entry counts and the HalfDiskHashMap bucket count (section 2);
- data file counts per store and compaction level (section 2);
- the per-stage rebuild split on the learner (section 2);
- a cgroup and NMT memory snapshot (section 3, option B).

**Still open:**
1. **Index load rate vs. reader open rate,** to split the rebuild's stage 2 (5–17 s of opens). Run the disk test on
   an idle node: it measures read throughput, the header read rate for 32K files, and hard link and unlink rates.
   ```bash
   IO_TEST=1 IO_GB=4 ./measure_merkledb.sh
   ```
2. **The teacher's rebuild split.** The teacher's rebuild took 51.2 s, against 34.7 s on the learner. Run the
   script on a teacher node, with `SINCE` and `UNTIL` covering a cycle in which that node was the teacher.
3. **Whether page cache pressure slows the snapshot writes** (section 2, suspected). Compare the disk test's direct
   and buffered write rates.
4. **Off-heap headroom for option B,** if it's chosen: the NMT and cgroup peak during a state write that overlaps
   the next reconnect.
