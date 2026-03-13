# CLPR Ethereum Implementation Specification

This document is the Ethereum-specific implementation specification for the CLPR (Cross Ledger Protocol). It maps every
construct from the [CLPR Protocol Specification](clpr-service-spec.md) to concrete Solidity contracts, storage layouts,
function signatures, events, and gas analyses. Engineers should use this document to break the work into epics and
issues.

For architectural rationale and design decisions, see the [CLPR Design Document](clpr-service.md). For the
cross-platform protocol specification, see the [CLPR Protocol Specification](clpr-service-spec.md).

---

## Notation

- References to the cross-platform spec use the form "Spec section X" (e.g., "Spec section 2.1").
- Gas estimates use the Cancun-era costs: SSTORE (cold) = 22,100; SSTORE (warm) = 5,000; SLOAD (cold) = 2,100;
  SLOAD (warm) = 100; SHA-256 precompile = 60 base + 12/word; CALLDATALOAD = 3; LOG = 375 + 375/topic + 8/byte.
- All ETH amounts are in wei unless otherwise noted.
- Solidity version: `^0.8.24` (via-IR pipeline, custom errors, user-defined value types).

---

# 1. Contract Architecture

## 1.1 Contract Topology

The CLPR Ethereum implementation consists of four contract categories:

```
ClprServiceProxy (EIP-1967 TransparentUpgradeableProxy)
  --> ClprService (implementation)
        |
        |--> IClprVerifier (one per Connection, externally deployed)
        |--> IClprConnectorAuth (one per Connector, externally deployed)
        |--> IClprApplication (one per target app, externally deployed)
```

| Contract              | Upgradeability       | Deployer                    | Trust Boundary                        |
|-----------------------|----------------------|-----------------------------|---------------------------------------|
| `ClprServiceProxy`    | EIP-1967 transparent | CLPR governance             | Governance multisig                   |
| `ClprService`         | Implementation       | CLPR governance             | Governance multisig                   |
| `ProxyAdmin`          | None                 | CLPR governance             | Governance multisig (TimelockController) |
| `IClprVerifier`       | Per-deployer choice  | Source ledger ecosystem     | Source ledger CLPR Service admin      |
| `IClprConnectorAuth`  | Per-deployer choice  | Connector operator          | Connector admin                       |
| `IClprApplication`    | Per-deployer choice  | Application developer       | Application admin                     |

## 1.2 Upgradeability Strategy

**EIP-1967 Transparent Proxy** is the chosen pattern for the `ClprService` contract:

- The proxy delegates all calls to the current implementation contract.
- Upgrades are gated by a `ProxyAdmin` owned by a `TimelockController` with a governance multisig as proposer.
- The timelock enforces a minimum delay (recommended: 48 hours for mainnet) between proposal and execution,
  giving the community time to audit upgrades.
- The `ProxyAdmin` is the only address that can call `upgradeTo()` on the proxy.

**Why not Diamond (EIP-2535)?** The Diamond pattern provides finer-grained upgradeability (per-function facets), but
introduces additional complexity in storage management, delegate-call routing, and auditing surface. The ClprService
contract, while large, has a bounded interface surface and benefits more from simplicity and auditability than from
per-function upgradeability. If the implementation exceeds the 24,576-byte contract size limit, the contract SHOULD be
split using a library-based architecture (internal libraries compiled into the implementation) rather than switching to
Diamond.

**Verifier contracts** MAY use upgradeable proxies at the discretion of the source ledger's ecosystem, subject to the
constraint in Spec section 8.8: the proxy's upgrade authority MUST be controlled by the source ledger's CLPR Service
admin. On Ethereum, this means the verifier proxy's `ProxyAdmin` must be owned by an address that the source ledger's
governance controls (e.g., via a cross-chain governance message, or by a designated key held by the source ledger's
admin).

## 1.3 Access Control

Access control uses OpenZeppelin's `AccessControl` with the following roles:

| Role                  | Grantable By         | Purpose                                                      |
|-----------------------|----------------------|--------------------------------------------------------------|
| `DEFAULT_ADMIN_ROLE`  | Governance multisig  | Grant/revoke other roles. Should be behind a timelock.       |
| `ADMIN_ROLE`          | `DEFAULT_ADMIN_ROLE` | `setLedgerConfiguration`, `severConnection`, `pauseConnection`, `resumeConnection`, `redactMessage` |
| `UPGRADER_ROLE`       | `DEFAULT_ADMIN_ROLE` | Reserved for future use if upgrade logic moves on-chain.     |

All permissionless functions (connection registration, endpoint registration, message sending, bundle submission,
misbehavior reporting) require no role -- only that the caller satisfies the function's specific preconditions.

---

# 2. Solidity Interface Definitions

## 2.1 IClprService

