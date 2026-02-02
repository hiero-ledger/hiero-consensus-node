## Wrapped record-file block hashes in state

This document describes the **feature-flagged** mechanism that lets the consensus node (CN) compute hashes for each
record-stream “record block” and enqueue them into state at the end of that record block.

### Feature flag

The behavior is gated by:

- **`hedera.recordStream.storeWrappedRecordFileBlockHashesInState`** (default `false`)

When disabled, the CN does **not** accumulate record-block inputs and does **not** enqueue anything to state.

### State model

Each record block produces a single queue element:

- **Message**: `WrappedRecordFileBlockHashes`
  - `block_number` (int64)
  - `consensus_timestamp_hash` (bytes, 48-byte SHA-384)
  - `output_items_tree_root_hash` (bytes, 48-byte SHA-384)
- **Queue state**: `BlockRecordService_I_WRAPPED_RECORD_FILE_BLOCK_HASHES`
  - State ID comes from `StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_WRAPPED_RECORD_FILE_BLOCK_HASHES.protoOrdinal()`

The queue is owned by `BlockRecordService` and created via the block record service schema.

### When the queue entry is written

At the moment the CN detects it must **close the current record block and start a new one** (record stream block
boundary), it computes the two hashes for the *just-finished* block and enqueues a `WrappedRecordFileBlockHashes` entry.

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
3. Compute the leaf hash:

\[
\mathrm{consensus\_timestamp\_hash} = \mathrm{hashLeaf}(\mathrm{Timestamp.PROTOBUF.toBytes}(ts))
\]

Where `hashLeaf` uses the block hashing leaf prefixing scheme (SHA-384).

#### `output_items_tree_root_hash`

This is the Merkle root of the **output-items subtree** for the wrapped record-file block. The subtree has **two leaves,
in order**:

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

### Merkle tree structure summary

For each record block \(N\), the enqueued hashes correspond to:

- **Consensus time leaf**:
  - leaf hash of the first transaction’s consensus timestamp in the record file contents
- **Output-items subtree**:
  - a 2-leaf tree over serialized `BlockItem`s:

```
root
├─ leaf: BlockItem(block_header)
└─ leaf: BlockItem(record_file = RecordFileItem(...))
```

The queue entry stores:

- `block_number = N`
- `consensus_timestamp_hash = hashLeaf(firstTxnConsensusTimestampBytes)`
- `output_items_tree_root_hash = root(leaf(headerItemBytes), leaf(recordFileItemBytes))`
