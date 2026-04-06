# CLPR Smart Contract Testing Guide

This document is a reference guide for creating and running smart contract tests for CLPR. It covers
the testing framework, contract architecture, test patterns, and the progression from local unit tests
through network-level integration tests. It is derived from working CLPR prototype tests and serves as
a how-to guide for developers new to CLPR smart contract testing.

Smart contract tests do not require SOLO deployments — they run locally against an in-process EVM
(Hardhat) or against already-deployed networks via JSON-RPC relay.

---

# 1. Framework and Tooling

## 1.1 Primary Framework: Hardhat

CLPR smart contract tests use Hardhat as the primary framework:

- **Compiler:** Solidity 0.8.24, optimizer enabled (500 runs), EVM version `cancun`
- **Test runner:** Mocha (bundled with Hardhat), with Chai assertions
- **Matchers:** `@nomicfoundation/hardhat-chai-matchers` for `.revertedWithCustomError`, `.emit`, etc.
- **Upgrades:** `@openzeppelin/hardhat-upgrades` for proxy testing
- **Coverage:** `solidity-coverage` for line/branch coverage reports
- **Foundry integration:** `@nomicfoundation/hardhat-foundry` available for Forge-based tests

## 1.2 Key Commands

```bash
# Run all Hardhat tests
npx hardhat test

# Run a specific test file
npx hardhat test test/solidity/clpr/clprMiddleware.js

# Run with Foundry
forge test

# Compile contracts
npx hardhat compile
```

## 1.3 Network Configurations

| Network | Use | How Accessed |
|---|---|---|
| `hardhat` (in-process) | Unit tests — fast, no external dependencies | Default; `npx hardhat test` |
| `local` | Network tests against SOLO-deployed Hiero via JSON-RPC relay | `--network local`; requires relay at `http://127.0.0.1:7546` |
| Custom URLs | Multi-network tests with separate SRC/DST providers | Configured via environment variables (`CLPR_SRC_RPC_URL`, `CLPR_DST_RPC_URL`) |

---

# 2. Contract Architecture

CLPR smart contracts are organized in layers. Understanding this layering is essential for writing tests
at the right level.

## 2.1 Types

`ClprTypes.sol` — Shared data structures used across all CLPR contracts:
- `ClprMessage` — A cross-ledger message with sender, target application, connector ID, and payload.
- `ClprMessageResponse` — Response to a message, with status and reply data.
- `ClprApplicationMessage` — Wrapper that adds routing metadata (source/destination connector addresses).
- `ClprFundingState` — Enum tracking whether a connector is funded or underfunded.
- `ClprBalanceReport` — Connector balance information propagated as control messages.

## 2.2 Interfaces

| Interface | Purpose |
|---|---|
| `IClprMiddleware` | Core CLPR Service API: register/pair connectors, authorize messages, process responses |
| `IClprConnector` | Connector API: authorize, reimburse, deposit, funding state management |
| `IClprApplication` | Application callback API: receive messages, receive responses |
| `IClprQueue` | Message queue API: enqueue, dequeue, deliver |

## 2.3 Implementations

| Contract | Role |
|---|---|
| `ClprMiddleware` | The CLPR Service implementation: connector registration, authorization routing, funding-state control message propagation |
| `ClprFundingAwareConnectorBase` | Abstract base for connectors: handles deposits (native + ERC20), funding-state machine, epoch tracking, auto-notification to middleware |

## 2.4 Mock Contracts (Critical for Testing)

| Mock | What It Replaces | Behavior |
|---|---|---|
| `MockClprConnector` | Real connector | Full `IClprConnector` implementation with admin controls (`setDenyAuthorize`) for testing authorization failure paths |
| `MockClprQueue` | Real CLPR queue | In-memory, single-process queue: delivers requests synchronously, stores responses for explicit delivery via `deliverAllMessageResponses`. Simulates the async boundary between send and response. |
| `MockClprRelayedQueue` | Real CLPR queue for multi-network | Outbox/inbox queue: stores ABI-encoded messages in on-chain arrays for off-chain relayer pickup. Used when two separate EVM instances need to communicate. |
| `EchoApplication` | Destination application | Echoes inbound payload as response. Validates message round-trip. |
| `SourceApplication` | Source application | Sends messages with connector preference list and failover strategy. Stores responses for assertion. |

---

# 3. Test Tiers

CLPR smart contract tests are organized into three tiers, each exercising progressively more of the
real infrastructure.

## 3.1 Tier 1: Unit Tests (Hardhat In-Process EVM)

**Location:** `test/solidity/clpr/`

**What runs:** All contracts deployed in the Hardhat in-process EVM. No external network, no Kubernetes,
no SOLO. Tests execute in seconds.

**Queue mock:** `MockClprQueue` — delivers requests synchronously within the same transaction, stores
responses for explicit delivery later. This simulates the asynchronous boundary between source and
destination without actually crossing a network boundary.