```solidity
// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

/// @title IClprService
/// @notice The main CLPR Service contract interface on Ethereum.
/// @dev Maps to Spec section 6 pseudo-API reference. All connection_id parameters
///      are bytes32 (keccak256 of the uncompressed secp256k1 public key).
interface IClprService {

    // =========================================================================
    // Configuration Management (Spec section 6.1)
    // =========================================================================

    /// @notice Set or update this ledger's local CLPR configuration.
    /// @dev Enqueues a ConfigUpdate Control Message on every active Connection.
    ///      Caller MUST have ADMIN_ROLE.
    /// @param configuration ABI-encoded ClprLedgerConfiguration struct.
    function setLedgerConfiguration(bytes calldata configuration) external;

    /// @notice Query this ledger's current CLPR configuration.
    /// @return configuration ABI-encoded ClprLedgerConfiguration struct.
    function getLedgerConfiguration() external view returns (bytes memory configuration);

    // =========================================================================
    // Connection Management (Spec section 6.2)
    // =========================================================================

    /// @notice Register a new Connection to a peer ledger. Permissionless.
    /// @dev The caller must provide a valid ECDSA_secp256k1 signature proving
    ///      control of the connectionId keypair.
    /// @param connectionId The Connection ID (keccak256 of uncompressed pubkey).
    /// @param ecdsaSignature ECDSA_secp256k1 signature over the registration data.
    ///        Signed payload: keccak256(abi.encodePacked(connectionId, verifierContract,
    ///        address(this), block.chainid)).
    /// @param verifierContract Address of the locally deployed verifier contract.
    /// @param zkProof ZK proof attesting to the peer ledger's configuration.
    /// @param seedEndpoints ABI-encoded array of ClprEndpoint structs.
    function registerConnection(
        bytes32 connectionId,
        bytes calldata ecdsaSignature,
        address verifierContract,
        bytes calldata zkProof,
        bytes calldata seedEndpoints
    ) external;

    /// @notice Update the verifier contract on an existing Connection. Permissionless.
    /// @param connectionId The Connection ID.
    /// @param verifierContract Address of the new verifier contract.
    /// @param zkProof Optional ZK proof for the recovery path. Pass empty bytes for local check.
    function updateConnectionVerifier(
        bytes32 connectionId,
        address verifierContract,
        bytes calldata zkProof
    ) external;

    /// @notice Recover a Connection's peer endpoint roster from a state proof. Permissionless.
    /// @param connectionId The Connection ID.
    /// @param proofBytes Proof bytes passed to the verifier's verifyEndpoints().
    function recoverEndpointRoster(
        bytes32 connectionId,
        bytes calldata proofBytes
    ) external;

    /// @notice Sever (permanently close) a Connection. ADMIN_ROLE only.
    /// @param connectionId The Connection ID.
    function severConnection(bytes32 connectionId) external;

    /// @notice Pause a Connection (temporarily halt outbound processing). ADMIN_ROLE only.
    /// @param connectionId The Connection ID.
    function pauseConnection(bytes32 connectionId) external;

    /// @notice Resume a paused Connection. ADMIN_ROLE only.
    /// @param connectionId The Connection ID.
    function resumeConnection(bytes32 connectionId) external;

    /// @notice Query a Connection's current state.
    /// @param connectionId The Connection ID.
    /// @return connection ABI-encoded Connection struct.
    function getConnection(bytes32 connectionId) external view returns (bytes memory connection);

    /// @notice Query a Connection's current outbound queue depth.
    /// @param connectionId The Connection ID.
    /// @return depth Current number of unacknowledged messages.
    /// @return max Maximum allowed queue depth.
    function getQueueDepth(bytes32 connectionId) external view returns (uint64 depth, uint32 max);

    // =========================================================================
    // Connector Management (Spec section 6.3)
    // =========================================================================

    /// @notice Register a Connector on a Connection. Permissionless but requires ETH.
    /// @dev msg.value is split between initial_balance and stake per the parameters.
    /// @param connectionId The Connection ID.
    /// @param sourceConnectorAddress Address of the counterpart Connector on the source ledger.
    /// @param connectorContract Address of the Connector's IClprConnectorAuth contract.
    /// @param initialBalance Funds allocated for message execution (wei).
    /// @param stake Funds locked against misbehavior (wei). Must meet minimum.
    function registerConnector(
        bytes32 connectionId,
        bytes calldata sourceConnectorAddress,
        address connectorContract,
        uint256 initialBalance,
        uint256 stake
    ) external payable;

    /// @notice Add funds to a Connector's balance. Connector admin only.
    /// @param connectionId The Connection ID.
    /// @param sourceConnectorAddress Source-chain Connector address.
    function topUpConnector(
        bytes32 connectionId,
        bytes calldata sourceConnectorAddress
    ) external payable;

    /// @notice Withdraw surplus funds from a Connector's balance. Connector admin only.
    /// @param connectionId The Connection ID.
    /// @param sourceConnectorAddress Source-chain Connector address.
    /// @param amount Amount to withdraw (wei).
    function withdrawConnectorBalance(
        bytes32 connectionId,
        bytes calldata sourceConnectorAddress,
        uint256 amount
    ) external;

    /// @notice Deregister a Connector and return remaining funds and stake. Connector admin only.
    /// @param connectionId The Connection ID.
    /// @param sourceConnectorAddress Source-chain Connector address.
    function deregisterConnector(
        bytes32 connectionId,
        bytes calldata sourceConnectorAddress
    ) external;

    /// @notice Query a Connector's current state.
    /// @param connectionId The Connection ID.
    /// @param sourceConnectorAddress Source-chain Connector address.
    /// @return connector ABI-encoded Connector struct.
    function getConnector(
        bytes32 connectionId,
        bytes calldata sourceConnectorAddress
    ) external view returns (bytes memory connector);

    // =========================================================================
    // Messaging (Spec section 6.4)
    // =========================================================================

    /// @notice Send a cross-ledger message via a Connector. Permissionless.
    /// @param connectionId The Connection ID.
    /// @param connectorContract Address of the Connector's authorization contract on this ledger.
    /// @param targetApplication Destination application address (bytes, chain-agnostic).
    /// @param messageData Opaque application payload.
    /// @return messageId The assigned sequence number for this message.
    function sendMessage(
        bytes32 connectionId,
        address connectorContract,
        bytes calldata targetApplication,
        bytes calldata messageData
    ) external returns (uint64 messageId);

    /// @notice Submit a bundle received from a peer endpoint for on-chain processing.
    /// @dev Permissionless (typically called by endpoint nodes).
    /// @param connectionId The Connection ID.
    /// @param proofBytes Opaque proof bytes from ClprSyncPayload.
    /// @param remoteEndpointSignature Endpoint signature for attribution.
    /// @param remoteEndpointPublicKey Public key of the remote endpoint.
    function submitBundle(
        bytes32 connectionId,
        bytes calldata proofBytes,
        bytes calldata remoteEndpointSignature,
        bytes calldata remoteEndpointPublicKey
    ) external;

    /// @notice Redact a message from the outbound queue. ADMIN_ROLE only.
    /// @param connectionId The Connection ID.
    /// @param messageId The message to redact.
    function redactMessage(bytes32 connectionId, uint64 messageId) external;

    // =========================================================================
    // Endpoint Management (Spec section 6.5)
    // =========================================================================

    /// @notice Register as an endpoint for a Connection. Permissionless; bond required.
    /// @dev msg.value is the bond amount. Must meet minimum bond requirement.
    /// @param connectionId The Connection ID.
    /// @param endpoint ABI-encoded ClprEndpoint struct. account_id MUST match msg.sender.
    function registerEndpoint(
        bytes32 connectionId,
        bytes calldata endpoint
    ) external payable;

    /// @notice Deregister an endpoint from a Connection and return the bond.
    /// @dev Caller must be the endpoint's account.
    /// @param connectionId The Connection ID.
    function deregisterEndpoint(bytes32 connectionId) external;

    // =========================================================================
    // Misbehavior Reporting (Spec section 6.6)
    // =========================================================================

    /// @notice Submit a misbehavior report against a remote endpoint. Permissionless.
    /// @param connectionId The Connection ID.
    /// @param report ABI-encoded ClprMisbehaviorReport.
    function reportMisbehavior(
        bytes32 connectionId,
        bytes calldata report
    ) external;
}
```

## 2.2 IClprVerifier

```solidity
/// @title IClprVerifier
/// @notice Interface for verifier contracts deployed per-Connection.
/// @dev Implements Spec section 3.1 verification interfaces.
///      Verifier contracts are deployed by the source ledger's ecosystem
///      and endorsed via ApprovedVerifiers in the source ledger's configuration.
interface IClprVerifier {

    /// @notice Verify a configuration proof and return the verified configuration.
    /// @dev Used during registerConnection and updateConnectionVerifier (ZK path).
    ///      MUST revert if verification fails. SHOULD fail fast on malformed inputs.
    /// @param proofBytes Opaque proof bytes from the source ledger's proof system.
    /// @return configuration ABI-encoded ClprLedgerConfiguration.
    function verifyConfig(bytes calldata proofBytes)
        external view returns (bytes memory configuration);

    /// @notice Verify a bundle proof and return verified queue metadata and messages.
    /// @dev Used during submitBundle. MUST revert if verification fails.
    ///      SHOULD fail fast on obviously malformed inputs before expensive crypto.
    /// @param proofBytes Opaque proof bytes from the source ledger's proof system.
    /// @return metadata ABI-encoded ClprQueueMetadata.
    /// @return messages ABI-encoded array of ClprMessagePayload.
    function verifyBundle(bytes calldata proofBytes)
        external view returns (bytes memory metadata, bytes memory messages);

    /// @notice Verify an endpoint roster proof and return verified endpoints.
    /// @dev Used during recoverEndpointRoster. MUST revert if verification fails.
    /// @param proofBytes Opaque proof bytes from the source ledger's proof system.
    /// @return endpoints ABI-encoded array of ClprEndpoint.
    function verifyEndpoints(bytes calldata proofBytes)
        external view returns (bytes memory endpoints);
}
```

## 2.3 IClprConnectorAuth

```solidity
/// @title IClprConnectorAuth
/// @notice Interface for Connector authorization contracts.
/// @dev Implements Spec section 3.2 Connector Authorization Interface.
///      Called by the ClprService when an application submits a message.
///      MUST NOT modify ClprService state. MUST be deterministic.
interface IClprConnectorAuth {

    /// @notice Authorize a message for cross-ledger delivery.
    /// @dev By returning true, the Connector commits that its counterpart on the
    ///      destination ledger has sufficient funds for execution.
    ///      Called with a gas stipend of 100,000 gas (see section 7).
    /// @param sender The msg.sender of the sendMessage call (source-chain address).
    /// @param targetApplication Destination application address.
    /// @param messageSize Payload size in bytes (convenience parameter).
    /// @param messageData Opaque application payload.
    /// @return authorized True if the Connector authorizes this message.
    function authorizeMessage(
        address sender,
        bytes calldata targetApplication,
        uint64 messageSize,
        bytes calldata messageData
    ) external returns (bool authorized);
}
```

## 2.4 IClprApplication

