## Wrapped record-file block hashes on disk

This document describes the **feature-flagged** mechanism that lets the consensus node (CN) compute hashes for each
record-stream “record block” and append them to a file on disk at the end of that record block.

### Feature flag

The behavior is gated by:

- **`hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk`** (default `false`)
- **`hedera.recordStream.wrappedRecordHashesDir`** (default `"/opt/hgcapp/wrappedRecordHashes/"`)

When disabled, the CN does **not** accumulate record-block inputs and does **not** write anything to disk.

### On-disk model

Each record block produces a single entry:

- **Message**: `WrappedRecordFileBlockHashes`
  - `block_number` (int64)
  - `consensus_timestamp_hash` (bytes, 48-byte SHA-384)
  - `output_items_tree_root_hash` (bytes, 48-byte SHA-384)

Entries are appended to a single file named `wrapped-record-hashes.pb` under `wrappedRecordHashesDir`.
The file contents are an append-only sequence of protobuf-framed occurrences of entries.

### When the entry is written

At the moment the CN detects it must **close the current record block and start a new one** (record stream block
boundary), it computes the two hashes for the *just-finished* block and appends a `WrappedRecordFileBlockHashes` entry.

### Inputs accumulated per record block

When the feature flag is enabled, the CN tracks the data needed to deterministically reconstruct the wrapped
`RecordFileItem`:

- **`RecordStreamItem` list** for the record block (transaction + record)
- **`TransactionSidecarRecord` list** for the record block
- **`start_object_running_hash`**: the record stream running hash at the moment the record block started
- **`end_object_running_hash`**: the record stream running hash at the moment the record block ended

### Hash computations

#### `consensus_timestamp_hash`

This is the **leaf hash** of the consensus timestamp of the **first transaction** in the record block:

1. Take the first `RecordStreamItem` in the record file contents.
2. Extract `record.consensusTimestamp`.
3. Compute the leaf hash.

Where `hashLeaf` uses the block hashing leaf prefixing scheme (SHA-384).

#### `output_items_tree_root_hash`

This is the Merkle root of the **output-items subtree** for the wrapped record-file block.
Two leaves are added to the subtree, in this order:

1. A `BlockItem` containing a `BlockHeader` for the historical record-file block
2. A `BlockItem` containing the wrapped `RecordFileItem`

The leaf bytes are the protobuf serialization of each `BlockItem`:

1. `leaf0 = BlockItem.PROTOBUF.toBytes(BlockItem{ block_header = ... })`
2. `leaf1 = BlockItem.PROTOBUF.toBytes(BlockItem{ record_file = RecordFileItem{ ... } })`

The Merkle tree root is computed using the streaming Merkle tree algorithm used elsewhere in the node
(SHA-384 + leaf/internal-node prefixing):

```
output_items_tree_root_hash = MerkleRoot([
  leaf(BlockItem(block_header)),
  leaf(BlockItem(record_file))
])
```

### Constructing the wrapped `RecordFileItem`

The wrapped `RecordFileItem` includes:

- `creation_time`: the record block’s creation time (the first consensus time of the block)
- `record_file_contents`: a `RecordStreamFile` populated with
  - `hapi_proto_version`
  - `start_object_running_hash`
  - `record_stream_items` (all accumulated items)
  - `end_object_running_hash`
  - `block_number`
  - `sidecars` (metadata for each sidecar file)
- `sidecar_file_contents`: the list of `SidecarFile` messages, each containing its records

### Sidecar file splitting and hashing (must match v6 writer semantics)

Sidecar files are split exactly as the v6 sidecar writer does:

- A new sidecar file is started when:
  - `bytesWritten + recordBytes.length() > maxSideCarSizeInBytes`
- Where `bytesWritten` tracks **only** the uncompressed `recordBytes.length()` (not tag/varint overhead)

For each resulting sidecar file:

- The `SidecarFile` message contains the **repeated** `TransactionSidecarRecord` payloads.
- The `SidecarMetadata.hash` is computed to match `SidecarWriterV6` **exactly**:
  - For each record, write protobuf-framed bytes to a gzip stream:
    - tag = `(SIDECAR_RECORDS.number << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED`
    - length = recordBytes.length
    - bytes = `TransactionSidecarRecord.PROTOBUF.toBytes(record)`
  - Hash the **compressed bytes** with SHA-384
- The `SidecarMetadata.types` are the union of sidecar types present in that file, using the same mapping as v6:
  - `ACTIONS -> CONTRACT_ACTION`
  - `BYTECODE -> CONTRACT_BYTECODE`
  - `STATE_CHANGES -> CONTRACT_STATE_CHANGE`
