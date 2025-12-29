# Remove override-network.json usage for delayed-node upgrade path - implementation plan

## Goal

Stop using `override-network.json` in the *delayed-node* upgrade path (the path that starts existing nodes first, then starts new nodes from a post-upgrade signed state). After this change, upgrades should rely on the normal roster-change process and signed-state startup, not override files.

## Constraints and requirements

- No code changes until this plan is approved.
- Focus only on the delayed-node upgrade path; other upgrade paths should keep existing behavior unless explicitly extended in a later stage.
- Preserve the current port-retention behavior; do **not** reintroduce port reassignment.
- Avoid regressions in other HAPI tests; minimize impact by keeping legacy behavior available where needed.
- Tests must verify that the delayed-node path runs without `override-network.json` files and still reaches ACTIVE.

## Deep research summary (current behavior)

### How startup works without override-network.json

- `DiskStartupNetworks.genesisNetworkOrThrow(...)` loads `genesis-network.json` or falls back to `config.txt` (when present) for genesis startup.
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/info/DiskStartupNetworks.java:86-92`
- `DiskStartupNetworks.overrideNetworkFor(...)` first checks for unscoped `override-network.json`, then a scoped `<round>/override-network.json`. If neither exists, it may fall back to `config.txt` **only** when `addressBook.forceUseOfConfigAddressBook` is true; otherwise it returns empty.
  - `hedera-node/hedera-app/src/main/java/com/hedera/node/app/info/DiskStartupNetworks.java:96-120`
- The test harness also exposes a `startupNetwork()` helper that prefers `genesis-network.json` then `override-network.json`, then the newest scoped override.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/AbstractLocalNode.java:71-117`

**Implication:** If nodes start from a signed state (existing nodes) or from a copied signed state + PCES (new deferred nodes), they do *not* need override files. If override files are absent, `DiskStartupNetworks.overrideNetworkFor` returns empty, and the platform relies on signed state or config-based fallbacks.

### Where override-network.json is written today (test harness)

- `TryToStartNodesOp.submitOp(...)` refreshes override files for every start/restart when `refreshOverrides == true`:
  - `refreshOverrideWithCurrentPortsForConfigVersion(...)` / `refreshOverrideWithCurrentPorts(...)` are used when ports are stable.
  - `refreshOverrideWithNewPortsForConfigVersion(...)` / `refreshOverrideWithNewPorts(...)` are used when ports are reassigned.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/lifecycle/ops/TryToStartNodesOp.java:66-83`
- `FakeNmt.startNodes(...)` always uses `TryToStartNodesOp(..., refreshOverrides=true)` today.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java:52-59`
- `SubProcessNetwork.refreshOverrideNetworks(...)` writes `override-network.json` (unscoped and scoped) for each node, unless a `genesis-network.json` exists.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java:738-787`

### Additional override-network.json writes during add/remove

- `SubProcessNetwork.removeNode(...)` updates `configTxt` and then calls `refreshOverrideNetworks(...)`.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java:458-465`
- `SubProcessNetwork.addNode(...)` updates `configTxt`, initializes the new node working dir, and then calls `refreshOverrideNetworks(...)`.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java:468-511`
- These are invoked by `RemoveNodeOp` and `AddNodeOp`, used by `FakeNmt.removeNode(...)` and `FakeNmt.addNode(...)`.
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/upgrade/RemoveNodeOp.java:23-28`
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/upgrade/AddNodeOp.java:28-44`

### Where the delayed-node upgrade path currently triggers overrides

The staged helper `upgradeToNextConfigVersionWithDeferredNodes(...)` uses:
- `beginUpgradeToNextConfigVersion(...)` (freeze + shutdown), then
- `resumeUpgradedNetwork(...)` (which calls `FakeNmt.startNodes(...)`, which refreshes overrides), and
- pre-restart ops such as `FakeNmt.addNode(...)`/`FakeNmt.removeNode(...)` which call `SubProcessNetwork.addNode/removeNode(...)` (also refreshing overrides).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java:226-265`

**Implication:** Even though ports are now stable, the delayed-node upgrade path still writes `override-network.json` from multiple places. To truly remove override usage in this path, we must:
1) stop `TryToStartNodesOp` refreshes for this path; and
2) avoid override refresh in add/remove used during pre-restart ops; and
3) clear any stale override files before starting nodes.

## Design direction

1. Add “no-override” variants for:
   - node starts (`FakeNmt.startNodesNoOverride`), and
   - add/remove (`FakeNmt.addNodeNoOverride`, `FakeNmt.removeNodeNoOverride`), backed by new `SubProcessNetwork` methods that update `configTxt` without calling `refreshOverrideNetworks(...)`.
2. Update the delayed-node upgrade helper to use these no-override operations and to delete any stale override files **before** starting nodes.
3. Add explicit verification in MultiNetwork tests to assert `override-network.json` is absent after upgrades.
4. Run MultiNetwork and DAB upgrade tests to verify no regressions.

## Staged implementation plan

### Stage 1 - Add “no-override” utilities for add/remove and start