```solidity
/// @title IClprApplication
/// @notice Interface for applications receiving CLPR messages.
/// @dev Target applications MUST implement this interface to receive
///      cross-ledger messages and response callbacks.
interface IClprApplication {

    /// @notice Called by the ClprService when a Data Message arrives for this application.
    /// @dev Called with a gas stipend of maxGasPerMessage (from peer config).
    ///      If this function reverts, the ClprService generates an APPLICATION_ERROR
    ///      response. The revert does NOT affect other messages in the bundle.
    ///      MUST NOT call back into ClprService state-modifying functions (reentrancy guard).
    /// @param connectionId The Connection the message arrived on.
    /// @param sender The source-chain address that originated the message.
    /// @param messageData The opaque application payload.
    /// @return responseData Opaque response data to include in the Response Message.
    function onClprMessage(
        bytes32 connectionId,
        bytes calldata sender,
        bytes calldata messageData
    ) external returns (bytes memory responseData);

    /// @notice Called by the ClprService when a Response Message arrives for a
    ///         previously sent message.
    /// @dev Called with a gas stipend of maxGasPerMessage.
    ///      If this function reverts, the response is still considered delivered
    ///      (the revert is logged but does not affect protocol state).
    /// @param connectionId The Connection the response arrived on.
    /// @param messageId The ID of the original message this responds to.
    /// @param status The ClprMessageReplyStatus value.
    /// @param responseData The opaque response payload (empty on protocol errors).
    function onClprResponse(
        bytes32 connectionId,
        uint64 messageId,
        uint8 status,
        bytes calldata responseData
    ) external;
}
```

## 2.5 Events

```solidity
/// @notice Emitted when the ledger configuration is updated.
event LedgerConfigurationUpdated(bytes configuration);

/// @notice Emitted when a new Connection is registered.
event ConnectionRegistered(
    bytes32 indexed connectionId,
    string chainId,
    address verifierContract
);

/// @notice Emitted when a Connection's status changes.
event ConnectionStatusChanged(
    bytes32 indexed connectionId,
    uint8 previousStatus,
    uint8 newStatus
);

/// @notice Emitted when a Connection's verifier is updated.
event ConnectionVerifierUpdated(
    bytes32 indexed connectionId,
    address previousVerifier,
    address newVerifier
);

/// @notice Emitted when a Connector is registered on a Connection.
event ConnectorRegistered(
    bytes32 indexed connectionId,
    bytes sourceConnectorAddress,
    address connectorContract,
    uint256 balance,
    uint256 stake
);

/// @notice Emitted when a Connector is deregistered.
event ConnectorDeregistered(
    bytes32 indexed connectionId,
    bytes sourceConnectorAddress
);

/// @notice Emitted when a Connector's balance changes (topUp, withdraw, charge, slash).
event ConnectorBalanceChanged(
    bytes32 indexed connectionId,
    bytes sourceConnectorAddress,
    uint256 newBalance,
    uint256 newStake,
    string reason
);

/// @notice Emitted when a message is enqueued in the outbound queue.
event MessageSent(
    bytes32 indexed connectionId,
    uint64 indexed messageId,
    address indexed sender,
    address connectorContract,
    bytes targetApplication
);

/// @notice Emitted when a bundle is successfully processed.
event BundleProcessed(
    bytes32 indexed connectionId,
    uint64 firstMessageId,
    uint64 lastMessageId,
    address submitter
);

/// @notice Emitted for each Data Message dispatched to an application.
event MessageDispatched(
    bytes32 indexed connectionId,
    uint64 indexed messageId,
    bytes targetApplication,
    uint8 status
);

/// @notice Emitted when a Response Message is delivered to the originating application.
event ResponseDelivered(
    bytes32 indexed connectionId,
    uint64 indexed originalMessageId,
    uint8 status
);

/// @notice Emitted when a message is redacted.
event MessageRedacted(
    bytes32 indexed connectionId,
    uint64 indexed messageId
);

/// @notice Emitted when an endpoint registers.
event EndpointRegistered(
    bytes32 indexed connectionId,
    address indexed account,
    uint256 bond
);

/// @notice Emitted when an endpoint deregisters.
event EndpointDeregistered(
    bytes32 indexed connectionId,
    address indexed account,
    uint256 bondReturned
);

/// @notice Emitted when an endpoint's bond is slashed.
event EndpointSlashed(
    bytes32 indexed connectionId,
    address indexed account,
    uint256 amount,
    string reason
);

/// @notice Emitted when a Connector is slashed.
event ConnectorSlashed(
    bytes32 indexed connectionId,
    bytes sourceConnectorAddress,
    uint256 amount,
    string reason
);

/// @notice Emitted when a misbehavior report is processed.
event MisbehaviorReported(
    bytes32 indexed connectionId,
    bytes offendingEndpoint,
    uint8 evidenceType
);

/// @notice Emitted when the peer endpoint roster is recovered.
event EndpointRosterRecovered(
    bytes32 indexed connectionId,
    uint256 endpointCount
);
```

---

# 3. Storage Layout

## 3.1 Top-Level State

```solidity
/// @dev Storage layout for ClprService implementation.
///      Uses explicit storage slots to ensure upgrade safety.
///      All mappings use keccak256-based slot derivation per Solidity rules.

// --- Global Configuration ---
// Slot 0: master enable flag
bool public clprEnabled;

// Slot 1: pointer to ABI-encoded ClprLedgerConfiguration (stored as bytes in a separate mapping)
bytes internal _ledgerConfiguration;

// --- Connections ---
// Slot N: mapping from connectionId to Connection struct
mapping(bytes32 => Connection) internal _connections;

// --- Message Queue ---
// Slot M: mapping from (connectionId, messageId) to MessageEntry
mapping(bytes32 => mapping(uint64 => MessageEntry)) internal _messageQueue;

// --- Connectors ---
// Slot P: mapping from (connectionId, sourceConnectorHash) to Connector struct
// sourceConnectorHash = keccak256(sourceConnectorAddress) to normalize variable-length keys
mapping(bytes32 => mapping(bytes32 => Connector)) internal _connectors;

// --- Endpoints ---
// Slot Q: mapping from (connectionId, endpointAccount) to EndpointBond
mapping(bytes32 => mapping(address => EndpointBond)) internal _endpointBonds;

// --- Peer Endpoints ---
// Slot R: mapping from (connectionId, accountHash) to PeerEndpoint
mapping(bytes32 => mapping(bytes32 => PeerEndpoint)) internal _peerEndpoints;

// --- Reentrancy Guard ---
// Slot S: reentrancy status
uint256 private _reentrancyStatus; // 1 = not entered, 2 = entered
```

## 3.2 Connection Struct

```solidity
/// @dev On-chain Connection state. Maps to Spec section 2.1.
///      Packed for gas efficiency: identity fields in slot 1, status+metadata in slot 2, etc.
struct Connection {
    // --- Identity (slots 1-3) ---
    // connectionId is the mapping key, not stored here
    string chainId;                    // CAIP-2 chain ID of the peer (variable length)
    bytes serviceAddress;              // peer's CLPR Service address (variable length)

    // --- Peer Configuration (slot 4) ---
    uint64 peerConfigTimestampSeconds; // timestamp.seconds of last known peer config
    uint32 peerConfigTimestampNanos;   // timestamp.nanos

    // --- Verifier (slot 5) ---
    address verifierContract;          // 20 bytes
    // verifierFingerprint stored separately (32 bytes, does not pack well)
    bytes32 verifierFingerprint;       // EXTCODEHASH endorsed by peer (slot 6)

    // --- Status (slot 7, packed with queue metadata) ---
    uint8 status;                      // 0=UNSET, 1=ACTIVE, 2=PAUSED, 3=SEVERED, 4=HALTED

    // --- Outbound Queue Metadata (slots 7-9) ---
    uint64 nextMessageId;              // next sequence number for outgoing messages
    uint64 ackedMessageId;             // highest ID confirmed by peer
    bytes32 sentRunningHash;           // cumulative SHA-256 of all enqueued messages

    // --- Inbound Queue Metadata (slots 10-11) ---
    uint64 receivedMessageId;          // highest ID received from peer
    bytes32 receivedRunningHash;       // cumulative SHA-256 of all received messages

    // --- Peer Throttles (slot 12-13) ---
    uint32 maxMessagesPerBundle;
    uint32 maxSyncsPerSec;
    uint32 maxMessagePayloadBytes;
    uint64 maxGasPerMessage;
    uint32 maxQueueDepth;
    uint64 maxSyncPayloadBytes;

    // --- Accounting ---
    uint64 nextResponseExpectedId;     // ID of the next Data Message expecting a response
}
```

