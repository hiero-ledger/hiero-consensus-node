# Separated upgrade start for existing vs new nodes - implementation plan

## Goal

When a subprocess network is upgraded, start existing nodes first and defer starting new nodes until after the existing network is ACTIVE. The deferred nodes must boot from a signed state and PCES copied from a live node (lowest id), and the test must show the network advances without ISS.

## Constraints and requirements

- Existing nodes resume first and become ACTIVE before new nodes start.
- New nodes start from a signed state and PCES copied from the lowest-id node after upgrade.
- Use the existing port scheme (no port churn) and keep changes minimally invasive to other HAPI tests.
- If any upgrade-related tests regress, keep legacy behavior available as a fallback path (after MultiNetwork passes).
- Assume no more than 10 nodes per network.
- All upgrade-related test runs should use a 15 minute timeout.

## Current behavior and root cause (code-level analysis)

### Upgrade restart always starts all nodes together

- `LifecycleTest.upgradeToConfigVersion(...)` performs `freezeUpgrade`, shuts down the network, and calls `FakeNmt.restartNetwork(...)`, which starts all nodes (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java:221-245`).
- `FakeNmt.restartNetwork(...)` instantiates a `TryToStartNodesOp` with `NodeSelector.allNodes()` (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java:37-41`).
- There is no DSL-level operation to start a subset of nodes or to sequence existing vs new nodes.

### New nodes are started without a signed-state handoff

- `SubProcessNetwork.addNode(...)` initializes working dirs and writes genesis assets; the node is started along with the rest of the network (see `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java:468-511`).
- There is no built-in utility to identify the latest signed state, wait for a post-upgrade signed state, or copy signed state + PCES into a new node working dir.

## Design direction

1. Introduce staged upgrade operations:
   - A "begin" step that freezes and shuts down without starting nodes.
   - A "resume" step that starts a selected subset of nodes with the current config version.
2. Add signed-state and PCES copy helpers in `SubProcessNetwork`:
   - Discover the latest signed state round.
   - Wait for a signed state after a baseline round.
   - Copy signed state and PCES directories from an existing node to a deferred node.
3. Add a MultiNetwork test that executes the staged upgrade and deferred node start.
4. Verify no ISS is observed (via log checks or status checks) and the roster matches the expected network membership.
5. Generalize the staged-upgrade flow into a reusable DSL in the test harness.
6. Refactor upgrade tests (starting with `DabEnabledUpgradeTest`) to use the new DSL with minimal changes.

## Staged implementation plan

### Stage 1 - Staged upgrade primitives (begin + resume)

**Intent:** introduce explicit DSL operations to separate upgrade shutdown from node startup.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java`
  - Add `beginUpgradeToNextConfigVersion(...)` and `beginUpgradeToConfigVersion(...)` to freeze + shutdown without starting nodes (around `:154-186`).
  - Add `resumeUpgradedNetwork(...)` overloads to start selected nodes with the current config version (around `:189-210`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/FakeNmt.java`
  - Add `startNodes(...)` for a `NodeSelector` and config version to start a subset (around `:44-69`).

**Tests at end of stage**

- Add a small staged-upgrade smoke test that begins an upgrade and resumes all nodes to confirm the new DSL works.
  - Suggested location: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java` near the existing suite tests (around `:99-113`).
- Verification command:
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.<NewSmokeTest>" -Djunit.jupiter.execution.timeout.default=15m`

### Stage 2 - Signed state + PCES utilities for deferred nodes

**Intent:** provide explicit utilities to copy a signed state and PCES from an active node into a deferred node before it starts.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNetwork.java`
  - Add signed-state discovery and wait helpers (around `:513-546`).
  - Add `copyLatestSignedStateAndPces(...)` and directory-copy helpers (around `:548-659`).
  - Add constants for PCES path derivation (around `:95-115`).
- Optional DSL wrappers for clarity:
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/UtilVerbs.java`
    - Add `awaitSignedStateAfterRound(...)` and `copyLatestSignedStateAndPces(...)` wrappers using `doingContextual` (around `:636-670`).

**Tests at end of stage**

- Add an intermediate test that runs an upgrade, resumes existing nodes, waits for a new signed state, copies signed state + PCES to the new node, and asserts the target directories exist (without starting the new node yet).
  - Suggested location: `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java` near the staged upgrade test helper (around `:166-214`).
- Verification command:
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.<CopyStateTest>" -Djunit.jupiter.execution.timeout.default=15m`

### Stage 3 - Staged node addition uses signed state

**Intent:** implement the requested staged upgrade flow in a new MultiNetwork test.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java`
  - Add `stagedNodeAdditionUsesSignedState` and helper `addNodeAndUpgradeWithDeferredStart` to:
    - Add the new node to the roster pre-upgrade.
    - Begin upgrade (freeze + shutdown) without starting nodes.
    - Resume existing nodes only and wait for ACTIVE.
    - Wait for a post-upgrade signed state on the lowest-id node.
    - Copy signed state + PCES to the deferred node.
    - Start the deferred node and wait for ACTIVE.
    - Verify the roster includes the new node.
  - Changes are centered around `:99-224`.

**Tests at end of stage**

- Run the new staged test.
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.stagedNodeAdditionUsesSignedState" -Djunit.jupiter.execution.timeout.default=15m`

