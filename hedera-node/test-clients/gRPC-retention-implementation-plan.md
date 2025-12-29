# gRPC endpoint retention on upgrade - implementation plan

## Goal

When a subprocess network is upgraded (freeze upgrade + restart), every node that remains in the roster must keep the same ports (gRPC, gossip, node-operator, Prometheus, debug). The updated roster must be reflected in `override-network.json` without port churn. New nodes added during the upgrade must use a port scheme compatible with existing nodes to avoid collisions.

## Constraints and requirements

- Preserve all allocated ports (gRPC, gossip, node-operator, Prometheus, debug) for surviving nodes across upgrades.
- If a bind exception is seen on restart, retry startup with exponential backoff (no port reassignment) and fail after 1 minute.
- New node port allocation must use the per-network base/offset scheme (not a global pool).
- Assume no more than 10 nodes per network.
- Newly added nodes that start from `genesis-network.json` must have that file reflect the latest roster and ports.
- Minimize impact to existing HAPI tests; if upgrade tests regress, provide an explicit fallback path to keep prior behavior after MultiNetwork passes.

## Current behavior and root cause (code-level analysis)

### Upgrade restart reassigns ports for all nodes

- `LifecycleTest.upgradeToConfigVersion(...)` calls `FakeNmt.restartNetwork(...)` to restart the subprocess network after `FREEZE_UPGRADE` (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java:226-244`).
- `FakeNmt.restartNetwork(...)` unconditionally constructs a `TryToStartNodesOp` with `ReassignPorts.YES` (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java:37-41`).
- `TryToStartNodesOp.submitOp(...)` calls `SubProcessNetwork.refreshOverrideWithNewPorts()` whenever `ReassignPorts.YES` (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/lifecycle/ops/TryToStartNodesOp.java:49-55`).
- `SubProcessNetwork.refreshOverrideWithNewPorts()`:
  - Calls `reinitializePorts()` and then `SubProcessNode.reassignPorts(...)` for every node (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java:353-367`).
  - Rebuilds `configTxt` and refreshes override-network files (see `SubProcessNetwork.java:368-370`, `540-583`).

This is the direct reason gRPC and gossip endpoints change after every upgrade restart.

### Add/remove uses a global port base, not a per-network base

- The port base (`nextGrpcPort`, `nextInternalGossipPort`, etc.) is static and shared across all networks (see `SubProcessNetwork.java:95-101`).
- `liveNetwork(...)` advances this global port base after each network is created (see `SubProcessNetwork.java:503-528`).
- `addNode(...)` uses the current global base to compute the new node ports (see `SubProcessNetwork.java:410-421`).
- In multi-network scenarios, the global base points at the most recently created network, not necessarily the network being modified. This becomes a correctness issue once reassign-on-upgrade is removed.

### override-network.json uses current node metadata for gRPC ports

- `refreshOverrideNetworks(...)` uses `WorkingDirUtils.networkFrom(configTxt, ..., currentGrpcServiceEndpoints())` (see `SubProcessNetwork.java:540-545`).
- `currentGrpcServiceEndpoints()` derives gRPC endpoints from each node's metadata (`node.getGrpcPort()`) (see `SubProcessNetwork.java:465-469`).
- Therefore, if node metadata ports are stable, `override-network.json` will encode stable gRPC endpoints.

## Design direction

1. Track a per-network base port layout (all ports) at network creation time.
2. Use the per-network base to compute ports in:
   - `configTxt` generation
   - `addNode(...)`
   - `gossipEndpointsForNextNodeId()` / `grpcEndpointForNextNodeId()`
3. Ensure `genesis-network.json` for newly added nodes is generated from the updated roster and the per-network base scheme.
4. Stop reassigning ports as part of upgrade restart. Use stable metadata instead.
5. Add bind-exception retry with exponential backoff during restart (no port reassignment).
6. Add verification tests that capture ports before upgrade and assert no changes for surviving nodes; verify new node ports follow the same base; verify `override-network.json` contains the retained ports after upgrade.

