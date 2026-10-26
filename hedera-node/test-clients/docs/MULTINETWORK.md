# Multi-network tests

How a `@MultiNetworkHapiTest` run brings up its subprocess networks, from test-plan start through a
cold or warm network start to teardown. For the fixture files themselves (layout and
regeneration) see [`../tss-startup-assets/README.md`](../tss-startup-assets/README.md).

The main classes:

|                           Class                           |                                                                Role                                                                 |
|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `MultiNetworkHapiTest` / `@Network`                       | Declares the networks a test needs (name, size, setup overrides, mTLS, `tssPreload`).                                               |
| `SharedMultiNetworkLauncherSessionListener`               | Collects the declared networks and their groups at test-plan start and reserves their ports; stops any still running at plan end.   |
| `MultiNetworkGroupQueue` / `NetworkGroup`                 | Admits network groups under a node budget, so only as many groups run at once as the budget allows.                                 |
| `ClprNetworkLocksProvider` / `@MultiNetworkLeakyHapiTest` | Locks each test's networks: `READ` by default (tests on a network run concurrently), `READ_WRITE` for leaky tests (run alone).      |
| `MultiNetworkExtension`                                   | Boots a group's networks on its first test and tears them down after its last, runs the cold/warm TSS path, applies test overrides. |
| `TssFixtures`                                             | Resolves, merges, writes and installs the per-network genesis TSS fixtures.                                                         |

## Phase 0 — test-plan start

`SharedMultiNetworkLauncherSessionListener` scans the test plan for `@MultiNetworkHapiTest` methods
(skipping `@Disabled` ones, so e.g. the fixture generators never boot their networks) and collects one
`@Network` config per network name into `MultiNetworkExtension.DECLARED_CONFIGS`, reserving each one's ports
up front. It registers every test with `MultiNetworkGroupQueue` under its network group (the set of networks
it uses). A test class whose `@ResourceLock`s cover the networks of more than one group fails its groups,
since that could deadlock with the queue. It boots no nodes.

## Phase 1 — starting a network

A group's networks start on its first test (see Phase 2), in parallel, through `startNetworks`. For each
network, `startNetworks`:

1. **Allocates ports.** `ensureFixturePortReservations` first reserves the port range of every committed
   fixture (its lowest gRPC port and node count), when they do not overlap another fixture. Networks without a usable reservation get a free slot
   that avoids the reserved ranges.
2. **Seeds node config.** Infra defaults, the test's `setupOverrides` and the typed mTLS settings go into
   each node's `application.properties`.
3. **Chooses cold or warm.** Warm if `TssFixtures.fixturePresent(name)`; otherwise cold.

Then all networks start in parallel and wait for `ACTIVE`.

### Warm start (fixture present)

1. Before the node JVM starts, `TssFixtures.installFixture` gunzips the network's fixture into each node's
   `data/config/genesis-network.json`. If reserved ports overlap another fixture, the loader patches
   transport endpoints and construction roster references while preserving keys, weights and proofs.
2. At genesis, `TssStartupNetworks` preloads the hinTS and history (WRAPS) state from the file and writes
   only the self node's private key to its local key files.
3. The harness waits for the preload log line, then for `[CLPR-SYNC-POINT]` (the first block proof that
   embeds the WRAPS proof). From then on, state proofs captured on this network are verifiable by peers.

Typical cost: well under a minute per network.

### Cold start (no fixture)

1. The export overrides `networkAdmin.diskNetworkExport=ONLY_FREEZE_BLOCK` and
   `networkAdmin.diskNetworkExportTss=true` are added. WRAPS parameters are bundled in the library.
2. The nodes bootstrap TSS and WRAPS from scratch; this can take a few minutes depending on hardware.
3. After `WRAPS-extensible? true` and `[CLPR-SYNC-POINT]`, the harness settles briefly, then freezes the
   network (with background traffic so the freeze block's proof gets signed). Each node writes a
   TSS-enriched `output/network.json` carrying only its own private key.
4. `TssFixtures.generateNetworkFixture` merges the per-node exports into one
   `<network>-genesis-network.json.gz`: network-wide data from the lowest node, plus every node's own
   private-key entry. The merge fails if an export carries another node's private key or lacks its own.
5. The frozen network is replaced by a fresh one on the same ports with the new fixture installed — i.e. a
   warm start. Every later run then takes the warm path.

## Phase 2 — each test

Before a test runs, JUnit takes one lock per network it declares, through `ClprNetworkLocksProvider` (the
`@ResourceLock` provider on `@MultiNetworkHapiTest`). The lock is `READ` by default, so tests that only use
their own channel run concurrently on the same networks. A test, or a whole class, marked
`@MultiNetworkLeakyHapiTest` takes `READ_WRITE` and runs alone on its networks: mark any test that changes
ledger throttles or config, overrides a network property, or restarts a network. Tests on different
networks never share a lock.

`MultiNetworkExtension.beforeEach`:

- Fails the test if any of its networks was not declared by the listener (e.g. the test is `@Disabled`);
  networks are never started per test.
- Waits for the test's group to be admitted by `MultiNetworkGroupQueue`, which runs groups concurrently
  while their total node count fits the budget, lighter groups first.
- Boots the group's networks on its first test (Phase 1) and keeps them in `SHARED_NETWORKS`; later tests
  reuse them and re-check each node is `ACTIVE` (an earlier test may have frozen or restarted it). If the
  boot fails, the group's nodes are freed and its remaining tests fail fast. Concurrent tests of a group
  boot or reuse its networks one at a time, under a per-group lock.
- Applies the test's `setupOverrides` at runtime through a `0.0.121` file update, after snapshotting the
  previous values. Startup-only node settings (`clpr.mtlsPort`, CA paths) are skipped here — they only take
  effect from `application.properties`.

`MultiNetworkExtension.afterEach` restores the snapshotted values and clears each node's CLPR peer-endpoint
cache so the next test starts clean. After a group's last test (including tests that were skipped or never
ran), its networks are torn down and its nodes are returned to the budget.

## Phase 3 — test-plan end

The listener stops any shared network still running.
