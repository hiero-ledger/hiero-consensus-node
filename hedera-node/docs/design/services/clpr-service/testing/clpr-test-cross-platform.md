# CLPR Cross-Platform Test Definitions

This file covers CLPR tests that require non-Hiero networks: Hiero-to-Ethereum integration,
Ethereum-to-Ethereum integration, multi-ledger topologies (N >= 3), and EVM-specific adversarial
scenarios. These tests fall outside standard CITR suites because CITR infrastructure currently
deploys only Hiero networks.

The audience for these tests is developers working on the Ethereum/BESU CLPR Service contract, the
Hiero TSS verifier for EVM, and the Ethereum BLS verifier for Hiero. Multi-ledger topology tests
verify that the protocol works correctly when more than two networks are connected.

> These test definitions are subject to change as the CLPR specification evolves during implementation.

---

# 1. Common Test Infrastructure

## 1.1 Hiero-to-Ethereum Requirements

- **One Hiero network** deployed via SOLO with CLPR enabled.
- **One Ethereum network** (BESU node in Kubernetes, or local Hardhat/Anvil instance) with the CLPR
  Service Solidity contract deployed and initialized.
- **Verifier contracts:** A Hiero TSS verifier deployed on the Ethereum network (validates Hiero proofs
  on EVM). An Ethereum BLS sync committee verifier deployed on Hiero (validates Ethereum proofs).
- **Cross-network test driver** that can submit Hiero HAPI transactions and Ethereum contract calls,
  and query state on both sides.
- **JSON-RPC relay** on the Hiero side for EVM-compatible interactions.

## 1.2 Ethereum-to-Ethereum Requirements

- **Two Ethereum networks** (BESU, Hardhat, or Anvil). No Hiero or SOLO involved.
- **CLPR Service Solidity contract** deployed on both networks.
- **Test driver** that holds `ethers.js` providers for both networks.

## 1.3 Multi-Ledger Requirements (N >= 3)

- **Three networks** deployed via SOLO (three Hiero networks, or two Hiero + one Ethereum), depending
  on the test.
- **Connections registered between each pair** as required by the topology (hub-and-spoke, triangle).

---

# 2. Hiero-to-Ethereum Integration

## 2.1 End-to-End Message Flow

**Reference:** clpr-test-spec.md §4.2.1

**Purpose:** Verify a message flows from a Hiero application to an Ethereum application and the
response returns.

**Properties to Test:**

- P1: A Hiero endpoint constructs a TSS proof that the Ethereum verifier validates.
- P2: The Ethereum CLPR Service processes the bundle and dispatches to the target application.
- P3: The response flows back from Ethereum to Hiero through the Ethereum endpoint and Hiero bundle
  submission pipeline.
- P4: The Hiero application receives the response with `SUCCESS` status.

**Setup:**

Deploy Hiero and Ethereum networks. Register a Connection between them (Hiero TSS verifier on
Ethereum, Ethereum BLS verifier on Hiero). Register Connectors on both sides. Deploy echo
applications on both sides.

**Steps:**

1. On Hiero, send a message targeting the Ethereum application.

2. Wait for delivery on Ethereum (poll the Ethereum CLPR Service contract state or events).

3. Verify the Ethereum application received the message.

4. Wait for the response to return to Hiero.
   → Assert: Hiero application received `SUCCESS` with the expected reply data.

**Cleanup:** None.

## 2.2 Verifier Cross-Validation

**Reference:** clpr-test-spec.md §4.2.2

**Purpose:** Verify that both verifiers (Hiero TSS on Ethereum, Ethereum BLS on Hiero) correctly
validate proofs from their respective source ledgers.

**Properties to Test:**

- P1: The Hiero TSS verifier on Ethereum accepts valid Hiero proofs and rejects invalid ones.
- P2: The Ethereum BLS verifier on Hiero accepts valid Ethereum proofs and rejects invalid ones.

**Setup:**

Deploy both verifiers. Prepare known-good and known-bad proof bytes for each.

**Steps:**

1. Submit a valid Hiero proof to the TSS verifier on Ethereum.
   → Assert: verification succeeds. Correct configuration/messages returned.

