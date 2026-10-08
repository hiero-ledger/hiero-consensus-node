# Multi-network tests

How a `@MultiNetworkHapiTest` run brings up its subprocess networks, from test-plan start through a
cold or warm network start to teardown. For the fixture files themselves (layout, WRAPS artifacts,
regeneration) see [`../tss-startup-assets/README.md`](../tss-startup-assets/README.md).

The main classes:

|                    Class                    |                                          Role                                          |
|---------------------------------------------|----------------------------------------------------------------------------------------|
| `MultiNetworkHapiTest` / `@Network`         | Declares the networks a test needs (name, size, setup overrides, mTLS, `tssPreload`).  |
| `SharedMultiNetworkLauncherSessionListener` | Starts every declared network once at test-plan start; stops them at plan end.         |
| `MultiNetworkExtension`                     | Allocates ports, starts networks, runs the cold/warm TSS path, applies test overrides. |
| `TssFixtures`                               | Resolves, merges, writes and installs the per-network genesis TSS fixtures.            |
| `ClprWrapsProvingKeyInstaller`              | Extracts the WRAPS proving-key archive for cold starts.                                |

## Phase 0 — test-plan start

`SharedMultiNetworkLauncherSessionListener` scans the test plan for `@MultiNetworkHapiTest` methods
(skipping `@Disabled` ones, so e.g. the fixture generators never boot their networks) and collects one
`@Network` config per network name. It then calls `MultiNetworkExtension.startNetworks` once for all of
them and keeps the result in `MultiNetworkExtension.SHARED_NETWORKS`. Every test that only uses shared
networks reuses these instances.

## Phase 1 — starting a network

For each network, `startNetworks`:

1. **Allocates ports.** `ensureFixturePortReservations` first reserves the port range of every committed
   fixture (its lowest gRPC port and node count), so a network with a fixture gets back the exact ports it
   was captured with. A network without a fixture gets a free slot that avoids all of them.
2. **Seeds node config.** Infra defaults, the test's `setupOverrides` and the typed mTLS settings go into
   each node's `application.properties`.
3. **Chooses cold or warm.** Warm if `TssFixtures.fixturePresent(name)`; otherwise cold.

Then all networks start in parallel and wait for `ACTIVE`.

### Warm start (fixture present)

1. Before the node JVM starts, `TssFixtures.installFixture` gunzips the network's fixture into each node's
   `data/config/genesis-network.json`. Ports are not patched — they already match (step 1 above).
2. At genesis, `TssStartupNetworks` preloads the hinTS and history (WRAPS) state from the file and writes
   only the self node's private key to its local key files.
3. The harness waits for the preload log line, then for `[CLPR-SYNC-POINT]` (the first block proof that
   embeds the WRAPS proof). From then on, state proofs captured on this network are verifiable by peers.

Typical cost: well under a minute per network.

### Cold start (no fixture)

1. The export overrides `networkAdmin.diskNetworkExport=ONLY_FREEZE_BLOCK` and
   `networkAdmin.diskNetworkExportTss=true` are added, and `ClprWrapsProvingKeyInstaller` makes sure the
   WRAPS artifacts are extracted.
2. The nodes bootstrap TSS and WRAPS from scratch (~15 minutes on a 1-node network).
3. After `WRAPS-extensible? true` and `[CLPR-SYNC-POINT]`, the harness settles briefly, then freezes the
   network (with background traffic so the freeze block's proof gets signed). Each node writes a
   TSS-enriched `output/network.json` carrying only its own private key.
4. `TssFixtures.generateNetworkFixture` merges the per-node exports into one
   `<network>-genesis-network.json.gz`: network-wide data from the lowest node, plus every node's own
   private-key entry. The merge fails if an export carries another node's private key or lacks its own.
5. The frozen network is replaced by a fresh one on the same ports with the new fixture installed — i.e. a
   warm start. Every later run then takes the warm path.

## Phase 2 — each test

`MultiNetworkExtension.beforeEach`:

- **Shared networks:** reuses them and re-checks each node is `ACTIVE` (an earlier test may have
  frozen or restarted it).
- **Otherwise:** starts per-test networks through the same Phase 1 path.
- Applies the test's `setupOverrides` at runtime through a `0.0.121` file update, after snapshotting the
  previous values. Startup-only node settings (`clpr.mtlsPort`, CA paths) are skipped here — they only take
  effect from `application.properties`.

`MultiNetworkExtension.afterEach` restores the snapshotted values, stops per-test networks, and clears
each node's CLPR peer-endpoint cache so the next test starts clean.

## Phase 3 — test-plan end

The listener stops every shared network.
