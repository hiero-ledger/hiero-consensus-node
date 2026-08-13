<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS block-buffer investigation — context & handoff

Self-contained handoff for the "when does a node lose the ISS-round block from its in-memory buffer" investigation.
Written so it can be picked up on another machine (see [§9 Syncing](#9-syncing-to-another-machine)). Everything below was
verified against the code on the `iss-block-tests` branch during the investigation.

---

## TL;DR — current state

- **Two separate efforts:**
  1. **The feature** (PR **#25943**, branch `17267-iss-block-to-gcp`): upload the ISS-round block to a cloud bucket on a
     fatal ISS. Final design = **no block-buffer changes**, **best-effort gRPC capture + a `.txt` pointer fallback**.
     This is committed & pushed on `17267-iss-block-to-gcp` and is the real PR.
  2. **The investigation** (branch `iss-block-tests`, throwaway/local): understand **when the ISS block survives in the
     buffer vs is lost**, via 4-CN → 1-BN HAPI tests. New test classes + one diagnostic log. **Not merged; RUN this
     session (2026-08-12) — see the "Results & updates" section below and [`ISS_TEST_RESULTS.md`](ISS_TEST_RESULTS.md).**
- **The core finding:** a block node acknowledges **by block number, broadcast to all publishers**. On a **SELF_ISS**
  the honest majority's block N is acked and the ISS node marks its *own divergent* N acked → prunable. So survival is a
  **race between the (short) ISS-detection lag and `ackedBlocksToRetain`** — *not* the assumed "the BN won't ack it, so it
  stays." On a **CATASTROPHIC_ISS** no valid block forms → never acked → reliably retained. **Design inversion:** capture
  is *less* reliable for the common SELF case, *more* reliable for catastrophic.
- **Refined by this session (see Results below):** the exact rule is **the ISS block survives iff it is unacked, or
  `lag ≤ keep`** (`lag = currentBlock − issBlock`, `keep = ackedBlocksToRetain`). At `networkSize=4` the detection lag is
  always **1 block**, so the self-ISS block is reliably **KEPT** at any `keep ≥ 1` — the predicted common-case SELF loss
  did **not** reproduce here. The loss appears only when `lag > keep` (forced via `keep=0` or a late notification).

---

## Results & updates (session 2026-08-12) — TESTS RUN

All tests were run (SIM + REAL against a real dockerized BN `ghcr.io/hiero-ledger/hiero-block-node:0.40.0-rc1`).
**Full per-test numbers + plain-language write-up are in [`ISS_TEST_RESULTS.md`](ISS_TEST_RESULTS.md).** Summary of
what we learned and everything that changed:

### The quantitative finding (refines the TL;DR)

The ISS-round block is present in the buffer at capture **iff it is unacknowledged, OR `lag ≤ keep`**, where
`lag = currentBlock − issBlock` and `keep = ackedBlocksToRetain`. Derivation from `BlockBufferService.pruneBuffer`:
a closed acked block is pruned when `blockNumber < highestAcked − keep + 1`; with acks flowing
`highestAcked = currentBlock − 1`, so the ISS block is pruned exactly when `lag > keep`.

- At **networkSize=4 the detection lag is always 1 block** (SIM and REAL). So `lag=1`, and the self-ISS block is
  reliably **KEPT** at every `keep ≥ 1` — the default 10 (C1) and even `keep=1` (C2, C5). **The doc's worry that the
  *common* SELF case is the fragile one did NOT reproduce at these settings** — the SELF block is kept, not lost.
  C2 (predicted loss) kept by the tightest margin (`lag=1 = keep=1`); it keeps by *policy* (keep=1 retains the newest
  acked block, which fast detection keeps = the ISS block), not by the capture out-racing the prune.
- **CATASTROPHIC** (C6): confirmed — no valid block forms → never acked (`acked=false`, `highestAcked < issBlock`) →
  never prunable → always kept even at keep=1. The same "unacked → kept" reason covers C4 (acks withheld) and C8 (BN down).

### The loss WAS reproduced — but only by forcing `lag > keep` (two new SIM tests)

A loss is a `.txt` pointer with `issBlock=-1`, `inBuffer=false`. In the fast 4-node net it never happens on its own
(lag=1); we force `lag > keep` two ways:
- **C10 `selfIssRetain0Pruned`** — `keep=0`, so even the newest acked block is prunable (`lag=1 > keep=0`). Made
deterministic with `blockStream.buffer.workerInterval=100ms` + an ack warm-up (the loss is a two-axis race: the
block must be *acked* and then *pruned* before the single no-wait GRPC capture snapshot — `bufferReader.captureToDir`).
- **C11 `selfIssLateNotification`** — `keep=1` (normal), but `blockStream.blockPeriod=0` + `roundsPerBlock=1` makes
one block per round, so detection lags ~2-3 blocks (`lag > keep`). This is the **realistic** trigger — a
bigger/slower network detects the ISS several blocks late on its own, with no knobs.
- **C12 `selfIssRealKeepsEvenAtRetain0` (REAL, follow-up):** the real dockerized BN **cannot** be forced to lose at
`networkSize=4` — even `keep=0` + a 10ms prune worker **KEEPS** the block (3/3). On the real BN the ISS block's ack
lands ~at detection (proof-verify latency), so there is no window to prune it before the capture snapshot; the sim
loses only because its instant blind-ack acks the block much earlier. So the deterministic loss is a SIM result; on
the real path a loss needs a genuinely late notification (bigger/slower network). Reassuring for the feature: the
common self-ISS block is robustly kept on the real path.

### Test-harness bug found + FIXED (unblocks C4/C5/C8)

`blockNode(0)…` simulator/container control verbs NPE'd intermittently — `BlockNodeOp.java` read the
`TARGET_BLOCK_NODE_NETWORK` thread-local, which is null on the spec's pooled execution thread for `@HapiBlockNode`
specs. **Fixed** by resolving via `BlockNodeReader.activeNetwork()` (spec target, else the thread-safe static
`SHARED_BLOCK_NODE_NETWORK`): 1 import + 3 one-line swaps, **no product code**. C4/C5/C8 now pass.

### Run-command correction (the §6 commands were wrong)

`hapiTestIssGrpc` is a lifecycle task and does **not** accept `--tests`; a bare `testSubprocess` also matches
`:yahcli:testSubprocess` and fails the build. Correct form names the lifecycle task (for tag+port) **and** the
qualified test task — see the fixed §6 below. Locally the test task uses `doNotTrackState`, so each invocation runs.

### Design response (updates §8(d))

The self-ISS loss the feature guards against is real but only bites when **`lag > keep`** — i.e. slow/late detection
(large network) or a tiny retain. At production `keep=10` with normal detection it does not occur for a small fast
net; the risk grows with network size / detection latency. To close it: pin the ISS round's block against
acked-pruning (reconciled with #26701) or capture synchronously at detection before the ack can prune. The existing
`.txt` pointer fallback already covers the residual case.

### Files changed this session (on top of the pre-session set in §5.4)

|                                 Path                                 |                                         What                                         |
|----------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `hedera-node/test-clients/.../spec/utilops/BlockNodeOp.java`         | harness fix: resolve BN via `BlockNodeReader.activeNetwork()` (not the thread-local) |
| `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java` | + C10 `selfIssRetain0Pruned`, + C11 `selfIssLateNotification`                        |
| `ISS_TEST_RESULTS.md`                                                | new — full run results, the `lag ≤ keep` rule, per-test numbers                      |

---

## 1. Background — the feature (PR #25943)

On a fatal ISS the node preserves the ISS-round block for debugging by uploading it to an S3-compatible bucket
(`iss/{timestamp}/…`), off by default (`failureBlockUpload.issBlockUploadEnabled`). Two capture triggers in
`IssDetectionUploadCoordinator`: at **detection** (async, from `FatalIssListenerImpl.notify`) and at
**CATASTROPHIC_FAILURE** (synchronous, from `Hedera.newPlatformStatus`, before the block-node connections shut down).

- `FILE` / `FILE_AND_GRPC` writer modes: the block is on disk → resolved by `IssBlockResolver`. Robust; unaffected here.
- `GRPC` writer mode: the block lives only in the in-memory `BlockBufferService` → read by `IssBufferBlockReader`. This
  is the fragile path this whole investigation is about.

## 2. The conflict (#26701) and the design decision

PR **#26701** ("cap the block buffer by bytes") rewrites `BlockBufferService.pruneBuffer`, **renames
`minAckedBlocksToBuffer` → `ackedBlocksToRetain`**, and makes acked-block retention unconditional. Our feature originally
bumped `minAckedBlocksToBuffer` 10→27 and added an ISS retention floor — which both hard-conflicts with #26701 and fights
its design.

**Decision (on `17267-iss-block-to-gcp`):** drop **all** buffer changes; rely on existing behavior (in `streamMode=BLOCKS`
+ gRPC, backpressure is always on and **unacknowledged blocks are never pruned**). Capture is **best-effort**:
- block still in buffer → upload it (`iss/…/{block}.iss.gz`);
- block gone → upload a **`.txt` pointer** (`iss/…/iss-round-N.txt`) with ISS type/round, writer mode, self node, buffer
range, ack watermark, and the active BN endpoint + its last-sent/last-acked block, so an operator can fetch it from the BN.

That decision is what motivated this investigation: **how often is the block actually gone at detection?**

## 3. The investigation — the question

For node1 (the diverging node), at the ISS-detection instant, in pure gRPC with 4 CNs → 1 BN:
- **Q1** how many blocks behind is the ISS block vs the current block being produced? (the lag)
- **Q2** is the ISS block still in the buffer?
- **Q3** has it already been acked (so it will be pruned), and does the node keep acking past it?

The goal is **understanding the gaps**, not proving the current code correct.

## 4. Mechanism reference (verified facts)

|                      Topic                       |                                                                                                                                                                                          Fact                                                                                                                                                                                           |
|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ISS types                                        | `OTHER_ISS`, `SELF_ISS`, `CATASTROPHIC_ISS`. **Fatal** (halt + `"ISS detected"` log + capture) = SELF & CATASTROPHIC. OTHER is non-fatal → no capture.                                                                                                                                                                                                                                  |
| Induction (4 nodes, weight 1 each, majority = 3) | Give one node `ledger.transfers.maxLen=5` then submit a transfer with **7 adjustments** (`movingHbar(6).distributing(GENESIS,"3".."8")` = 1 debit + 6 credits). That node rejects it → diverges → **SELF_ISS** (the others see OTHER_ISS). Make **two** nodes diverge → **2-2 split → CATASTROPHIC_ISS on all**. `ISS_NODE_ID = 1`.                                                     |
| Detection lag                                    | Needs a majority of peers' round-N state-signature txns to reach consensus — **a few rounds (~2–5)** in a small fast net, not the worst-case 26 (that's the `roundsNonAncient` force-decide ceiling).                                                                                                                                                                                   |
| BN acks                                          | **By block number, broadcast to all publishers** (`BlockAcknowledgement`). The ISS node applies `ack(N)` to its own divergent N (`maybeJumpToBlock`) → numerically acked → prunable.                                                                                                                                                                                                    |
| SELF vs CATASTROPHIC                             | SELF (3-1): honest 3 form a valid proof for N → BN acks N → ISS node prunes per retention. CATASTROPHIC (2-2): no faction meets the proof threshold → no valid N → BN never acks → block retained.                                                                                                                                                                                      |
| Real vs simulator BN                             | **Real** (`ghcr.io/hiero-ledger/hiero-block-node`, gRPC 40840, needs Docker): verifies proofs, persists, acks — only container up/down is scriptable. **Simulator** (in-JVM, Docker-free): blindly acks by number (no verification) but exposes the full script surface (withhold acks, `BAD_BLOCK_PROOF`, `SkipBlock`, `ResendBlock`, `NodeBehindPublisher`) — **all simulator-only**. |
| Retention knob                                   | **`blockStream.buffer.ackedBlocksToRetain`** (default **10**). Set to 1 to hold ~1 acked block. `minAckedBlocksToBuffer` no longer exists on this branch (#26701 rename) — any override of that name is **silently inert**.                                                                                                                                                             |
| Buffer pruning                                   | Acked blocks pruned down to the retain floor in gRPC (no backpressure gate). **Unacked blocks never pruned** while backpressure is on (`streamMode=BLOCKS` + gRPC).                                                                                                                                                                                                                     |
| Observability                                    | The buffer's own earliest/highest-acked/prune lines are DEBUG/TRACE (invisible at default INFO). We added an INFO/WARN `ISS-DIAG` line (see §5.2).                                                                                                                                                                                                                                      |

## 5. What's on this branch (`iss-block-tests`)

Branch = `origin/main` + `bn-suite-with-77-configs` + `17267-iss-block-to-gcp`, merged.

### 5.1 The merge

Three conflicts, resolved as union merges:
- `hedera-node/hedera-app/.../module-info.java` — keep the `cloud.uploader` export.
- `hedera-node/test-clients/build.gradle.kts` — union the misc-tags exclusion list; port map keeps both, with
`hapiTestIssGrpc → 30000` (was colliding with `hapiTestGenesisSubProcess` at 29600).
- Also in `build.gradle.kts`: `testSubprocess`/`testSubprocessConcurrent` now forward `-Dhapi.spec.blocknode.mode` to the
test JVM (only needed if you want SIMULATOR via the shared launcher; the tests below set the BN mode via `@HapiBlockNode`).

### 5.2 Production instrumentation (the only non-test change)

`hedera-node/hedera-app/.../cloud/uploader/IssBufferBlockReader.java` `captureToDir(...)` now emits one greppable line at
every gRPC detection (found / not-found / empty-buffer):

```
ISS-DIAG round=<r> issBlock=<n|-1> currentBlock=<last> lag=<last-n> earliestBuffered=<e> highestAcked=<h> inBuffer=<bool> acked=<bool>
```

This is the source of truth for Q1–Q3. It fires only when `failureBlockUpload.issBlockUploadEnabled=true` (the tests set it).

### 5.3 The tests (the matrix)

Each test induces the ISS on node1, waits for `"ISS detected"` + `"ISS-DIAG "`, then **records the outcome** (logs which
`iss/` artifact appeared: `.iss.gz` = block captured, `.txt` = block lost → pointer). SELF cases only assert that *some*
artifact appeared (observation); the mechanism-certain cases hard-assert.

**`IssBufferRaceRealTest`** — `@Tag(BLOCK_NODE)`, **real dockerized BN**, real TSS (no mock sigs), all nodes `maxBlocks=200`:
| method | scenario | expectation |
|---|---|---|
| `selfIssRetain10` | C1: SELF, `ackedBlocksToRetain=10` (+ C7: a healthy node stays OTHER_ISS, no `"ISS detected"`) | acked but lag < 10 → **kept** (`.iss.gz`) |
| `selfIssRetain1` | C2: SELF, `ackedBlocksToRetain=1` | acked then pruned before detection → **lost** (`.txt`) |
| `catastrophicIssRetain1` | C6: CATASTROPHIC (nodes 1+2 diverge), retain=1 | never acked → **kept** even at retain=1 (hard-asserted) |
| `selfIssBnDown` | C8: SELF, BN container taken down around the window | unacked → **kept** |

**`IssBufferRaceSimTest`** — `@Tag(ISS_GRPC)`, **simulator BN** (Docker-free), mock sigs, all nodes `maxBlocks=200`:
| method | scenario |
|---|---|
| `selfIssRetain10Sim` | C3: SELF, retain=10, acks on (blind-ack) — Docker-free mirror of C1 |
| `selfIssBnBehindWithheldAcks` | C4: SELF, retain=1, acks **withheld** ("BN behind") → unacked → **kept** (hard-asserted) |
| `selfIssBadBlockProofRejection` | C5: SELF, retain=1, inject `BAD_BLOCK_PROOF` after detection → observe CN reaction |
| `selfIssCnBehindResend` | C9: SELF, retain=10, BN sends `ResendBlock(0)` → node1 reports `TOO_FAR_BEHIND` ("CN behind") |
| `selfIssRetain0Pruned` | **C10 (NEW):** SELF, `keep=0` + fast prune worker + ack warm-up → acked ISS block pruned before capture → **lost** (`.txt`, hard-asserted) |
| `selfIssLateNotification` | **C11 (NEW):** SELF, `keep=1` but `blockPeriod=0` (1 block/round) → detection lags 2-3 blocks (`lag>keep`) → **lost** (`.txt`, hard-asserted) |

> Why C9 is simulator-only and uses ResendBlock: at `networkSize=4` you can't take a peer offline during a SELF_ISS
> without dropping the agreeing weight below the 3-of-4 majority (turns it catastrophic/stalls), so we model "CN behind"
> with a streaming signal instead of a node kill.

### 5.4 Files

|                                 Path                                  |                                               What                                                |
|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| `hedera-node/hedera-app/.../cloud/uploader/IssBufferBlockReader.java` | + `ISS-DIAG` instrumentation                                                                      |
| `hedera-node/test-clients/.../suites/misc/IssBufferTestSupport.java`  | shared: in-JVM S3 mock, `configureNode(...)`, key helpers                                         |
| `hedera-node/test-clients/.../suites/misc/IssBufferRaceRealTest.java` | REAL-BN matrix (C1, C2, C6, C8, C7)                                                               |
| `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java`  | SIM-BN matrix (C3, C4, C5, C9; + C10, C11 added this session)                                     |
| `hedera-node/test-clients/.../spec/utilops/BlockNodeOp.java`          | harness fix (this session): resolve BN via `BlockNodeReader.activeNetwork()` not the thread-local |
| `.context/iss-investigation-test-plan.md`                             | fuller plan (LOCAL only — gitignored, won't sync; content folded into this README)                |

## 6. How to run

Multi-minute subprocess-network runs. **Name the lifecycle task (for tag + port) AND the qualified
`:test-clients:testSubprocess`.** `hapiTestIssGrpc` alone rejects `--tests` (it is a lifecycle task), and a bare
`testSubprocess` also matches `:yahcli:testSubprocess` and fails the build. Locally each invocation actually runs
(`doNotTrackState`).

```bash
# Docker-free (simulator):
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest"

# Real dockerized BN (needs Docker + first-run pull of ghcr.io/hiero-ledger/hiero-block-node:0.40.0-rc1
# and Maven-Central download of its 0.40.0-rc1 plugin jars):
./gradlew hapiTestBlockNodeCommunication :test-clients:testSubprocess --tests "*IssBufferRaceRealTest"

# A single scenario, e.g.:
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssLateNotification"
```

## 7. How to read the result

Two sources, both certain:
1. **Gradle test output** — each test logs `Cx … outcome: blockCaptured(.iss.gz)=… blockLost(.txt)=… keys=[…]`.
2. **node1's application log** — grep the `ISS-DIAG` line for the numbers:

```bash
grep -r "ISS-DIAG" hedera-node/test-clients/build/**/node1/output/hgcaa.log
# (path varies by run; search the test working dirs' output/hgcaa.log)
```

`lag = currentBlock − issBlock`; `inBuffer=true` ⇒ Q2 kept; `acked=true` ⇒ Q3 already acked (→ prunable).

Expected headline (pre-run hypothesis): **C2 loses** the self-ISS block, **C6 keeps** it. **Actual (this session):
C2 KEPT** the block (`lag=1 ≤ keep=1`) and C6 kept it (never acked). The predicted C2 loss did **not** occur — the
loss only appears at `lag > keep` (reproduced via C10 `keep=0` and C11 late notification). See the Results section.

## 8. Status, caveats, next steps

- **Run this session (2026-08-12); all tests pass.** Compiles clean; the harness NPE that blocked the `blockNode(0)`
  tests (C4/C5/C8) is fixed (`BlockNodeOp` → `BlockNodeReader.activeNetwork()`). Full results in
  [`ISS_TEST_RESULTS.md`](ISS_TEST_RESULTS.md). (Spotless was **not** re-run after the session's edits — run before any PR.)
- **C6's two-reconnect catastrophic induction landed cleanly** (all 4 nodes CATASTROPHIC_ISS). It is still the most
  fragile spot — if a future run lands 2-1-1 instead, adjust which/how-many nodes diverge.
- Outcomes: SELF observation tests (C1/C2/C3/C5/C9) all **KEPT** the block (`lag=1 ≤ keep`). C4/C6/C8 hard-assert keep
  (unacked). C10/C11 hard-assert loss (`lag > keep`).
- **Next steps / open design question:** the self-ISS loss only occurs at `lag > keep`. Decide on
  `17267-iss-block-to-gcp` whether that residual risk (large/slow networks, or a tiny retain) warrants pinning the ISS
  round's block against acked-pruning (reconciled with #26701) or a synchronous capture at detection — or whether the
  `.txt` pointer fallback is sufficient. To quantify `lag` on a bigger/slower topology, raise `networkSize`.

## 9. Syncing to another machine

`.context/` is gitignored and will **not** sync — this README and all code changes are in the git tree, so:

```bash
# On THIS machine — commit the working-tree changes and push the branch (nothing is committed automatically):
git add -A
git commit -m "wip: ISS buffer investigation (tests + ISS-DIAG instrumentation)"
git push -u origin iss-block-tests

# On the OTHER machine:
git fetch origin && git checkout iss-block-tests
# then read this file and continue from §8.
```

Branches to know:
- `17267-iss-block-to-gcp` — the **feature PR #25943** (buffer reverts + `.txt` fallback), committed & pushed; the real deliverable.
- `iss-block-tests` — this **investigation** (merge of main + bn-suite-with-77-configs + 17267 + these tests); throwaway.
- `bn-suite-with-77-configs` — the block-node test-harness branch merged in for the real-BN plumbing.