2. Submit an invalid Hiero proof (tampered bytes) to the TSS verifier on Ethereum.
   → Assert: verification fails (revert).

3. Submit a valid Ethereum proof to the BLS verifier on Hiero.
   → Assert: verification succeeds.

4. Submit an invalid Ethereum proof to the BLS verifier on Hiero.
   → Assert: verification fails.

**Cleanup:** None.

## 2.3 Gas Cost Profiling

**Reference:** clpr-test-spec.md §4.2.3

**Purpose:** Measure and record Ethereum gas costs for CLPR operations.

**Properties to Test:**

- P1: Gas cost of `registerConnection` on Ethereum.
- P2: Gas cost of `registerConnector` on Ethereum.
- P3: Gas cost of `sendMessage` on Ethereum.
- P4: Gas cost of `submitBundle` on Ethereum (varies with bundle size).
- P5: Gas cost of `closeConnection` on Ethereum.

**Setup:**

Ethereum network with CLPR Service deployed. A registered Connection and Connector.

**Steps:**

For each operation, execute it and record `gasUsed` from the transaction receipt.

→ Report: gas cost per operation. For `submitBundle`, report gas as a function of message count (1,
10, 50). Verify all operations fit within Ethereum block gas limits.

**Cleanup:** None.

## 2.4 Commitment Level Enforcement

**Reference:** clpr-test-spec.md §4.2.5

**Purpose:** Verify that verifiers enforce their configured commitment level.

**Properties to Test:**

- P1: A `finalized`-level verifier rejects proofs at `latest` commitment.
- P2: A `latest`-level verifier accepts proofs that a `finalized`-level verifier would reject.

**Setup:**

Deploy two verifiers on Hiero for Ethereum proofs: one enforcing `finalized`, one accepting `latest`.

**Steps:**

1. Produce a proof at `latest` commitment (recent but not yet finalized block).

2. Submit to the `latest` verifier.
   → Assert: accepted.

3. Submit the same proof to the `finalized` verifier.
   → Assert: rejected (block not yet finalized).

**Cleanup:** None.

---

# 3. Ethereum-to-Ethereum Integration

## 3.1 End-to-End Message Flow

**Reference:** clpr-test-spec.md §4.3.1

**Purpose:** Validate the Solidity CLPR Service contract independently of Hiero by sending a message
between two EVM networks.

**Properties to Test:**

- P1: The complete message lifecycle works using only the Solidity contracts.
- P2: ABI encoding/decoding round-trips correctly across two separate EVM instances.

**Setup:**

Two Ethereum networks. CLPR Service deployed on both. Connection registered, Connectors funded,
echo applications deployed.

**Steps:**

1. On Network 1, send a message to Network 2.

2. Relay the bundle (via the test driver or an inline relayer function).

3. Verify delivery on Network 2 and response return to Network 1.

**Cleanup:** None.

## 3.2 Gas Optimization Validation

**Reference:** clpr-test-spec.md §4.3.2

**Purpose:** Verify that Ethereum storage optimization patterns (aggressive payload deletion after
acknowledgement) are effective.

**Properties to Test:**

- P1: After acknowledgement, message payloads are cleared from contract storage.
- P2: Long-term gas cost of bundle processing does not grow with the number of historical messages
  (storage slots are freed).

**Setup:**

Two Ethereum networks with sustained message traffic.

**Steps:**

1. Send 100 messages and wait for all responses.

2. Query contract storage to verify payload slots are cleared for acknowledged messages.

3. Send another 100 messages. Compare `submitBundle` gas cost with the first batch.
   → Assert: gas cost is comparable (not growing due to storage accumulation).

**Cleanup:** None.

## 3.3 Proxy Upgrade Testing

**Reference:** clpr-test-spec.md §4.3.3

**Purpose:** Verify that upgrading the CLPR Service behind a proxy does not corrupt state.

**Properties to Test:**

- P1: After a proxy upgrade, Connection state (queue metadata, Connector balances) is intact.
- P2: Message delivery continues normally after the upgrade.

**Setup:**

Deploy the CLPR Service behind an EIP-1967 upgradeable proxy. Establish a Connection with active
traffic.

**Steps:**

1. Send messages and verify delivery (baseline).

