# Signed State Snapshot On-Disk Representation

This document describes the exact on-disk layout and binary formats produced by
`SignedStateFileWriter.writeSignedStateToDisk` — the entry point used by the platform
whenever a signed state is persisted for a round (periodic snapshot, freeze state,
state dump). It consolidates every wire-level definition the reader needs,
whether declared in a `.proto` file or in Java via PBJ `FieldDefinition` constants.

> **Source anchors.** `SignedStateFileWriter`, `SignedStateFilePath`,
> `SignedStateFileUtils`, `SavedStateMetadata[Field]` (platform-core);
> `VirtualMap`, `VirtualMapMetadata`, `VirtualLeafBytes`, `VirtualHashChunk`
> (virtualmap); `MerkleDbDataSource`, `MerkleDbPaths`, `MerkleDbDataSourceBuilder`,
> `DataFileCollection`, `DataFileMetadata`, `DataFileCommon`, `HalfDiskHashMap`,
> `Bucket`, `AbstractLongList` (merkledb); `platform_state.proto`,
> `virtual_map_state.proto`, `roster.proto`, `bucket.proto` (hapi / merkledb proto).

---

## 1. Top-level directory

`SignedStateFilePath` computes the round directory as:

```
<stateConfig.savedStateDirectory>/<mainClassName>/<selfId>/<swirldName>/<round>/
```

Example: `data/saved/com.hedera.services.ServicesMain/0/123/4242`.

### 1.1 Atomic write

`SignedStateFileWriter.writeSignedStateToDisk` wraps the whole directory build
inside `FileUtils.executeAndRename`, which:

1. Creates a fresh temporary directory under `swirlds-tmp/` (from `TemporaryFileConfig`).
2. Runs the full `writeSignedStateFilesToDirectory` operation there.
3. Calls `Files.move(tmp, finalRoundDir, ATOMIC_MOVE)`.

Readers therefore never observe a half-built round directory; if the process is
killed mid-write, the temp directory is orphaned (and cleaned up) without
affecting `saved/…/<round>/`.

### 1.2 Round-directory layout

```
<round>/
├── stateMetadata.txt                ← SavedStateMetadata (human-readable k/v)
├── hashInfo.txt                     ← state-hash mnemonic + info string
├── currentRoster.json               ← active Roster as PBJ JSON
├── signatureSet.pbj                 ← SigSet (PBJ binary)
├── settingsUsed.txt                 ← effective Configuration dump
├── data/
│   └── state/                      ← MerkleDb snapshot (see §3)
│       ├── table_metadata.pbj
│       ├── idToDiskLocationHashChunks.ll
│       ├── pathToDiskLocationLeafNodes.ll
│       ├── idToHashChunk/           ← DataFileCollection
│       │   ├── state_idToHashChunk_metadata.pbj
│       │   └── state_idToHashChunk_<ts>_L<lvl>_<idx>.pbj   (1..N)
│       ├── pathToHashKeyValue/      ← DataFileCollection
│       │   ├── state_pathToHashKeyValue_metadata.pbj
│       │   └── state_pathToHashKeyValue_<ts>_L<lvl>_<idx>.pbj   (1..N)
│       └── objectKeyToPath/         ← HalfDiskHashMap
│           ├── state_objectkeytopath_metadata.hdhm
│           ├── state_objectkeytopath_bucket_index.ll
│           ├── state_objectkeytopath_metadata.pbj     (DataFileCollection metadata)
│           └── state_objectkeytopath_<ts>_L<lvl>_<idx>.pbj   (1..N bucket files)
└── <pces sub-tree, see §4>
```

Data files (`*.pbj`) inside the three sub-directories are **hard-linked** from
the live `swirlds-tmp/…` working directory, never byte-copied. This makes
snapshots cheap and preserves the immutable view even if compaction later
deletes the originating files from the working directory.

---

## 2. Round-directory files