**Pattern for writing unit tests:**

1. **Deploy all contracts in `beforeEach`:**
   ```javascript
   const ClprMiddleware = await ethers.getContractFactory('ClprMiddleware');
   const middleware = await ClprMiddleware.deploy(queueAddress);
   ```

2. **Set up connector topology:** Deploy mock connectors, pair them via middleware, configure
   authorization behavior (allow/deny), and fund them with mock ERC20 tokens.

3. **Send messages through the source application:** The application calls the middleware, which calls
   the connector's `authorizeMessage`, which (if approved) enqueues via the mock queue.

4. **Deliver responses explicitly:**
   ```javascript
   await mockQueue.deliverAllMessageResponses();
   ```
   This simulates the asynchronous return path. Responses are delivered to the source middleware, which
   forwards them to the source application.

5. **Assert on state:** Check connector balances, authorization counts, funding states, response
   statuses, and event emissions.

**What to test at this tier:**
- Connector authorization logic (approve, reject, failover to next connector)
- Funding-state transitions (funded → underfunded → topped up → funded)
- Pre-enqueue rejection (middleware rejects before calling authorize when it knows the remote connector
  is underfunded, based on cached balance reports)
- Balance report propagation via control messages
- Input validation (zero addresses, unauthorized callers, invalid parameters)
- Event emissions for state transitions

**What NOT to test at this tier:** ABI encoding round-trips across networks, real transaction receipts,
gas costs on Hedera, system contract interactions.

## 3.2 Tier 2: Network Tests (Two SOLO Networks via JSON-RPC Relay)

**Location:** `test/network/clpr/`

**What runs:** Contracts deployed to two separate SOLO-deployed Hiero networks, accessed via their
JSON-RPC relay endpoints. Tests take minutes.

**Queue mock:** `MockClprRelayedQueue` — an outbox/inbox pattern. The source queue stores outbound
messages as ABI-encoded bytes. An inline relayer function reads them, submits them to the destination
queue's `deliverInboundMessage`, reads the pending response, and delivers it back via
`deliverInboundResponse`.

**Pattern for writing network tests:**

1. **Create separate providers for each network:**
   ```javascript
   const srcProvider = new ethers.JsonRpcProvider(process.env.CLPR_SRC_RPC_URL || 'http://127.0.0.1:7546');
   const dstProvider = new ethers.JsonRpcProvider(process.env.CLPR_DST_RPC_URL || 'http://127.0.0.1:7547');
   const srcWallet = new ethers.Wallet(privateKey, srcProvider);
   const dstWallet = new ethers.Wallet(privateKey, dstProvider);
   ```

