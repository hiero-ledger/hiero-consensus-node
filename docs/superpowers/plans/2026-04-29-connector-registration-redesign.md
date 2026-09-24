# Connector Registration Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the global `source_connector_address` connector model with a per-connection commit-reveal scheme matching the upstream spec and EVM smart contracts.

**Architecture:** Two new HAPI transactions (`registerConnector` simplified to commit-only, new `completeConnector` for reveal). Connector state keyed by `(connectionId, connectorId)` instead of global `sourceConnectorAddress`. New `PENDING_CONNECTOR_COMMITMENTS` state mirrors the existing connection commitment store. Signature verification follows the same crypto path as `ClprCompleteConnectionHandler`.

**Tech Stack:** Java 21, protobuf (PBJ), Swirlds state API, Mockito + JUnit 5 tests, `MiscCryptoUtils.keccak256DigestOf`, `CryptographyProvider.getInstance().verifySync`.

---

## File Map

**Proto — create:**
- `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_complete_connector.proto`

**Proto — modify:**
- `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_register_connector.proto`
- `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_deregister_connector.proto`
- `hapi/hedera-protobuf-java-api/src/main/proto/services/state/clpr/clpr_connector.proto`
- `hapi/hedera-protobuf-java-api/src/main/proto/services/basic_types.proto` (add `ClprCompleteConnector = 126` to `HederaFunctionality`)
- `hapi/hedera-protobuf-java-api/src/main/proto/services/transaction.proto` (add `clprCompleteConnector = 90` to `TransactionBody`)
- `hapi/hedera-protobuf-java-api/src/main/proto/platform/state/virtual_map_state.proto` (add `PENDING_CONNECTOR_COMMITMENTS = 64`)
- `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_service.proto` (add `completeConnector` RPC)

**Impl — create:**
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritablePendingConnectorCommitmentStore.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectorHandler.java`

**Impl — modify:**
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/schemas/V0650ClprSchema.java`
- `hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ReadableConnectorStore.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ReadableConnectorStoreImpl.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritableConnectorStore.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprSlashingUtils.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprRegisterConnectorHandler.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprDeregisterConnectorHandler.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprHandlers.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java`
- `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java`
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java`
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java`
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java`
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/IngestChecker.java`
- `hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/ApiPermissionConfig.java`
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java`

**Spec:**
- `hedera-node/docs/design/services/clpr-service/clpr-service.md`

**Tests — create:**
- `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprCompleteConnectorHandlerTest.java`

**Tests — modify:**
- `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprRegisterConnectorHandlerTest.java`
- `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprDeregisterConnectorHandlerTest.java`
- `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/ClprServiceApiImplTest.java`
- `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprSubmitBundleHandlerTest.java`

---

## Task 1: Update proto state definitions

**Files:**
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/platform/state/virtual_map_state.proto`
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/state/clpr/clpr_connector.proto`

- [ ] **Step 1: Add PENDING_CONNECTOR_COMMITMENTS field to virtual_map_state.proto**

Find the block ending at field 63 (`ClprService_I_CONNECTORS`) in `virtual_map_state.proto` (around line 307). Add after it in the `KeyOneOfType` oneof:

```proto
/**
 * A state identifier for CLPR pending connector commitments.
 * Key is the commitment hash bytes.
 */
proto.ProtoBytes ClprService_I_PENDING_CONNECTOR_COMMITMENTS = 64;
```

Also update the comment on field 63 (key description changed):

```proto
/**
 * A state identifier for CLPR connectors.
 * Key is ClprConnectorKey (connection_id + connector_id).
 */
com.hedera.hapi.node.state.clpr.ClprConnectorKey ClprService_I_CONNECTORS = 63;
```

In the value oneof (around line 686), add after `ClprService_I_PENDING_COMMITMENTS`:

```proto
/**
 * CLPR pending connector commitment value (mirrors key — acts as a set).
 */
proto.ProtoBytes ClprService_I_PENDING_CONNECTOR_COMMITMENTS = 64;
```

- [ ] **Step 2: Replace ClprConnectorKey and ClprConnector in clpr_connector.proto**

Replace the entire content of `clpr_connector.proto`:

```proto
syntax = "proto3";

package com.hedera.hapi.node.state.clpr;

// SPDX-License-Identifier: Apache-2.0
option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.state.clpr">>> This comment is special code for setting PBJ Compiler java package
option java_multiple_files = true;

import "services/basic_types.proto";

/**
 * Key for the CONNECTORS state.
 * Uniquely identifies a Connector within a specific Connection.
 */
message ClprConnectorKey {
    /**
     * The 32-byte Connection ID this connector is bound to.
     */
    bytes connection_id = 1;

    /**
     * The 32-byte Connector ID: keccak256(connectionId || pubKey || salt).
     */
    bytes connector_id = 2;
}

/**
 * On-ledger state for a single CLPR Connector.
 * Keyed by ClprConnectorKey (connection_id + connector_id).
 *
 * A Connector maps a per-connection identity to a local authorization
 * contract. The connector contract is a smart contract with its own balance
 * used to pay for inbound message execution.
 */
message ClprConnector {
    /**
     * 32-byte Connector ID: keccak256(connectionId || pubKey || salt).
     * Same on every ledger where the operator registers this Connector.
     */
    bytes connector_id = 1;

    /**
     * 32-byte Connection ID this Connector is bound to.
     */
    bytes connection_id = 2;

    /**
     * Local authorization contract that governs message dispatch.
     */
    proto.ContractID connector_contract = 3;

    /**
     * Key that authorizes administrative operations (deregister, topUp).
     */
    proto.Key admin_key = 4;

    /**
     * Slashable bond (in tinybars).
     */
    uint64 locked_stake = 5;

    /**
     * Cumulative slash count for penalty escalation.
     */
    uint32 slash_count = 6;
}
```

- [ ] **Step 3: Commit**

```bash
git add hapi/hedera-protobuf-java-api/src/main/proto/platform/state/virtual_map_state.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/state/clpr/clpr_connector.proto
git commit -m "feat(clpr): add PENDING_CONNECTOR_COMMITMENTS state, update ClprConnectorKey to (connectionId, connectorId)"
```

---

## Task 2: Update transaction proto files

**Files:**
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_register_connector.proto`
- Create: `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_complete_connector.proto`
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_deregister_connector.proto`
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/basic_types.proto`
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/transaction.proto`
- Modify: `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_service.proto`

- [ ] **Step 1: Simplify clpr_register_connector.proto to commit-only**

Replace the entire content:

```proto
syntax = "proto3";

package com.hedera.hapi.node.clpr;

// SPDX-License-Identifier: Apache-2.0
option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.clpr">>> This comment is special code for setting PBJ Compiler java package
option java_multiple_files = true;

/**
 * Phase 1 (Commit): Register a Connector commitment.
 *
 * Permissionless and idempotent. Stores
 * keccak256(connectorId || pubKey) on-ledger so the reveal phase can verify it.
 * Anyone may call; only the holder of the matching private key can complete.
 */