### 2.1 `stateMetadata.txt`

Written by `SavedStateMetadata.write`. It is a plain text file rendered with
`TextTable` (borders disabled), one field per line, **fields emitted in
alphabetical order of the enum name**, format `"<KEY>: <value>"`.

Fields (`SavedStateMetadataField`):

|                Field                 |       Type       |                      Source                      |
|--------------------------------------|------------------|--------------------------------------------------|
| `ROUND`                              | long             | `SignedState.getRound()`                         |
| `HASH`                               | `Hash` (hex)     | `State.getHash()`                                |
| `HASH_MNEMONIC`                      | string           | `Mnemonics.generateMnemonic(hash)`               |
| `NUMBER_OF_CONSENSUS_EVENTS`         | long             | `ConsensusSnapshot.nextConsensusNumber`          |
| `CONSENSUS_TIMESTAMP`                | `Instant`        | `SignedState.getConsensusTimestamp()` or `EPOCH` |
| `LEGACY_RUNNING_EVENT_HASH`          | `Hash` nullable  | `PlatformState.legacy_running_event_hash`        |
| `LEGACY_RUNNING_EVENT_HASH_MNEMONIC` | string nullable  | mnemonic of the above                            |
| `MINIMUM_BIRTH_ROUND_NON_ANCIENT`    | long             | `ancientThresholdOf(state)`                      |
| `SOFTWARE_VERSION`                   | string           | `creationSoftwareVersionOf(state).toString()`    |
| `WALL_CLOCK_TIME`                    | `Instant`        | `Instant.now()` at write time                    |
| `NODE_ID`                            | long             | `selfId.id()` (`Long.MAX_VALUE` if non-node)     |
| `SIGNING_NODES`                      | CSV of longs     | `sigSet.getSigningNodes()`, sorted ascending     |
| `SIGNING_WEIGHT_SUM`                 | long             | `SignedState.getSigningWeight()`                 |
| `TOTAL_WEIGHT`                       | long             | `RosterUtils.computeTotalWeight(activeRoster)`   |
| `FREEZE_STATE`                       | boolean nullable | `SignedState.isFreezeState()`                    |

Compatibility rule (encoded in the class javadoc): new fields must be added as
`@Nullable` / optional. Once all production states have migrated, a field may
be tightened to `@NonNull` / primitive.

### 2.2 `hashInfo.txt`

UTF-8 text, produced via:

```java
writer.write(String.format(PlatformStateUtils.HASH_INFO_TEMPLATE, mnemonic));
```

`mnemonic = Mnemonics.generateMnemonic(state.getHash())`.

The file contains a mnemonic of the virtual map root hash and is provided for
informational purposes only.

### 2.3 `currentRoster.json`

`Roster.JSON.toJSON(roster)` — PBJ’s JSON encoding of the active `Roster`
retrieved via `SignedState.getRoster()`. Schema (`roster.proto`, HAPI):

```proto
message Roster {
  repeated RosterEntry rosterEntries = 1;   // sorted ascending by node_id
}
message RosterEntry {
  uint64 node_id              = 1;
  uint64 weight               = 2;
  bytes  gossip_ca_certificate = 3;         // X.509 DER-encoded
  reserved 4;                               // legacy tls_certificate_hash
  repeated proto.ServiceEndpoint gossip_endpoint = 5;
}

message ServiceEndpoint {
    bytes ipAddressV4 = 1;
    int32 port = 2;
    string domain_name = 3;
}
```

### 2.4 `signatureSet.pbj`

Produced by `SignedState.getSigSet().serialize(WritableStreamingData)`; read
back by `SigSet.deserialize(ReadableStreamingData)`. The payload is plain PBJ
protobuf bytes.

Schema (`platform_state.proto`):

