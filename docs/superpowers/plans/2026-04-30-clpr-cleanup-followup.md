# CLPR Cleanup Follow-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address review findings from the CLPR debug-logging cleanup arc (commits `bef4f6c3fd..793fd05473`). Three real issues: an untracked `ClprConnectionLifecycle` SPI that the cleanup commits implicitly depend on, an over-deleted warn for failed bundle submission in `ClprConnectionManager`, and a behavioral change in `syncTick` whose `onConnectionClosed` notifier currently has no production caller (so `knownConnectionIds` will leak forever).

**Architecture:** No new abstractions. Three small fixes:
1. Stage and commit the existing-on-disk `ClprConnectionLifecycle.java` interface and its three module wirings (Hedera DI graph, sync-workflow Dagger module, standalone-mode no-op binding).
2. Restore one `logger.warn(...)` line that was deleted alongside genuine debug-logging cleanup.
3. Wire `onConnectionClosed` into `ClprSubmitBundleHandler` so it fires when a connection's state transitions to `CLOSED` in step 11 — that's the only place a connection becomes terminal.

**Tech Stack:** Java 21, Gradle 9.3, Dagger 2.x, log4j-core 2.x

---

## Pre-flight

### Task 0: Confirm starting state

**Files:** none modified

- [ ] **Step 1: Confirm the three follow-up scopes match expectations**

Run:

```bash
cd <clpr-hiero-checkout> && \
  git status --short | grep -E "ClprConnectionLifecycle|ClprSyncWorkflowInjectionModule|StandaloneModule|HederaInjectionComponent"
```

Expected:

```
 M hedera-node/hedera-app/src/main/java/com/hedera/node/app/HederaInjectionComponent.java
 M hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowInjectionModule.java
 M hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java
?? hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ClprConnectionLifecycle.java
```

If the output differs (e.g. the file was deleted, the modules already committed), re-read the situation before continuing.

- [ ] **Step 2: Confirm the over-deletion target**

Run:

```bash
git show c0c695f453:hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java | grep -n "Bundle submission failed"
```

Expected: one match showing the original warn line that was deleted in commit `2e12a1b43c`.

```bash
grep -c "Bundle submission failed" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
```

Expected: `0` (the warn is currently absent in HEAD; that is what we will restore).

- [ ] **Step 3: Confirm `onConnectionClosed` has no production callers**

Run:

```bash
grep -rn "onConnectionClosed" --include="*.java" hedera-node/ | grep -v test
```

Expected: matches in `ClprConnectionLifecycle.java`, `ClprConnectionManager.java`, and the `StandaloneModule.java` no-op stub — but **no** call site outside the interface declaration and the no-op stub. That's the leak this plan closes.

---

## Task 1: Commit the `ClprConnectionLifecycle` SPI and its module wirings

This is the blocker. The interface and three wiring sites already exist on disk and the build passes locally because of that, but the interface is untracked. Any reviewer who checks out the branch fresh will hit `cannot find symbol: class ClprConnectionLifecycle` in three already-committed files.

**Files:**
- Add: `hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ClprConnectionLifecycle.java` (currently untracked, content is correct as-is)
- Stage: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/HederaInjectionComponent.java`
- Stage: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowInjectionModule.java`
- Stage: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java`

- [ ] **Step 1: Inspect each file and confirm there is nothing else to change**

Run:

```bash
git diff -- hedera-node/hedera-app/src/main/java/com/hedera/node/app/HederaInjectionComponent.java \
            hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowInjectionModule.java \
            hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java
```

Expected diffs:
- `HederaInjectionComponent.java`: adds one accessor `com.hedera.node.app.workflows.clpr.ClprSyncWorkflow clprSyncWorkflow();`
- `ClprSyncWorkflowInjectionModule.java`: adds `import com.hedera.node.app.service.clpr.ClprConnectionLifecycle;` and one `@Binds` method binding `ClprConnectionLifecycle` to `ClprConnectionManager`.
- `StandaloneModule.java`: adds imports for `ClprConnectionLifecycle` and `Bytes`, plus a `@Provides @Singleton` method returning a no-op anonymous `ClprConnectionLifecycle` (standalone mode has no orchestrator to notify).

If any file shows additional unrelated changes, abort and investigate.

- [ ] **Step 2: Verify the StandaloneModule no-op uses `@NonNull` correctly**

Run:

```bash
grep -n "@NonNull" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java
```

Expected: at least two matches inside the new `provideNoopClprConnectionLifecycle()` method, on the two `Bytes connectionId` parameters. If the import for `@NonNull` (`edu.umd.cs.findbugs.annotations.NonNull`) is missing at the top of the file, add it now.

```bash
grep -n "import edu.umd.cs.findbugs.annotations.NonNull" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java
```

If the result is empty, edit the imports block to add it (alphabetical order — it goes near other `edu.*` or after the last `com.*` import).

- [ ] **Step 3: Verify the build still compiles with these files staged**

Run:

```bash
./gradlew :app:compileJava :app-service-clpr:compileJava :app-service-clpr-impl:compileJava 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. If a compile error mentions `ClprConnectionLifecycle`, double-check the file's package declaration matches its directory: `package com.hedera.node.app.service.clpr;` and that the file lives under `hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service/src/main/java/com/hedera/node/app/service/clpr/ClprConnectionLifecycle.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/HederaInjectionComponent.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowInjectionModule.java \
        hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/standalone/impl/StandaloneModule.java