2. **Deploy contracts using Hardhat artifacts (not Hardhat's `ethers`):**
   ```javascript
   const artifact = await hre.artifacts.readArtifact('ClprMiddleware');
   const factory = new ethers.ContractFactory(artifact.abi, artifact.bytecode, srcWallet);
   const middleware = await factory.deploy(queueAddress, { gasLimit: 12_000_000 });
   ```
   The explicit `gasLimit` is important — Hedera's gas metering differs from Hardhat's default estimates.

3. **Relay messages between networks:**
   ```javascript
   async function relayMessageId(srcQueue, dstQueue, messageId) {
     const outboundBytes = await srcQueue.getOutboundMessage(messageId);
     await dstQueue.deliverInboundMessage(outboundBytes, { gasLimit: 12_000_000 });
     const responseBytes = await dstQueue.getPendingResponse(messageId);
     await srcQueue.deliverInboundResponse(responseBytes, { gasLimit: 12_000_000 });
   }
   ```

4. **Assert on state from both providers:** Query contract state on both networks independently.

**What to test at this tier:**
- ABI encoding/decoding round-trip across real EVM instances
- Transaction receipt validation on Hedera's EVM
- Gas consumption on real Hedera EVM (vs Hardhat estimates)
- Cross-network relay pattern correctness

**What NOT to test at this tier:** Native CLPR queue (system contract), consensus node CLPR subsystem,
TSS proof verification. These require Tier 3.

## 3.3 Tier 3: Native E2E Tests (Real CLPR System Contracts via HAPI)

**Location:** `scripts/clpr/native-messaging-solo/`

**What runs:** Contracts interact with the real CLPR system contract (`0x16e`) on SOLO-deployed Hiero
networks. Communication uses the Hedera JavaScript SDK over direct gRPC (not JSON-RPC relay). Tests
take 10-30 minutes including SOLO deployment.

**Queue:** The real CLPR system contract queue — not a mock. Messages are enqueued via the system
contract and transported by the consensus node's native CLPR subsystem.

**Pattern for writing native E2E tests:**

1. **Deploy contracts via Hedera SDK:**
   ```javascript
   const contractCreate = new ContractCreateFlow()
     .setBytecode(compiledBytecode)
     .setGas(2_000_000)
     .setConstructorParameters(params);
   const receipt = await contractCreate.execute(client);
   ```

2. **Call contracts via Hedera SDK:**
   ```javascript
   const contractExec = new ContractExecuteTransaction()
     .setContractId(contractId)
     .setGas(1_000_000)
     .setFunction('functionName', params);
   await contractExec.execute(client);
   ```

3. **The queue address is the CLPR system contract at `0x16e`.** Contracts that need to enqueue
   messages interact with this address. No mock queue is deployed — the real native CLPR subsystem
   handles message transport between the two networks.

4. **Trusted callback configuration:** Because CLPR bundle processing dispatches synthetic
   `ContractCall` transactions, the middleware must be configured to trust the operator's EVM address
   as an authorized callback caller:
   ```javascript
   await middleware.setTrustedCallbackCaller(operatorEvmAddress);
   ```

**What to test at this tier:**
- Full native CLPR message transport (enqueue → endpoint sync → bundle submit → application dispatch
  → response → return)
- System contract integration
- Funding-state control message propagation across real CLPR channels
- Block stream observability (optional: subscribe to block node gRPC for CLPR transaction visibility)

---

# 4. Shared Utilities

## 4.1 Deterministic Connector IDs

The `deriveConnectorIds` utility computes connector IDs deterministically from ledger identifiers:

```javascript
const connectorId = ethers.keccak256(
  ethers.solidityPacked(
    ['string', 'bytes32', 'bytes32', 'bytes32'],
    [prefix, ownerKey, localLedgerId, remoteLedgerId]
  )
);
```

This ensures the same connector has the same ID on both ledgers. The utility is shared across all
test tiers.

## 4.2 The 6-Message Scenario

All three test tiers exercise the same core scenario, providing confidence that behavior is consistent
from unit tests through full integration:

1. **Messages 1-2:** Connector 1 denies authorization. Connector 2 accepts. Messages delivered and
   responses returned.
2. **Response delivery:** Source middleware learns remote balance state from control messages in
   responses.
3. **Messages 3-4:** Pre-enqueue rejection — source middleware knows Connector 2 is underfunded from
   balance reports and rejects without calling `authorize`.
4. **Message 5:** Top-off — ERC20 deposit restores Connector 2's funding. Funding-state transition
   triggers control message to source middleware. Message succeeds.
5. **Message 6:** Connector 2 depletes again. Rejection resumes.

This scenario exercises authorization, failover, funding-state tracking, control message propagation,
pre-enqueue optimization, and recovery — all in a single test flow.

---

# 5. Key Differences from Standard Hiero Smart Contract Testing

| Aspect | Standard Hiero SC Tests | CLPR SC Tests |
|---|---|---|
| Network count | 1 | 1 (unit) or 2 (network/E2E) |
| Queue | N/A | Mock (unit/network) or real system contract (E2E) |
| Message transport | N/A | Synchronous mock → relayed mock → native CLPR |
| Provider pattern | Single `ethers.provider` | Dual providers (SRC + DST) for network tests |
| Gas limits | Hardhat defaults | Explicit `gasLimit: 12_000_000` for Hedera EVM |
| Callback trust | N/A | `setTrustedCallbackCaller` for synthetic CLPR dispatches |
| Connector identity | N/A | Deterministic derivation from ledger IDs |

---

# 6. Creating New CLPR Smart Contract Tests

## 6.1 For a New Unit Test

1. Create a test file in `test/solidity/clpr/`.
2. Deploy `ClprMiddleware`, `MockClprQueue`, and the application contracts in `beforeEach`.
3. Set up connector pairs with `MockClprConnector`.
4. Send messages through `SourceApplication`.
5. Call `mockQueue.deliverAllMessageResponses()` to simulate async response delivery.
6. Assert on contract state and events.

## 6.2 For a New Network Test

1. Ensure two SOLO networks are running with relay endpoints (see
   [SOLO Multi-Network Guide](clpr-test-solo-multi-network.md)).
2. Create a test file in `test/network/clpr/`.
3. Use `hre.artifacts.readArtifact` + `ethers.ContractFactory` with explicit providers (not
   Hardhat's `ethers`).
4. Deploy `MockClprRelayedQueue` on both networks instead of `MockClprQueue`.
5. Implement the relay loop: read outbox → deliver to inbox → read response → deliver response.
6. Use explicit `gasLimit` on all transactions.

## 6.3 For a New Native E2E Test

1. Ensure two SOLO networks are running with CLPR enabled (see
   [SOLO Multi-Network Guide](clpr-test-solo-multi-network.md)).
2. Create a script in `scripts/clpr/native-messaging-solo/`.
3. Use the Hedera JavaScript SDK (direct gRPC, not JSON-RPC relay).
4. Deploy contracts using `ContractCreateFlow`.
5. Use the real CLPR system contract at `0x16e` as the queue address.
6. Configure `setTrustedCallbackCaller` for the operator's EVM address.
7. Send messages and wait for native CLPR transport (poll for responses).
