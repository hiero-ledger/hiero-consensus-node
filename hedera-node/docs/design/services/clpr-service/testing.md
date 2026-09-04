# CLPR Testing (Hiero)

> Prereq: `clpr-spec/clpr-test-spec.md` for the cross-implementation conformance suite
> intent. This doc covers the *Hiero test infrastructure* — where the tests live, how to
> run them, and how the multi-network harness works.

## Test taxonomy

|       Layer       |                               Where                               |                            Notes                            |
|-------------------|-------------------------------------------------------------------|-------------------------------------------------------------|
| Unit tests        | `hedera-clpr-service-impl/src/test/...`                           | Per-handler + utils (`ClprHashUtils`, `ClprSlashingUtils`). |
| EVM unit tests    | `hedera-smart-contract-service-impl/src/test/...`                 | `SendMessageCall`, `GetChannelCall`, translators.           |
| App-layer tests   | `hedera-app/src/test/.../workflows/clpr/`                         | `ClprBundleSubmitter`, sync workflow, throttle.             |
| BDD / HAPI suites | `test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/` | Single-network and multi-network.                           |

## BDD suite catalogue (`bdd/suites/clpr/`)

All tagged `CLPR` (`TestTags.CLPR`). Multi-network ones additionally tagged
`MULTINETWORK`.

|             Suite              |                        Tags                         |                                                                              Covers                                                                               |
|--------------------------------|-----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ClprConnectorSuite`           | CLPR                                                | Connector commit-reveal (CLPR-3.1, 3.2): commitment, reveal, stake locking, error paths.                                                                          |
| `ClprCloseChannelSuite`        | CLPR                                                | Admin auth + channel status transitions for `closeChannel`.                                                                                                       |
| `ClprLedgerConfigurationSuite` | CLPR                                                | Admin auth + validation for `updateLedgerConfiguration` (CLPR-1.2): seed-endpoint limits, invalid configs.                                                        |
| `ClprRedactMessageSuite`       | CLPR                                                | Admin-key redaction; running-hash preservation.                                                                                                                   |
| `ClprSendMessageSuite`         | CLPR                                                | EVM `0x16e.sendMessage` happy path + throttle / authorization revert paths.                                                                                       |
| `ClprSubmitBundleSuite`        | CLPR                                                | Submit-bundle handler validation incl. proof verification failure paths.                                                                                          |
| `ClprOrchestratorSubmitTest`   | CLPR + `@LeakyEmbeddedHapiTest(NEEDS_STATE_ACCESS)` | Embedded test of the orchestrator submitting a bundle through the regular consensus path.                                                                         |
| `ClprHieroToHieroSuite`        | CLPR + MULTINETWORK + `@MultiNetworkHapiTest`       | End-to-end against two real subprocess networks: register, complete channels+connectors, contract calls, cross-ledger transfer; asserts message delivery on peer. |

## Multi-network harness (`bdd/junit/`)

Used only for the Hiero↔Hiero E2E. Avoid for ordinary handler/EVM coverage.

### `@MultiNetworkHapiTest`

Custom JUnit 5 `@TestFactory` annotation. Per test method, provisions N isolated
`SubProcessNetwork`s. Default `[PRIMARY, PEER]`. Each `Network` declaration accepts:

- `name` — logical role (`PRIMARY`, `PEER`, …) used for parameter resolution.
- `size` — node count.
- `shard`, `realm` — entity ID prefix.
- `firstGrpcPort` — port allocation start.
- `setupOverrides[]` — per-network config overrides applied before launch.

Uses `@ResourceLock("NETWORK")` to enforce sequential execution (cannot share a host with
other network-using tests).

### `MultiNetworkExtension`

`BeforeEach` / `AfterEach` / `ParameterResolver`:

- `BeforeEach`: creates each `SubProcessNetwork` via `newIsolatedNetwork(...)`,
  starts them, registers them in the extension store.
- Parameter resolution: matches `SubProcessNetwork` / `HederaNetwork` parameters by
  declaration order — the first matches `PRIMARY`, the second `PEER`, etc.
- `AfterEach`: terminates networks cleanly + purges `HapiClients.channelPools` of stale
  URIs. Without the purge, the next test would re-use a closed channel and hang.

### `SubProcessNetwork.newIsolatedNetwork`

Modifications to `SubProcessNetwork` and `ProcessUtils` allow distinct working
directories and non-overlapping port ranges per network. See
`bdd/junit/hedera/subprocess/SubProcessNetwork.java`.

## Running locally

```bash
# Unit tests for the impl module
./gradlew :hedera-node:hedera-clpr-service-impl:test

# All CLPR-tagged BDD suites
./gradlew :test-clients:test --tests '*Clpr*'

# Multi-network E2E only (slow; spawns 2 networks)
./gradlew :test-clients:test --tests '*ClprHieroToHiero*'
```

The multi-network suite requires enough free TCP ports (~12 per network) and enough RAM
to run two consensus nodes simultaneously.

## Patterns to follow when adding tests

- For any **new handler**: add a suite under `bdd/suites/clpr/` driven by
  `HapiSpecOperations` mirroring the patterns in the connector / close-channel suites.
- For **EVM precompile changes**: extend `ClprSendMessageSuite` (HAPI-level) plus a
  unit test under `hedera-smart-contract-service-impl/src/test/...`.
- For **consensus-path features that need real sync**: use the multi-network harness.
  Don't try to fake a peer with mocks at the BDD layer — the gRPC sync RPC is real.
- For **state-snooping tests**: use `@LeakyEmbeddedHapiTest(NEEDS_STATE_ACCESS)` with
  the embedded harness, like `ClprOrchestratorSubmitTest`.

## Known weak assertions

### `ClprHieroToHieroSuite.oneWayDelivery` / `fullRoundTrip`

These tests currently assert only that `receivedMessageId` and `ackedMessageId`
advance by the expected amount after cross-ledger delivery. That is insufficient:

- **Counter advancement alone is not a success proof.** Both counters advance on
  `APPLICATION_ERROR` and `CONNECTOR_NOT_FOUND` as well as on `SUCCESS`. A test that
  passes with these counters while the application contract is returning errors is a
  false positive.
- **Missing application-side receipt assertion.** Spec test 4.1.1 requires asserting
  that the target application contract actually received the message — i.e. an
  application-side receipt or observable side-effect confirming dispatch succeeded.
- **Missing queue-metadata consistency check.** After delivery, the outbound queue on
  the sender and the reply queue on the receiver should be in a consistent state
  (correct running hashes, correct ack IDs). These are not currently asserted.

**Status (D-10 from DRIFT-REVIEW-2026-05.md):** the above gaps are tracked and known.
Until the tests grow proper assertions, treat a passing `ClprHieroToHieroSuite` as
"the sync pipeline completed without a crash" rather than "message delivery was
semantically correct." When adding new multi-network test coverage, always include:

1. An explicit `SUCCESS` status check on at least one reply.
2. A state-snoop (via `@LeakyEmbeddedHapiTest`) or an application-contract query
   confirming the application received the message.
3. A post-delivery check of the running hash on both ends.

## Cross-implementation conformance

The `clpr-spec/clpr-test-spec.md` describes the implementation-agnostic conformance
tests every CLPR implementation must pass. The Hiero suites above cover the
transaction-shape / state-shape parts of those requirements; the cross-ledger E2E suite
covers the wire-protocol parts. There is currently no automated mapping from
`clpr-test-spec.md` test IDs to suite methods — keep that mapping in mind when
adding/reorganising tests.