message ClprRegisterConnectorTransactionBody {
    /**
     * keccak256(connectorId || pubKey) where
     * connectorId = keccak256(connectionId || pubKey || salt).
     * Must be exactly 32 bytes.
     */
    bytes commitment = 1;
}
```

- [ ] **Step 2: Create clpr_complete_connector.proto**

Create `hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_complete_connector.proto`:

```proto
syntax = "proto3";

package com.hedera.hapi.node.clpr;

// SPDX-License-Identifier: Apache-2.0
option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.clpr">>> This comment is special code for setting PBJ Compiler java package
option java_multiple_files = true;

import "services/basic_types.proto";
import "services/clpr_complete_connection.proto";

/**
 * Phase 2 (Reveal): Complete a pending connector commitment.
 *
 * Permissionless. The handler:
 *   1. Re-derives connectorId = keccak256(connectionId || publicKey || salt).
 *   2. Verifies keccak256(connectorId || publicKey) was committed.
 *   3. Verifies sig over keccak256(connectorId || 0x000000000000000000000000000000000000016e).
 *   4. Creates the Connector in state keyed by (connectionId, connectorId).
 */
message ClprCompleteConnectorTransactionBody {
    /**
     * The Connector ID the caller claims: keccak256(connectionId || publicKey || salt).
     * Must be exactly 32 bytes. The handler re-derives and compares.
     */
    bytes connector_id = 1;

    /**
     * Public key proving ownership of the commitment.
     * Encoding depends on signature_scheme:
     *   ECDSA_SECP256K1: 64 bytes (uncompressed x||y without 0x04 prefix)
     *   ED25519: 32 bytes
     */
    bytes public_key = 2;

    /**
     * Signature over keccak256(connectorId || 0x000000000000000000000000000000000000016e).
     * Always 64 bytes regardless of scheme.
     */
    bytes signature = 3;

    /**
     * The signature scheme used for public_key and signature.
     * Defaults to ECDSA_SECP256K1.
     */
    ClprSignatureScheme signature_scheme = 4;

    /**
     * Operator-chosen 32-byte label differentiating connectors on the same
     * connection for the same operator. Use all-zeros for a single Connector.
     */
    bytes salt = 5;

    /**
     * The 32-byte Connection ID this Connector is bound to.
     */
    bytes connection_id = 6;

    /**
     * Local authorization contract that governs message dispatch for this
     * Connector. Must refer to a deployed smart contract.
     */
    proto.ContractID connector_contract = 7;

    /**
     * Key that authorizes administrative operations on this Connector.
     * Required.
     */
    proto.Key admin_key = 8;

    /**
     * Slashable bond (in tinybars) to lock at registration.
     * Must be >= clpr.minLockedStake.
     */
    uint64 locked_stake = 9;
}
```

- [ ] **Step 3: Update clpr_deregister_connector.proto**

Replace the entire content:

```proto
syntax = "proto3";

package com.hedera.hapi.node.clpr;

// SPDX-License-Identifier: Apache-2.0
option java_package = "com.hederahashgraph.api.proto.java";
// <<<pbj.java_package = "com.hedera.hapi.node.clpr">>> This comment is special code for setting PBJ Compiler java package
option java_multiple_files = true;

import "services/basic_types.proto";

/**
 * Remove a Connector from the CLPR Service.
 *
 * Deregisters a Connector and returns any locked stake to the specified
 * stake_recipient account. Requires the connector's admin_key to sign.
 *
 * Authority: Connector admin_key must sign.
 */
message ClprDeregisterConnectorTransactionBody {
    /**
     * The 32-byte Connection ID the Connector is registered on.
     */
    bytes connection_id = 1;

    /**
     * The 32-byte Connector ID: keccak256(connectionId || pubKey || salt).
     */
    bytes connector_id = 2;

    /**
     * Account to receive the returned locked_stake.
     * Must be explicitly specified and must sign the transaction.
     */
    proto.AccountID stake_recipient = 3;
}
```

- [ ] **Step 4: Add ClprCompleteConnector to HederaFunctionality enum in basic_types.proto**

Find the line `ClprDeregisterConnector = 125;` in `basic_types.proto` (around line 1925). Add after it:

```proto
/**
 * Complete a Connector registration (reveal phase).
 */
ClprCompleteConnector = 126;
```

- [ ] **Step 5: Add clprCompleteConnector to TransactionBody oneof in transaction.proto**

Find `clprDeregisterConnector = 89;` in `transaction.proto` (around line 753). Add after it:

```proto
/**
 * Phase 2 (Reveal): Complete a pending Connector commitment.
 */
com.hedera.hapi.node.clpr.ClprCompleteConnectorTransactionBody clprCompleteConnector = 90;
```

- [ ] **Step 6: Add completeConnector RPC to clpr_service.proto**

Find the `deregisterConnector` RPC and add after it:

```proto
/**
 * Complete a connector commitment (Phase 2: Reveal).
 * <p>
 * Reveals the preimage and proves key ownership to register the connector.<br/>
 * The request body MUST be a
 * [ClprCompleteConnectorTransactionBody](#com.hedera.hapi.node.clpr.ClprCompleteConnectorTransactionBody)
 */
rpc completeConnector (Transaction) returns (TransactionResponse);
```

- [ ] **Step 7: Commit**

```bash
git add hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_register_connector.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_complete_connector.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_deregister_connector.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/basic_types.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/transaction.proto \
        hapi/hedera-protobuf-java-api/src/main/proto/services/clpr_service.proto
git commit -m "feat(clpr): add completeConnector commit-reveal protos and HederaFunctionality entry"
```

---

## Task 3: Update schema and create WritablePendingConnectorCommitmentStore

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/schemas/V0650ClprSchema.java`
- Create: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritablePendingConnectorCommitmentStore.java`

- [ ] **Step 1: Add PENDING_CONNECTOR_COMMITMENTS state constants and definition to V0650ClprSchema.java**

Add the following constants after the existing `CONNECTORS_STATE_LABEL` constant:

```java
/** Pending connector commitments state ID */
public static final int PENDING_CONNECTOR_COMMITMENTS_STATE_ID =
        StateKey.KeyOneOfType.CLPRSERVICE_I_PENDING_CONNECTOR_COMMITMENTS.protoOrdinal();

/** Pending connector commitments state key */
public static final String PENDING_CONNECTOR_COMMITMENTS_KEY = "PENDING_CONNECTOR_COMMITMENTS";

/** Pending connector commitments state label */
public static final String PENDING_CONNECTOR_COMMITMENTS_STATE_LABEL =
        computeLabel(ClprService.NAME, PENDING_CONNECTOR_COMMITMENTS_KEY);
```

In `statesToCreate()`, add the new state alongside the existing ones:

```java
StateDefinition.keyValue(
        PENDING_CONNECTOR_COMMITMENTS_STATE_ID,
        PENDING_CONNECTOR_COMMITMENTS_KEY,
        ProtoBytes.PROTOBUF,
        ProtoBytes.PROTOBUF),
