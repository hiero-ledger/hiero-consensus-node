# CLPR PR Debug Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all `CLPR-DBG`-tagged debug logging that accumulated during a multi-day debugging session, restore `ClprSubmitBundleHandler.doHandle` to a single method (no inner wrapper), and verify both CLPR test suites still pass.

**Architecture:** Two logging patterns to remove:
- `System.out.println("CLPR-DBG ...")` — used in non-CLPR framework files (HandleWorkflow, DispatchProcessor, ParentTxnFactory, CustomMessageCallProcessor)
- `logger.warn("CLPR-DBG ...")` — used in CLPR service files via log4j

Three production-correct changes accumulated during debugging that **must be preserved**:
1. `rawEvmResult` try-catch wrapper in `ClprSubmitBundleHandler` step 10 (handles the case where the dispatched contract has no bytecode at the target address — `BlockStreamBuilder.getEvmCallResult()` throws NPE on null `evmTransactionResult`)
2. Test funding: `cryptoTransfer(GENESIS → 0.0.3, 100 ℏ)` in `ClprOrchestratorSubmitTest` and the same plus connector funding in `ClprHieroToHieroSuite.setupNetwork`
3. New `ClprPassThroughVerifier.sol` (and regenerated `.bin`/`.json`) that walks the `HieroProofBytes` proto and re-emits as `ClprBundleContent`
* Post-implementation note: `HieroProofBytes` is hallucinated code and should not be used. Use `StateProof` instead.

**Tech Stack:** Java 21, Gradle 9.3, log4j-core 2.x

---

## Pre-flight: Capture Baseline

### Task 0: Verify starting state

**Files:** none modified

- [ ] **Step 1: Verify CLPR-DBG count matches expectation**

Run:

```bash
cd <clpr-hiero-checkout> && \
grep -rn "CLPR-DBG" --include="*.java" \
  hedera-node/hedera-app/src/main/java \
  hedera-node/hedera-clpr-service-impl/src/main/java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java | wc -l
```

Expected output: `90`

If the count differs, additional debug lines have been added since this plan was written — proceed cautiously and read each file before deleting.

- [ ] **Step 2: Run the multi-network round-trip test as baseline**

Run:

```bash
cd <clpr-hiero-checkout> && \
./gradlew :test-clients:testEmbedded --tests "com.hedera.services.bdd.suites.clpr.ClprHieroToHieroSuite.fullRoundTrip" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, `5 passing`. If this fails before any cleanup, **STOP** — the cleanup will mask whatever broke. Investigate first.

---

## Task 1: Clean ClprSubmitBundleHandler.java

This is the largest file (30 CLPR-DBG occurrences) and the only one with the `doHandle` / `doHandleInner` wrapper.

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java`

- [ ] **Step 1: Remove the `doHandle` / `doHandleInner` wrapper**

Currently the file has:

```java
@Override
protected void doHandle(@NonNull final HandleContext context) throws HandleException {
    try {
        doHandleInner(context);
    } catch (final HandleException he) {
        logger.warn("CLPR-DBG doHandle THREW HandleException status={}", he.getStatus(), he);
        throw he;
    } catch (final RuntimeException re) {
        logger.warn("CLPR-DBG doHandle THREW RuntimeException", re);
        throw re;
    } catch (final Throwable t) {
        logger.warn("CLPR-DBG doHandle THREW Throwable", t);
        throw t;
    }
}

private void doHandleInner(@NonNull final HandleContext context) throws HandleException {
    final var op = context.body().clprSubmitBundleOrThrow();
    logger.warn(
            "CLPR-DBG doHandle ENTER connectionId={} bundle.size={} endpointNodeId={} payer={}",
            op.connectionId().toHex(),
            op.bundlePayload().length(),
            op.endpointNodeId(),
            context.payer());
    final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
    ...
```

Replace with a single `doHandle` (delete the wrapper, rename `doHandleInner` → `doHandle`, delete the ENTER log):

```java
@Override
protected void doHandle(@NonNull final HandleContext context) throws HandleException {
    final var op = context.body().clprSubmitBundleOrThrow();
    final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
    ...
```