## Staged implementation plan

### Stage 1 - Per-network port layout and stable add-node allocation

**Intent:** decouple port allocation from the global static base so new nodes always use the same scheme as existing nodes, even across multiple networks.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java`
  - Introduce per-network base fields (or a `PortLayout` value) for all ports near the static port fields (around `:95-115`).
  - Capture the base in `liveNetwork(...)` and pass into the constructor (around `:489-528`).
  - Use per-network base fields in `configTxt` generation (around `:184-185`, `:391-392`, `:431-432`) so the roster matches the existing scheme.
  - Use per-network base fields in `addNode(...)` (around `:410-421`) and endpoint helpers (around `:447-462`).
  - Ensure `addNode(...)` continues to create `genesis-network.json` for the new node using the updated roster and per-network base (via `initWorkingDir(configTxt, currentGrpcServiceEndpoints())` around `:431-433`).
  - Ensure `refreshOverrideWithNewPorts()` updates the per-network base when it does reassign (around `:353-370`, `:600-606`) for any explicit reassign fallback.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/utils/AddressBookUtils.java`
  - No logic change expected, but ensure callers now pass the per-network base values (current usage is around `:82-136`, `:154-184`).

**Tests at end of stage**

- Add a lightweight unit test (or small Hapi test) that:
  - Creates two isolated subprocess networks with explicit `firstGrpcPort` values.
  - Adds a node to the first network after the second network is created.
  - Asserts the new node's ports align with the first network's base (gRPC and gossip ports computed from node0's base).

Suggested test location:
- New file: `hedera-node/test-clients/src/test/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetworkPortLayoutTest.java` (new)

**Regression requirement:** any code paths shared with standard HAPI tests must still pass without modifying those tests. This stage should be validated by running existing subprocess-network test tasks unchanged.

Verification command pattern:
`./gradlew :test-clients:testSubprocess --tests "<ClassPath>.<Class>.<OptionalMethod>"`

### Stage 2 - Stop port reassignment during upgrade restart

**Intent:** preserve ports for all surviving nodes across upgrade restarts.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java`
  - Change `restartNetwork(...)` to pass `ReassignPorts.NO` (around `:37-41`).
  - Change `restartNetworkWithDisabledNodeOperatorPort(...)` to pass `ReassignPorts.NO` (around `:50-55`).
  - Optionally add `restartNetworkWithReassignedPorts(...)` for tests that explicitly want reassign-on-restart.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java`
  - If a new `restartNetworkWithReassignedPorts` method is added, use it only where port reassign is required; otherwise keep current uses but with no reassign (around `:157-165`, `:226-244`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/lifecycle/ops/TryToStartNodesOp.java`
  - Add retry/backoff on bind exception for subprocess nodes. This likely needs a retry policy field and a loop that:
    - starts the node;
    - waits for ACTIVE for a bounded attempt window;
    - on bind exception, stops the node, sleeps with exponential backoff, and retries until 1 minute (around `:60-71`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNode.java`
  - Expose a bind-exception detection helper (e.g., `hasBindExceptionInLogs()`), so the retry loop can detect binding failures without port reassignment (around `:200-230`).
- (Optional cleanup) `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/UtilVerbs.java`
  - Update the comment or name for `waitForActiveNetworkWithReassignedPorts(...)` to reflect that ports may not be reassigned (around `:658-668`).

**Tests at end of stage**

- Run existing HAPI test suites unchanged to ensure no regressions from removing the reassign step. This stage should not modify existing tests.
- Run upgrade-focused tests (at minimum `DabEnabledUpgradeTest`) unchanged to detect regressions. If these fail, defer remediation until after Stage 4.

Verification command pattern:
`./gradlew :test-clients:testSubprocess --tests "<ClassPath>.<Class>.<OptionalMethod>"`

Example upgrade test:
`./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest"`

### Stage 3 - Compatibility fallback path (only if regressions appear)

**Intent:** provide an explicit path to the old reassign-on-restart behavior for upgrade tests that cannot pass under stable ports.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java`
  - Add `restartNetworkWithReassignedPorts(...)` that uses `ReassignPorts.YES` to preserve legacy behavior (around `:37-55`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java`
  - Update only the failing upgrade tests to use the legacy restart path (after Stage 4 if needed).

**Tests at end of stage**

- Re-run any failing upgrade tests with the legacy path; confirm other tests remain unchanged.

Verification command pattern:
`./gradlew :test-clients:testSubprocess --tests "<ClassPath>.<Class>.<OptionalMethod>"`

### Stage 4 - MultiNetworkNodeLifecycleSuite validation for retained ports

**Intent:** explicitly verify that the multi-network upgrade path retains gRPC/gossip ports for surviving nodes and uses a compatible scheme for newly added nodes.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java`
  - Capture pre-upgrade port maps (gRPC + gossip) for each network.
  - After `upgradeToNextConfigVersion(...)`, assert that surviving node ports match pre-upgrade values.
  - Validate the new node’s ports align with the network’s base scheme (relative to node0).
  - (Optional) Validate `override-network.json` in `data/config/` reflects the retained ports when present.

**Tests at end of stage**

- Run `MultiNetworkNodeLifecycleSuite` and assert:
  - rosters change as expected;
  - surviving node ports do not change across upgrade;
  - new node ports align with the existing scheme.
- Run existing HAPI test suites unchanged to confirm no regressions from the test update.

Verification command:
`./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite"`

## Notes on edge cases

- `refreshOverrideNetworks(...)` only writes `override-network.json` when `data/config/genesis-network.json` is absent (see `SubProcessNetwork.java:548-560`). This relies on the node archiving genesis assets after initial startup. Tests should account for this behavior (skip or assert presence before reading override files).
- The port block reservation uses `MAX_NODES_PER_NETWORK` (see `SubProcessNetwork.java:88` and `:495-528`). Adding nodes beyond this block risks collisions with later networks.

## Progress log

Append implementation notes here.

- 2025-12-22: Stage 1 started.
- 2025-12-22: Stage 1 complete. Added per-network port base fields in `SubProcessNetwork`, added `SubProcessNetworkPortLayoutTest`, and verified it via `:test-clients:testSubprocess`.
- 2025-12-22: Stage 2 started.
- 2025-12-22: Stage 2 code changes complete; `DabEnabledUpgradeTest` run timed out after 2 minutes (needs a longer run or alternative verification).
- 2025-12-22: Re-ran `DabEnabledUpgradeTest` with a 15-minute timeout; failed after ~3 minutes with `HapiTxnCheckStateException: Unable to resolve txn status` in `upgradeWithSameNodesExportsTheOriginalAddressBook`, followed by gRPC `Connection refused` failures to `127.0.0.1:36498` across subsequent tests.
- 2025-12-22: Root cause identified: after restart with preserved ports, gRPC channel pools reused stale channels from pre-restart; previously, port reassignment forced new URIs and fresh channels. Implemented forced channel rebuild in `SubProcessNetwork.refreshClients()` via `HapiClients.rebuildChannels(true)` and added `HapiClients.rebuildChannels(force)`; re-ran `DabEnabledUpgradeTest` with 15-minute timeout but the run exceeded the timeout (no failure captured yet).
- 2025-12-22: Stage 3 deferred; no regressions found that require legacy port reassignment yet.
- 2025-12-22: Stage 4 complete. Added retained-port assertions to `MultiNetworkNodeLifecycleSuite` and verified it passes with a 15-minute timeout.
- 2025-12-22: Fixed `DabEnabledUpgradeTest.upgradeWithSameNodesExportsTheOriginalAddressBook` by refreshing `override-network.json` on restart even when ports are not reassigned; root cause was the one-time override customizer never being applied without a refresh, leaving node cert hashes empty. Verified the test passes.