git commit -m "feat(clpr): add ClprConnectionLifecycle SPI and wire DI bindings"
```

---

## Task 2: Restore over-deleted warn in `ClprConnectionManager.performSync`

Commit `2e12a1b43c` deleted `logger.warn("Bundle submission failed for connection {} via peer {}", ...)` along with the CLPR-DBG cleanup. That warn fires when `ClprBundleSubmitter.submitBundle` returns `false` — i.e. gossip rejected our submission. The submitter logs the underlying gossip failure too (at `warn`), but the call site is the one that records the **circuit-breaker and reputation hits** for the peer, so it's the operationally meaningful event. Restore it.

**Files:**
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java`

- [ ] **Step 1: Locate the affected branch**

Run:

```bash
grep -n "circuitBreaker.recordFailure\|reputation.recordFailure" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
```

Expected: matches inside the `else` branch of the `if (success)` block in `performSync`. There may also be matches in a peer-error catch block — those are separate and already log.

Read approximately ten lines of context around the first match (line ~370 area). The current code is:

```java
} else {
    circuitBreaker.recordFailure();
    reputation.recordFailure();
}
```

- [ ] **Step 2: Re-add the warn**

Edit the file, replacing the three-line `else` body above with:

```java
} else {
    circuitBreaker.recordFailure();
    reputation.recordFailure();
    logger.warn(
            "Bundle submission failed for connection {} via peer {}",
            connectionId,
            selectedPeer);
}
```

Use the Edit tool with enough surrounding context to make the `old_string` unique (the bare three-line block may appear elsewhere; include the prior `if (success) {` line and one line after the `}` to anchor it).

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
git commit -m "fix(clpr): restore warn for failed bundle submission in performSync"
```

---

## Task 3: Wire `onConnectionClosed` from `ClprSubmitBundleHandler`

`syncTick`'s null-state branch was changed (in commit `2e12a1b43c`) from "auto-remove from `knownConnectionIds`" to "skip and rely on explicit `onConnectionClosed` calls". That's the right design — auto-remove was racy with rolled-back transactions — but the only existing caller of the lifecycle interface is `ClprCompleteConnectionHandler.onConnectionActivated`. Without a closed-side caller, every connection that ever transitions to `CLOSED` will leak its 32-byte ID into `knownConnectionIds` forever and incur a state lookup every sync tick.

The natural call site is `ClprSubmitBundleHandler.doHandle` step 11, where the connection's `currentStatus` becomes `CLOSED`. We inject the lifecycle, and after the `connectionStore.put(updatedConnection)` call, we fire `onConnectionClosed` if the new status is `CLOSED`.

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java`

- [ ] **Step 1: Find the constructor and the `connectionStore.put(updatedConnection)` call**

Run:

```bash
grep -n "public ClprSubmitBundleHandler\|connectionStore.put(updatedConnection)" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java
```

Expected: two matches — the `@Inject` constructor (around line 80) and the step-11 persist call (around line 530).

- [ ] **Step 2: Add the `ClprConnectionLifecycle` import**

Add to the imports block (alphabetical, between the other `com.hedera.node.app.service.clpr.*` imports):

```java
import com.hedera.node.app.service.clpr.ClprConnectionLifecycle;
```

- [ ] **Step 3: Inject the lifecycle in the constructor**

Locate:

```java
    private final ClprVerifierFactory verifierFactory;
    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprSubmitBundleHandler(
            @NonNull final ClprVerifierFactory verifierFactory, @NonNull final EntityIdFactory entityIdFactory) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }
```

Replace with:

```java
    private final ClprVerifierFactory verifierFactory;
    private final EntityIdFactory entityIdFactory;
    private final ClprConnectionLifecycle connectionLifecycle;

    @Inject
    public ClprSubmitBundleHandler(
            @NonNull final ClprVerifierFactory verifierFactory,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ClprConnectionLifecycle connectionLifecycle) {
        this.verifierFactory = requireNonNull(verifierFactory);
        this.entityIdFactory = requireNonNull(entityIdFactory);
        this.connectionLifecycle = requireNonNull(connectionLifecycle);
    }
```

- [ ] **Step 4: Fire `onConnectionClosed` after the persist**

Locate the step-11 region:

```java
    connectionStore.put(updatedConnection);
}
```

(That `}` closes `doHandle`. The `connectionStore.put(updatedConnection)` line is the last statement.)

Replace with:

