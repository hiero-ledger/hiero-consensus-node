## State data specification: `data/state/`

```
<round>/
├── data/
│   └── state/
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
```

Produced by:

```
StateLifecycleManager.createSnapshot
  → VirtualMapStateLifecycleManager.createSnapshot
    → VirtualMap.createSnapshot(outputDirectory)
      → MerkleDbDataSourceBuilder.snapshot(outputDirectory, dataSource)
        → MerkleDbDataSource.snapshot(snapshotDbPaths)
```

`MerkleDbDataSource.snapshot` runs six parallel tasks against a `MerkleDbPaths`
rooted at `data/state/`:

|       Task        |                       Writes                        |
|-------------------|-----------------------------------------------------|
| metadata          | `table_metadata.pbj`                                |
| idToDiskLocation… | `idToDiskLocationHashChunks.ll` (LongList flush)    |
| pathToDiskLocat…  | `pathToDiskLocationLeafNodes.ll` (LongList flush)   |
| hashChunkStore    | `idToHashChunk/` (DataFileCollection.snapshot)      |
| keyValueStore     | `pathToHashKeyValue/` (DataFileCollection.snapshot) |
| keyToPath         | `objectKeyToPath/` (HalfDiskHashMap.snapshot)       |

### 1. `table_metadata.pbj` — MerkleDb table metadata

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

### 2. `idToDiskLocationHashChunks.ll` & `pathToDiskLocationLeafNodes.ll` — index flush

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

### 3. `idToHashChunk/` and `pathToHashKeyValue/` — `DataFileCollection`

Each directory contains:

#### 3.1 `<storeName>_metadata.pbj`

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

#### 3.2 `<storeName>_<ts>_L<level>_<index>.pbj`

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

### 4. `objectKeyToPath/` — `HalfDiskHashMap` (key → leaf-path index)

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

#### 4.1 `<storeName>_metadata.hdhm`

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

#### 4.2 `<storeName>_bucket_index.ll`

Same LongList v3 format as §3.2. Maps bucket ID (0 … numOfBuckets-1) to
`dataLocation` inside the HDHM data-file collection. Note that this index is
sparse — entries at `index[x]` and `index[x + N/2]` may point to the same
data location after HDHM resize doubling, which is why contiguous-chunk
assumptions must not be made for this list.

#### 4.3 Bucket data files (`<storeName>_<ts>_L<lvl>_<idx>.pbj`)

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

## Consolidated protobuf / field reference

All schemas required to parse a state snapshot, in one place.

### 1. Declared in `.proto` files

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

### 2. Declared in code via PBJ `FieldDefinition`

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

### 3. Non-protobuf headers

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