```proto
message SigSet {
  repeated NodeIdSignaturePair nodeIdSignaturePairs = 1;
}
message NodeIdSignaturePair {
  int64 nodeId         = 1;
  int32 signatureType  = 2;   // SignatureType.ordinal() — 0=RSA, 1=ED25519, 2=ECDSA_SECP256K1
  bytes signatureBytes = 3;
}
```

### 2.5 `settingsUsed.txt`

Produced by `PlatformConfigUtils.writeSettingsUsed(directory, configuration)`:
a dump of every effective configuration property resolved for this run. Format
is a human-readable `key=value` listing (not consumed programmatically by the
platform — purely diagnostic).

---

## 3. State data sub-tree: `data/<vm-label>/`

Produced by:

```
StateLifecycleManager.createSnapshot
  → VirtualMapStateLifecycleManager.createSnapshot
    → VirtualMap.createSnapshot(outputDirectory)
      → MerkleDbDataSourceBuilder.snapshot(outputDirectory, dataSource)
        → MerkleDbDataSource.snapshot(snapshotDbPaths)
```

`MerkleDbDataSource.snapshot` runs six parallel tasks against a `MerkleDbPaths`
rooted at `data/<vm-label>/`:

|       Task        |                           Writes                           |
|-------------------|------------------------------------------------------------|
| metadata          | `table_metadata.pbj`                                       |
| idToDiskLocation… | `idToDiskLocationHashChunks.ll` (off-heap LongList flush)  |
| pathToDiskLocat…  | `pathToDiskLocationLeafNodes.ll` (off-heap LongList flush) |
| hashChunkStore    | `idToHashChunk/` (DataFileCollection.snapshot)             |
| keyValueStore     | `pathToHashKeyValue/` (DataFileCollection.snapshot)        |
| keyToPath         | `objectKeyToPath/` (HalfDiskHashMap.snapshot)              |

### 3.1 `table_metadata.pbj` — MerkleDb table metadata

Produced by `MerkleDbDataSource.saveMetadata`. Pure PBJ fields, no message
wrapper, no file header:

```proto
// Synthetic schema — declared in-code via FieldDefinition
message MerkleDbTableMetadata {
  uint64 minValidKey                = 1;  // optional — omitted when 0
  uint64 maxValidKey                = 2;  // optional — omitted when 0
  uint64 initialCapacity            = 3;  // optional — always > 0, always written
  uint64 hashesRamToDiskThreshold   = 4;  // optional, @Deprecated — legacy migration only
  // field 5, 6 — reserved
  uint32 hashChunkHeight            = 7;  // optional — validated against config on load
}
```

`(minValidKey, maxValidKey)` = the valid leaf-path range, i.e. the VirtualMap’s
`(firstLeafPath, lastLeafPath)`. `VirtualMap` reconstructs its
`VirtualMapMetadata` on load directly from these two values; no separate virtual
map metadata file is written.

### 3.2 `idToDiskLocationHashChunks.ll` & `pathToDiskLocationLeafNodes.ll` — off-heap index flush

Produced by `AbstractLongList.writeToFile`. Proprietary binary format (not
protobuf):

```
+---------+----------------------+---------------------------------------+
| u32 BE  | u64 BE               |  size * u64 BE                        |
| version | minValidIndex        |  raw long values                      |
+---------+----------------------+---------------------------------------+
```

- `version = CURRENT_FILE_FORMAT_VERSION = 3` (`NO_CAPACITY_VERSION`).
  Reader also accepts `MIN_VALID_INDEX_SUPPORT_VERSION = 2` which adds a legacy
  `(int longsPerChunk, long capacity)` pair between the version and
  `minValidIndex` words.
- Header size: `Integer.BYTES + Long.BYTES` (12 bytes).
- `maxValidIndex` is **not** written — derived on load as
  `minValidIndex + (fileSize - headerSize) / 8 - 1`.
- Body is a single contiguous run of longs; chunks that hold no live indices
  are re-packed out during write (`writeLongsData` sequentially processes
  chunks from the first chunk containing `minValidIndex`).