## 3.3 MessageEntry Struct

```solidity
/// @dev Queued message entry. Maps to Spec section 1.4 (ClprMessageKey + ClprMessageValue).
///      Optimized for aggressive deletion: payload is stored as bytes and zeroed on ack/redaction.
struct MessageEntry {
    bytes payload;                     // ABI-encoded ClprMessagePayload; zeroed on deletion
    bytes32 runningHashAfterProcessing; // retained even after payload deletion
    uint8 messageType;                 // 0=unset, 1=data, 2=response, 3=control
    bool redacted;                     // true if payload was redacted
}
```

## 3.4 Connector Struct

```solidity
/// @dev On-chain Connector state. Maps to Spec section 2.2.
struct Connector {
    bytes sourceConnectorAddress;      // address of counterpart on source ledger
    address connectorContract;         // IClprConnectorAuth contract address
    address admin;                     // admin authority (can top up, adjust, shut down)
    uint256 balance;                   // available funds for execution (wei)
    uint256 lockedStake;               // slashable stake (wei)
    uint64 inFlightCount;              // number of unresolved in-flight messages
    uint64 slashCount;                 // cumulative slash events (for escalation)
    bool active;                       // false = banned or deregistered
}
```

## 3.5 EndpointBond Struct

```solidity
/// @dev Bond state for a locally registered endpoint.
///      Maps to Spec section 2.3 (platform-specific bond structure).
struct EndpointBond {
    uint256 bondAmount;                // ETH locked against misbehavior
    uint64 registeredAt;               // block.number when registered
    bool active;                       // false = deregistered or slashed
}
```

## 3.6 Gas Optimization Notes

**Storage packing.** The `Connection` struct packs `status` (1 byte), `nextMessageId` (8 bytes), and
`ackedMessageId` (8 bytes) into a single 32-byte slot. Throttle parameters are similarly packed. This reduces
SSTORE operations during bundle processing.

**Message queue.** Message payloads are stored as `bytes` (dynamic length). When a message is acknowledged and
eligible for deletion, the payload bytes are zeroed (earning the SSTORE gas refund of 4,800 per slot cleared),
but the `runningHashAfterProcessing` is retained for redaction verification. Full slot clearance occurs when both
the payload and hash are no longer needed.

**Connector key normalization.** The `sourceConnectorAddress` is variable-length (different chains use different
address sizes). The storage mapping uses `keccak256(sourceConnectorAddress)` as the key to normalize lookups to
a single SLOAD per access.

---

# 4. Endpoint Registration and Bond Management

## 4.1 Registration Flow

On Ethereum, endpoint registration is permissionless (Spec section 6.5). Any address may register as an endpoint
by posting a bond in ETH.

```
registerEndpoint(connectionId, endpoint) payable
  1. Require clprEnabled == true.
  2. Require _connections[connectionId].status == ACTIVE.
  3. Decode endpoint; require endpoint.account_id == msg.sender (20 bytes).
  4. Require msg.value >= MIN_ENDPOINT_BOND.
  5. Require _endpointBonds[connectionId][msg.sender].active == false (not already registered).
  6. Store EndpointBond { bondAmount: msg.value, registeredAt: block.number, active: true }.
  7. Emit EndpointRegistered(connectionId, msg.sender, msg.value).
```

## 4.2 Deregistration Flow

```
deregisterEndpoint(connectionId)
  1. Require _endpointBonds[connectionId][msg.sender].active == true.
  2. Require no in-flight sync submissions from this endpoint (implementation-specific tracking).
  3. Set active = false.
  4. Transfer bondAmount to msg.sender.
  5. Zero the EndpointBond storage (gas refund).
  6. Emit EndpointDeregistered(connectionId, msg.sender, bondAmount).
```

## 4.3 Bond Parameters

| Parameter             | Recommended Value | Rationale                                                    |
|-----------------------|-------------------|--------------------------------------------------------------|
| `MIN_ENDPOINT_BOND`   | 1 ETH             | Must make Sybil attacks economically infeasible (Spec 8.4).  |
| `BOND_LOCKUP_PERIOD`  | 7 days (in blocks) | Prevents register-submit-deregister attacks.                 |
| `SLASH_PERCENTAGE`     | 100%              | Full bond forfeiture for proven misbehavior.                  |

The `BOND_LOCKUP_PERIOD` is enforced during deregistration: an endpoint cannot deregister until
`block.number >= registeredAt + BOND_LOCKUP_BLOCKS`. This prevents an attacker from registering, submitting a
malicious bundle, and immediately deregistering before the misbehavior is reported.

## 4.4 Bond Slashing

When misbehavior is proven (via `reportMisbehavior` or duplicate submission detection during `submitBundle`):

```
  1. Verify the misbehavior evidence (see section 9 for security model).
  2. Set _endpointBonds[connectionId][offender].active = false.
  3. Transfer slash proceeds:
     - DUPLICATE_BROADCAST: full bond to the reporter.
     - EXCESS_FREQUENCY: full bond to the protocol treasury.
  4. Emit EndpointSlashed(connectionId, offender, amount, reason).
```

---

# 5. Bundle Submission

## 5.1 submitBundle Transaction Flow

The `submitBundle` function is the highest-gas operation in the protocol. It implements Spec section 4.2 (Bundle
Verification Algorithm) and Spec section 4.6 (Slashing Decision).

```
submitBundle(connectionId, proofBytes, remoteEndpointSignature, remoteEndpointPublicKey)
  // --- Step 1: Verifier Call ---
  1. Load connection = _connections[connectionId].
  2. Require connection.status == ACTIVE || connection.status == PAUSED.
     (PAUSED accepts inbound bundles per Spec section 2.1.1.)
  3. Call connection.verifierContract.verifyBundle(proofBytes).
     If reverts: revert entire transaction. Submitter pays gas.
     Returns: (metadata, messages) -- ABI-encoded.
  4. Decode metadata as ClprQueueMetadata.
  5. Decode messages as ClprMessagePayload[].

  // --- Step 2: Bundle Size Check ---
  6. Require messages.length <= connection.maxMessagesPerBundle.
  7. For each message, require payload.length <= connection.maxMessagePayloadBytes.

  // --- Step 3: Replay Defense ---
  8. Require messages[0].id == connection.receivedMessageId + 1.
  9. Require contiguous ascending IDs.
  10. Require messages[last].id == metadata.nextMessageId - 1.

  // --- Step 4: Running Hash Verification ---
  11. hash = connection.receivedRunningHash.
  12. For each message:
      hash = sha256(abi.encodePacked(hash, message.serializedPayload)).
  13. Require hash == metadata.sentRunningHash.

  // --- Step 5: Acknowledgement Update ---
  14. Update connection.ackedMessageId = metadata.receivedMessageId.
  15. Delete acknowledged Response Messages and Control Messages from outbound queue.
  16. Retain acknowledged Data Messages (needed for response ordering).

  // --- Step 6: Message Dispatch (see section 8 for details) ---
  17. For each message in order:
      - If Control: apply directly (update peer roster or config).
      - If Data: resolve connector, charge, dispatch to application, enqueue response.
      - If Response: deliver to application, verify ordering.
  18. Update connection.receivedMessageId and connection.receivedRunningHash.

  // --- Step 7: Reimburse Submitter ---
  19. Transfer endpoint reimbursement from Connector charges to msg.sender.

  20. Emit BundleProcessed(connectionId, firstId, lastId, msg.sender).
```

## 5.2 Gas Budget Analysis

A bundle submission's gas cost scales with the number of messages and the cost of each message dispatch.

