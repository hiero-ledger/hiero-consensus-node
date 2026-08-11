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
     buffer vs is lost**, via 4-CN → 1-BN HAPI tests. New test classes + one diagnostic log. **Not merged, not run yet.**
- **The core finding:** a block node acknowledges **by block number, broadcast to all publishers**. On a **SELF_ISS**
  the honest majority's block N is acked and the ISS node marks its *own divergent* N acked → prunable. So survival is a
  **race between the (short) ISS-detection lag and `ackedBlocksToRetain`** — *not* the assumed "the BN won't ack it, so it
  stays." On a **CATASTROPHIC_ISS** no valid block forms → never acked → reliably retained. **Design inversion:** capture
  is *less* reliable for the common SELF case, *more* reliable for catastrophic.

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

| Topic | Fact |
|---|---|
| ISS types | `OTHER_ISS`, `SELF_ISS`, `CATASTROPHIC_ISS`. **Fatal** (halt + `"ISS detected"` log + capture) = SELF & CATASTROPHIC. OTHER is non-fatal → no capture. |
| Induction (4 nodes, weight 1 each, majority = 3) | Give one node `ledger.transfers.maxLen=5` then submit a transfer with **7 adjustments** (`movingHbar(6).distributing(GENESIS,"3".."8")` = 1 debit + 6 credits). That node rejects it → diverges → **SELF_ISS** (the others see OTHER_ISS). Make **two** nodes diverge → **2-2 split → CATASTROPHIC_ISS on all**. `ISS_NODE_ID = 1`. |
| Detection lag | Needs a majority of peers' round-N state-signature txns to reach consensus — **a few rounds (~2–5)** in a small fast net, not the worst-case 26 (that's the `roundsNonAncient` force-decide ceiling). |
| BN acks | **By block number, broadcast to all publishers** (`BlockAcknowledgement`). The ISS node applies `ack(N)` to its own divergent N (`maybeJumpToBlock`) → numerically acked → prunable. |
| SELF vs CATASTROPHIC | SELF (3-1): honest 3 form a valid proof for N → BN acks N → ISS node prunes per retention. CATASTROPHIC (2-2): no faction meets the proof threshold → no valid N → BN never acks → block retained. |
| Real vs simulator BN | **Real** (`ghcr.io/hiero-ledger/hiero-block-node`, gRPC 40840, needs Docker): verifies proofs, persists, acks — only container up/down is scriptable. **Simulator** (in-JVM, Docker-free): blindly acks by number (no verification) but exposes the full script surface (withhold acks, `BAD_BLOCK_PROOF`, `SkipBlock`, `ResendBlock`, `NodeBehindPublisher`) — **all simulator-only**. |
| Retention knob | **`blockStream.buffer.ackedBlocksToRetain`** (default **10**). Set to 1 to hold ~1 acked block. `minAckedBlocksToBuffer` no longer exists on this branch (#26701 rename) — any override of that name is **silently inert**. |
| Buffer pruning | Acked blocks pruned down to the retain floor in gRPC (no backpressure gate). **Unacked blocks never pruned** while backpressure is on (`streamMode=BLOCKS` + gRPC). |
| Observability | The buffer's own earliest/highest-acked/prune lines are DEBUG/TRACE (invisible at default INFO). We added an INFO/WARN `ISS-DIAG` line (see §5.2). |

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

> Why C9 is simulator-only and uses ResendBlock: at `networkSize=4` you can't take a peer offline during a SELF_ISS
> without dropping the agreeing weight below the 3-of-4 majority (turns it catastrophic/stalls), so we model "CN behind"
> with a streaming signal instead of a node kill.

### 5.4 Files
| Path | What |
|---|---|
| `hedera-node/hedera-app/.../cloud/uploader/IssBufferBlockReader.java` | + `ISS-DIAG` instrumentation |
| `hedera-node/test-clients/.../suites/misc/IssBufferTestSupport.java` | shared: in-JVM S3 mock, `configureNode(...)`, key helpers |
| `hedera-node/test-clients/.../suites/misc/IssBufferRaceRealTest.java` | REAL-BN matrix (C1, C2, C6, C8, C7) |
| `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java` | SIM-BN matrix (C3, C4, C5, C9) |
| `.context/iss-investigation-test-plan.md` | fuller plan (LOCAL only — gitignored, won't sync; content folded into this README) |

## 6. How to run

Both are multi-minute subprocess-network runs. Neither has been executed yet.

```bash
# Docker-free (simulator):
./gradlew hapiTestIssGrpc --tests "*IssBufferRaceSimTest"

# Real dockerized BN (needs Docker running + first-run image pull of ghcr.io/hiero-ledger/hiero-block-node):
./gradlew hapiTestBlockNodeCommunication --tests "*IssBufferRaceRealTest"

# A single scenario, e.g.:
./gradlew hapiTestIssGrpc --tests "*IssBufferRaceSimTest.selfIssBnBehindWithheldAcks"
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

Expected headline: **C2 loses** the self-ISS block (`inBuffer=false`, `.txt`), **C6 keeps** it (`acked=false`, `.iss.gz`) —
the design gap. C1 vs C2 brackets the retention-vs-lag boundary.

## 8. Status, caveats, next steps

- **Compiles clean** (`:app` + `:test-clients`, spotless applied). **Not run** — needs Docker (REAL) / time (both).
- **Fragile spot:** C6's two-reconnect catastrophic induction — if the 2-2 split doesn't cleanly form (e.g. it lands
  2-1-1 or a reconnect burst triggers early), adjust which/how-many nodes diverge. This is the first thing to watch on a run.
- The tests **observe**; SELF cases don't hard-fail on the measured outcome (they assert only that an artifact appeared).
- **Next steps:** (a) run the SIM suite first (Docker-free, fast feedback); (b) run the REAL suite with Docker; (c) read
  the `ISS-DIAG` lag/inBuffer/acked across C1/C2/C6 to quantify the gap; (d) if C2 confirms the self-ISS loss, decide the
  design response on `17267-iss-block-to-gcp` (e.g. pin the ISS round's block against acked-pruning, or capture at
  detection before the ack can prune) — note that any buffer-pinning must be reconciled with #26701.

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
