# CLPR State & Protobuf Map (Hiero)

> Prereq: read `clpr-service-spec.md` §1 (protobuf definitions) and §2 (on-ledger state model)
> for the protocol-level shapes. This document only covers what is *Hiero-specific*: the
> physical state IDs, where the proto files live, and what concrete container types are used.

## Proto file inventory

All under `hapi/hedera-protobuf-java-api/src/main/proto/`.

### HAPI surface (`services/`)

Transaction bodies (one body per HAPI tx):

|                   File                   |                 Functionality                 |
|------------------------------------------|-----------------------------------------------|
| `clpr_register_channel.proto`            | `CLPR_REGISTER_CHANNEL`                       |
| `clpr_complete_channel.proto`            | `CLPR_COMPLETE_CHANNEL`                       |
| `clpr_close_channel.proto`               | `CLPR_CLOSE_CHANNEL`                          |
| `clpr_register_connector.proto`          | `CLPR_REGISTER_CONNECTOR`                     |
| `clpr_complete_connector.proto`          | `CLPR_COMPLETE_CONNECTOR`                     |
| `clpr_deregister_connector.proto`        | `CLPR_DEREGISTER_CONNECTOR`                   |
| `clpr_submit_bundle.proto`               | `CLPR_SUBMIT_BUNDLE`                          |
| `clpr_redact_message.proto`              | `CLPR_REDACT_MESSAGE`                         |
| `clpr_update_ledger_configuration.proto` | `CLPR_UPDATE_LEDGER_CONFIGURATION`            |
| `clpr_get_ledger_configuration.proto`    | `CLPR_GET_LEDGER_CONFIGURATION` (query)       |
| `clpr_service.proto`                     | gRPC `proto.ClprService` definition for above |

### State / shared types (`services/state/clpr/`)

|               File                |                                                                                         Defines                                                                                         |
|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `clpr_channel.proto`              | `ClprChannel`, `ClprChannelStatus` enum                                                                                                                                                 |
| `clpr_connector.proto`            | `ClprConnector`, `ClprConnectorKey` (composite key: `channel_id` + `connector_id`, where `connector_id = keccak256(channel_id ‖ public_key ‖ salt)` per spec §2.2)                      |
| `clpr_message.proto`              | `ClprMessage`, `ClprMessageKey`, `ClprMessageValue`, `ClprMessagePayload` (oneof), `ClprControlMessage`, `ClprConfigUpdate`, `ClprQueueMetadata`, `ClprSyncPayload`                     |
| `clpr_ledger_configuration.proto` | `ClprLedgerConfiguration`, `ClprThrottles`                                                                                                                                              |
| `clpr_discovery.proto`            | `ClprDiscoverEndpointsRequest/Response`, `ClprEndpoint`                                                                                                                                 |
| `clpr_bundle_content.proto`       | `ClprBundleContent` (returned by `ClprVerifier.verifyBundle`)                                                                                                                           |
| `state_proof.proto`               | **Hiero-specific.** `StateProof`, `MerklePath`, `SiblingNode`, `MerkleSiblingHash`. The proof envelope a Hiero-as-source ledger uses; consumed by the verifier on the destination side. |

### Endpoint sync gRPC (definition lives in `proto.ClprEndpointService`)

Defined alongside state types; the Java SPI is `ClprEndpointServiceDefinition` (not a HAPI
service, no `Transaction` envelopes — payloads are raw `ClprSyncPayload` bytes). RPCs:
`sync(ClprSyncPayload → ClprSyncPayload)` and `discoverEndpoints(...)`.

## State schema (genesis): `V0650ClprSchema`

Path: `hedera-clpr-service-impl/.../impl/schemas/V0650ClprSchema.java`. Version `0.65.0`, no
later schemas exist (no migrations yet).

### States created