| Operation                          | Gas Cost (estimate)       | Notes                                    |
|------------------------------------|---------------------------|------------------------------------------|
| Verifier call (BLS sig verify)     | 150,000 - 300,000         | Depends on verifier implementation       |
| Replay defense (per message)       | ~500                      | Comparisons and memory ops               |
| SHA-256 per message                | 60 + 12 * ceil(len/32)   | ~200 gas for a 256-byte payload          |
| SLOAD connection metadata          | 2,100 (cold) + 100 (warm)| ~3,000 for initial load                  |
| SSTORE connection metadata update  | ~10,000                   | Warm stores for IDs and hashes           |
| Message dispatch (per Data msg)    | maxGasPerMessage + 30,000 | Application callback + overhead          |
| Response enqueue (per Data msg)    | ~25,000                   | SSTORE for new queue entry               |
| Ack cleanup (per acked msg)        | ~5,000 (net, after refund)| SSTORE zero + refund                     |

**Maximum practical bundle size.** With a 30M block gas limit:

- Verification overhead: ~400,000 gas
- Per-message overhead (excluding app callback): ~30,000 gas
- Available for messages: ~29,600,000 gas
- If maxGasPerMessage = 2,000,000: max ~13 messages per bundle
- If maxGasPerMessage = 500,000: max ~55 messages per bundle

**Recommended configuration for Ethereum:**

| Parameter              | Value    | Rationale                                          |
|------------------------|----------|----------------------------------------------------|
| maxMessagesPerBundle   | 10       | Conservative; leaves gas for other txns in block   |
| maxGasPerMessage       | 2,000,000| Sufficient for most DeFi callbacks                 |
| maxMessagePayloadBytes | 8,192    | Balances calldata cost vs. utility                 |
| maxQueueDepth          | 1,000    | Bounds storage growth; ~25M gas to store fully     |

## 5.3 Calldata Cost Considerations

Calldata costs 4 gas per zero byte and 16 gas per non-zero byte. For a bundle with 10 messages averaging
512 bytes of payload each:

- Proof bytes: ~2 KB = ~32,000 gas calldata
- 10 messages x 512 bytes = ~5 KB = ~80,000 gas calldata
- Metadata + signatures: ~1 KB = ~16,000 gas calldata
- **Total calldata: ~128,000 gas**

With EIP-4844 blob transactions, large proof bytes could be submitted as blobs (131,072 bytes each, ~131,000 gas
per blob) if the verifier is adapted to read from blob data. This is a future optimization and is NOT required for
initial deployment.

---

# 6. Verifier Contract Standard

## 6.1 Ethereum-Specific Verifier Requirements

Each verifier contract deployed on Ethereum MUST implement `IClprVerifier` (section 2.2). The verifier is
called via `STATICCALL` by the ClprService (the verifier functions are `view`), meaning:

- Verifier contracts MUST NOT modify state.
- Verifier contracts MUST be deterministic across calls with the same input.
- Verifier contracts MAY maintain internal state (e.g., sync committee tracking) that is updated via
  separate administrative transactions, not during `verifyBundle` calls.

## 6.2 BLS Signature Verification (Ethereum Sync Committee)

For verifying Ethereum consensus state, the verifier tracks the sync committee and validates BLS12-381
aggregate signatures. Post-Cancun, Ethereum provides BLS precompiles (EIP-2537) that reduce verification cost.

**Estimated gas for sync committee signature verification:**

| Step                              | Gas        |
|-----------------------------------|------------|
| BLS pairing check (2 pairings)   | ~120,000   |
| Sync committee bitfield check    | ~10,000    |
| Beacon state root extraction     | ~5,000     |
| Merkle proof verification        | ~20,000    |
| Storage proof verification       | ~30,000    |
| **Total**                        | **~185,000** |

Without BLS precompiles (pre-Cancun or if precompiles are not available), BLS verification in Solidity costs
approximately 1,500,000+ gas, which is prohibitive within a bundle submission. The implementation SHOULD
require Cancun or later, or use a ZK-wrapped proof that compresses the BLS verification.

## 6.3 EXTCODEHASH Fingerprint Verification

The verifier's legitimacy is checked during `registerConnection` by comparing `EXTCODEHASH(verifierContract)`
against the peer's `ApprovedVerifiers` entries. This is a single opcode costing 2,600 gas (cold) or 100 gas
(warm).

```solidity
bytes32 codeHash;
assembly {
    codeHash := extcodehash(verifierContract)
}
require(isApprovedFingerprint(peerConfig, codeHash), "Verifier not endorsed");
```

**Proxy considerations.** When a verifier is behind an EIP-1967 proxy, `EXTCODEHASH` returns the hash of the
proxy's bytecode, not the implementation. The `ApprovedVerifiers` entry MUST contain the proxy's code hash.
Changing the proxy's implementation does not change `EXTCODEHASH`, so upgrades to the underlying logic do not
require re-registration. This is by design (Spec section 8.8) and acceptable only if the proxy's upgrade
authority is controlled by the source ledger's CLPR Service admin.

## 6.4 Built-in ZK Verifier

The ClprService contract includes a hardcoded ZK verifier function used exclusively for connection registration
and the recovery path of `updateConnectionVerifier`. This verifier validates a zero-knowledge proof attesting
to a peer ledger's configuration state.

**Implementation options (choose one per deployment):**

1. **Groth16 verifier** -- ~230,000 gas per proof. Compact proof size (~128 bytes). Requires trusted setup.
   Well-suited for Ethereum due to low on-chain verification cost.
2. **PLONK verifier** -- ~300,000 gas per proof. No trusted setup. Slightly larger proofs (~512 bytes).
3. **STARK verifier** -- ~500,000+ gas per proof. No trusted setup. Large proofs (~50-200 KB). Higher calldata cost.

The recommended choice is **Groth16** for initial deployment due to lowest gas cost, with a migration path to
PLONK if the trusted setup is deemed unacceptable for governance.

The built-in ZK verifier is compiled into the ClprService implementation contract. Upgrading the ZK verifier
requires upgrading the ClprService implementation via the proxy.

---

# 7. Connector Authorization

## 7.1 Authorization Call Mechanics

When `sendMessage` is called, the ClprService invokes `IClprConnectorAuth.authorizeMessage()` on the Connector's
contract:

```solidity
(bool success, bytes memory result) = connectorContract.call{gas: CONNECTOR_AUTH_GAS_STIPEND}(
    abi.encodeCall(
        IClprConnectorAuth.authorizeMessage,
        (msg.sender, targetApplication, uint64(messageData.length), messageData)
    )
);
require(success && abi.decode(result, (bool)), "Connector rejected");
```

**Gas stipend:** `CONNECTOR_AUTH_GAS_STIPEND = 100,000 gas`. This bounds the cost of the authorization call
while allowing moderate logic (allow-list lookups, rate limit checks, signature verification). Connectors
that need more gas can use a two-step pattern (pre-authorize off-chain, verify on-chain).

## 7.2 Reentrancy Protection

The `authorizeMessage` call hands execution to external code. The ClprService MUST:

1. **NOT** have updated any connection state before the authorization call. The call happens during
   validation, before the message is enqueued (checks-effects-interactions).
2. Use a reentrancy guard (`_reentrancyStatus`) on `sendMessage`, `submitBundle`, and all other
   state-modifying functions. If the Connector's `authorizeMessage` attempts to call back into
   any ClprService state-modifying function, the call reverts.

The authorization call is NOT a `STATICCALL` because the Connector may need to update its own internal state
(e.g., decrement a rate limit counter, record a payment). However, it MUST NOT modify ClprService state.

## 7.3 Connector Payment and Slashing

**Charging during Data Message dispatch** (within `submitBundle`):

```
For each Data Message:
  1. Resolve sourceConnectorAddress -> local Connector via _connectors mapping.
  2. If Connector not found: generate CONNECTOR_NOT_FOUND response. Submitter absorbs cost.
  3. Calculate charge = gasCost + margin (margin = 10% of gasCost, minimum 0.001 ETH).
  4. If Connector.balance < charge:
     a. Slash min(Connector.lockedStake, SLASH_AMOUNT) -> submitter.
     b. Generate CONNECTOR_UNDERFUNDED response.
     c. Increment Connector.slashCount.
     d. If slashCount >= MAX_SLASH_BEFORE_BAN: set Connector.active = false.
  5. Else: deduct charge from Connector.balance. Credit margin to submitter.
  6. Dispatch to application (see section 8).
  7. Enqueue response in outbound queue.
```

**Slashing schedule:**