```

The full `statesToCreate()` return should be:

```java
return Set.of(
        StateDefinition.keyValue(
                CONNECTIONS_STATE_ID, CONNECTIONS_KEY, ProtoBytes.PROTOBUF, ClprConnection.PROTOBUF),
        StateDefinition.keyValue(
                PENDING_COMMITMENTS_STATE_ID,
                PENDING_COMMITMENTS_KEY,
                ProtoBytes.PROTOBUF,
                ProtoBytes.PROTOBUF),
        StateDefinition.keyValue(
                PENDING_CONNECTOR_COMMITMENTS_STATE_ID,
                PENDING_CONNECTOR_COMMITMENTS_KEY,
                ProtoBytes.PROTOBUF,
                ProtoBytes.PROTOBUF),
        StateDefinition.keyValue(
                MESSAGE_QUEUE_STATE_ID, MESSAGE_QUEUE_KEY, ClprMessageKey.PROTOBUF, ClprMessageValue.PROTOBUF),
        StateDefinition.keyValue(
                CONNECTORS_STATE_ID, CONNECTORS_KEY, ClprConnectorKey.PROTOBUF, ClprConnector.PROTOBUF),
        StateDefinition.singleton(
                LEDGER_CONFIGURATION_STATE_ID, LEDGER_CONFIGURATION_KEY, ClprLedgerConfiguration.PROTOBUF));
```

- [ ] **Step 2: Create WritablePendingConnectorCommitmentStore.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Store for CLPR pending connector ownership commitments.
 * Acts as a set — the key and value are both the commitment hash.
 */
public class WritablePendingConnectorCommitmentStore {

    private final WritableKVState<ProtoBytes, ProtoBytes> state;

    public WritablePendingConnectorCommitmentStore(@NonNull final WritableStates states) {
        requireNonNull(states);
        this.state = states.get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID);
    }

    public boolean contains(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        return state.get(new ProtoBytes(commitment)) != null;
    }

    public void put(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        final var key = new ProtoBytes(commitment);
        state.put(key, key);
    }

    public void remove(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        state.remove(new ProtoBytes(commitment));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/schemas/V0650ClprSchema.java \
        hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritablePendingConnectorCommitmentStore.java
git commit -m "feat(clpr): add PENDING_CONNECTOR_COMMITMENTS state and store"
```

---

## Task 4: Update connector store classes

**Files:**
- Modify: `hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ReadableConnectorStore.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ReadableConnectorStoreImpl.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritableConnectorStore.java`

- [ ] **Step 1: Update ReadableConnectorStore.java interface**

The existing `getConnector(ClprConnectorKey key)` method signature stays the same — the `ClprConnectorKey` type is already the right name, it just gains new fields. No change needed to the interface itself unless you want to add a convenience overload. Leave as-is: the key type change in the proto automatically propagates.

Verify the file still compiles after the proto change by reading it — no edits needed if `ClprConnectorKey` is referenced only by type name.

- [ ] **Step 2: Update WritableConnectorStore.put() to use new key fields**

In `WritableConnectorStore.java`, update the `put` method:

```java
public void put(@NonNull final ClprConnector connector) {
    requireNonNull(connector);
    final var key = new ClprConnectorKey(connector.connectionId(), connector.connectorId());
    connectorState().put(key, connector);
}
```

- [ ] **Step 3: Update WritableConnectorStore.remove() — no change needed**

The `remove(ClprConnectorKey key)` signature is unchanged. Callers that still use `new ClprConnectorKey(sourceAddr)` will break at compile time — fix those in later tasks.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ReadableConnectorStore.java \
        hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ReadableConnectorStoreImpl.java \
        hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/WritableConnectorStore.java
git commit -m "feat(clpr): update connector stores for (connectionId, connectorId) key"
```

---

## Task 5: Update ClprSlashingUtils

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprSlashingUtils.java`

- [ ] **Step 1: Fix connector removal key in applySlash**

In `applySlash`, replace:

```java
connectorStore.remove(new ClprConnectorKey(connector.sourceConnectorAddress()));
```

with:

```java
connectorStore.remove(new ClprConnectorKey(connector.connectionId(), connector.connectorId()));
```

- [ ] **Step 2: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprSlashingUtils.java
git commit -m "fix(clpr): update ClprSlashingUtils to use (connectionId, connectorId) key"
```

---

## Task 6: Rewrite ClprRegisterConnectorHandler (commit-only, permissionless)

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprRegisterConnectorHandler.java`
- Test: `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprRegisterConnectorHandlerTest.java`

- [ ] **Step 1: Write the failing tests**

Replace the entire test file:

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprRegisterConnectorTransactionBody;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRegisterConnectorHandler;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRegisterConnectorHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final Bytes VALID_COMMITMENT = Bytes.wrap(new byte[32]);

    @Mock private PureChecksContext pureChecksContext;
    @Mock private HandleContext handleContext;
    @Mock private StoreFactory storeFactory;
    @Mock private WritableStates writableStates;

    private ClprRegisterConnectorHandler subject;
    private WritablePendingConnectorCommitmentStore commitmentStore;

    @BeforeEach
    void setUp() {
        subject = new ClprRegisterConnectorHandler();

        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID, "ClprService:PENDING_CONNECTOR_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingConnectorCommitmentStore(writableStates);
    }

    @Test
    @DisplayName("should reject when commitment is not 32 bytes")
    void rejectsWrongCommitmentLength() {
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when commitment is empty")
    void rejectsEmptyCommitment() {
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(Bytes.EMPTY)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with 32-byte commitment")
    void passesWithValidCommitment() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should store commitment in PENDING_CONNECTOR_COMMITMENTS")
    void storesCommitment() {
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        assertThat(commitmentStore.contains(VALID_COMMITMENT)).isTrue();
    }

    @Test
    @DisplayName("should be idempotent — re-registering same commitment does not throw")
    void isIdempotent() {
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        subject.handle(handleContext);
        assertThat(commitmentStore.contains(VALID_COMMITMENT)).isTrue();
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .getOrCreateConfig();
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class))
                .thenReturn(commitmentStore);
    }

    private TransactionBody validTxn() {
        return txnWith(ClprRegisterConnectorTransactionBody.newBuilder()
                .commitment(VALID_COMMITMENT)
                .build());
    }

    private TransactionBody txnWith(final ClprRegisterConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprRegisterConnector(op)
                .build();
    }
}
```

- [ ] **Step 2: Run the tests (expect compile failure — new handler not written yet)**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprRegisterConnectorHandlerTest" 2>&1 | tail -30
```

Expected: compile errors because `ClprRegisterConnectorHandler` still uses old API.

- [ ] **Step 3: Rewrite ClprRegisterConnectorHandler**

Replace the entire handler:

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_REGISTER_CONNECTOR} transactions.
 *
 * <p>Phase 1 (Commit): stores the commitment hash permissionlessly.
 * The caller must follow up with {@code completeConnector} to finalize registration.
 */
@Singleton
public class ClprRegisterConnectorHandler extends AbstractClprHandler {