Key meaning — `idToDiskLocationHashChunks.ll` maps hash-chunk IDs to packed
`dataLocation` longs (40-bit byte offset | 24-bit file index, see
`DataFileCommon.dataLocation`). `pathToDiskLocationLeafNodes.ll` maps leaf
paths the same way.

### 3.3 `idToHashChunk/` and `pathToHashKeyValue/` — `DataFileCollection`

Each directory contains:

#### 3.3.1 `<storeName>_metadata.pbj`

Produced by `DataFileCollection.saveMetadata`. Pure PBJ fields:

```proto
// Synthetic schema — FieldDefinitions declared in DataFileCollection
message DataFileCollectionMetadata {
  uint64 minValidKey = 1;   // optional — omitted when 0
  uint64 maxValidKey = 2;   // optional — omitted when 0
}
```

This is the store’s valid key range (for the leaf KV store:
leaf-path range; for the hash chunk store: chunk-ID range; for HDHM’s
underlying collection: bucket-ID range).

#### 3.3.2 `<storeName>_<ts>_L<level>_<index>.pbj`

Naming convention is set by `DataFileCommon`:

- `<storeName>` matches the owning store.
- `<ts>` is formatted with `DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(UTC)`.
  Flush files use `Instant.now()` at flush time; compaction output files use
  `Instant.now()` at compaction time (see `merkledb-compaction.md` — timestamps
  are purely informational; conflict resolution is CAS-based via `putIfEqual`).
- `<level>` is the compaction level (`L0` = freshly flushed). Range
  `0..MAX_COMPACTION_LEVEL=127`.
- `<index>` is the zero-padded monotonic file index inside the collection.
- Extension: `.pbj` (`DataFileCommon.FILE_EXTENSION`).

**File-level schema (synthetic, declared on `DataFileCommon`):**

```proto
message DataFile {
  DataFileHeader metadata = 1;          // always at offset 0
  repeated bytes items    = 11;         // length-delimited, payload opaque to the collection
}
```

A data file is therefore a single `DataFileHeader` tag/length/value, followed
by an arbitrary number of `items` tag/length/value records. The
`dataLocation` stored in the index is `(fileIndex << 40) | fileOffset`, where
`fileOffset` points at the start of an `items` record (the tag byte), not the
payload.

##### DataFileHeader (`DataFileMetadata`)

Declared in `DataFileMetadata`:

```proto
message DataFileHeader {
  uint32  index                = 1;  // optional (== file index in collection)
  uint64  creationDateSeconds  = 2;
  uint32  creationDateNanos    = 3;
  fixed64 itemsCount           = 4;  // FIXED64 so it occupies 8 bytes regardless of value —
                                     //  enables in-place header rewrite on close()
  // field 5 — reserved
  uint32  compactionLevel      = 6;  // optional; 0 ≤ level < MAX_COMPACTION_LEVEL = 127
}
```

`DataFileWriter` maps the first 1024 bytes of the file as the header
scratch space. On `close()` it rewrites the header with the final
`itemsCount`, then `truncate(totalFileSize)` so there is no zero padding.
Because `itemsCount` is `FIXED64`, its encoded size never changes, so the
rewrite can never corrupt item bytes written after the header.

##### Item payload — `idToHashChunk/`

Each `items[k]` payload is a `VirtualHashChunk` message (`VirtualHashChunk`
class; no `.proto` file, only in-code `FieldDefinition`s):

```proto
message VirtualHashChunk {
  fixed64 path      = 1;  // chunk path (root of the sub-tree)
  // field 2, 3   — reserved
  bytes   hashData  = 4;  // packed raw hash bytes (len = 48 * 2^dataRank)
}
```