| Occurrence | Fine                         | Action                                |
|------------|------------------------------|---------------------------------------|
| 1st        | 10% of lockedStake           | Warning event emitted                 |
| 2nd        | 25% of lockedStake           | Warning event emitted                 |
| 3rd        | 50% of lockedStake           | Warning event emitted                 |
| 4th+       | 100% of remaining stake      | Connector banned from Connection      |

---

# 8. Application Dispatch

## 8.1 Data Message Dispatch

When a verified Data Message is dispatched to its target application:

```solidity
// State updates BEFORE external call (checks-effects-interactions)
connection.receivedMessageId = messageId;
connection.receivedRunningHash = newHash;
connector.balance -= charge;

// External call with gas stipend
(bool success, bytes memory responseData) = targetApp.call{gas: connection.maxGasPerMessage}(
    abi.encodeCall(
        IClprApplication.onClprMessage,
        (connectionId, message.sender, message.messageData)
    )
);

uint8 replyStatus;
if (success) {
    replyStatus = 1; // SUCCESS
} else {
    replyStatus = 2; // APPLICATION_ERROR
    responseData = ""; // discard revert data for response
}

// Enqueue response
_enqueueResponse(connectionId, messageId, replyStatus, responseData);
```

## 8.2 Response Message Delivery

```solidity
// Decode the target application from the original sent message
bytes memory originalPayload = _messageQueue[connectionId][response.messageId].payload;
address targetApp = _extractSenderApp(originalPayload);

// Deliver response (failure here does NOT affect protocol state)
try IClprApplication(targetApp).onClprResponse(
    connectionId,
    response.messageId,
    response.status,
    response.messageReplyData
) {} catch {
    emit ResponseDeliveryFailed(connectionId, response.messageId);
}

// Response ordering verification (Spec section 4.5)
require(response.messageId == connection.nextResponseExpectedId, "Response ordering violation");
connection.nextResponseExpectedId = _nextDataMessageId(connectionId, response.messageId);

// Delete the matched Data Message from outbound queue
delete _messageQueue[connectionId][response.messageId];
```

## 8.3 Gas Stipend for Application Callbacks

The gas stipend for `onClprMessage` is set to the peer's `maxGasPerMessage` configuration value. This:

- Bounds worst-case execution cost per message (predictable charging).
- Prevents a malicious application from consuming the entire block's gas.
- Is charged to the Connector regardless of whether the application uses all of it.

If the application callback exceeds the stipend, it reverts with an out-of-gas error, and an `APPLICATION_ERROR`
response is generated.

## 8.4 Reentrancy Guards

The ClprService uses a nonReentrant modifier on ALL state-modifying external functions:

```solidity
modifier nonReentrant() {
    require(_reentrancyStatus != 2, "ReentrancyGuard: reentrant call");
    _reentrancyStatus = 2;
    _;
    _reentrancyStatus = 1;
}
```

Applied to: `setLedgerConfiguration`, `registerConnection`, `updateConnectionVerifier`,
`recoverEndpointRoster`, `severConnection`, `pauseConnection`, `resumeConnection`, `registerConnector`,
`topUpConnector`, `withdrawConnectorBalance`, `deregisterConnector`, `sendMessage`, `submitBundle`,
`redactMessage`, `registerEndpoint`, `deregisterEndpoint`, `reportMisbehavior`.

This means application callbacks (from `submitBundle` dispatching messages) CANNOT call back into any
ClprService function. If an application attempts this, the transaction reverts for that message, and an
`APPLICATION_ERROR` response is generated.

---

# 9. Security Model

## 9.1 Reentrancy

**Attack:** A malicious target application receives a message dispatch and calls back into `submitBundle`,
`sendMessage`, or another state-modifying function to manipulate Connection state mid-dispatch.

**Mitigation:** Global reentrancy guard (section 8.4). ALL state-modifying functions are protected. The
checks-effects-interactions pattern is followed: all Connection state (message IDs, running hashes,
Connector charges) is updated BEFORE the external call to the application.

**Residual risk:** The application callback CAN call other contracts and CAN call `view` functions on
ClprService. This is intentional -- applications need to read state. View functions are safe because they
do not modify state.

## 9.2 Front-Running and MEV

**Attack:** A MEV searcher observes a `submitBundle` transaction in the mempool and front-runs it with
their own `submitBundle` containing the same proof bytes. The original submitter's transaction then fails
(duplicate bundle / replay defense), and the front-runner earns the Connector reimbursement.

**Mitigation options (recommended: option B):**

- **A. Commit-reveal:** The endpoint commits to a hash of the bundle, then reveals. Two transactions,
  higher cost, slower.
- **B. Submitter binding:** The proof bytes include the submitter's address, and the verifier validates
  that `msg.sender` matches the bound address. This requires verifier cooperation but prevents replay
  by a different submitter. The remote endpoint signs `hash(proof_bytes || submitter_address)`, binding
  the bundle to a specific local endpoint.
- **C. Flashbots/private mempools:** Use Flashbots Protect or similar private transaction submission to
  avoid mempool exposure. Works but is infrastructure-dependent.

**Recommended approach:** Option B (submitter binding) as the primary defense, with option C as an
operational best practice for endpoint operators.

## 9.3 Gas Griefing

**Attack 1: Large calldata.** An attacker submits a `submitBundle` with extremely large `proofBytes` that
are valid enough to pass initial checks but cause the verifier to consume excessive gas before reverting.

**Mitigation:** Verifier contracts SHOULD fail fast on malformed inputs (Spec section 3.1). The
`maxSyncPayloadBytes` configuration bounds the total payload size. The ClprService SHOULD check
`proofBytes.length <= maxSyncPayloadBytes` before calling the verifier.

**Attack 2: Application gas consumption.** A malicious application's `onClprMessage` consumes exactly
`maxGasPerMessage` gas doing nothing useful, wasting the Connector's funds.

**Mitigation:** This is the Connector's problem. Connectors SHOULD only authorize messages to applications
they trust. The `maxGasPerMessage` bounds the worst case. Connectors can use their `authorizeMessage` logic
to reject messages to untrusted applications.

## 9.4 Storage Collision

**Mitigation:** The ClprService uses OpenZeppelin's storage layout conventions. The EIP-1967 proxy
uses well-known storage slots (`0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc` for
implementation, etc.). The implementation contract uses sequential storage slots starting from slot 0.
Upgrades MUST be tested with OpenZeppelin's storage layout compatibility checker.

## 9.5 Upgradeable Proxy Security

- The `ProxyAdmin` is behind a `TimelockController` with a 48-hour delay.
- The governance multisig requires M-of-N signatures (recommended: 3-of-5 minimum).
- An `UpgradeScheduled` event is emitted when an upgrade is proposed, giving the community time to review.
- The proxy cannot be upgraded by the implementation contract itself (transparent proxy pattern).

## 9.6 Flash Loan Attacks on Bonds

**Attack:** An attacker takes a flash loan to temporarily hold enough ETH to register as an endpoint
(meeting `MIN_ENDPOINT_BOND`), submits a malicious bundle, and repays the loan in the same transaction.

**Mitigation:** The `BOND_LOCKUP_PERIOD` (section 4.3) prevents immediate deregistration and bond
withdrawal. Since flash loans must be repaid within the same transaction, the attacker cannot withdraw
their bond, making the attack unprofitable. Additionally, `registerEndpoint` and `submitBundle` are
protected by the reentrancy guard, so they cannot be called in the same transaction by the same contract.

## 9.7 Denial of Service via Queue Monopolization

**Attack:** A Connector authorizes a flood of messages to fill a Connection's queue to `maxQueueDepth`,
blocking all other Connectors.

**Mitigation (to be implemented):**

1. **Per-Connector queue quota:** Each Connector may occupy at most `maxQueueDepth / maxConnectorsPerConnection`
   queue slots. Exceeding the quota rejects the message.
2. **Escrow at send time:** Each `sendMessage` call escrows a small deposit (e.g., 0.0001 ETH) from the sender,
   returned when the response arrives. This makes queue monopolization expensive.
3. **Priority pricing:** Queue slot cost increases as occupancy grows (similar to EIP-1559 base fee).