    @Inject
    public ClprRegisterConnectorHandler() {}

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprRegisterConnectorOrThrow();
        validateTruePreCheck(op.commitment().length() == COMMITMENT_LENGTH, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprRegisterConnectorOrThrow();
        final var commitmentStore =
                context.storeFactory().writableStore(WritablePendingConnectorCommitmentStore.class);
        commitmentStore.put(op.commitment());
    }
}
```

- [ ] **Step 4: Run tests (expect pass)**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprRegisterConnectorHandlerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprRegisterConnectorHandler.java \
        hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprRegisterConnectorHandlerTest.java
git commit -m "feat(clpr): rewrite ClprRegisterConnectorHandler as commit-only, permissionless"
```

---

## Task 7: Create ClprCompleteConnectorHandler

**Files:**
- Create: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectorHandler.java`
- Create: `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprCompleteConnectorHandlerTest.java`

- [ ] **Step 1: Write failing tests**

Create `ClprCompleteConnectorHandlerTest.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTION_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTIONS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCompleteConnectorTransactionBody;
import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.hapi.node.state.clpr.ClprConnection;
import com.hedera.hapi.node.state.clpr.ClprConnectionStatus;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteConnectorHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.security.MessageDigest;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCompleteConnectorHandlerTest {

    // 0x000000000000000000000000000000000000016e as 20 bytes
    static final byte[] CLPR_SERVICE_ADDRESS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
    };

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final AccountID STAKING_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(803).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(2001).build();
    private static final AccountID CONTRACT_ACCOUNT_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(2001).build();
    private static final Key ADMIN_KEY =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    private static final long MIN_LOCKED_STAKE = 100_000_000L;
    private static final long VALID_STAKE = 200_000_000L;

    // Deterministic 32-byte private key for secp256k1 tests
    private static final byte[] SECRET_KEY = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };

    private static final Bytes CONNECTION_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });
    private static final Bytes SALT = Bytes.wrap(new byte[32]);

    @Mock private PureChecksContext pureChecksContext;
    @Mock private HandleContext handleContext;
    @Mock private StoreFactory storeFactory;
    @Mock private WritableStates writableStates;
    @Mock private ReadableAccountStore accountStore;
    @Mock private TokenServiceApi tokenServiceApi;

    private ClprCompleteConnectorHandler subject;
    private WritableConnectorStore connectorStore;
    private WritablePendingConnectorCommitmentStore commitmentStore;

    private byte[] ecdsaPublicKey64;
    private Bytes derivedConnectorId;
    private Bytes ecdsaCommitment;
    private Bytes ecdsaSignature;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        final EntityIdFactory idFactory = new AppEntityIdFactory(config);
        subject = new ClprCompleteConnectorHandler(idFactory);

        ecdsaPublicKey64 = deriveEcdsaPublicKey(SECRET_KEY);
        derivedConnectorId = computeConnectorId(
                CONNECTION_ID.toByteArray(), ecdsaPublicKey64, SALT.toByteArray());
        ecdsaCommitment = computeCommitment(derivedConnectorId.toByteArray(), ecdsaPublicKey64);
        ecdsaSignature = signEcdsa(SECRET_KEY, derivedConnectorId.toByteArray(), CLPR_SERVICE_ADDRESS);

        // Connectors state
        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);

        // Pending connector commitments state
        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID, "ClprService:PENDING_CONNECTOR_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingConnectorCommitmentStore(writableStates);

        // Connections state (read-only stub via readable)
        final var writableConnections = MapWritableKVState.<ProtoBytes, ClprConnection>builder(
                        CONNECTIONS_STATE_ID, "ClprService:CONNECTIONS")
                .build();
        final var activeConnection = ClprConnection.newBuilder()
                .connectionId(CONNECTION_ID)
                .status(ClprConnectionStatus.ACTIVE)
                .build();
        writableConnections.put(new ProtoBytes(CONNECTION_ID), activeConnection);
        lenient()
                .when(writableStates.<ProtoBytes, ClprConnection>get(CONNECTIONS_STATE_ID))
                .thenReturn(writableConnections);
    }

    // ========== pureChecks ==========

    @Test
    @DisplayName("should reject when connector_id is not 32 bytes")
    void rejectsWrongConnectorIdLength() throws PreCheckException {
        final var op = validOpBuilder()
                .connectorId(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when connection_id is not 32 bytes")
    void rejectsWrongConnectionIdLength() throws PreCheckException {
        final var op = validOpBuilder()
                .connectionId(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when salt is not 32 bytes")
    void rejectsWrongSaltLength() throws PreCheckException {
        final var op = validOpBuilder()
                .salt(Bytes.wrap(new byte[16]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when ecdsa public_key is not 64 bytes")
    void rejectsWrongEcdsaKeyLength() throws PreCheckException {
        final var op = validOpBuilder()
                .publicKey(Bytes.wrap(new byte[33]))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when signature is not 64 bytes")
    void rejectsWrongSignatureLength() throws PreCheckException {
        final var op = validOpBuilder()
                .signature(Bytes.wrap(new byte[32]))
                .build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject missing connector_contract")
    void rejectsMissingContract() throws PreCheckException {
        final var op = validOpBuilder().connectorContract((ContractID) null).build();
        given(pureChecksContext.body()).willReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with valid input")
    void passesWithValidInput() throws PreCheckException {
        given(pureChecksContext.body()).willReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    // ========== handle ==========

    @Test
    @DisplayName("should reject when CLPR not enabled")
    void rejectsWhenClprNotEnabled() {
        commitmentStore.put(ecdsaCommitment);
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when commitment not found")
    void rejectsWhenCommitmentNotFound() {
        // do NOT put commitment
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    @DisplayName("should reject when connectorId does not match re-derivation")
    void rejectsWhenConnectorIdMismatch() {
        // commitment is present but connectorId in the op is wrong
        final var wrongConnectorId = Bytes.wrap(new byte[32]); // all zeros != derived
        final var wrongCommitment = computeCommitment(wrongConnectorId.toByteArray(), ecdsaPublicKey64);
        commitmentStore.put(wrongCommitment);
        final var op = validOpBuilder()
                .connectorId(wrongConnectorId)
                .build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    @DisplayName("should reject when connection does not exist")
    void rejectsWhenConnectionNotFound() {
        commitmentStore.put(ecdsaCommitment);
        final var missingConnectionId = Bytes.wrap(new byte[32]); // not in state
        final var connIdForMissing = computeConnectorId(
                missingConnectionId.toByteArray(), ecdsaPublicKey64, SALT.toByteArray());
        final var commitmentForMissing = computeCommitment(connIdForMissing.toByteArray(), ecdsaPublicKey64);
        commitmentStore.put(commitmentForMissing);
        final var sig = signEcdsa(SECRET_KEY, connIdForMissing.toByteArray(), CLPR_SERVICE_ADDRESS);
        final var op = validOpBuilder()
                .connectorId(connIdForMissing)
                .connectionId(missingConnectionId)
                .signature(sig)
                .build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTION_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when connector already exists")
    void rejectsWhenConnectorAlreadyExists() {
        commitmentStore.put(ecdsaCommitment);
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(derivedConnectorId)
                .connectionId(CONNECTION_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                .build());
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("should reject when signature is invalid")
    void rejectsInvalidSignature() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        final var badSig = Bytes.wrap(new byte[64]); // all zeros
        final var op = validOpBuilder().signature(badSig).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    @DisplayName("should reject when connector_contract is not a deployed smart contract")
    void rejectsWhenContractNotFound() {
        commitmentStore.put(ecdsaCommitment);
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(null);
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CONNECTOR_CONTRACT));
    }

    @Test
    @DisplayName("should reject when locked_stake is below minimum")
    void rejectsInsufficientStake() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        final var op = validOpBuilder().lockedStake(MIN_LOCKED_STAKE - 1).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INSUFFICIENT_STAKE));
    }

    @Test
    @DisplayName("should register connector, transfer stake, remove commitment")
    void registersConnectorSuccessfully() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        final var key = new ClprConnectorKey(CONNECTION_ID, derivedConnectorId);
        final var connector = connectorStore.getConnector(key);
        assertThat(connector).isNotNull();
        assertThat(connector.connectorId()).isEqualTo(derivedConnectorId);
        assertThat(connector.connectionId()).isEqualTo(CONNECTION_ID);
        assertThat(connector.connectorContract()).isEqualTo(CONTRACT_ID);
        assertThat(connector.lockedStake()).isEqualTo(VALID_STAKE);
        assertThat(connector.slashCount()).isZero();
        assertThat(commitmentStore.contains(ecdsaCommitment)).isFalse();
    }

    // ========== helpers ==========

    private ClprCompleteConnectorTransactionBody.Builder validOpBuilder() {
        return ClprCompleteConnectorTransactionBody.newBuilder()
                .connectorId(derivedConnectorId)
                .publicKey(Bytes.wrap(ecdsaPublicKey64))
                .signature(ecdsaSignature)
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .salt(SALT)
                .connectionId(CONNECTION_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE);
    }

    private TransactionBody validTxn() {
        return txnWith(validOpBuilder().build());
    }

    private TransactionBody txnWith(final ClprCompleteConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCompleteConnector(op)
                .build();
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .withValue("clpr.minLockedStake", MIN_LOCKED_STAKE)
                .withValue("clpr.stakingAccount", 803L)
                .getOrCreateConfig();
        setupHandleContext(txn, config);
    }

    private void setupHandleContext(final TransactionBody txn, final Configuration config) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class))
                .thenReturn(commitmentStore);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient().when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
        // Wire up the connection store so requireConnection can resolve CONNECTION_ID
        lenient()
                .when(storeFactory.readableStore(
                        com.hedera.node.app.service.clpr.ReadableConnectionStore.class))
                .thenReturn(new com.hedera.node.app.service.clpr.impl.ReadableConnectionStoreImpl(
                        writableStates));
    }

    private void setupSmartContractMock() {
        final var smartContract = Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(true)
                .build();
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(smartContract);
    }

    // ---- crypto helpers ----

    static Bytes computeConnectorId(
            final byte[] connectionId, final byte[] pubKey, final byte[] salt) {
        final var preimage = new byte[connectionId.length + pubKey.length + salt.length];
        System.arraycopy(connectionId, 0, preimage, 0, connectionId.length);
        System.arraycopy(pubKey, 0, preimage, connectionId.length, pubKey.length);
        System.arraycopy(salt, 0, preimage, connectionId.length + pubKey.length, salt.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    static Bytes computeCommitment(final byte[] connectorId, final byte[] pubKey) {
        final var preimage = new byte[connectorId.length + pubKey.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(pubKey, 0, preimage, connectorId.length, pubKey.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    static Bytes signEcdsa(
            final byte[] secretKey, final byte[] connectorId, final byte[] serviceAddress) {
        final var msgPreimage = new byte[connectorId.length + serviceAddress.length];
        System.arraycopy(connectorId, 0, msgPreimage, 0, connectorId.length);
        System.arraycopy(serviceAddress, 0, msgPreimage, connectorId.length, serviceAddress.length);
        final byte[] msgHash = MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(msgPreimage)).toByteArray();

        final var nativeOutput = LibSecp256k1.secp256k1_ecdsa_sign(secretKey, msgHash);
        final byte[] sig = new byte[64];
        System.arraycopy(nativeOutput, 0, sig, 0, 64);
        return Bytes.wrap(sig);
    }

    static byte[] deriveEcdsaPublicKey(final byte[] secretKey) {
        return LibSecp256k1.secp256k1_ecdsa_pubkey(secretKey);
    }
}
```

- [ ] **Step 2: Run tests (expect compile failure)**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprCompleteConnectorHandlerTest" 2>&1 | tail -20
```

Expected: compile error — `ClprCompleteConnectorHandler` does not exist yet.

- [ ] **Step 3: Create ClprCompleteConnectorHandler.java**

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableConnectionStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;

/**
 * Handler for {@link HederaFunctionality#CLPR_COMPLETE_CONNECTOR} transactions.
 *
 * <p>Phase 2 (Reveal): validates the commitment, re-derives the connectorId, verifies
 * the signature, then creates the Connector keyed by (connectionId, connectorId).
 */
@Singleton
public final class ClprCompleteConnectorHandler extends AbstractClprHandler {

    // 0x000000000000000000000000000000000000016e as 20 bytes
    private static final byte[] CLPR_SERVICE_ADDRESS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
    };

    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprCompleteConnectorHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprCompleteConnectorOrThrow();
        validateTruePreCheck(op.connectorId().length() == CONNECTION_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.connectionId().length() == CONNECTION_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.salt().length() == CONNECTION_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.signature().length() == SIGNATURE_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasConnectorContract(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasAdminKey(), INVALID_TRANSACTION_BODY);

        final var expectedKeyLength =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> ECDSA_UNCOMPRESSED_KEY_LENGTH;
                    case ED25519 -> ED25519_KEY_LENGTH;
                    default -> throw new PreCheckException(INVALID_TRANSACTION_BODY);
                };
        validateTruePreCheck(op.publicKey().length() == expectedKeyLength, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprCompleteConnectorOrThrow();
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var storeFactory = context.storeFactory();

        final var commitmentStore = storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class);
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var connectionStore = storeFactory.readableStore(ReadableConnectionStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        final var pubKeyBytes = op.publicKey().toByteArray();
        final var saltBytes = op.salt().toByteArray();
        final var connectionIdBytes = op.connectionId().toByteArray();

        // 1. Re-derive connectorId and check it matches submitted value
        final var derivedConnectorId = deriveConnectorId(connectionIdBytes, pubKeyBytes, saltBytes);
        final var submittedConnectorId = op.connectorId();
        final var expectedCommitment = computeCommitment(derivedConnectorId.toByteArray(), pubKeyBytes);

        // 2. Check commitment matches (also validates re-derivation implicitly)
        if (!derivedConnectorId.equals(submittedConnectorId)
                || !commitmentStore.contains(expectedCommitment)) {
            throw new HandleException(CLPR_COMMITMENT_MISMATCH);
        }

        // 3. Verify the referenced connection exists
        requireConnection(connectionStore, op.connectionId());

        // 4. Check connector does not already exist
        final var connectorKey = new ClprConnectorKey(op.connectionId(), submittedConnectorId);
        validateTrue(connectorStore.getConnector(connectorKey) == null, CLPR_CONNECTOR_ALREADY_EXISTS);

        // 5. Verify signature over keccak256(connectorId || clprServiceAddress)
        final var sigMsgHash = computeSignatureMessage(derivedConnectorId.toByteArray());
        final var signatureType =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> SignatureType.ECDSA_SECP256K1;
                    case ED25519 -> SignatureType.ED25519;
                    default -> throw new HandleException(INVALID_TRANSACTION_BODY);
                };
        final var isValid = CryptographyProvider.getInstance()
                .verifySync(sigMsgHash, op.signature().toByteArray(), pubKeyBytes, signatureType);
        if (!isValid) {
            throw new HandleException(CLPR_INVALID_SIGNATURE);
        }

        // 6. Verify connector_contract is a deployed smart contract
        final var contractAccount = accountStore.getContractById(op.connectorContractOrThrow());
        validateTrue(contractAccount != null, CLPR_INVALID_CONNECTOR_CONTRACT);

        // 7. Verify locked_stake meets minimum
        validateTrue(op.lockedStake() >= clprConfig.minLockedStake(), CLPR_INSUFFICIENT_STAKE);

        // 8. Transfer locked_stake from payer to CLPR staking account
        final var stakingAccountId = entityIdFactory.newAccountId(clprConfig.stakingAccount());
        storeFactory.serviceApi(TokenServiceApi.class)
                .transferFromTo(context.payer(), stakingAccountId, op.lockedStake());

        // 9. Store connector and remove consumed commitment
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(submittedConnectorId)
                .connectionId(op.connectionId())
                .connectorContract(op.connectorContractOrThrow())
                .adminKey(op.adminKeyOrThrow())
                .lockedStake(op.lockedStake())
                .slashCount(0)
                .build());
        commitmentStore.remove(expectedCommitment);
    }

    private static Bytes deriveConnectorId(
            final byte[] connectionId, final byte[] pubKey, final byte[] salt) {
        final var preimage = new byte[connectionId.length + pubKey.length + salt.length];
        System.arraycopy(connectionId, 0, preimage, 0, connectionId.length);
        System.arraycopy(pubKey, 0, preimage, connectionId.length, pubKey.length);
        System.arraycopy(salt, 0, preimage, connectionId.length + pubKey.length, salt.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    private static Bytes computeCommitment(final byte[] connectorId, final byte[] pubKey) {
        final var preimage = new byte[connectorId.length + pubKey.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(pubKey, 0, preimage, connectorId.length, pubKey.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    private static byte[] computeSignatureMessage(final byte[] connectorId) {
        final var preimage = new byte[connectorId.length + CLPR_SERVICE_ADDRESS.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(CLPR_SERVICE_ADDRESS, 0, preimage, connectorId.length, CLPR_SERVICE_ADDRESS.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage)).toByteArray();
    }
}
```

- [ ] **Step 4: Run tests (expect pass)**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprCompleteConnectorHandlerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectorHandler.java \
        hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprCompleteConnectorHandlerTest.java
git commit -m "feat(clpr): add ClprCompleteConnectorHandler (reveal phase)"
```

---

## Task 8: Update ClprDeregisterConnectorHandler

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprDeregisterConnectorHandler.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprDeregisterConnectorHandlerTest.java`

- [ ] **Step 1: Update ClprDeregisterConnectorHandler**

Replace the entire file:

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_UNAUTHORIZED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_DEREGISTER_CONNECTOR} transactions.
 *
 * <p>Removes a Connector from the CLPR Service and returns any locked stake
 * to the explicitly specified stake_recipient. Requires the connector's admin_key to sign.
 */
@Singleton
public class ClprDeregisterConnectorHandler extends AbstractClprHandler {

    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprDeregisterConnectorHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprDeregisterConnectorOrThrow();
        validateTruePreCheck(op.connectionId().length() == CONNECTION_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.connectorId().length() == CONNECTION_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasStakeRecipient(), INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprDeregisterConnectorOrThrow();
        final var connectorStore = context.createStore(ReadableConnectorStore.class);
        final var key = new ClprConnectorKey(op.connectionId(), op.connectorId());
        final var connector = connectorStore.getConnector(key);
        validateTruePreCheck(connector != null, CLPR_CONNECTOR_NOT_FOUND);
        context.requireKeyOrThrow(connector.adminKeyOrElse(null), CLPR_CONNECTOR_UNAUTHORIZED);
        context.requireKeyOrThrow(op.stakeRecipientOrThrow(), CLPR_CONNECTOR_UNAUTHORIZED);
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprDeregisterConnectorOrThrow();
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var key = new ClprConnectorKey(op.connectionId(), op.connectorId());
        final var storeFactory = context.storeFactory();
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var connector = connectorStore.getConnector(key);
        validateTrue(connector != null, CLPR_CONNECTOR_NOT_FOUND);

        final var lockedStake = connector.lockedStake();
        if (lockedStake > 0) {
            final var stakingAccountId = entityIdFactory.newAccountId(clprConfig.stakingAccount());
            storeFactory.serviceApi(TokenServiceApi.class)
                    .transferFromTo(stakingAccountId, op.stakeRecipientOrThrow(), lockedStake);
        }

        connectorStore.remove(key);
    }
}
```

- [ ] **Step 2: Update ClprDeregisterConnectorHandlerTest**

Replace the entire test file — adapt the existing tests to use `connectionId` + `connectorId` instead of `sourceConnectorAddress`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprDeregisterConnectorTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprDeregisterConnectorHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprDeregisterConnectorHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID STAKE_RECIPIENT_ID =
            AccountID.newBuilder().accountNum(9999).build();
    private static final AccountID STAKING_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(803).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(2001).build();
    private static final Key ADMIN_KEY =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    private static final Bytes CONNECTION_ID = Bytes.wrap(new byte[32]);
    private static final Bytes CONNECTOR_ID = Bytes.wrap(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    });
    private static final long VALID_STAKE = 200_000_000L;

    @Mock private PureChecksContext pureChecksContext;
    @Mock private HandleContext handleContext;
    @Mock private StoreFactory storeFactory;
    @Mock private WritableStates writableStates;
    @Mock private TokenServiceApi tokenServiceApi;

    private ClprDeregisterConnectorHandler subject;
    private WritableConnectorStore connectorStore;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        final EntityIdFactory idFactory = new AppEntityIdFactory(config);
        subject = new ClprDeregisterConnectorHandler(idFactory);

        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);
    }

    @Test
    @DisplayName("should reject when connection_id is not 32 bytes")
    void rejectsWrongConnectionIdLength() {
        final var op = ClprDeregisterConnectorTransactionBody.newBuilder()
                .connectionId(Bytes.wrap(new byte[16]))
                .connectorId(CONNECTOR_ID)
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when connector_id is not 32 bytes")
    void rejectsWrongConnectorIdLength() {
        final var op = ClprDeregisterConnectorTransactionBody.newBuilder()
                .connectionId(CONNECTION_ID)
                .connectorId(Bytes.wrap(new byte[16]))
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        putConnector();
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when connector not found")
    void rejectsWhenConnectorNotFound() {
        // connector not in state
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_NOT_FOUND));
    }

    @Test
    @DisplayName("should deregister connector and transfer stake")
    void deregistersConnectorAndTransfersStake() {
        putConnector();
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        final var key = new ClprConnectorKey(CONNECTION_ID, CONNECTOR_ID);
        assertThat(connectorStore.getConnector(key)).isNull();
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT_ID, STAKE_RECIPIENT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should deregister connector without transfer when stake is zero")
    void deregistersConnectorWithZeroStake() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .connectionId(CONNECTION_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(0L)
                .build());
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        assertThat(connectorStore.getConnector(new ClprConnectorKey(CONNECTION_ID, CONNECTOR_ID))).isNull();
    }

    private void putConnector() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .connectionId(CONNECTION_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                .build());
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .withValue("clpr.stakingAccount", 803L)
                .getOrCreateConfig();
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
        lenient().when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
    }

    private TransactionBody validTxn() {
        return txnWith(ClprDeregisterConnectorTransactionBody.newBuilder()
                .connectionId(CONNECTION_ID)
                .connectorId(CONNECTOR_ID)
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build());
    }

    private TransactionBody txnWith(final ClprDeregisterConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprDeregisterConnector(op)
                .build();
    }
}
```

- [ ] **Step 3: Run tests**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprDeregisterConnectorHandlerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprDeregisterConnectorHandler.java \
        hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprDeregisterConnectorHandlerTest.java
git commit -m "feat(clpr): update ClprDeregisterConnectorHandler for (connectionId, connectorId) key"
```

---

## Task 9: Wire up ClprCompleteConnectorHandler in app infrastructure

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprHandlers.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/IngestChecker.java`
- Modify: `hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/ApiPermissionConfig.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java`

- [ ] **Step 1: Add ClprCompleteConnectorHandler to ClprHandlers.java**

Add field, constructor parameter, and getter mirroring the existing `clprCompleteConnectionHandler` pattern:

```java
// Add field
private final ClprCompleteConnectorHandler clprCompleteConnectorHandler;

// Add to constructor parameters (after clprRegisterConnectorHandler):
@NonNull final ClprCompleteConnectorHandler clprCompleteConnectorHandler,

// Add to constructor body:
this.clprCompleteConnectorHandler = Objects.requireNonNull(
        clprCompleteConnectorHandler, "clprCompleteConnectorHandler must not be null");

// Add getter:
public ClprCompleteConnectorHandler clprCompleteConnectorHandler() {
    return clprCompleteConnectorHandler;
}
```

Add the import at the top of the file:

```java
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteConnectorHandler;
```

- [ ] **Step 2: Add to TransactionHandlers.java**

In `TransactionHandlers.java`, find the record that holds handler references. Add:

```java
@NonNull ClprCompleteConnectorHandler clprCompleteConnectorHandler,
```

after the `clprRegisterConnectorHandler` parameter. Add the import:

```java
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteConnectorHandler;
```

- [ ] **Step 3: Add dispatch entries to TransactionDispatcher.java**

Add import at top:

```java
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
```

In the permissionless-allowed set (around line 211, where `CLPR_REGISTER_CONNECTION` and `CLPR_COMPLETE_CONNECTION` are listed):

```java
CLPR_COMPLETE_CONNECTOR,
```

In the dispatch switch (around line 320-321):

```java
case CLPR_COMPLETE_CONNECTOR -> handlers.clprCompleteConnectorHandler();
```

- [ ] **Step 4: Add to ServiceScopeLookup.java**

Around line 131 where `CLPR_REGISTER_CONNECTOR` is listed:

```java
CLPR_COMPLETE_CONNECTOR,
```

Add import:

```java
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
```

- [ ] **Step 5: Add to IngestChecker.java**

Around line 126-128 where `CLPR_COMPLETE_CONNECTION` and `CLPR_REGISTER_CONNECTOR` are listed:

```java
CLPR_COMPLETE_CONNECTOR,
```

Add import:

```java
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
```

- [ ] **Step 6: Add to ApiPermissionConfig.java**

Add a field in the record (after `clprRegisterConnector`):

```java
@ConfigProperty(defaultValue = "0-*") PermissionedAccountsRange clprCompleteConnector,
```

Add the permission mapping (after the `CLPR_REGISTER_CONNECTOR` line):

```java
permissionKeys.put(CLPR_COMPLETE_CONNECTOR, c -> c.clprCompleteConnector);
```

Add import:

```java
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
```

- [ ] **Step 7: Add WritablePendingConnectorCommitmentStore to WritableStoreFactory.java**

After the existing `WritablePendingCommitmentStore` entry (around line 141-143):

```java
WritablePendingConnectorCommitmentStore.class,
new StoreEntry(
        ClprService.NAME,
        (states, entityCounters) -> new WritablePendingConnectorCommitmentStore(states)));
```

Add imports at the top of the file:

```java
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
```

- [ ] **Step 8: Build to verify no compile errors**

```bash
cd hedera-node
./gradlew :hedera-app:compileJava :hedera-clpr-service-impl:compileJava 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 9: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprHandlers.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionHandlers.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/services/ServiceScopeLookup.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/ingest/IngestChecker.java \
        hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/ApiPermissionConfig.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/WritableStoreFactory.java
git commit -m "feat(clpr): wire ClprCompleteConnectorHandler into app infrastructure"
```

---

## Task 10: Update ClprServiceApiImpl connector lookup (sendMessage)

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/ClprServiceApiImplTest.java`

`ClprServiceApiImpl.sendMessage` looks up a connector using `new ClprConnectorKey(connectorContract)` (the old global key). In the new design, the connector is identified by `(connectionId, connectorId)` both of which are parameters to `sendMessage`.

- [ ] **Step 1: Find the sendMessage signature in ClprServiceApiImpl**

The method at line 58 takes `connectorContract` as a `Bytes` parameter. After the proto change, the message field carrying the connector identity is the `connectorId` (bytes), and `connectionId` is already passed. Update:

```java
// Old (line 84):
final var connectorKey = new ClprConnectorKey(connectorContract);

// New:
final var connectorKey = new ClprConnectorKey(connectionId, connectorContract);
```

Note: in the existing code, the `connectorContract` parameter name actually holds the connector identifier passed by `sendMessage` callers. It should be renamed to `connectorId` for clarity, but the type is `Bytes` in both cases. Rename the parameter from `connectorContract` to `connectorId` wherever it appears in this method.

Also update `countConnectorMessages` (line 198): change the equality check from:

```java
if (sourceConnectorAddress.equals(value.payload().message().connectorId())) {
```

to use the passed-in `connectorId` (already the same variable after renaming).

- [ ] **Step 2: Update ClprServiceApiImplTest**

In the test, wherever `ClprConnectorKey` is constructed with a single `Bytes` argument (old `sourceConnectorAddress`), change to `new ClprConnectorKey(connectionId, connectorId)`. Update the test helper that puts a connector into the store to use the new `ClprConnector` fields (`connectionId`, `connectorId`) instead of `sourceConnectorAddress`.

- [ ] **Step 3: Run tests**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprServiceApiImplTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java \
        hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/ClprServiceApiImplTest.java
git commit -m "fix(clpr): update sendMessage connector lookup to (connectionId, connectorId)"
```

---

## Task 11: Update ClprSubmitBundleHandler connector lookup

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprSubmitBundleHandlerTest.java`

- [ ] **Step 1: Update connector lookup in ClprSubmitBundleHandler**

At line 321 (data message processing):

```java
// Old:
final var connectorKey = new ClprConnectorKey(dataMsg.connectorId());

// New (connectionId is available as the outer variable throughout the bundle processing loop):
final var connectorKey = new ClprConnectorKey(connectionId, dataMsg.connectorId());
```

At line 467 (response message processing — looking up the source connector for slashing):

```java
// Old:
final var sourceConnectorKey = new ClprConnectorKey(origDataMsg.connectorId());

// New:
final var sourceConnectorKey = new ClprConnectorKey(connectionId, origDataMsg.connectorId());
```

- [ ] **Step 2: Update ClprSubmitBundleHandlerTest**

In the test helpers that set up connectors in state, change from `new ClprConnectorKey(sourceAddr)` / `connector.sourceConnectorAddress(...)` to `new ClprConnectorKey(connectionId, connectorId)` / `connector.connectionId(...)` / `connector.connectorId(...)`.

- [ ] **Step 3: Run tests**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test --tests "*.ClprSubmitBundleHandlerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java \
        hedera-node/hedera-clpr-service-impl/src/test/java/com/hedera/node/app/service/clpr/impl/test/handlers/ClprSubmitBundleHandlerTest.java
git commit -m "fix(clpr): update bundle handler connector lookup to (connectionId, connectorId)"
```

---

## Task 12: Update spec document §3.3.1

**Files:**
- Modify: `hedera-node/docs/design/services/clpr-service/clpr-service.md`

- [ ] **Step 1: Replace §3.3.1 Connectors section**

Find the heading `### 3.3.1 Connectors` (around line 1087). The section ends just before `### 3.3.2 Sending a Message` (around line 1106). Replace everything between those two headings with the text from the upstream spec's §3.3.1 (lines 1109–1155 of `clpr-service.md`):

The old text to remove (lines 1087–1105 in the Hiero spec):

```
### 3.3.1 Connectors

A Connector is a separate entity ...

**Cross-ledger identity.** When a Connector registers on the destination ledger, it specifies the address of its
counterpart on the source ledger. The CLPR Service maintains an index mapping
`(Connection, source_connector_address) → local_connector` so that when a message arrives with a `connector_id`
stamped on the source chain, the destination can resolve it to the local Connector that will pay for execution. On
ledger pairs that share an address format (e.g., Hiero and Ethereum both use EVM addresses), the source and destination
Connectors MAY share the same address — but this is a convenience, not a requirement. The explicit mapping is the
authoritative mechanism and works across any chain combination.
```

The new text to insert (copy verbatim from `../clpr-service.md` lines 1109–1155):

```markdown
### 3.3.1 Connectors

A Connector is a separate entity (a smart contract) that sits outside the CLPR Service but interacts with it. To create
a Connector, a party must specify which Connections it operates on, provide an initial balance of native tokens (to pay
for message handling when receiving messages), and lock a stake that can be slashed for misbehavior. The Connector also
specifies an admin authority that can top up funds, adjust settings, or shut it down.

A Connector must exist on **both** ledgers — one side authorizes and enqueues messages, the other side pays for their
execution on arrival, depending on the direction of message passing. The relationship is many-to-many: multiple
Connectors may serve the same Connection, and a single Connector may operate across multiple Connections.

**Connector ID derivation.** Each Connector is identified by a **Connector ID** (32 bytes) that is derived
per-connection from the operator's public key and an optional salt:

```

connectorId = keccak256(connectionId || pubKey || salt)

```

where `connectionId` is the specific connection this Connector is bound to, `pubKey` is the operator's full
public key in platform-specific encoding, and `salt` is an optional `bytes32` label for operators who need
multiple Connectors on the same connection (defaults to `bytes32(0)`). Because the formula is deterministic, the
Connector ID is identical on every ledger where the operator registers this Connector for this connection.
Operators and applications can compute it off-chain using `deriveConnectorId(connectionId, pubKey, salt)`.

**Connector registration.** Registration uses the same commit-reveal scheme as Connections:

- *Commit:* `keccak256(connectorId || pubKey)` — permissionless and idempotent.
- *Reveal:* The caller submits `(connectorId, pubKey, sig, salt, connectionId)`. The service re-derives the
  expected Connector ID from those inputs, checks the commitment, and validates the signature (which is scoped
  to the deployment address: `keccak256(connectorId || address(this))`). Only the private key holder can produce
  the required signature, so squatters cannot complete registration even if they commit.

**Per-connection scoping.** The storage lookup key is `(connectionId, connectorId)` — not a global namespace. A
squatter on a third ledger that the legitimate operator has not yet reached can register a Connector there, but
the registration only affects connections on that specific ledger. It does not interfere with the operator's
Connectors on any other connection, because each connection maintains its own independent Connector namespace.

**Cross-ledger identity.** When a Connector registers on the destination ledger, it specifies the Connection it
operates on. The CLPR Service maintains an index mapping
`(connectionId, connectorId) → local_connector` so that when a message arrives with a `connector_id`
stamped on the source chain, the destination can resolve it to the local Connector that will pay for execution.
Because the Connector ID is derived from the same public key and salt on every ledger, no explicit address
cross-referencing is required — the same derived ID works across any chain combination.

**Many-to-many preserved.** The relationship remains many-to-many: multiple Connectors may serve the same
Connection, and a single operator (keypair) can register the same Connector on multiple Connections by using
the same `pubKey` and `salt` with different `connectionId` values.
```

> 💡 **Hiero:** The signature in `completeConnector` is over `keccak256(connectorId || 0x000000000000000000000000000000000000016e)` where `0x16e` is the EVM address of the CLPR system contract — the Hiero equivalent of `address(this)` on EVM.

- [ ] **Step 2: Commit**

```bash
git add hedera-node/docs/design/services/clpr-service/clpr-service.md
git commit -m "docs(clpr): update §3.3.1 Connectors to match upstream commit-reveal design"
```

---

## Task 13: Full build and test pass

- [ ] **Step 1: Run all CLPR tests**

```bash
cd hedera-node/hedera-clpr-service-impl
./gradlew test 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Run broader build to catch any missed compile errors**

```bash
cd hedera-node
./gradlew :hedera-clpr-service-impl:build :hedera-app:compileJava 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Fix any remaining compile errors**

Common issues to look for:
- Any remaining `connector.sourceConnectorAddress()` calls → change to `connector.connectionId()` + `connector.connectorId()`
- Any remaining `new ClprConnectorKey(singleBytes)` → change to `new ClprConnectorKey(connectionId, connectorId)`
- Missing imports for `ClprCompleteConnectorHandler`

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "fix(clpr): resolve remaining compile errors from connector redesign"
```