2. Upgrade the proxy implementation to a new version (with no behavioral changes).

3. Query Connection state.
   → Assert: all queue metadata, Connector balances, and status are unchanged.

4. Send more messages.
   → Assert: delivery works normally.

**Cleanup:** None.

---

# 4. Multi-Ledger Topologies (N >= 3)

## 4.1 Hub-and-Spoke

**Reference:** clpr-test-spec.md §4.4.1

**Purpose:** Verify that a hub ledger with Connections to multiple spokes operates correctly with
concurrent traffic on all Connections.

**Properties to Test:**

- P1: Messages on Hub↔Spoke1 and Hub↔Spoke2 flow independently.
- P2: Throughput on one Connection does not degrade the other.

**Setup:**

Three networks (Hub, Spoke1, Spoke2). Connections: Hub↔Spoke1, Hub↔Spoke2. Connectors and
applications on all.

**Steps:**

1. Send messages concurrently on both Connections.

2. Verify all messages delivered on both spokes.

3. Verify queue metadata on each Connection is independent.

**Cleanup:** None.

## 4.2 Triangle Topology

**Reference:** clpr-test-spec.md §4.4.2

**Purpose:** Verify three-way connectivity for multi-ledger swap testing.

**Properties to Test:**

- P1: Messages flow correctly on each of the three Connections (A↔B, B↔C, C↔A).
- P2: Each Connection operates independently.

**Setup:**

Three networks with Connections forming a triangle.

**Steps:**

1. Send a message on each of the three Connections concurrently.

2. Verify all three messages delivered and responses returned.

3. Verify queue metadata on each Connection is independent.

**Cleanup:** None.

## 4.3 Connection Isolation

**Reference:** clpr-test-spec.md §4.4.3

**Purpose:** Verify that adverse conditions on one Connection do not affect others.

**Properties to Test:**

- P1: Closing one Connection does not affect traffic on other Connections.
- P2: A PAUSED Connection does not affect other Connections.

**Setup:**

Hub-and-spoke or triangle topology with active traffic on all Connections.

**Steps:**

1. Close Connection A↔B.

2. Send messages on Connection A↔C.
   → Assert: delivery succeeds. Connection A↔C is unaffected.

3. On a fresh topology, trigger PAUSED on one Connection (response ordering violation).

4. Send messages on another Connection.
   → Assert: delivery succeeds. The unaffected Connection operates normally.

**Cleanup:** None.

---

# 5. EVM-Specific Adversarial Tests

## 5.1 Reentrancy During Application Dispatch

**Reference:** clpr-test-spec.md §5.6.1

**Purpose:** Verify that the Ethereum CLPR Service's reentrancy guard prevents re-entrance during
application dispatch.

**Properties to Test:**

- P1: A malicious application that attempts to call `sendMessage` from within its message callback
  is blocked by the reentrancy guard.
- P2: Connection state is not corrupted.

**Setup:**

Deploy a malicious application contract on Ethereum that calls `sendMessage` inside its
`receiveMessage` callback.

**Steps:**

1. Send a CLPR message targeting the malicious application.

2. On the Ethereum CLPR Service, verify: the reentrancy attempt was blocked. The transaction either
   reverts the inner call or the entire dispatch (depending on guard implementation).

3. Verify: Connection state (queue metadata, Connector balances) is not corrupted.

**Cleanup:** None.

## 5.2 Gas Exhaustion During Callback

**Reference:** clpr-test-spec.md §5.6.2

**Purpose:** Verify that a gas-consuming application callback is bounded by the fixed gas stipend.

**Properties to Test:**

- P1: The application callback runs out of gas within the stipend.
- P2: The CLPR Service handles the revert gracefully (generates an `APPLICATION_ERROR` response).
- P3: Connection state is not corrupted.

**Setup:**

Deploy an application contract on Ethereum whose callback consumes excessive gas (e.g., an infinite
loop or large memory allocation).

**Steps:**

1. Send a CLPR message targeting the gas-consuming application.

2. Verify: the callback reverts due to gas exhaustion. The CLPR Service generates an
   `APPLICATION_ERROR` response.

3. Verify: Connection state is intact. Subsequent messages process normally.

**Cleanup:** None.