Use Edit tool to replace the entire 25-line wrapper region with the cleaned 3-line entry. The framework (`DispatchProcessor` line ~211) already logs `Possibly CATASTROPHIC failure` for unhandled throws.

- [ ] **Step 2: Remove every other `logger.warn("CLPR-DBG …")` call in the file**

Per-step removals (line numbers approximate; search for each unique tag):

|                  Step tag                   |                                                                                Action                                                                                 |
|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `step1 connection-loaded`                   | Delete the entire `logger.warn(...)` block.                                                                                                                           |
| `step4 calling verifier.verifyBundle`       | Delete the single-line call.                                                                                                                                          |
| `step4 verifier returned`                   | Delete the multi-line call.                                                                                                                                           |
| `step5 replay-defense`                      | Delete the multi-line call.                                                                                                                                           |
| `step6 running-hash`                        | Delete the multi-line call.                                                                                                                                           |
| `step7 ack`                                 | Delete the multi-line call.                                                                                                                                           |
| `step8 prescan range=`                      | Delete the single-line call.                                                                                                                                          |
| `step8 prescan id=` (inside loop)           | Delete the multi-line call.                                                                                                                                           |
| `step8 prescan FAIL no-matching-reply`      | Delete the single-line call.                                                                                                                                          |
| `step8 prescan FAIL trailing-reply`         | Delete the single-line call.                                                                                                                                          |
| `step8 prescan OK responseIndex=`           | Delete the single-line call.                                                                                                                                          |
| `step10 dispatch-loop start`                | Delete the multi-line call.                                                                                                                                           |
| `step10 msg[{}]`                            | Delete the multi-line call.                                                                                                                                           |
| `step10 data-msg connectorId=`              | Delete the multi-line call.                                                                                                                                           |
| `step10 connectorAccount`                   | Delete the multi-line call.                                                                                                                                           |
| `step10 underfunded → slash+reimburse path` | Delete the single-line call.                                                                                                                                          |
| `step10 slashed penalty=`                   | Delete the multi-line call.                                                                                                                                           |
| `step10 reimburse OK`                       | Delete the single-line call.                                                                                                                                          |
| `step10 dispatching app contract call`      | Delete the single-line call.                                                                                                                                          |
| `step10 app dispatch status=`               | Delete the multi-line call.                                                                                                                                           |
| `step10 app dispatch THREW HandleException` | Delete the single-line call (keep the surrounding `} catch (final HandleException e) {` and the `replyStatus = APPLICATION_ERROR;` assignment — only remove the log). |
| `step10 transferFromTo connector=`          | Delete the multi-line call.                                                                                                                                           |
| `step10 transferFromTo THREW → slash path`  | Delete the single-line call.                                                                                                                                          |
| `step10 enqueueReply status=`               | Delete the multi-line call.                                                                                                                                           |
| `step11 connection-persisted`               | Delete the multi-line call.                                                                                                                                           |
| `validateTrueOrPenalize FAIL`               | Delete the multi-line call inside `validateTrueOrPenalize`.                                                                                                           |

**CRITICAL: Preserve these (they are NOT debug):**
- The whole `Bytes rawEvmResult = null; try { rawEvmResult = result.getEvmCallResult(); } catch (final Exception ignored) { ... }` block in step 10 (around line 478-486 area). Only remove the `logger.warn(... evmResult.len=...)` call after it; keep the try-catch and the `if (rawEvmResult != null) { responseData = rawEvmResult; }` block.
- The `result.status() == SUCCESS ? SUCCESS : APPLICATION_ERROR` assignment.
- The `HandleException` catch that sets `replyStatus = APPLICATION_ERROR`.

- [ ] **Step 3: Remove the unused `logger` field and imports**

After all `logger.warn(...)` calls are gone, remove these lines from the top of the file:

```java
private static final Logger logger = LogManager.getLogger(ClprSubmitBundleHandler.class);
```

And the imports:

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
```

- [ ] **Step 4: Verify no CLPR-DBG remains in the file**

Run:

```bash
grep -c "CLPR-DBG\|doHandleInner" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java
```

Expected: `0`

- [ ] **Step 5: Verify file compiles**

Run:

```bash
./gradlew :app-service-clpr-impl:compileJava 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. If `Logger` or `LogManager` is reported as unused, also remove the import line.