**Intent:** create safe, targeted APIs so only the delayed-node path skips override writes.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java`
  - Add `addNodeWithoutOverrides(long nodeId)` and `removeNodeWithoutOverrides(NodeSelector selector)` that:
    - update `configTxt`,
    - init the new node working dir (for add),
    - **do not** call `refreshOverrideNetworks(...)`.
  - Location: near existing `addNode(...)` / `removeNode(...)` (`:458-511`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/upgrade/AddNodeOp.java`
  - Add a boolean or a new constructor to select override behavior, calling `addNodeWithoutOverrides(...)` when requested.
  - Location: `:16-44`.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/upgrade/RemoveNodeOp.java`
  - Mirror the AddNode change, calling `removeNodeWithoutOverrides(...)` when requested.
  - Location: `:15-28`.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java`
  - Add `addNodeNoOverride(...)` and `removeNodeNoOverride(...)` helpers to construct the new ops.
  - Location: near `addNode(...)` / `removeNode(...)` (`:93-105`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java`
  - Add `startNodesNoOverride(...)` helper that constructs `TryToStartNodesOp(..., refreshOverrides=false)`.
  - Location: near `startNodes(...)` (`:52-69`).

**Tests at end of stage**

- No behavior change yet; only new APIs. No tests required here beyond compilation.

### Stage 2 - Use “no-override” APIs in the delayed-node upgrade helper

**Intent:** remove override writes and usage from the delayed-node upgrade path.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java`
  - Update `upgradeToNextConfigVersionWithDeferredNodes(...)` to:
    - use `FakeNmt.startNodesNoOverride(...)` for both existing and deferred nodes (instead of `resumeUpgradedNetwork(...)`).
    - clear any stale override files **before** starting nodes (call `clearOverrideNetworksForConfigVersion(...)` just before the first resume).
  - Location: `:226-265` and `:198-203`.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java`
  - Replace pre-restart ops in delayed paths to use `FakeNmt.addNodeNoOverride(...)` / `FakeNmt.removeNodeNoOverride(...)`.
  - Locations:
    - `deleteAndUpgradeNetwork(...)` path (`:120-199`),
    - `addNodeAndUpgradeWithDeferredStart(...)` path (`:158-198`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/DabEnabledUpgradeTest.java`
  - Update the deferred upgrade uses to call the no-override FakeNmt ops in pre-restart operations:
    - `newNodeId4InCandidateRosterAfterAddition`
    - `exportedAddressBookReflectsOnlyEditsBeforePrepareUpgrade`
    - `RecordsOutputPath.newNodeUpdate`
  - Locations: `:267-272`, `:337-342`, `:385-390`.

**Tests at end of stage**

- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.nodeRosterChangesAcrossNetworks" -Djunit.jupiter.execution.timeout.default=15m`
- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.stagedNodeAdditionUsesSignedState" -Djunit.jupiter.execution.timeout.default=15m`
- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest" -Djunit.jupiter.execution.timeout.default=1h`

### Stage 3 - Add explicit assertions that override-network.json is not used

**Intent:** verify the new path never creates or relies on override files.

**Code changes**

- Add a small helper to check for override-file absence:
  - Option A: a helper in `SubProcessNetwork` (e.g., `assertNoOverrideNetworkFiles(int configVersion)`).
  - Option B: local helper in `MultiNetworkNodeLifecycleSuite` that inspects each node’s `data/config` path.
  - Suggested locations:
    - `SubProcessNetwork` near `clearOverrideNetworksForConfigVersion(...)` (`:432-441`), or
    - `MultiNetworkNodeLifecycleSuite` near helper methods (`:201-240`).
- Call the helper:
  - immediately after the upgrade resume for existing nodes, and again after the deferred node starts (to ensure no late writes).

**Tests at end of stage**

- Re-run the two MultiNetwork tests above; ensure assertions pass.

### Stage 4 - Full-suite verification for delayed-node upgrades

**Intent:** confirm the delayed-node upgrade path works across both MultiNetwork and DAB upgrade suites with no regressions.

**Tests at end of stage**

- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite" -Djunit.jupiter.execution.timeout.default=15m`
- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest" -Djunit.jupiter.execution.timeout.default=1h`

### Stage 5 (optional follow-up) - Remove override usage from all upgrade paths

**Intent:** align *all* upgrade paths with “no override network” behavior once the delayed path is stable.

**Code changes**

- `LifecycleTest.upgradeToNextConfigVersion(...)` would need a parallel “no-override” start path (or switch to `startNodesNoOverride(...)`) and removal of override refresh calls during normal upgrades.
  - Location: `LifecycleTest.java:425-463`.
- Run the same upgrade tests (DabEnabled, MultiNetwork) to confirm no regressions.

**Tests at end of stage**

- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest" -Djunit.jupiter.execution.timeout.default=1h`
- `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite" -Djunit.jupiter.execution.timeout.default=15m`

## Risks and mitigations

- **Stale override files**: If previous runs left override files on disk, nodes may still pick them up. Mitigation: explicitly clear override files *before* starting nodes in the delayed path (Stage 2).
- **Add/remove side effects**: `SubProcessNetwork.addNode/removeNode` currently refresh override files. Mitigation: add “no-override” variants and use them only in the delayed path (Stage 1/2).
- **Config.txt fallback**: If `addressBook.forceUseOfConfigAddressBook` is false and there is no override file, `DiskStartupNetworks.overrideNetworkFor` returns empty. This is acceptable because nodes in the delayed path start from a signed state; they do not require override or config address book.
- **Existing tests that inspect startup network**: `AbstractLocalNode.startupNetwork()` prefers override files. If any tests explicitly assert on startup network contents, they may need to be updated to accept the absence of override files (evaluate if encountered).

## Progress log

Append implementation notes here after approval and execution.

- 2025-__-__: Stage 1 started.
- 2025-__-__: Stage 1 complete.
- 2025-__-__: Stage 2 started.
- 2025-__-__: Stage 2 complete.
- 2025-__-__: Stage 3 started.
- 2025-__-__: Stage 3 complete.
- 2025-__-__: Stage 4 started (optional).
- 2025-__-__: Stage 4 complete (optional).