The specific mitigation is an open design question (Spec section 8.9). The initial implementation SHOULD use
per-Connector queue quotas as the simplest effective mitigation.

---

# 10. Gas Economics

## 10.1 Operation Gas Costs

| Operation                      | Estimated Gas     | Who Pays                              |
|--------------------------------|-------------------|---------------------------------------|
| `registerConnection`           | 400,000 - 600,000 | Caller (permissionless)              |
| `updateConnectionVerifier`     | 100,000 - 400,000 | Caller (permissionless)              |
| `registerConnector`            | 150,000           | Connector admin                       |
| `registerEndpoint`             | 80,000            | Endpoint operator                     |
| `sendMessage` (enqueue)        | 80,000 - 150,000  | Message sender                        |
| `submitBundle` (10 messages)   | 2,000,000 - 25,000,000 | Endpoint (reimbursed by Connectors) |
| `redactMessage`                | 30,000            | CLPR admin                            |
| `reportMisbehavior`            | 200,000 - 500,000 | Reporter (compensated by slash)       |

## 10.2 Fee Model

The CLPR protocol on Ethereum uses a fee model based on Connector charges:

1. **Transaction fees:** All callers pay standard Ethereum gas fees for their transactions.
2. **Connector charges:** When a Data Message is dispatched on the destination, the Connector is charged:
   - `executionCost = gasUsed * tx.gasprice` (actual execution cost of the application callback)
   - `margin = max(executionCost * MARGIN_PERCENTAGE / 100, MIN_MARGIN)` (endpoint reimbursement)
   - `total = executionCost + margin`
3. **No protocol fee:** The initial deployment does not charge a protocol-level fee. A protocol fee
   (percentage of Connector charges directed to a treasury) may be introduced via governance vote.

**Recommended parameters:**

| Parameter          | Value      | Rationale                                      |
|--------------------|------------|-------------------------------------------------|
| `MARGIN_PERCENTAGE`| 10%        | Incentivizes endpoint operation                |
| `MIN_MARGIN`       | 0.0001 ETH | Ensures minimum endpoint compensation          |
| `MIN_CONNECTOR_STAKE` | 0.5 ETH | Must cover worst-case slash exposure           |
| `MIN_CONNECTOR_BALANCE` | 0.1 ETH | Must cover at least one message execution     |

## 10.3 Storage Cost Considerations

Ethereum storage is expensive. Key cost drivers:

- **Message enqueue:** ~25,000 gas per message (new SSTORE for payload + hash).
- **Message deletion on ack:** ~5,000 gas net (SSTORE to zero earns 4,800 refund, minus overhead).
- **Connection metadata update:** ~10,000 gas per bundle (warm SSTORE for IDs and hashes).

**Storage growth bound:** With `maxQueueDepth = 1,000` messages per Connection and an average message payload
of 512 bytes, each Connection's queue occupies at most ~1,000 storage slots (~32 KB of storage). At 1,000
Connections, total queue storage is ~32 MB. This is manageable for Ethereum state, but aggressive cleanup
is essential.

---

# 11. Events and Indexing

## 11.1 Event Design

All state changes emit events (defined in section 2.5). Events are designed for off-chain indexing:

- **Indexed parameters:** `connectionId` is indexed on all events for per-Connection filtering.
  `messageId` and `account` are indexed where relevant for targeted queries.
- **Three indexed parameters maximum** per event (Solidity limit), chosen for the most common query patterns.

## 11.2 Off-Chain Indexing Architecture

Applications learn about CLPR events through standard Ethereum event subscription mechanisms:

```
Application Backend
  |
  |--> eth_subscribe("logs", {address: ClprServiceProxy, topics: [...]})
  |--> eth_getLogs({fromBlock, toBlock, address: ClprServiceProxy, topics: [...]})
  |
  |--> The Graph subgraph (recommended for production indexing)
```

**Key query patterns:**

| Query                              | Event(s)                                   | Index Strategy          |
|------------------------------------|--------------------------------------------|-------------------------|
| Messages sent on a Connection      | `MessageSent` filtered by connectionId     | Topic 1 = connectionId  |
| Message delivery status            | `MessageDispatched` filtered by messageId  | Topic 2 = messageId     |
| Response for my sent message       | `ResponseDelivered` filtered by messageId  | Topic 2 = messageId     |
| All bundles on a Connection        | `BundleProcessed` filtered by connectionId | Topic 1 = connectionId  |
| Connector balance changes          | `ConnectorBalanceChanged`                  | Topic 1 = connectionId  |

## 11.3 Response Notification

Applications can learn about responses via two mechanisms:

1. **Event-based (recommended):** Subscribe to `ResponseDelivered` events filtered by the Connection and
   message ID. The event includes the status, allowing the application to react off-chain.
2. **Callback-based:** Implement `IClprApplication.onClprResponse()`. The ClprService calls this during
   `submitBundle` processing. This is synchronous and on-chain but costs gas (paid by the endpoint/Connector).
3. **Pull-based:** Call `getConnection(connectionId)` to check `receivedMessageId` and determine whether
   specific messages have been processed. Less efficient but requires no subscription infrastructure.

---

# 12. Platform-Specific Gaps and Decisions

This section documents areas where the cross-platform spec leaves decisions to the platform, and the
Ethereum-specific choices made.

## 12.1 Endpoint Bond Structure

**Spec gap:** Spec section 2.3 states "Platform-specific specifications MUST define the bond structure,
minimum amounts, and slashing conditions."

**Ethereum decision:** Fixed minimum bond in ETH (`MIN_ENDPOINT_BOND`), with a lockup period
(`BOND_LOCKUP_PERIOD`) and full forfeiture on proven misbehavior. Bonds are held by the ClprService
contract. No bond delegation or liquid staking is supported in the initial implementation.

## 12.2 Slashing Schedule

**Spec gap:** Spec section 4.6 states "Platform-specific specifications MUST define the slashing schedule
(fine amounts, escalation thresholds, ban conditions)."

**Ethereum decision:** Escalating percentage-based slashing (section 7.3). Four-strike policy leading to
permanent ban. Slash proceeds go to the submitting endpoint that was harmed.

## 12.3 ECDSA Signature Format

**Spec gap:** Spec section 5.1 states "The exact signed payload format is defined in platform-specific
specifications."

**Ethereum decision:** The signed payload for `registerConnection` is:
```
signedPayload = keccak256(abi.encodePacked(
    connectionId,        // bytes32
    verifierContract,    // address
    address(this),       // ClprService proxy address
    block.chainid        // uint256 -- prevents cross-chain replay
))
```
Signature recovery uses `ecrecover` to derive the signer's address, then verifies
`keccak256(uncompressedPubkey) == connectionId`. This requires the caller to provide the uncompressed
public key alongside the signature for verification.

## 12.4 Application Callback Interface

**Spec gap:** Spec section 6.5 (Application Delivery) states platform specs MUST define the callback interface,
gas budget, return conventions, and sync/async behavior.

**Ethereum decisions:**
- **Interface:** `IClprApplication` with `onClprMessage` and `onClprResponse` (section 2.4).
- **Gas budget:** `maxGasPerMessage` from the peer's configuration.
- **Return convention:** `onClprMessage` returns `bytes` (response data). Revert = `APPLICATION_ERROR`.
- **Synchronous:** Callbacks execute within the `submitBundle` transaction. No async queuing.

## 12.5 Minimum Connector Bond

**Spec gap:** Spec section 4.6 states "Platform specs MUST define minimum Connector bond requirements."

**Ethereum decision:** `MIN_CONNECTOR_STAKE = 0.5 ETH`. This must cover the worst-case scenario where a
Connector authorizes `maxQueueDepth` messages and all fail with `CONNECTOR_UNDERFUNDED`, requiring
`maxQueueDepth` endpoint reimbursements. The formula is:

```
MIN_CONNECTOR_STAKE >= maxQueueDepth * averageExecutionCost * REIMBURSEMENT_MULTIPLIER
```

With `maxQueueDepth = 1,000` and average execution cost of 0.0005 ETH: `MIN_CONNECTOR_STAKE >= 0.5 ETH`.

## 12.6 Queue Monopolization Mitigation

**Spec gap:** Spec section 8.9 notes queue monopolization as an open design issue.