- [ ] **Step 6: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java
git commit -m "refactor(clpr): remove debug logging from ClprSubmitBundleHandler"
```

---

## Task 2: Clean ClprSyncWorkflowImpl.java

**Files:**
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowImpl.java`

- [ ] **Step 1: Remove all six CLPR-DBG logger calls**

Run to locate them:

```bash
grep -n "CLPR-DBG" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowImpl.java
```

Each is a `logger.warn("CLPR-DBG ...")` call (single or multi-line). Tags include: `handleSync entry`, `Failed to parse ClprSyncPayload` (this one is genuinely useful — keep it but **rename** the prefix from `CLPR-DBG` to whatever the file's normal style is), `buildResponsePayload`, `about to submit inbound bundle`, `submitBundle returned`.

Re-read the lines around each match before deciding. Delete pure debug; keep error-path logs but strip the `CLPR-DBG` prefix.

- [ ] **Step 2: Verify no CLPR-DBG remains**

```bash
grep -c "CLPR-DBG" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowImpl.java
```

Expected: `0`

- [ ] **Step 3: Check the logger field is still needed**

If any error-path `logger.warn(...)` calls remain, keep the field and imports. Otherwise remove them.

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprSyncWorkflowImpl.java
git commit -m "refactor(clpr): remove debug logging from ClprSyncWorkflowImpl"
```

---

## Task 3: Clean EvmClprVerifier.java

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/verifier/EvmClprVerifier.java`

- [ ] **Step 1: Locate the two `System.out.println("CLPR-DBG …")` calls in `dispatchVerify`**

Run:

```bash
grep -n "CLPR-DBG" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/verifier/EvmClprVerifier.java
```

There are two: one before `context.dispatch(...)` (`dispatching to verifier contract …`) and one after (`dispatch returned status=… evmResult=…`).

- [ ] **Step 2: Delete both println blocks**

Each spans multiple lines. Remove the entire `System.out.println(...)` statement plus any continuation lines. Do NOT touch the surrounding logic — `final var result = context.dispatch(...)`, the `if (result.status() != SUCCESS)` branch, and the `getEvmCallResult()` handling are all production code.

- [ ] **Step 3: Verify no CLPR-DBG remains**

```bash
grep -c "CLPR-DBG" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/verifier/EvmClprVerifier.java
```

Expected: `0`

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app-service-clpr-impl:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/verifier/EvmClprVerifier.java
git commit -m "refactor(clpr): remove debug logging from EvmClprVerifier"
```

---

## Task 4: Clean ClprConnectionManager.java

**Files:**
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java`

This file has 18 CLPR-DBG instances (the most after ClprSubmitBundleHandler). They cover sync orchestration: `syncTick`, `registerConnection`, `initiateSync`, `performSync` (multiple stages: `entered`, `endpoints for`, `selected peer`, `about to gRPC connect`, `gRPC returned`, `Sync succeeded`).

- [ ] **Step 1: Locate all CLPR-DBG lines**

```bash
grep -n "CLPR-DBG" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
```

- [ ] **Step 2: Delete each `logger.warn("CLPR-DBG …")` call**

Read each match in context first. Some may be inside if-branches that warrant a non-debug log — convert them to debug-level (`logger.debug`) only if they describe error/edge conditions. Pure progress traces should be deleted entirely.

Heuristic: anything with the words `entering`, `entered`, `selected`, `about to`, `returned`, `succeeded`, `syncTick on instance`, `registerConnection: added` is pure trace — delete.

- [ ] **Step 3: Verify no CLPR-DBG remains**

```bash
grep -c "CLPR-DBG" hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
```

Expected: `0`

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/ClprConnectionManager.java
git commit -m "refactor(clpr): remove debug logging from ClprConnectionManager"
```

---

## Task 5: Clean ClprServiceApiImpl.java + ClprCompleteConnectionHandler.java

**Files:**
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java`
- Modify: `hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectionHandler.java`

- [ ] **Step 1: Locate and delete all CLPR-DBG in both files**

```bash
grep -n "CLPR-DBG" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java \
  hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectionHandler.java
```

`ClprServiceApiImpl` has 5 (`sendMessage entry`, `sendMessage proceeding past connector lookup`, etc.). `ClprCompleteConnectionHandler` has 1 (`calling onConnectionActivated`).

Delete each `logger.warn("CLPR-DBG …")` call. If a `logger` field becomes unused, remove it and the imports.

- [ ] **Step 2: Verify no CLPR-DBG remains**

```bash
grep -c "CLPR-DBG" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java \
  hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectionHandler.java
```

Expected: both `0`.

- [ ] **Step 3: Verify compile**

```bash
./gradlew :app-service-clpr-impl:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/ClprServiceApiImpl.java \
  hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprCompleteConnectionHandler.java
git commit -m "refactor(clpr): remove debug logging from ClprServiceApiImpl and ClprCompleteConnectionHandler"
```

---

## Task 6: Clean framework files (HandleWorkflow, DispatchProcessor, ParentTxnFactory)

These three files were instrumented earlier in the debugging session with raw `System.out.println("CLPR-DBG …")` calls (not log4j). They are general framework files; debug output here was needed to trace why CLPR transactions were being dropped.

**Files:**
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java`
- Modify: `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/steps/ParentTxnFactory.java`

- [ ] **Step 1: Locate every println in all three files**

```bash
grep -n "CLPR-DBG\|System.out.println" \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/steps/ParentTxnFactory.java
```

- [ ] **Step 2: Delete each `System.out.println("CLPR-DBG …");` block**

Some are single-line, some span multiple lines (string concatenation across lines). Use the Edit tool with enough context to make `old_string` unique. Do NOT touch the surrounding control flow (e.g., the `if (topLevelTxn == null)` branch in `HandleWorkflow.handlePlatformTransaction` is real production logic — only the println inside it is debug).

- [ ] **Step 3: Verify no CLPR-DBG remains in any of the three files**

```bash
grep -c "CLPR-DBG\|System.out.println" \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/steps/ParentTxnFactory.java
```

Expected: all `0`. If any pre-existing `System.out.println` is found that isn't `CLPR-DBG`-tagged, leave it alone — it's not from this PR.

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflow.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/DispatchProcessor.java \
  hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/steps/ParentTxnFactory.java
git commit -m "refactor: remove CLPR-DBG println debug logging from handle workflow"
```

---

## Task 7: Clean smart-contract-service-impl files

**Files:**
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/processors/CustomMessageCallProcessor.java`
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/ClprSystemContract.java`
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/common/AbstractNativeSystemContract.java`
- Modify: `hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/SendMessageCall.java`

- [ ] **Step 1: Locate every CLPR-DBG line in all four files**

```bash
grep -n "CLPR-DBG\|System.out.println" \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/processors/CustomMessageCallProcessor.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/ClprSystemContract.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/common/AbstractNativeSystemContract.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/SendMessageCall.java
```

Counts: `CustomMessageCallProcessor`=4, `ClprSystemContract`=2, `AbstractNativeSystemContract`=8, `SendMessageCall`=4. `CustomMessageCallProcessor` uses `System.out.println`; the others use `logger.warn`.

- [ ] **Step 2: Delete each CLPR-DBG line**

For `CustomMessageCallProcessor`: delete the four `System.out.println` blocks. Be careful — one is inside an `if (inSystemContracts)` branch and another in the `else`; the conditional itself is production logic.

For the other three (`ClprSystemContract`, `AbstractNativeSystemContract`, `SendMessageCall`): delete each `logger.warn("CLPR-DBG …")` call. If a `logger` field becomes unused, remove it and the imports.

- [ ] **Step 3: Verify no CLPR-DBG remains**

```bash
grep -rc "CLPR-DBG" hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl
```

Expected: every file shows `0`.

- [ ] **Step 4: Verify compile**

```bash
./gradlew :app-service-contract-impl:compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/processors/CustomMessageCallProcessor.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/ClprSystemContract.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/common/AbstractNativeSystemContract.java \
  hedera-node/hedera-smart-contract-service-impl/src/main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/clpr/sendmessage/SendMessageCall.java
git commit -m "refactor: remove CLPR-DBG debug logging from contract service impl"
```

---

## Task 8: Final verification

### Task 8a: Confirm zero CLPR-DBG anywhere

- [ ] **Step 1: Repo-wide scan**

```bash
cd <clpr-hiero-checkout> && \
grep -rn "CLPR-DBG" --include="*.java" . 2>/dev/null
```

Expected: empty output. If anything matches, address it before proceeding.

- [ ] **Step 2: Full compile**

```bash
./gradlew assemble 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`. Watch for any "unused import" warnings on log4j classes — remove them if they appear.

### Task 8b: Run the embedded single-node CLPR test

- [ ] **Step 1: Run ClprOrchestratorSubmitTest**

```bash
./gradlew :test-clients:testEmbedded --tests "com.hedera.services.bdd.suites.clpr.ClprOrchestratorSubmitTest" 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, the test passes. The log should be **dramatically quieter** than before — no `CLPR-DBG` lines at all.

### Task 8c: Run the multi-network round-trip test

- [ ] **Step 1: Run ClprHieroToHieroSuite**

```bash
./gradlew :test-clients:testEmbedded --tests "com.hedera.services.bdd.suites.clpr.ClprHieroToHieroSuite" 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass (`5 passing` in the previous run).

The `CLPR_BUNDLE_VERIFICATION_FAILED` exceptions caused by duplicate-bundle delivery will still appear in the framework's `Possibly CATASTROPHIC failure` log — that's expected and correct (the receiver is correctly rejecting stale bundles). You're verifying the test passes, not that the log is silent.

### Task 8d: Sanity-check the production-correct changes are still in place

- [ ] **Step 1: Confirm `rawEvmResult` try-catch is intact**

```bash
grep -n "rawEvmResult\|getEvmCallResult" hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/ClprSubmitBundleHandler.java
```

Expected: shows `Bytes rawEvmResult = null;`, `rawEvmResult = result.getEvmCallResult();`, and `if (rawEvmResult != null) { responseData = rawEvmResult; }`.

- [ ] **Step 2: Confirm test-side funding is intact**

```bash
grep -n "tinyBarsFromTo(GENESIS" hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprOrchestratorSubmitTest.java \
  hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/clpr/ClprHieroToHieroSuite.java
```

Expected: `ClprOrchestratorSubmitTest` shows the `0.0.3` funding line; `ClprHieroToHieroSuite` shows both the `0.0.3` and `CONNECTOR_CONTRACT` funding lines in `setupNetwork`.

- [ ] **Step 3: Confirm new ClprPassThroughVerifier is intact**

```bash
grep -c "HieroProofBytes\|HPB_QUEUE_METADATA_TAG" hedera-node/test-clients/src/main/resources/contract/contracts/ClprPassThroughVerifier/ClprPassThroughVerifier.sol
```

Expected: at least `2` (proves the new proto-walking version is still in place rather than the old verbatim-passthrough).

- [ ] **Step 4: Confirm Solidity .bin matches the .sol**

```bash
wc -c hedera-node/test-clients/src/main/resources/contract/contracts/ClprPassThroughVerifier/ClprPassThroughVerifier.bin
```

Expected: ~6016 bytes (the new walker version). If you see ~1428 bytes, the .bin reverted to the old passthrough — recompile the .sol with `solcjs --bin --abi --pretty-json -o ./_out ClprPassThroughVerifier.sol` and copy the new artifacts.

### Task 8e: Final commit and summary

- [ ] **Step 1: Verify clean working tree**

```bash
git status
```

Expected: working tree clean (all cleanup commits already made in previous tasks). If there are leftover modifications, review and either commit or revert them.

- [ ] **Step 2: Show the cleanup commits**

```bash
git log --oneline -10
```

Expected: 7 cleanup commits (one per task that modifies files: tasks 1-7), each with a `refactor(clpr):` or `refactor:` conventional-commit prefix.

---

## Done

The PR is now clean: all 90 `CLPR-DBG` debug lines removed across 13 files, the `doHandle` / `doHandleInner` wrapper is undone, the three production-correct fixes (rawEvmResult guard, test funding, new pass-through verifier) are preserved, and both CLPR test suites pass.