Chunk height is **not** serialized — it is constant across a data source and
passed as a parameter to `parseFrom(in, height)`. Partial chunks (lower ranks
not yet populated) are stored in packed form: only `2^dataRank` hashes are
written; the reader derives `dataRank` from `len / 48` and redistributes the
hashes at stride `2^(height-dataRank)`. Digest length is always
`Cryptography.DEFAULT_DIGEST_TYPE.digestLength()` = 48 (SHA-384).

##### Item payload — `pathToHashKeyValue/`

Each `items[k]` payload is a `VirtualLeafBytes` message:

```proto
// virtual_map_state.proto declares this as StateItem; in-code FieldDefinitions
// are declared on VirtualLeafBytes.
message VirtualLeaf {
  fixed64 path  = 1;  // optional — omitted when 0
  bytes   key   = 2;
  bytes   value = 3;  // optional — absent for tombstones, empty-bytes for empty value
}
```

The `key` and `value` byte strings are **`StateKey` / `StateValue` envelopes**
(`virtual_map_state.proto`), not the domain bytes directly. Each envelope is
itself a `oneof` whose field number equals the state ID and whose payload is
the domain key/value bytes (singletons carry the state ID as a varint, KV
entries carry the key bytes length-delimited, queue elements carry the queue
index as a varint). Wrapping/unwrapping is handled by `StateKeyUtils` /
`StateValue` in `swirlds-state-impl`; the VirtualMap sees opaque `Bytes`.

Note: the `VirtualLeafBytes.writeTo` on-disk encoding and
`VirtualLeafBytes.writeToForHashing` encoding differ — the hashing form
prepends a `0x00` domain-separation byte and **omits `path`**, so leaf
positions do not influence their hash.

### 3.4 `objectKeyToPath/` — `HalfDiskHashMap` (key → leaf-path index)

Maps domain-key bytes to leaf paths using an on-disk hash map that keeps only
an in-memory bucket-ID → disk-location index.

Snapshot layout (from `HalfDiskHashMap.snapshot(dir)`):

```
objectKeyToPath/
├── <storeName>_metadata.hdhm        ← HDHM metadata (NOT protobuf, legacy)
├── <storeName>_bucket_index.ll      ← LongList v3 (see §3.2)
├── <storeName>_metadata.pbj         ← DataFileCollection metadata (see §3.3.1)
└── <storeName>_<ts>_L<lvl>_<idx>.pbj   (1..N bucket data files)
```

#### 3.4.1 `<storeName>_metadata.hdhm`

Written with a raw `java.io.DataOutputStream` (big-endian), fixed 12-byte
payload — **not PBJ**:

```
+---------+---------+-----------------+
| u32 BE  | u32 BE  | u32 BE          |
| version | 0 (res.)| numOfBuckets    |
+---------+---------+-----------------+
```

- `version = METADATA_FILE_FORMAT_VERSION = 1`.
- Second word is zero, reserved (was `minimumBuckets` historically).
- `numOfBuckets` is always a power of two.

#### 3.4.2 `<storeName>_bucket_index.ll`

Same LongList v3 format as §3.2. Maps bucket ID (0 … numOfBuckets-1) to
`dataLocation` inside the HDHM data-file collection. Note that this index is
sparse — entries at `index[x]` and `index[x + N/2]` may point to the same
data location after HDHM resize doubling, which is why contiguous-chunk
assumptions must not be made for this list.

#### 3.4.3 Bucket data files (`<storeName>_<ts>_L<lvl>_<idx>.pbj`)

Same `DataFileCollection` structure as §3.3.2. Each `items[k]` payload is a
`Bucket` message (`bucket.proto` + `FieldDefinition`s on `Bucket.java`):

```proto
syntax = "proto3";
package merkledb;
option java_package = "com.swirlds.merkledb.files";
option java_multiple_files = true;

message Bucket {
  optional fixed32 index   = 1;   // always written (even when 0)
  repeated BucketEntry entries = 11;
}
message BucketEntry {
  fixed32 hashCode       = 1;     // Java hashCode of the key
  optional fixed64 value = 2;     // leaf path (or HDHM.INVALID_VALUE = Long.MIN_VALUE for tombstone)
  bytes    keyBytes      = 3;     // the StateKey envelope bytes (see §3.3.2 note)
}
```