```java
        connectionStore.put(updatedConnection);

        // Notify the runtime sync orchestrator on terminal state. The connection
        // ID will not appear in any future bundle, so the orchestrator can drop
        // it from its in-memory registry. If the surrounding transaction rolls
        // back, the orchestrator self-corrects on its next tick (state lookup
        // will return the still-non-CLOSED connection and the entry is re-kept).
        if (currentStatus == ClprConnectionStatus.CLOSED) {
            connectionLifecycle.onConnectionClosed(connectionId);
        }
    }
```

- [ ] **Step 5: Verify compile**

```bash
./gradlew :app-service-clpr-impl:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. A failure mentioning Dagger missing-binding errors means the binding from Task 1 is not on the classpath — re-check that Task 1 was committed and that `:app-service-clpr-impl` declares a `compile` dependency on `:app-service-clpr` (it should, transitively, via its existing imports of `com.hedera.node.app.service.clpr.*` types).

- [ ] **Step 6: Verify the existing ClprSubmitBundleHandler unit tests still construct the handler correctly**

Run:

```bash
grep -rn "new ClprSubmitBundleHandler(" hedera-node/hedera-clpr-service-impl/src/test/java
```

For each match, the test must now pass a third argument (a mocked `ClprConnectionLifecycle`). If matches exist, edit each test to add the mock:

```java
@Mock
private ClprConnectionLifecycle connectionLifecycle;
// ... in setUp / constructor call:
new ClprSubmitBundleHandler(verifierFactory, entityIdFactory, connectionLifecycle);
```

If no test directly constructs the handler (the test may use Dagger or mock it at a higher level), no test edit is needed.

```bash
./gradlew :app-service-clpr-impl:compileTestJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the unit tests for the bundle handler**

```bash
./gradlew :app-service-clpr-impl:test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. If any tests fail with NPE on `connectionLifecycle`, the mock is missing — go back to Step 6.

- [ ] **Step 8: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java
# Also stage any test files modified in Step 6:
# git add hedera-node/hedera-clpr-service-impl/src/test/java/.../*.java
git commit -m "fix(clpr): notify connection-closed lifecycle hook on terminal state"
```

---

## Task 4: Final verification

### Task 4a: Repo-wide build

- [ ] **Step 1: Full assemble**

```bash
./gradlew assemble 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: All javadoc**

```bash
./gradlew javadoc 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

### Task 4b: CLPR test suites

- [ ] **Step 1: Run the embedded single-node test**

```bash
./gradlew :test-clients:testEmbedded --tests "com.hedera.services.bdd.suites.clpr.ClprOrchestratorSubmitTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. The closed-connection notification path from Task 3 will not fire in this test (the test exercises receive-side message dispatch, not full close), but compile and DI wiring must work.

- [ ] **Step 2: Run the multi-network round-trip test**

```bash
./gradlew :test-clients:testEmbedded --tests "com.hedera.services.bdd.suites.clpr.ClprHieroToHieroSuite" 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all sub-tests pass (5 passing in the prior baseline). This suite drives connections through full lifecycle and will exercise the new `onConnectionClosed` call site if any sub-test triggers a CLOSED transition; otherwise the new code is dormant. Either way, the test must stay green.

### Task 4c: Verify `knownConnectionIds` cannot leak

- [ ] **Step 1: Static check that `onConnectionClosed` has at least one production caller**

```bash
grep -rn "onConnectionClosed" --include="*.java" hedera-node/ | grep -v "/test/" | grep -v "interface\|@Override\|// " | grep -v "ClprConnectionLifecycle.java"
```

Expected: at least two matches — the implementation in `ClprConnectionManager.java` and the new call site in `ClprSubmitBundleHandler.java`. If the bundle-handler match is missing, Task 3 was not applied.

### Task 4d: Final commit log review

- [ ] **Step 1: Confirm three new commits land cleanly on top**

```bash
git log --oneline 793fd05473..HEAD
```

Expected: exactly three commits, one per task above:
- `feat(clpr): add ClprConnectionLifecycle SPI and wire DI bindings`
- `fix(clpr): restore warn for failed bundle submission in performSync`
- `fix(clpr): notify connection-closed lifecycle hook on terminal state`

- [ ] **Step 2: Confirm working tree shows only unrelated WIP**

```bash
git status --short | grep -vE "^ M hedera-node/(data/config|docs/design)" | head -20
```

Expected output should not list any of the four files from Task 1, the handler from Task 3, or the manager from Task 2 — those should all be committed. Anything else listed is pre-existing WIP from the broader CLPR feature branch and is not in scope here.

---

## Done

Three follow-up commits address every blocker and should-fix from the post-cleanup review. The PR now: (1) checks out and compiles fresh on any clone, (2) preserves the operationally important "bundle submission failed" warn that was lost in the cleanup, and (3) closes the `knownConnectionIds` leak by giving `onConnectionClosed` a real caller. The nits flagged in review (`else if` → split-`if` in `HandleWorkflow`, blank-line touch in `ClprServiceApiImpl`) are intentionally left alone — they are semantically correct and reverting them would just churn the diff.