|              Constant               |   Kind    |                         Key type                         |        Value type         |                                       Cap                                        |
|-------------------------------------|-----------|----------------------------------------------------------|---------------------------|----------------------------------------------------------------------------------|
| `CHANNELS_KEY`                      | KV        | `ProtoBytes` (32B channel ID)                            | `ClprChannel`             | `MAX_CHANNELS = 10 000`                                                          |
| `PENDING_COMMITMENTS_KEY`           | KV        | `ProtoBytes`                                             | `ProtoBytes`              | `MAX_PENDING_COMMITMENTS = 10 000` (independent)                                 |
| `PENDING_CONNECTOR_COMMITMENTS_KEY` | KV        | `ProtoBytes`                                             | `ProtoBytes`              | `MAX_PENDING_COMMITMENTS = 10 000` (independent — same constant, separate state) |
| `MESSAGE_QUEUE_KEY`                 | KV        | `ClprMessageKey` (channelId + messageId)                 | `ClprMessageValue`        | unbounded (queue depth gated by `ClprThrottles.max_queue_depth`)                 |
| `CONNECTORS_KEY`                    | KV        | `ClprConnectorKey` (channel_id + connector_id, both 32B) | `ClprConnector`           | `MAX_CONNECTORS = 100 000`                                                       |
| `LEDGER_CONFIGURATION_KEY`          | Singleton | —                                                        | `ClprLedgerConfiguration` | 1                                                                                |

State IDs are `StateKey.KeyOneOfType.CLPRSERVICE_I_*.protoOrdinal()` (or
`SingletonType.CLPRSERVICE_I_LEDGER_CONFIGURATION.protoOrdinal()` for the singleton). Treat
those constants as the canonical identity — the string keys are labels.

### Genesis migration (in `migrate(...)`)

Seeds `LEDGER_CONFIGURATION` with:

- `chainId` and `protocolVersion` from `ClprConfig` (node config).
- Default `ClprThrottles`:
  - `maxSyncBytes = 4 MiB`
  - `maxMessagesPerBundle = 1 000`
  - `maxQueueDepth = 10 000`
  - `maxMessagePayloadBytes = 64 KiB`
  - `maxSyncsPerSec = 10`
  - `maxLocalEndpoints = 10`
  - `maxPeerEndpoints = 10`
- `endpoints` is **not** seeded by the schema; it is populated via
  `CLPR_UPDATE_LEDGER_CONFIGURATION` (admin tx). A fresh genesis network has empty seeds
  and no channels — bootstrap must perform an admin update before any sync can run.

> **Spec mapping:** the singleton corresponds to `ClprLedgerConfiguration` from spec
> §1.1. Throttle defaults satisfy the §7 "TBD per platform" placeholders.

## Stores (Java types) over these states

Interface module (`hedera-clpr-service`):

- `ReadableChannelStore`, `ReadableConnectorStore`, `ReadableMessageQueueStore`,
  `ReadableLedgerConfigurationStore`.

Impl module (`hedera-clpr-service-impl`):

- Readable: `*Impl` of each.
- Writable (only used inside handler/api code): `WritableChannelStore`,
  `WritableConnectorStore`, `WritableMessageQueueStore`,
  `WritableLedgerConfigurationStore`, `WritablePendingCommitmentStore`,
  `WritablePendingConnectorCommitmentStore`.

`ReadableStoreFactoryImpl` and `WritableStoreFactory` register all of these under
`ClprService.NAME`; `HederaNativeOperations` exposes `ReadableChannelStore` to the EVM
scope.

## Hiero-specific notes about the wire types

These items are *implementation-defined* in `clpr-service-spec.md` and pinned here:

- **Balance / stake widths:** `uint64` tinybars throughout (Hiero's native unit). Spec §2
  permits `uint256` on EVM-style ledgers.
- **`service_address`:** the well-known precompile address `0x16e` zero-padded to 20 bytes
  (spec §1.1, §6.3 callout).
- **`endpoint_signature`:** ECDSA secp256k1 over `keccak256(channelId ‖ bundle_payload)`
  using the platform event-level signature (i.e. the same key the node uses to sign events).
  No separate dedicated CLPR signing key in Hiero today.
- **Connector signing-key bind:** ECDSA secp256k1 over
  `keccak256(connector_id ‖ 0x000000000000000000000000000000000000016e)`.
- **Endpoint roster:** mirrors the consensus roster — there is no `ClprEndpointBond`,
  `registerEndpoint`, or `deregisterEndpoint` HAPI tx (spec §6.5 platform-managed model).
- **Hiero proof:** `StateProof` (in `block/stream/state_proof.proto`) is the proof shape
  this ledger emits for sources; the verifier on the destination ledger decodes it via the
  configured verifier contract — there is no Java-side decoder. See
  [`verifier.md`](verifier.md).

## Where to look next

- For the *meaning* of each field: `clpr-service-spec.md` §1 (proto messages).
- For who *writes* each state: [`handlers.md`](handlers.md) (HAPI) and
  [`evm-integration.md`](evm-integration.md) (EVM `sendMessage`).
- For who *reads* each state: same docs, plus [`sync-workflow.md`](sync-workflow.md) for
  outbound sync.