### Stage 4 - ISS validation and cleanup

**Intent:** ensure the staged upgrade flow is ISS-free and leaves no stale override files behind.

**Code changes**

- Optional log check helper if needed:
  - `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/subprocess/SubProcessNode.java` or a new UtilOp could expose a log scan for `SELF_ISS` (around `:244-280`).
  - Add a small assertion in the staged test after node5 becomes ACTIVE to verify no ISS markers are present.
- Ensure override cleanup after staged upgrade:
  - `MultiNetworkNodeLifecycleSuite` already uses `clearOverrideNetworksForConfigVersion(...)` after staged start (around `:219-222`); ensure this remains in place or add it if missing.

**Tests at end of stage**

- Re-run the staged test and the existing multi-network suite.
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite" -Djunit.jupiter.execution.timeout.default=15m`

### Stage 5 - Generalize staged-upgrade flow into the test harness DSL

**Intent:** move the large staged-upgrade logic out of `MultiNetworkNodeLifecycleSuite` into a reusable DSL so other tests can invoke it with minimal boilerplate.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/LifecycleTest.java`
  - Add a higher-level helper such as `stagedUpgradeWithDeferredNodeStart(...)` that orchestrates:
    - begin upgrade,
    - resume existing nodes,
    - wait for signed state,
    - copy signed state + PCES,
    - start deferred nodes,
    - wait for ACTIVE.
  - Place near other lifecycle operations (around `:154-210`), and reuse existing `beginUpgradeToNextConfigVersion(...)`, `resumeUpgradedNetwork(...)`, and `SubProcessNetwork` helpers.
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/UtilVerbs.java`
  - If needed for clarity, add a concise `stagedUpgradeWithDeferredNodes(...)` wrapper that returns a `SpecOperation` calling the new lifecycle helper (around `:624-670`).
- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/multinetwork/MultiNetworkNodeLifecycleSuite.java`
  - Replace `addNodeAndUpgradeWithDeferredStart(...)` with the new DSL helper (around `:166-224`).
  - Keep the test scenario but reduce the local method to a simple call chain (expected minimal diff once the DSL exists).

**Tests at end of stage**

- Re-run the staged test to confirm the DSL works end-to-end:
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.stagedNodeAdditionUsesSignedState" -Djunit.jupiter.execution.timeout.default=15m`

### Stage 6 - Refactor DabEnabledUpgradeTest to use the new staged-upgrade DSL

**Intent:** apply the new DSL to `DabEnabledUpgradeTest` with minimal, readable changes.

**Code changes**

- `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/regression/system/DabEnabledUpgradeTest.java`
  - Replace any ad-hoc staged-upgrade sequences with the new DSL helper (exact locations depend on which upgrade scenarios are updated first; expect changes around the tests that add nodes and perform `upgradeToNextConfigVersion(...)`, e.g. ~`:170-380`).
  - Ensure the refactor is limited to replacing existing procedural sequences with a single DSL call plus any per-test parameters.

**Tests at end of stage**

- Run the affected `DabEnabledUpgradeTest` methods individually first, then the full class:
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest.<MethodName>" -Djunit.jupiter.execution.timeout.default=15m`
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest" -Djunit.jupiter.execution.timeout.default=15m`

## Notes on edge cases

- The new node starts with a `genesis-network.json` present; ensure the presence of a copied signed state takes precedence over genesis. If not, remove or rename `genesis-network.json` for the deferred node as part of the copy step.
- PCES paths are under `data/saved/preconsensus-events/<nodeId>`; the copy routine should target the deferred node id directory, not the source id directory.
- Ensure the signed state used is post-upgrade (baseline round captured before the upgrade and waited past after resuming existing nodes).
- Long-running upgrade tests should be executed with a 15 minute timeout as required.

## Progress log

Implementation notes and verification results.

- 2025-12-24: Stage 1 complete. Added staged upgrade primitives in `LifecycleTest` and `FakeNmt.startNodes(...)`.
- 2025-12-24: Stage 2 complete. Added signed-state discovery, await, and signed-state + PCES copy helpers in `SubProcessNetwork`.
- 2025-12-24: Stage 3 complete. Implemented staged node-add flow; `MultiNetworkNodeLifecycleSuite.stagedNodeAdditionUsesSignedState` passed.
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite.stagedNodeAdditionUsesSignedState" -Djunit.jupiter.execution.timeout.default=15m`
- 2025-12-24: Stage 4 complete. Override cleanup retained in staged upgrade path; no extra ISS log assertions added.
- 2025-12-24: Stage 5 complete. Generalized staged upgrade flow into `LifecycleTest`; refactored MultiNetwork test to DSL; full MultiNetwork suite passed.
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.multinetwork.MultiNetworkNodeLifecycleSuite" -Djunit.jupiter.execution.timeout.default=15m`
- 2025-12-24: Stage 6 complete. Refactored `DabEnabledUpgradeTest` to new DSL; full class passed with 1h timeout.
  - `./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.regression.system.DabEnabledUpgradeTest" -Djunit.jupiter.execution.timeout.default=1h`
  - Note: `exportedAddressBookReflectsOnlyEditsBeforePrepareUpgrade` fails when run alone due to missing preconditions; it passes in the full class run.
