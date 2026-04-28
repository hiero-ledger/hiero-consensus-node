# Signed State Snapshot On-Disk Representation

This document describes the exact on-disk layout and binary formats produced by
`SignedStateFileWriter.writeSignedStateToDisk` — the entry point used by the consensus layer
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

## 3. State data sub-tree: `data/state/`

See all the details in the [State snapshot spec](../../swirlds-state-api/docs/state-snapshot-spec.md).

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

All schemas required to parse a signed snapshot, in one place.

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

See more details about proto definition in the [State snapshot spec field reference](../../swirlds-state-api/docs/state-snapshot-spec.md#consolidated-protobuf--field-reference).

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