Bucket entries whose low `log2(numOfBuckets)` bits of `hashCode` no longer
match the bucket index are purged during resize (`Bucket.readFrom` /
HalfDiskHashMap repair path).

---

## 4. PCES files

If `selfId != null`, `SignedStateFileWriter` instantiates a `PcesModule` and
invokes:

```java
pcesModule.copyPcesFilesRetryOnFailure(
        configuration, selfId, directory,
        ancientThresholdOf(signedState.getState()),
        signedState.getRound());
```

This hard-links into the round directory every PCES event file whose content
is still needed to replay state from this snapshot (bounded by the ancient
threshold). File naming follows `PcesFile`:

```
<pcesDir>/<ts>_seq<sequenceNumber>_minBR<min>_maxBR<max>_orig<origin>.pces
```

`writeSignedStateFilesToDirectory` explicitly notes this step is a temporary
arrangement (#23415); PCES file format is out of scope for this document.

---

## 5. Consolidated protobuf / field reference

All schemas required to parse a snapshot, in one place.

### 5.1 Declared in `.proto` files

**`hapi/hedera-protobuf-java-api/src/main/proto/platform/state/platform_state.proto`**

```proto
message SigSet {
  repeated NodeIdSignaturePair nodeIdSignaturePairs = 1;
}
message NodeIdSignaturePair {
  int64 nodeId         = 1;
  int32 signatureType  = 2;   // 0=RSA, 1=ED25519, 2=ECDSA_SECP256K1
  bytes signatureBytes = 3;
}
```

**`hapi/…/services/state/roster/roster.proto`** (summarized):

```proto
message Roster       { repeated RosterEntry rosterEntries = 1; }
message RosterEntry  {
  uint64 node_id                = 1;
  uint64 weight                 = 2;
  bytes  gossip_ca_certificate  = 3;
  repeated proto.ServiceEndpoint gossip_endpoint = 5;
}
```

**`hapi/…/platform/state/virtual_map_state.proto`** (leaf-level envelopes):

```proto
message StateItem   { StateKey key = 2; StateValue value = 3; }
message StateKey    { oneof key   { /* stateId → domain key,   see proto */ } }
message StateValue  { oneof value { /* stateId → domain value, see proto */ } }
message QueueState  { uint64 head = 1; uint64 tail = 2; }
```

**`platform-sdk/swirlds-merkledb/src/main/resources/com/swirlds/merkledb/files/bucket.proto`**

```proto
message Bucket       { optional uint32 index = 1; repeated BucketEntry entries = 11; }
message BucketEntry  { int32 hashCode = 1; optional int64 value = 2; bytes keyBytes = 3; }
```

### 5.2 Declared in code via PBJ `FieldDefinition`

**`MerkleDbDataSource` — `table_metadata.pbj`**

| # |            Name            |  Type  |            Notes            |
|---|----------------------------|--------|-----------------------------|
| 1 | `minValidKey`              | UINT64 | optional, omitted when 0    |
| 2 | `maxValidKey`              | UINT64 | optional, omitted when 0    |
| 3 | `initialCapacity`          | UINT64 | always written              |
| 4 | `hashesRamToDiskThreshold` | UINT64 | optional, `@Deprecated`     |
| 7 | `hashChunkHeight`          | UINT32 | optional, validated on load |

**`DataFileCollection` — `<store>_metadata.pbj`**

| # |     Name      |  Type  |
|---|---------------|--------|
| 1 | `minValidKey` | UINT64 |
| 2 | `maxValidKey` | UINT64 |

**`DataFileCommon` — outer data-file framing**

| #  |    Name    |  Type   | Repeated |
|----|------------|---------|----------|
| 1  | `metadata` | MESSAGE | no       |
| 11 | `items`    | MESSAGE | yes      |

**`DataFileMetadata` — header of every data file**

| # |         Name          |  Type   |                     Notes                     |
|---|-----------------------|---------|-----------------------------------------------|
| 1 | `index`               | UINT32  | optional                                      |
| 2 | `creationDateSeconds` | UINT64  |                                               |
| 3 | `creationDateNanos`   | UINT32  |                                               |
| 4 | `itemsCount`          | FIXED64 | fixed-width: allows in-place rewrite on close |
| 6 | `compactionLevel`     | UINT32  | optional; 0 ≤ level < 127                     |

**`VirtualLeafBytes` — leaf record payload**

| # |  Name   |  Type   |                      Notes                       |
|---|---------|---------|--------------------------------------------------|
| 1 | `path`  | FIXED64 | optional, omitted when 0                         |
| 2 | `key`   | BYTES   | StateKey envelope                                |
| 3 | `value` | BYTES   | optional; StateValue envelope (absent = deleted) |

**`VirtualHashChunk` — hash chunk payload**

| # |    Name    |  Type   |                 Notes                  |
|---|------------|---------|----------------------------------------|
| 1 | `path`     | FIXED64 | chunk path                             |
| 4 | `hashData` | BYTES   | packed `2^dataRank` SHA-384 hash bytes |

### 5.3 Non-protobuf headers

**`AbstractLongList` — `*.ll` files**

```
offset  size  field
0       4     u32 BE  version        (= 3, NO_CAPACITY_VERSION)
4       8     u64 BE  minValidIndex
12      8*N   u64 BE  longs          (N = size - minValidIndex)
```

Version 2 is accepted on read (inserts `int longsPerChunk, long capacity`
between `version` and `minValidIndex`).

**`HalfDiskHashMap` — `<store>_metadata.hdhm`**

```
offset  size  field
0       4     u32 BE  version              (= METADATA_FILE_FORMAT_VERSION = 1)
4       4     u32 BE  reserved             (always 0; was minimumBuckets)
8       4     u32 BE  numOfBuckets         (power of two)
```

Written via `java.io.DataOutputStream`, big-endian.

---

## 6. Read path summary

`SignedStateFileReader.readStateFile` mirrors the writer:

1. `stateLifecycleManager.loadSnapshot(stateDir)`
   → `VirtualMap.loadFromDirectory`
   → private `VirtualMap` constructor
   → `MerkleDbDataSourceBuilder.build(LABEL, snapshotPath, true, false)`
   → `hardLinkTree(snapshotDir/data/<label>, swirlds-tmp/<new working dir>)`
   → `MerkleDbDataSource` opens the linked directory as a live store.
   The call returns the hash captured from the first loaded
   `VirtualMap.getHash()` **before** the mutable copy is made.
2. Reads `signatureSet.pbj` with `SigSet.deserialize(in)`.
3. Constructs a `SignedState`, attaches the `SigSet`, registers service
   stub states, and returns a `DeserializedSignedState(reservedState, originalHash)`.

The other files (`stateMetadata.txt`, `hashInfo.txt`, `currentRoster.json`,
`settingsUsed.txt`) are advisory and not required for state reconstitution.

---

## 7. Appendix: deprecated paths that may appear in old snapshots

`MerkleDbPaths` still resolves three legacy locations that the current writer
does not produce but the reader may encounter when loading an old on-disk
snapshot:

|                 Path                 |                Replaced by                 |
|--------------------------------------|--------------------------------------------|
| `pathToDiskLocationInternalNodes.ll` | `idToDiskLocationHashChunks.ll` (chunked)  |
| `internalHashStoreRam.hl`            | chunked hash store + `hashChunkHeight` = N |
| `internalHashStoreDisk/`             | chunked hash store                         |

These only matter for migration from pre-chunked hash storage and should not
appear in any newly written snapshot.