**Ethereum decision:** Per-Connector queue quotas as the initial mitigation (section 9.7). The maximum
messages per Connector on a Connection is `maxQueueDepth / 2`, ensuring at least two Connectors can share
the queue. This is a conservative starting point; more sophisticated mechanisms (escrow, priority pricing)
can be introduced via governance upgrade.

## 12.7 Encoding Format

**Spec note:** The design document (section 3) mentions XDR as a potential alternative to protobuf for
gas efficiency on Ethereum.

**Ethereum decision:** Use ABI encoding (native Solidity encoding) for all on-chain data structures. Protobuf
encoding is used only for the wire format between endpoints (off-chain). The verifier contract is responsible
for translating proof bytes (which may contain protobuf-encoded data) into ABI-encoded Solidity structs.
This avoids the need for a protobuf decoder in Solidity, which would be gas-expensive.

## 12.8 Misbehavior Frequency Measurement

**Spec gap:** Spec section 1.6 states frequency MUST be measured in sync rounds or blocks, not wall-clock time.

**Ethereum decision:** Frequency is measured in Ethereum block numbers. `EXCESS_FREQUENCY` is defined as more
than `maxSyncsPerSec * 12` bundle submissions per block (12-second block time, rounded). A tolerance of +1
bundle per boundary block is applied.

---

# 13. Inconsistencies and Findings

## 13.1 Contradictions with Cross-Platform Spec

**None found.** The Ethereum implementation maps cleanly to all MUST-level requirements in the cross-platform
spec. All platform-specific decisions fill explicit gaps designated for platform resolution.

## 13.2 Ethereum Architecture Concerns

### 13.2.1 Gas Feasibility of Bundle Processing

**Finding:** Bundle processing is gas-feasible but tightly constrained. With a 30M block gas limit, a bundle
of 10 messages with `maxGasPerMessage = 2,000,000` consumes approximately 22M gas (73% of a block). This
means at most one bundle can be processed per block, and it monopolizes most of the block's capacity.

**Impact:** On high-activity Connections, bundles compete for block space with all other Ethereum transactions.
During gas price spikes, endpoint operators may need to pay premium gas prices to ensure inclusion.

**Recommendation:** The initial deployment should use conservative parameters (`maxMessagesPerBundle = 10`,
`maxGasPerMessage = 2,000,000`). If throughput is insufficient, the protocol can migrate to using multiple
Connections in parallel (each processing bundles independently) or adopt blob transactions for proof data.

### 13.2.2 Storage Costs for Message Queue

**Finding:** Storing message payloads on-chain is expensive (~25,000 gas per message). For a Connection
processing 100 messages per day, the daily storage cost is approximately 2.5M gas (~0.00625 ETH at 25 gwei).

**Impact:** Manageable at moderate throughput. At high throughput (1,000+ messages/day), storage costs
become significant. Aggressive cleanup of acknowledged messages (earning SSTORE refunds) is essential.

**Recommendation:** Implement aggressive payload deletion immediately on acknowledgement. Consider a future
optimization where message payloads are stored in transient storage (EIP-1153) during bundle processing
rather than in persistent storage, if the verifier can provide them in the same transaction.

### 13.2.3 Verifier State Updates (Sync Committee Rotation)

**Finding:** The `IClprVerifier` interface uses `view` functions, but BLS-based Ethereum verifiers need to
track the sync committee, which rotates every ~27 hours. This state update cannot happen within a `view` call.

**Impact:** The verifier contract must have a separate `updateSyncCommittee()` function that is called by
a keeper or relayer to update the committee periodically. This adds operational complexity and a potential
liveness dependency -- if the committee is not updated in time, bundle verification will fail.

**Recommendation:** The verifier contract should expose a public `updateSyncCommittee(bytes calldata proof)`
function callable by anyone. The proof demonstrates that the new committee was attested by the current committee.
A bounty mechanism should incentivize timely updates.

### 13.2.4 ECDSA vs. BLS for Connection ID

**Finding:** The cross-platform spec mandates ECDSA_secp256k1 for Connection ID derivation, which maps
naturally to Ethereum's `ecrecover` precompile. No issues here.

### 13.2.5 SHA-256 for Running Hash

**Finding:** SHA-256 is available via the Ethereum precompile at address `0x02`. Cost is 60 + 12 per 32-byte
word, making it affordable for running hash computation (~200 gas per message for a 256-byte payload). This
is significantly cheaper than keccak256 for large inputs and aligns with the cross-platform choice.

## 13.3 Ambiguities Resolved

### 13.3.1 Connector Address Format

**Ambiguity:** The spec uses `bytes` for `source_connector_address` (variable length, chain-agnostic).

**Resolution:** On Ethereum, local Connectors use `address` (20 bytes) for the `connectorContract` field.
The `sourceConnectorAddress` remains `bytes` because it represents an address on a foreign chain that may
have a different format. The storage mapping uses `keccak256(sourceConnectorAddress)` as the key.

### 13.3.2 Simultaneous Bundle Submissions

**Ambiguity:** The spec does not explicitly address what happens when two endpoints submit bundles for the
same Connection simultaneously (possible on Ethereum where transactions are unordered in the mempool).

**Resolution:** The replay defense (Spec section 4.2, step 3) ensures that only one bundle can succeed --
the second bundle's first message ID will not equal `receivedMessageId + 1` and will be rejected. The
losing endpoint pays their transaction's gas cost. This is an expected cost of operating on a permissionless
network and is mitigated by the submitter-binding mechanism (section 9.2, option B).

### 13.3.3 Response Delivery Failure

**Ambiguity:** The spec does not specify what happens if `onClprResponse` reverts on the source ledger.

**Resolution:** Response delivery failure does NOT affect protocol state. The response is still considered
delivered (the ordering verification and Data Message cleanup proceed normally). The revert is logged via
a `ResponseDeliveryFailed` event. This prevents a malicious source application from blocking protocol
progress by reverting on every response.

### 13.3.4 Config Update Propagation Gas Cost

**Ambiguity:** The spec says `setLedgerConfiguration` "enqueues a ConfigUpdate Control Message on every
active Connection." On Ethereum, if there are hundreds of Connections, this single transaction could
exceed the block gas limit.

**Resolution:** `setLedgerConfiguration` accepts an optional `connectionIds` parameter (not in the
cross-platform spec) to batch the enqueue across multiple transactions. If `connectionIds` is empty,
it enqueues on ALL active Connections. The admin is responsible for ensuring each transaction fits within
gas limits. An alternative is lazy propagation: store a global config version counter and have each
Connection check the counter during bundle processing, but this changes the ordering guarantees and is
NOT recommended.

## 13.4 Gas Feasibility Summary

| Operation                  | Gas Required    | Feasible? | Notes                                  |
|----------------------------|-----------------|-----------|----------------------------------------|
| Connection registration    | ~500,000        | Yes       | One-time cost                          |
| Message enqueue            | ~100,000        | Yes       | Comparable to a Uniswap swap           |
| Bundle (10 msgs, 2M gas/msg) | ~22,000,000  | Marginal  | 73% of block; at most 1/block          |
| Bundle (10 msgs, 500K gas/msg) | ~7,000,000 | Yes       | Leaves room for other txns             |
| BLS verification (Cancun)  | ~185,000        | Yes       | Pre-Cancun: prohibitive without ZK     |
| ZK proof verification      | ~230,000        | Yes       | Groth16; one-time at registration      |
| SHA-256 running hash (10 msgs) | ~2,000     | Yes       | Negligible                             |
| Config update (100 conns)  | ~5,000,000      | Yes       | May need batching for 500+ connections |

**Overall assessment:** The CLPR protocol is gas-feasible on Ethereum with the recommended parameters. The
primary constraint is `submitBundle`, which dominates gas consumption. Low `maxGasPerMessage` values
(500K-1M) allow practical bundle sizes of 10-20 messages. High `maxGasPerMessage` values (2M+) limit
bundles to 5-10 messages. The protocol works but is not suitable for high-frequency, low-latency use cases
on Ethereum -- it is better suited for moderate-throughput cross-ledger messaging where Ethereum's finality
guarantees justify the cost.
