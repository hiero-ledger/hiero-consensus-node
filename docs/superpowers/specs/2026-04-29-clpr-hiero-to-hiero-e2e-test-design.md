# CLPR Hiero-to-Hiero End-to-End Integration Test

**Date:** 2026-04-29
**Status:** Approved
**Scope:** `hedera-node/test-clients` — two-network CLPR integration test using `@MultiNetworkHapiTest`

---

## Goal

Add integration tests that spin up two independent Hiero subprocess networks and exercise the full
CLPR pipeline end-to-end: connection registration, connector registration, message send, and bundle
delivery via the nodes' built-in `ClprConnectionManager` sync mechanism. Two tests: one-way
delivery and full round-trip.

---

## Background

The EVM integration tests in `clpr-relay` spin up two Besu/Anvil nodes and a relay process to
exercise the same pipeline. On Hiero, the relay is built into the node as `ClprConnectionManager`
— no external relay process is needed. The `@MultiNetworkHapiTest` annotation and
`MultiNetworkExtension` exist in the prototype branch (`20111-clpr-prototype`) but were never
merged to main. They are ported here with minimal changes.

---

## File Structure

|               File               |       Action        |                                   Location                                   |
|----------------------------------|---------------------|------------------------------------------------------------------------------|
| `MultiNetworkHapiTest.java`      | Port from prototype | `test-clients/src/main/java/com/hedera/services/bdd/junit/`                  |
| `MultiNetworkExtension.java`     | Port from prototype | `test-clients/src/main/java/com/hedera/services/bdd/junit/extensions/`       |
| `HapiClprCompleteConnector.java` | New                 | `test-clients/src/main/java/com/hedera/services/bdd/spec/transactions/clpr/` |
| `ClprHieroToHieroSuite.java`     | New                 | `test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/`            |

No modifications to existing files.

---

## Network Configuration

Each test method declares two 1-node subprocess networks with distinct chain IDs and non-overlapping
gRPC port ranges:

```java
@MultiNetworkHapiTest(networks = {
    @Network(name = "ledgerA", size = 1, firstGrpcPort = 35400,
        setupOverrides = {
            @ConfigOverride(key = "clpr.enabled",  value = "true"),
            @ConfigOverride(key = "clpr.chainId",  value = "hiero:298")
        }),
    @Network(name = "ledgerB", size = 1, firstGrpcPort = 36400,
        setupOverrides = {
            @ConfigOverride(key = "clpr.enabled",  value = "true"),
            @ConfigOverride(key = "clpr.chainId",  value = "hiero:299")
        })
})
Stream<DynamicTest> testMethod(SubProcessNetwork ledgerA, SubProcessNetwork ledgerB) { ... }
```

`MultiNetworkExtension` starts both networks in `@BeforeEach`, tears them down in `@AfterEach`.
Networks are independent — no shared ports, no shared state.

---

## Shared Setup (both test methods)

Executed via `withOpContext` targeting each network in turn using `HapiSpec.multiNetworkHapiTest`.

### Step 1 — Endpoint wiring

Each network must know where to reach the other so `ClprConnectionManager` can discover the peer:

- `HapiClprUpdateLedgerConfiguration` on ledger A: set `seedEndpoints` to `[127.0.0.1:<portB>]`
- `HapiClprUpdateLedgerConfiguration` on ledger B: set `seedEndpoints` to `[127.0.0.1:<portA>]`

Ports are read from `network.nodes().get(0).getGrpcPort()` after the network starts.

### Step 2 — Contract deployment

On each network:
- `contractCreate(ClprPassThroughVerifier)` — accepts all inbound proofs; used as the verifier in `completeConnection`
- `contractCreate(MockClprConnector)` — pass-through connector; authorizes all outbound messages and pays for inbound execution

Both contracts are already in `test-clients/src/main/resources/contract/contracts/`.

### Step 3 — Connection registration (commit-reveal)

A deterministic keypair (fixed secp256k1 secret key) + all-zero salt produces the same
`connectionId` on both networks. This is required for the CLPR protocol.

On ledger A:
1. `HapiClprRegisterConnection(commitment)` — commit phase
2. `HapiClprCompleteConnection(connectionId, pubKey, sig, salt, verifier=passThrough, configProof)` — reveal phase

On ledger B: same two steps with the same `connectionId`, using ledger B's deployed
`ClprPassThroughVerifier`.

