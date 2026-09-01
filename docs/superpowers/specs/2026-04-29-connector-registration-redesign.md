# Connector Registration Redesign

**Date:** 2026-04-29
**Status:** Approved
**Scope:** Hiero CLPR Service — connector state, protos, handlers, spec

---

## Background

The upstream CLPR spec (`clpr-service.md`) and EVM smart contracts have moved from a global
`source_connector_address → local_connector` mapping to a per-connection commit-reveal scheme
identical in structure to connection registration. The Hiero implementation still uses the old
design. This spec drives the update.

---

## Design

### Connector ID Derivation

```
connectorId = keccak256(connectionId || pubKey || salt)
```

- `connectionId` — 32-byte connection this connector is bound to
- `pubKey` — operator's full public key (Ed25519: 32 bytes, secp256k1: 64 bytes uncompressed)
- `salt` — operator-chosen `bytes32` label (defaults to all-zeros)

The formula is deterministic and platform-independent, so the same operator keypair produces
the same `connectorId` on every ledger where it registers for a given connection.

### Connector Registration (Commit-Reveal)

**Phase 1 — Commit (`registerConnector`):**
Permissionless. Stores `commitment = keccak256(connectorId || pubKey)` in the pending connector
commitments set. Idempotent.

**Phase 2 — Reveal (`completeConnector`):**
Caller submits `(connectorId, pubKey, sig, salt, connectionId, connectorContract, adminKey, lockedStake)`.
The handler:
1. Re-derives `connectorId = keccak256(connectionId || pubKey || salt)` and compares to submitted value.
2. Checks `keccak256(connectorId || pubKey)` exists in the pending connector commitments set.
3. Verifies `sig` over `keccak256(connectorId || 0x000...016e)` using the key scheme indicated by
`pubKey` length (Ed25519 or secp256k1). `0x16e` is the 20-byte EVM address of the Hiero CLPR
system contract — the Hiero equivalent of `address(this)` in the EVM contract.
4. Verifies the referenced connection exists.
5. Verifies no connector with key `(connectionId, connectorId)` already exists.
6. Verifies `connectorContract` refers to a deployed smart contract.
7. Verifies `lockedStake >= clprConfig.minLockedStake()`.
8. Transfers `lockedStake` from payer to the CLPR staking account.
9. Stores the connector keyed by `(connectionId, connectorId)`.

### Per-Connection Scoping

Storage lookup key is `(connectionId, connectorId)`, not a global namespace. A squatter on
another ledger can register a connector there, but it only affects connections on that ledger
and does not interfere with the legitimate operator's connectors on other connections.

### Cross-Ledger Identity

Because `connectorId` is derived from the same formula on every ledger, no explicit address
cross-referencing is needed. When a message arrives carrying a `connectorId` stamped on the
source chain, the destination resolves it directly via `(connectionId, connectorId)`.

---

## Changes

### Spec

`hedera-node/docs/design/services/clpr-service/clpr-service.md` §3.3.1:
- Remove "Cross-ledger identity" paragraph describing the old `source_connector_address` mapping.
- Add: Connector ID derivation, Connector registration (commit-reveal), Per-connection scoping,
updated Cross-ledger identity.

### Proto — Transaction Bodies

**`clpr_register_connector.proto`** (commit phase only):

```protobuf
message ClprRegisterConnectorTransactionBody {
    bytes commitment = 1; // keccak256(connectorId || pubKey)
}
```

**New `clpr_complete_connector.proto`** (reveal phase):

```protobuf
message ClprCompleteConnectorTransactionBody {
    bytes connector_id              = 1;
    bytes pub_key                   = 2;
    bytes sig                       = 3;
    bytes salt                      = 4; // 32 bytes
    bytes connection_id             = 5; // 32 bytes
    proto.ContractID connector_contract = 6;
    proto.Key admin_key             = 7;
    uint64 locked_stake             = 8;
}
```

**`clpr_deregister_connector.proto`** — replace `source_connector_address` with scoped key:

```protobuf
message ClprDeregisterConnectorTransactionBody {
    bytes connection_id             = 1;
    bytes connector_id              = 2;
    proto.AccountID stake_recipient = 3;
}
```

**`clpr_service.proto`** — add `completeConnector` RPC.

### Proto — State

**`state/clpr/clpr_connector.proto`** — new key and updated value:

```protobuf
message ClprConnectorKey {
    bytes connection_id = 1;
    bytes connector_id  = 2;
}

message ClprConnector {
    bytes connector_id                  = 1;
    bytes connection_id                 = 2;
    proto.ContractID connector_contract = 3;
    proto.Key admin_key                 = 4;
    uint64 locked_stake                 = 5;
    uint32 slash_count                  = 6;
}
```

### Schema (`V0650ClprSchema`)

- Add `PENDING_CONNECTOR_COMMITMENTS` KV state (`ProtoBytes → ProtoBytes`).
- `CONNECTORS` state key type changes to new `ClprConnectorKey`.

### New / Updated Classes

|                   Class                   |                                             Action                                             |
|-------------------------------------------|------------------------------------------------------------------------------------------------|
| `ClprRegisterConnectorHandler`            | Rewrite: validate 32-byte commitment, store in `PENDING_CONNECTOR_COMMITMENTS`; permissionless |
| `ClprCompleteConnectorHandler`            | New: full reveal logic (derive ID, check commitment, verify sig, transfer stake, store)        |
| `ClprDeregisterConnectorHandler`          | Update key lookup to `ClprConnectorKey(connectionId, connectorId)`                             |
| `WritablePendingConnectorCommitmentStore` | New: mirrors `WritablePendingCommitmentStore`                                                  |
| `ReadableConnectorStoreImpl`              | Key type → new `ClprConnectorKey`                                                              |
| `WritableConnectorStore`                  | Key type → new `ClprConnectorKey`; `put` builds key from connector fields                      |
| `ClprServiceApiImpl`                      | Connector lookup → `ClprConnectorKey(connectionId, connectorId)`                               |
| `ClprSubmitBundleHandler`                 | Same connector lookup update; `countConnectorMessages` matches on `connectorId`                |

### Removed

- `CLPR_CONNECTOR_ALREADY_EXISTS` response code that referenced `source_connector_address`
  (replaced by the same code with updated semantics)
- All references to `source_connector_address` in connector proto/state/handlers

---

## Signature Verification Detail

The Hiero CLPR system contract address is `0x000000000000000000000000000000000000016e`
(20 bytes). The message to sign is:

```
keccak256(connectorId || 0x000000000000000000000000000000000000016e)
```

Verification uses `CryptographyProvider.getInstance().verifySync()` with `SignatureType.ED25519`
or `SignatureType.ECDSA_SECP256K1`, determined by `pubKey` length (32 vs 64 bytes), exactly as
`ClprCompleteConnectionHandler` does for connection registration.