### Step 4 — Connector registration (commit-reveal)

`connectorId = keccak256(connectionId || pubKey || salt)` — same formula on both sides gives the
same `connectorId`.

On each network:
1. `HapiClprRegisterConnector(commitment)` — commit phase
2. `HapiClprCompleteConnector(connectorId, pubKey, sig, salt, connectionId, connectorContract, adminKey, lockedStake)` — reveal phase

`lockedStake` must be >= `clpr.minLockedStake` (default 100 000 000 tinybars).

---

## Test 1 — One-Way Delivery

**Goal:** Prove a message enqueued on ledger A is delivered to ledger B by the background sync.

```
1. contractCallWithFunctionAbi("sendMessage", connectionId, connectorId, targetApp, sender, payload)
   targeting the CLPR system contract (0x16e) on ledger A

2. Poll ledger B (up to 2 minutes, every 2 seconds):
   contractCallLocalWithFunctionAbi("getConnection", connectionId) on 0x16e
   until connection.receivedMessageId >= 1

3. Assert: receivedMessageId == 1
```

`targetApp` is ledger B's `MockClprConnector` address (or any 20-byte address — on
`APPLICATION_ERROR` a response is still generated). The test does not assert application behavior,
only delivery.

---

## Test 2 — Full Round-Trip

**Goal:** Prove a message sent from A reaches B and a response travels back to A.

```
1. Same send as Test 1

2. Poll ledger B until receivedMessageId >= 1  (delivery confirmed)

3. Poll ledger A (up to 2 minutes):
   contractCallLocalWithFunctionAbi("getConnection", connectionId) on 0x16e
   until connection.ackedMessageId >= 1

4. Assert: ackedMessageId == 1
```

`ackedMessageId` advances when ledger A receives the bundle from ledger B carrying the response
message. The response may be `SUCCESS` or `APPLICATION_ERROR` — both advance the ack counter.

---

## MultiNetworkHapiTest Annotation

Ported from `origin/20111-clpr-prototype` commit `3179c1bf`. Changes from prototype:
- Remove any references to the old `clprQueue` config keys
- Keep `@ResourceLock(value = "NETWORK", mode = READ)` to prevent parallel network startup conflicts

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({MultiNetworkExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK", mode = READ)
public @interface MultiNetworkHapiTest {
    Network[] networks() default { @Network(name = "PRIMARY"), @Network(name = "PEER") };

    @interface Network {
        String name();
        int size() default 4;
        long shard() default -1;
        long realm() default -1;
        int firstGrpcPort() default -1;
        ConfigOverride[] setupOverrides() default {};
    }
}
```

## MultiNetworkExtension

Ported from `origin/20111-clpr-prototype` commit `6d9db4c7`. Responsibilities:
- `@BeforeEach`: call `SubProcessNetwork.liveNetwork(name, size, shard, realm)` for each declared network, apply `setupOverrides` via `configureApplicationProperties`, start each network, await ready
- `@AfterEach`: call `safeTerminate()` on each network
- `ParameterResolver`: inject networks as `SubProcessNetwork` or `HederaNetwork` method parameters in declaration order

---

## HapiClprCompleteConnector

New builder for `ClprCompleteConnectorTransactionBody`. Follows the exact pattern of
`HapiClprCompleteConnection`:

Fields: `connectorId` (bytes), `publicKey` (bytes), `signature` (bytes),
`signatureScheme` (ClprSignatureScheme), `salt` (bytes), `connectionId` (bytes),
`connectorContract` (ContractID), `adminKey` (Key), `lockedStake` (long).

`opBodyDef` sets all fields on `ClprCompleteConnectorTransactionBody`. `defaultSigners` returns
payer only (permissionless). `type()` returns `HederaFunctionality.ClprCompleteConnector`.

---

## Test Tag

`ClprHieroToHieroSuite` is tagged `@Tag(TestTags.MULTINETWORK)`. Add `MULTINETWORK = "MULTINETWORK"`
to `TestTags` if not present.

---

## Known Limitations

- **Verifier:** `ClprPassThroughVerifier` accepts all proofs without cryptographic validation.
  Replace with a real Hiero state proof verifier once that is implemented.
- **Application dispatch:** Tests assert queue state advancement only; they do not verify
  application-layer payload correctness.
- **Single node per network:** Each network runs one node. Multi-node network behavior (e.g.,
  consensus under partial failure) is not covered.
