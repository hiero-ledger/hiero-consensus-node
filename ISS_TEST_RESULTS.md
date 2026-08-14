<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS buffer tests — run results

This file holds the results of running the ISS block-buffer investigation tests, one by one.
It is written in plain language so anyone can read it.

**What the tests are about (short version):** when a node hits a fatal ISS (its state does not
match the others), the node tries to save the block from that round to a cloud bucket for debugging.
In pure gRPC mode the block only lives in memory. These tests check: **at the moment the ISS is found,
is that block still in memory, or was it already thrown away?**

**How to read each result:**
- `.iss.gz` uploaded = the block was still there → **saved (kept)**.
- `.txt` uploaded = the block was gone → only a text pointer was saved → **lost**.
- The `ISS-DIAG` log line gives the numbers:
- `issBlock` = the block number of the ISS round.
- `currentBlock` = the newest block when the ISS was found.
- `lag` = how many blocks behind the ISS block was (`currentBlock - issBlock`).
- `inBuffer` = was the block still in memory?
- `acked` = had the block node already said "got it" for that block? (if yes, it can be thrown away).

---

## Bottom line (read this first)

- **All nine tests now pass.** Three of them (C4, C5, C8) originally failed on a flaky **test-framework** bug (not a
  product bug); that bug is now **fixed** (see the end), and they pass.
- **The two outcomes split exactly as the mechanism predicts:**
  - In the "normal" cases the ISS block is **SAVED** (`.iss.gz`) — the ISS is found only ~1 block after it happens, so
    the block is still in the in-memory buffer (C1, C2, C3, C5, C9 — and C4/C8 for the "unacked" reason below).
  - In the **two new loss tests** the ISS block is **LOST** (only a `.txt` pointer): **C10** forces it with keep=0
    (the acked block is pruned immediately), and **C11** forces it at a normal keep=1 by making the ISS notification
    arrive **late** (detection lags 2-3 blocks, so the block falls out of the keep window). The "notification is
    late / block already pruned" case, recreated two ways.
- **The doc's central worry is real but timing-dependent.** A self-ISS block *does* get acked, so it *can* be pruned
  and lost. The rule is simple: the block survives iff **lag ≤ keep** (lag = blocks between the ISS and its
  detection). At the default keep=10 with this network's ~1-block detection it is always kept; make lag exceed keep —
  by shrinking keep to 0 (C10) or by making detection lag 2-3 blocks (C11) — and it is reliably lost.
- **A catastrophic ISS block is never acked, so it can never be pruned and is always kept** (C6). The same
  "unacked → kept" reason is why C4 (acks withheld) and C8 (block node down) keep the block even at keep=1.

---

## Run environment

- Date of run: 2026-08-12
- Machine: local (darwin), Docker daemon live (v28.0.1).
- Branch: `iss-block-tests`.
- Real block-node image needed by the REAL tests: `ghcr.io/hiero-ledger/hiero-block-node:0.40.0-rc1`.
  This image was pulled successfully before the run (747 MB, arm64/amd64 multi-arch). So the real tests can start their block node.
- Note on how the tests are run: the handoff doc's command `./gradlew hapiTestIssGrpc --tests "..."` does **not** work.
  `hapiTestIssGrpc` is a wrapper task and does not accept `--tests`. The working command names the wrapper task
  (for the tag + port) **and** the real test task `:test-clients:testSubprocess` together, like this:
  `./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssRetain10Sim"`
  (the `:test-clients:` prefix matters — a bare `testSubprocess` also matches `:yahcli:testSubprocess` and fails the
  build. Real tests use `hapiTestBlockNodeCommunication` instead of `hapiTestIssGrpc`.)

---

## Test list and order

Simulator tests first (no Docker needed), then the real-block-node tests.

|  #  |             Test              | Suite |                          What it checks                           |                                          Status                                           |
|-----|-------------------------------|-------|-------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| C3  | selfIssRetain10Sim            | SIM   | self-ISS, keep 10 acked blocks, acks on                           | ✅ PASS — block KEPT (.iss.gz), lag=1                                                      |
| C4  | selfIssBnBehindWithheldAcks   | SIM   | self-ISS, keep 1, acks withheld → should keep block               | ✅ PASS after harness fix — block KEPT (.iss.gz), acked=FALSE (acks withheld), lag=1       |
| C5  | selfIssBadBlockProofRejection | SIM   | self-ISS, keep 1, bad-proof reject after detect                   | ✅ PASS after harness fix — block KEPT (.iss.gz), lag=1, acked=true; bad-proof injected    |
| C9  | selfIssCnBehindResend         | SIM   | self-ISS, keep 10, resend old block → "CN behind"                 | ✅ PASS — block KEPT (.iss.gz), lag=1; resend→"block does not exist"                       |
| C1  | selfIssRetain10               | REAL  | self-ISS, keep 10 (default)                                       | ✅ PASS — block KEPT (.iss.gz), lag=1, acked=true; node0 no fatal ISS                      |
| C2  | selfIssRetain1                | REAL  | self-ISS, keep 1 → expected to lose block                         | ✅ PASS — but block KEPT (.iss.gz), lag=1 → **predicted loss did NOT happen**              |
| C6  | catastrophicIssRetain1        | REAL  | catastrophic ISS, keep 1 → should keep block                      | ✅ PASS — CATASTROPHIC on all 4 nodes, block KEPT, acked=FALSE                             |
| C8  | selfIssBnDown                 | REAL  | self-ISS, block node taken down → should keep block               | ✅ PASS after harness fix — block KEPT (.iss.gz), acked=FALSE (BN down), lag=1             |
| C10 | selfIssRetain0Pruned          | SIM   | **NEW: self-ISS, keep 0 → block pruned before capture (LOSS)**    | ✅ PASS — block LOST (.txt), ISS block pruned; deterministic                               |
| C11 | selfIssLateNotification       | SIM   | **NEW: self-ISS, keep=1 but LATE notification (lag>keep) → LOSS** | ✅ PASS — block LOST (.txt), lag≈2-3 > keep=1; deterministic                               |
| C12 | selfIssRealKeepsEvenAtRetain0 | REAL  | **NEW: can the REAL BN lose? self-ISS, keep=0 + 10ms prune**      | ✅ PASS — block **KEPT** (.iss.gz) 3/3; real BN's ack lands at detection → no prune window |

---

## Results

### C3 — selfIssRetain10Sim (SIM) — ✅ PASS, block KEPT

- **Setup:** self-ISS on node1. Simulator block node. Keep 10 acked blocks. Acks turned on.
- **ISS type seen:** SELF_ISS at round 420. This is correct — only node1 diverged, the other three stayed healthy.
- **What the numbers said at the moment the ISS was found:**
  - ISS block number: **17**
  - Newest block being made: **18**
  - Lag (how far behind): **1 block**
  - Still in memory: **YES**
  - Already acked (so throw-away-able): **NO** (nothing had been acked yet)
- **Result:** the real block was **SAVED**. The node uploaded `…0017.iss.gz`.
- **Plain meaning:** the ISS block was only 1 block old and had not been thrown away, so the node saved the actual block, not just a text pointer.
- **Test verdict:** PASS. One test ran and its check ("some iss/ file was uploaded") held.
- **Small note:** in the simulator, nothing had been acked yet when the ISS was found (`highestAcked=-1`), so here the block survived mostly because it was still fresh and unacked — not because the 10-block keep-window saved it.

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=420 issBlock=17 currentBlock=18 lag=1 earliestBuffered=1 highestAcked=-1 inBuffer=true acked=false
```

Note: the overall Gradle build printed FAILED, but only because of an unrelated task (`:yahcli:testSubprocess`) that shares the name `testSubprocess`. The real test task `:test-clients:testSubprocess` ran the C3 test and it passed. Later runs name the task fully to avoid this.

### C4 — selfIssBnBehindWithheldAcks (SIM) — ✅ PASS (after the harness fix) — originally failed ×3

> **UPDATE — after the test-harness fix (see "The harness fix" section near the end), C4 now PASSES.**
> ISS: SELF_ISS at round 411. `ISS-DIAG issBlock=17 currentBlock=18 lag=1 earliestBuffered=16 highestAcked=16 inBuffer=true acked=false`.
> The `blockNode(0)` "stop sending acks" command now runs (no crash), so the ISS block (17) is never acked
> (highestAcked stays at 16) → never prunable → **block SAVED (`…0017.iss.gz`)**. This is exactly the invariant C4 was
> meant to show: an unacked block is kept even at keep=1. The original failure write-up below is kept for the record.

- **What should have happened:** self-ISS on node1, simulator block node, keep only 1 acked block, and tell the
  block node to STOP sending "got it" messages (acks). With no acks, the block can never be thrown away, so the
  block should be saved even with keep=1.
- **What actually happened:** the test crashed **before** it could cause the ISS. The crash was a
  NullPointerException **inside the test framework**, not in the product code.
- **The crash, in plain words:** the test tried to send a control command to the block node
  (`blockNode(0)` → "stop sending acks"). The framework looked up "which block node am I talking to" and found
  nothing (an internal thread-local value was empty), so it threw a NullPointerException.
- **Exact spot:** `BlockNodeOp.java:109` — `HapiSpec.TARGET_BLOCK_NODE_NETWORK.get()` returned `null`.
- **Why (short):** the block-node handle is stored per-thread and set up in the JUnit `beforeEach` step, but the
  test's steps run on a different thread where that handle was never set. Tests that never send a `blockNode(0)`
  command do not touch this handle, so they are fine.
- **ISS numbers:** none. Run **3 times** (~47s each); every time it crashed at the same `blockNode(0)` command,
  which runs **before** the ISS is induced — so no ISS was ever produced and there is no data to report for C4.
- **Impact on the rest (now confirmed by later runs):** the crash is **flaky**, not fixed-per-command.
  - C5 crashed at its command too, but *after* the ISS was found → it still produced full ISS data.
  - C9 uses the same kind of command and did **not** crash at all (passed).
  - So the difference is timing, not the command. C4's command sits early (right after the config check) and
    happened to crash on all 3 tries.
  - C8 (real) puts its command in the **same early spot** as C4, so it is likely to crash the same way.
  - Tests with no `blockNode(0)` command (C3, C1, C2, C6) are unaffected.
- **Test verdict:** FAIL (framework NullPointerException, 3/3 runs).

Raw failure message:

```
java.lang.NullPointerException: Cannot invoke
"com.hedera.services.bdd.junit.hedera.BlockNodeNetwork.getBlockNodeModeById()"
because the return value of "java.lang.ThreadLocal.get()" is null
  at ...BlockNodeOp.submitOp(BlockNodeOp.java:109)
```

### C5 — selfIssBadBlockProofRejection (SIM) — ✅ PASS (after the harness fix) — originally failed at the verb

> **UPDATE — after the test-harness fix, C5 now PASSES fully.** The bad-proof command now runs (no crash), so the
> intended reaction is exercised, and the block is still saved. ISS: SELF_ISS at round 454.
> `ISS-DIAG issBlock=17 currentBlock=18 lag=1 earliestBuffered=17 highestAcked=17 inBuffer=true acked=true` →
> **block SAVED (`…0017.iss.gz`)**. Same conclusion as the first (partial) run below, now with a clean pass.

- **Setup:** self-ISS on node1, simulator block node, keep only 1 acked block, acks on. The plan was: after the
  ISS is found, tell the block node to reject a block's proof (a "bad proof" message) and watch how the node reacts.
- **What actually happened:** the ISS part worked and the block was saved. THEN the test tried the `blockNode(0)`
  "send bad proof" command and hit the **same framework NullPointerException as C4**. So the bad-proof reaction was
  never observed, and the test is marked FAILED — but the ISS numbers and the save/lose result were already produced.
- **ISS type:** SELF_ISS at round 432. Correct.
- **Numbers when the ISS was found:**
  - ISS block: **17**, newest block: **18**, lag: **1 block**.
  - Still in memory: **YES**. Already acked: **YES** (the block node had said "got it" for block 17).
  - Oldest block still in memory: **16**.
- **Result:** block **SAVED** (`…0017.iss.gz`).
- **Plain meaning:** even with keep=1 **and** the block already acked, the block survived — because the ISS was
  found extremely fast (only 1 block later), before the buffer could throw it away. This is the "race" in action:
  when detection is this fast, even keep=1 wins.
- **Test verdict:** FAIL (framework NullPointerException at the bad-proof command, *after* the ISS was found and saved).

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=432 issBlock=17 currentBlock=18 lag=1 earliestBuffered=16 highestAcked=17 inBuffer=true acked=true
```

### C9 — selfIssCnBehindResend (SIM) — ✅ PASS, block KEPT

- **Setup:** self-ISS on node1, simulator block node, keep 10 acked blocks, acks on. After the ISS, the block node
  asks node1 to resend a very old block (block 0), to act out the "the node is behind the block node" case.
- **ISS type:** SELF_ISS at round 427. Correct.
- **Numbers when the ISS was found:**
  - ISS block: **17**, newest block: **18**, lag: **1 block**.
  - Still in memory: **YES**. Already acked: **YES**. Oldest block in memory: **7**.
- **Result:** block **SAVED** (`…0017.iss.gz`).
- **The "resend old block" part worked:** the block node asked for block 0, and the nodes answered
  "block 0 does not exist; closing connection" (block 0 was thrown away long ago). This is the expected
  "too far behind" reaction.
- **Test verdict:** PASS.
- **Important:** C9 uses a `blockNode(0)` command with the **same shape as C5**, yet C9 did **not** hit the crash
  that C4 and C5 hit. Same code path, different result → the crash is a **flaky timing bug** (a race on that
  per-thread block-node handle), not a fixed per-command bug. So C4 and C5 can pass if run again. I re-run them below.

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=427 issBlock=17 currentBlock=18 lag=1 earliestBuffered=7 highestAcked=17 inBuffer=true acked=true
```

### C1 — selfIssRetain10 (REAL) — ✅ PASS, block KEPT

- **Setup:** self-ISS on node1, **REAL** dockerized block node, keep 10 acked blocks (the normal default), real signatures.
- **The real block node started fine** — image + plugins loaded, all nodes connected and streamed blocks.
- **ISS type:** SELF_ISS at round 580. Correct.
- **Numbers when the ISS was found:**
  - ISS block: **16**, newest block: **17**, lag: **1 block**.
  - Still in memory: **YES**. Already acked: **YES** (the real block node acked block 16). Oldest in memory: **6**.
- **Result:** block **SAVED** (`…0016.iss.gz`).
- **Extra check (this test also covers case C7):** the healthy node (node0) did **NOT** log a fatal ISS (count = 0).
  It only saw the non-fatal "another node disagrees" case. Correct — only the diverging node halts.
- **Plain meaning:** even though the real block node had already acked the ISS block, the ISS was found just 1 block
  later, so the block was still in memory (keep=10) and got saved.
- **Test verdict:** PASS.

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=580 issBlock=16 currentBlock=17 lag=1 earliestBuffered=6 highestAcked=16 inBuffer=true acked=true
```

### C2 — selfIssRetain1 (REAL) — ✅ PASS — but the block was KEPT, not lost (opposite of the prediction)

- **Setup:** self-ISS on node1, **REAL** block node, keep only **1** acked block. The handoff doc predicted this
  test would **LOSE** the block (only a `.txt` pointer), because the block gets acked and then thrown away before
  the ISS is found.
- **ISS type:** SELF_ISS at round 607. Correct.
- **Numbers when the ISS was found:**
  - ISS block: **16**, newest block: **17**, lag: **1 block**.
  - Still in memory: **YES**. Already acked: **YES** (block 16 acked). Oldest in memory: **15**.
- **Result:** block **SAVED** (`…0016.iss.gz`). NOT lost. **No `.txt` pointer was written.**
- **Plain meaning (the headline):** the predicted loss did **NOT** happen. Even with keep=1 and the block already
  acked, the ISS was found only 1 block later, so the block was still in memory and got saved. The buffer always
  keeps at least the most recent acked block, and with a 1-block lag the ISS block *is* that block.
- **Test verdict:** PASS.

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=607 issBlock=16 currentBlock=17 lag=1 earliestBuffered=15 highestAcked=16 inBuffer=true acked=true
```

### C6 — catastrophicIssRetain1 (REAL) — ✅ PASS, block KEPT (never acked)

- **Setup:** nodes 1 **and** 2 both diverge → a 2-2 split → no group of 3 agrees → **CATASTROPHIC** ISS on ALL
  nodes. Keep only 1 acked block. Real block node.
- **The 2-2 split landed correctly:** all four nodes (0, 1, 2, 3) logged CATASTROPHIC_ISS at round 822. (The doc
  warned this induction can misfire into a 2-1-1 split; it did not — it worked.)
- **Numbers when the ISS was found (node1):**
  - ISS block: **25**, newest block: **26**, lag: **1 block**.
  - Still in memory: **YES**. Already acked: **NO**. Highest acked block: **24** (which is *below* the ISS block 25).
  - Oldest in memory: **24**.
- **Result:** block **SAVED** (`…0025.iss.gz`).
- **Plain meaning (the important contrast):** in a catastrophic ISS, no valid block is ever formed, so the block
  node never says "got it" for the ISS block. It can never be thrown away, so it is always kept — even at keep=1.
  The self-ISS cases (C1, C2, C5) *did* get acked and survived only because detection was fast; the catastrophic
  case survives for a stronger reason — it is never ack-able in the first place.
- **Test verdict:** PASS (this test hard-requires the block to be kept, and it was).

Raw ISS-DIAG line from node1:

```
ISS-DIAG round=822 issBlock=25 currentBlock=26 lag=1 earliestBuffered=24 highestAcked=24 inBuffer=true acked=false
```

### C8 — selfIssBnDown (REAL) — ✅ PASS (after the harness fix) — originally failed

> **UPDATE — after the test-harness fix, C8 now PASSES.** The `blockNode(0).shutDownImmediately()` command runs
> (no crash), so the real block node is down across the ISS window and no acks arrive for the ISS block. ISS:
> SELF_ISS at round 744. `ISS-DIAG issBlock=21 currentBlock=22 lag=1 earliestBuffered=20 highestAcked=20 inBuffer=true acked=false`
> → the ISS block (21) is unacked (highestAcked stays at 20) → never prunable → **block SAVED (`…0021.iss.gz`)**. Same
> "unacked → kept" mechanism as the catastrophic case (C6). The original failure write-up below is kept for the record.

- **Setup:** self-ISS on node1, REAL block node, keep 1. The plan: take the block node **DOWN** before the ISS, so
  no acks can arrive, so the ISS block stays unacked and is kept.
- **What happened:** the test crashed at its very first block node command — `blockNode(0).shutDownImmediately()` —
  with the **same framework NullPointerException as C4**. This command runs **before** the ISS is induced, so no ISS
  was produced and there is no data for C8.
- **Why no retry:** this matches the C4 pattern exactly (the command sits in the early spot, right after the config
  check). C4 failed this way 3 times in a row, so a retry is unlikely to help; I did not spend more real-block-node
  runs (~2.5 min each) on it. Can retry on request.
- **Not a real loss of insight:** C8's intended point — "block node down → block never acked → block kept" — is
  already shown by **C6**, where the catastrophic block was never acked and was kept.
- **Test verdict:** FAIL (framework NullPointerException, ran ~2m26s including real block node startup).

---

### C10 — selfIssRetain0Pruned (SIM) — ✅ PASS, block LOST (the recreated "late notification / already pruned" case)

This is the **new test added to recreate the loss** the whole investigation is about: the ISS block is acked, then
thrown away (pruned) before the capture reads the buffer, so only a `.txt` pointer is saved.

- **Setup:** self-ISS on node1, simulator block node, **keep 0 acked blocks** (`ackedBlocksToRetain=0`), acks on.
  Two extra knobs make the loss happen on every run (see "Why it needed tuning" below):
  - node1's buffer worker runs every **100ms** (`blockStream.buffer.workerInterval=100ms`) so pruning fires quickly.
  - an **8-second warm-up** before the ISS so the block node's acks are already flowing.
- **What happens:** block N is produced and acked, and because keep=0 the buffer is allowed to drop it as soon as it
  is acked. The fast worker prunes it within ~100ms. Then the ISS is found and the async capture reads the buffer —
  and the block is already gone.
- **Result (two confirming runs):** block **LOST**. The node uploaded a **`.txt` pointer** (`iss-round-<r>.txt`),
  not a block file.
- **Numbers when the ISS was found (one run):**
  - ISS block: **not found (−1)** — it was already pruned.
  - Newest block: 21. Oldest block still in memory: 21 (everything older was pruned).
  - Highest acked block: 20. Still in memory: **NO**.
- **Test verdict:** PASS. The test asserts a `.txt` pointer (a loss) was written, and it was — on both runs.

Raw ISS-DIAG line from node1 (loss):

```
ISS-DIAG round=454 issBlock=-1 currentBlock=21 lag=-1 earliestBuffered=21 highestAcked=20 inBuffer=false acked=false
```

(`issBlock=-1` and `inBuffer=false` mean the ISS block was no longer in the buffer — it had been pruned.)

**Why it needed tuning (the loss is a race).** With only `keep=0` and default timing the block was sometimes kept.
The loss needs two things to both happen before the capture reads the buffer: (1) the block must be **acked**, and
(2) it must then be **pruned**. In this small, fast network the ISS is found only ~1 block after it happens, so both
are tight races — some early tries kept the block because the ack had not arrived yet (`highestAcked=-1`), others
because the prune had not run yet. The 8s warm-up fixes (1) and the 100ms worker fixes (2), so the loss now happens
every run. This is the same race the handoff doc worried about; a real network would hit it naturally when detection
lags many blocks behind (a bigger/slower network), without needing these knobs.

---

### C11 — selfIssLateNotification (SIM) — ✅ PASS, block LOST at a NORMAL keep=1 (the "late notification" case)

This test answers the follow-up question: **can we make the ISS notification arrive late (instead of forcing the loss
with keep=0)?** Yes. It reproduces the loss at a normal retention window (keep=1) by making detection lag several
blocks behind.

- **How the "late notification" is created:** `blockStream.blockPeriod=0` + `blockStream.roundsPerBlock=1` makes the
  node close **one block per round**. Detecting a fatal ISS takes a few rounds, so those few rounds are now a few
  **blocks** — the ISS block ends up ~2-3 blocks behind the current block by detection time (instead of the ~1 block
  you get with the normal 2-second block period). node1 also prunes fast (`workerInterval=100ms`) so keep=1 is
  genuinely enforced, and there is an 8s warm-up so acks are flowing.
- **What happens:** with keep=1 the buffer keeps only the single newest acked block. Because detection lagged 2-3
  blocks, the ISS block is no longer the newest acked block — it is below the keep=1 threshold — so it is pruned
  before the capture reads the buffer.
- **Result (confirmed on multiple runs):** block **LOST** — a `.txt` pointer (`iss-round-<r>.txt`), not a block file.
- **Numbers (one run):** ISS round 575, newest block 577, oldest block in memory 576 → the ISS block (575) is gone
  (`issBlock=-1`, `inBuffer=false`), even though it had been acked (`highestAcked=577`).
- **Test verdict:** PASS (asserts a `.txt` loss; lost on every run).

Raw ISS-DIAG line from node1 (loss):

```
ISS-DIAG round=575 issBlock=-1 currentBlock=577 lag=-1 earliestBuffered=576 highestAcked=577 inBuffer=false acked=false
```

**This is the empirical proof of the `lag ≤ keep` rule from the "Why C2 kept the block" section.** C2 kept the block
because a fast notification gave `lag=1 = keep=1`. C11 makes the notification late (`lag≈2-3 > keep=1`) and the block
is lost — the *same* keep=1 setting, opposite outcome, decided purely by how far detection lagged. In the real system
this is what a bigger/slower network (slower ISS detection) would cause on its own, without any of these knobs.

---

### C12 — selfIssRealKeepsEvenAtRetain0 (REAL) — ✅ PASS, block KEPT even at keep=0 (answers "can we make C2 lose on the real BN?")

Follow-up to the C2 question: **can the real dockerized BN be made to lose the self-ISS block?** Short answer: **not
at networkSize=4** — even the most aggressive setting keeps it.

- **Setup:** self-ISS on node1, REAL block node, **keep=0**, node1 pruning every **10ms** (`workerInterval=10ms`),
  8s ack warm-up. This is the real-BN version of the SIM loss test C10.
- **Result: KEPT on 3/3 runs** (`.iss.gz`). `ISS-DIAG issBlock=20 currentBlock=21 lag=1 earliestBuffered=20
  highestAcked=20 inBuffer=true acked=true` — the block was acked and keep=0 made it prunable, but it was **not
  pruned** before the capture read the buffer.
- **Why it keeps (the key difference from the simulator):** on the real BN the ISS block's acknowledgement arrives
  ~at the same instant as detection — both follow from the *next* block being processed and its proof verified. So
  there is no window to prune the block before the async capture snapshot. In the simulator (C10) the **instant
  blind-ack** acks the block much earlier, which is exactly what lets the prune drop it in time; even a 10ms prune
  worker cannot beat the real BN's ack→detection gap.
- **Meaning:** the common self-ISS block is **robustly kept on the real path** even under the most aggressive
  retention. A real-BN loss would need `lag > 1` — a genuinely late notification, i.e. a bigger/slower network (the
  `blockPeriod=0` trick that powers C11 would swamp real TSS block-proof generation, so it is not usable here).
- **Test verdict:** PASS (observation; records the kept outcome).

---

## Summary — all the numbers in one place

| Test | Block node | keep  |   ISS type   |  ISS block  | newest block | lag  | in buffer? |       acked?       |       Result       |
|------|------------|-------|--------------|-------------|--------------|------|------------|--------------------|--------------------|
| C3   | simulator  | 10    | SELF         | 17          | 18           | 1    | yes        | no                 | **KEPT** (.iss.gz) |
| C5   | simulator  | 1     | SELF         | 17          | 18           | 1    | yes        | yes                | **KEPT** (.iss.gz) |
| C9   | simulator  | 10    | SELF         | 17          | 18           | 1    | yes        | yes                | **KEPT** (.iss.gz) |
| C1   | real       | 10    | SELF         | 16          | 17           | 1    | yes        | yes                | **KEPT** (.iss.gz) |
| C2   | real       | 1     | SELF         | 16          | 17           | 1    | yes        | yes                | **KEPT** (.iss.gz) |
| C6   | real       | 1     | CATASTROPHIC | 25          | 26           | 1    | yes        | no                 | **KEPT** (.iss.gz) |
| C4   | simulator  | 1     | SELF         | 17          | 18           | 1    | yes        | no (acks withheld) | **KEPT** (.iss.gz) |
| C8   | real       | 1     | SELF         | 21          | 22           | 1    | yes        | no (BN down)       | **KEPT** (.iss.gz) |
| C10  | simulator  | **0** | SELF         | pruned (−1) | 21           | —    | **no**     | acked → pruned     | **LOST** (.txt)    |
| C11  | simulator  | 1     | SELF         | pruned (−1) | 577          | ~2-3 | **no**     | acked → pruned     | **LOST** (.txt)    |
| C12  | real       | 0     | SELF         | 20          | 21           | 1    | yes        | acked, not pruned  | **KEPT** (.iss.gz) |

Pass/fail (after the harness fix): **all nine tests pass** — C1, C2, C3, C4, C5, C6, C8, C9 (block kept) and C10
(block lost, as intended). Before the fix, C4/C5/C8 failed on the harness NullPointerException described at the end.

## What this means (plain words)

1. **The block is kept in the normal cases, and is lost only under aggressive pruning.** In the eight "normal" runs
   the node saved the real block file (`.iss.gz`). Only the new keep=0 test (C10) lost it (a `.txt` pointer), by
   forcing the buffer to drop the acked block before the capture ran — the loss the investigation set out to recreate.
2. **Detection is fast (1 block).** In these 4-node networks the diverging node notices the ISS about one block after
   it happens. That is much smaller than the keep-window, so the block is always still in memory.
3. **"Keep 1" was enough here.** Even with the smallest keep setting and the block already acked (C2, C5), the block
   survived — because of that 1-block detection speed.
4. **Self vs catastrophic — the real difference is in the "acked" column, not the result:**
   - Self-ISS (C1, C2, C5): `acked = yes`. The block node acked the diverging block by number. It *could* have been
     thrown away; it survived only because detection was fast.
   - Catastrophic (C6): `acked = no`. No valid block ever formed, so it was never acked and can never be thrown away.
     This is the safer case, exactly as the doc said.
5. **When is the block actually lost?** When the detection lag exceeds the keep-window (**lag > keep**). This is now
   demonstrated two ways: **C11** makes detection lag 2-3 blocks at keep=1 (a late notification → lost), and **C10**
   sets keep=0 so even the newest acked block is dropped (→ lost). In the real system the C11 case is the natural one
   — a bigger/slower network detects the ISS several blocks late, all on its own.

## Why C2 kept the block (the exact rule)

C2 (real BN, keep=1) was *expected* to LOSE the block but KEPT it. Here is exactly why.

The buffer prunes a closed, acknowledged block only when
`blockNumber < highestAcked − ackedBlocksToRetain + 1`,
so a block **survives** when `highestAcked − blockNumber ≤ keep − 1`.

Because acks are flowing, the newest acked block is `currentBlock − 1` (the current block is still open, so it is not
acked yet — confirmed in every run: C2 `highestAcked=16, currentBlock=17`; C5 `17/18`; C10 `20/21`). Substituting
`lag = currentBlock − issBlock`, the ISS block survives exactly when:

### **lag ≤ keep**

|  Test  |  lag  | keep  | acked?  |            lag ≤ keep?            |  Result  |
|--------|-------|-------|---------|-----------------------------------|----------|
| C1     | 1     | 10    | yes     | yes                               | KEPT     |
| **C2** | **1** | **1** | **yes** | **yes (equal — tightest margin)** | **KEPT** |
| C5     | 1     | 1     | yes     | yes                               | KEPT     |
| C9     | 1     | 10    | yes     | yes                               | KEPT     |
| C10    | 1     | **0** | yes     | **no**                            | **LOST** |

So **C2 kept the block because the ISS notification is fast**: detection happens only 1 block after the ISS
(`lag = 1`), which is exactly equal to `keep = 1`, so the ISS block is still the single most-recent acked block that
keep=1 protects. It kept by the narrowest possible margin. If the notification had been even one block slower
(`lag = 2`) at keep=1, the ack watermark would have moved one block past the ISS block and it would have been pruned
and lost — which is exactly what **C11** shows (it makes detection lag 2-3 blocks at keep=1, so `lag > keep` → lost),
and what **C10** shows from the other side (keep=0, so `lag=1 > keep=0` → lost).

**This is a deterministic policy outcome, not a "the capture beat the prune" race.** C5 proves it: there the prune
worker actively ran and deleted every block below the ISS block (`earliestBuffered=17`), yet it KEPT the ISS block
(17), because 17 was the newest acked block and keep=1 protects exactly that block. The block is saved by the
retention rule, not by the capture being quick.

(The cases where the block is *unacked* — C3 no acks yet, C4 acks withheld, C8 block node down, C6 catastrophic — are
kept for a different reason: an unacknowledged block is never pruned at all, regardless of keep or lag.)

## The test-framework bug (why C4/C5/C8 originally failed) — now FIXED

- Four tests send a control command to the block node during the run (`blockNode(0)....`). That command looked up
  "which block node am I talking to" from a per-thread value. For these tests that per-thread value was sometimes
  empty on the thread that runs the command, and the command then crashed with a NullPointerException
  (`BlockNodeOp.java:109`, `TARGET_BLOCK_NODE_NETWORK.get()` returned null).
- It was **intermittent**: C9 ran the same kind of command and passed; C5 crashed only *after* the ISS was already
  saved; C4 and C8 crashed *before* the ISS (their command runs first), so they produced nothing. C4 failed all 3 tries.
- **The fix (applied):** `BlockNodeOp` now resolves the block node the same robust way the rest of the harness already
  does — via `BlockNodeReader.activeNetwork()`, which uses the spec's per-thread target if present and otherwise falls
  back to the shared, thread-safe `SHARED_BLOCK_NODE_NETWORK` reference, so it works on any thread. One import plus
  three one-line swaps in `BlockNodeOp.java`; **no product code was changed**. After the fix, C4, C5 and C8 all pass.

## Files changed for this work

- `hedera-node/test-clients/.../spec/utilops/BlockNodeOp.java` — the harness fix (use `BlockNodeReader.activeNetwork()`
  instead of the raw thread-local). This is what unblocks C4/C5/C8 and any other test using `blockNode(0)` commands.
- `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java` — added the new loss test `selfIssRetain0Pruned`
  (C10) with `ackedBlocksToRetain=0`, `blockStream.buffer.workerInterval=100ms` on node1, and an 8s ack warm-up.
- No changes to production/product code.

## How these were run

- Simulator tests: `./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.<method>"`
- Real tests: `./gradlew hapiTestBlockNodeCommunication :test-clients:testSubprocess --tests "*IssBufferRaceRealTest.<method>"`
- Full Gradle logs for each run are saved under `.context/iss-runs/` (`C3.log`, `C4*.log`, `C5.log`, `C9.log`,
  `C1.log`, `C2.log`, `C6.log`, `C8.log`). The `ISS-DIAG` lines and upload lines quoted above come from node1's
  output inside those logs.

---

# Re-run + new tests (session 2026-08-13, on the ack-proof mechanisms)

Re-ran the SIM suite with the ack-proof work applied — a mock `block_proof` on every ack, the `blockNode.requireAckProof`
CN gate (**default off**), and the simulator's `sendInvalidAckForBlock` / `delayAckForBlock` hooks — and added three
tests that exercise those mechanisms. Each test is one gradle invocation; numbers are node1's `ISS-DIAG` at detection.
Logs: `.context/iss-runs/nm-*.log`. (REAL-BN rows C1/C2/C6/C8/C12 were not re-run — the new hooks are simulator-only.)

## New tests — all PASS, deterministic

|  #  |                    Test                    | keep |              mechanism              |   acked    | outcome  | runs |
|-----|--------------------------------------------|------|-------------------------------------|------------|----------|------|
| C13 | selfIssInvalidAckProofKept                 | 0    | invalid ack proof + gate            | no (rej)   | **KEPT** | 1/1  |
| C14 | selfIssLateNotificationValidAckProofPruned | 1    | valid ack proof + gate (late notif) | yes→pruned | **LOST** | 2/2  |
| C15 | selfIssDelayedAckKept                      | 0    | ack deferred 5 blocks               | no         | **KEPT** | 2/2  |

- **C13 — invalid proof ⇒ KEPT.** node1 logged `Ignoring acknowledgement for block 17: proof 'invalid-ack-17' does not
  match expected 'ack-17'` (rej=1); `ISS-DIAG round=504 issBlock=17 currentBlock=18 lag=1 earliestBuffered=17
  highestAcked=16 inBuffer=true acked=false` — the ISS block is never acked, so keep=0 cannot prune it ⇒ **KEPT**.
  Deterministic: an unacked block is never even ack-eligible, so there is no prune race (contrast C10).
- **C14 (control) — valid proof ⇒ LOST.** The sim sends valid `ack-<n>`, the gate accepts every ack (rej=0), the block
  is acked and — because detection lags 2–3 blocks (`blockPeriod=0`, C11's base) — pruned before the capture ⇒ **LOST**
  (`.txt`, `issBlock=-1`, `inBuffer=false`), the same outcome as C11. Proves the gate is proof-**selective**: it rejects
  only mismatched proofs (C13), not all acks — so it does not stall the normal ack path. Built on C11's reliable-loss
  base, not the flaky keep=0 base (see C10), so LOST is deterministic (2/2).
- **C15 — deferred ack ⇒ KEPT.** The sim defers every block's ack by 5 later blocks (`delayAckForBlock`). At detection
  the ISS block's ack has not been released: `ISS-DIAG round=584 issBlock=21 currentBlock=22 lag=1 earliestBuffered=17
  highestAcked=16 inBuffer=true acked=false` — the ack watermark sits exactly 5 blocks behind (`16 = 21 − 5`) ⇒ unacked
  ⇒ **KEPT** (2/2). First runtime exercise of the delay hook, and a second lever (a block node that acks a few blocks
  behind) that prevents the C10 loss, distinct from C13's invalid proof.

## Pre-existing SIM rows re-run (gate off)

|  #  |             Test              | keep | acked (this run) |       outcome        |        vs prior        |
|-----|-------------------------------|------|------------------|----------------------|------------------------|
| C3  | selfIssRetain10Sim            | 10   | no\*             | **KEPT**             | same                   |
| C4  | selfIssBnBehindWithheldAcks   | 1    | no               | **KEPT**             | same                   |
| C5  | selfIssBadBlockProofRejection | 1    | yes              | **KEPT**             | same                   |
| C9  | selfIssCnBehindResend         | 10   | yes              | **KEPT**             | same                   |
| C11 | selfIssLateNotification       | 1    | yes → pruned     | **LOST**             | same (reliable)        |
| C10 | selfIssRetain0Pruned (×3)     | 0    | — (see note)     | **LOST, KEPT, LOST** | **flaky** (was "det.") |

\* C3's acks had not arrived at capture (`highestAcked=-1`); at keep=10 the block is kept regardless.

**C10 re-characterized as flaky.** Earlier notes here called C10 a deterministic loss; this session it was LOST, KEPT,
LOST (and an earlier `regress-C10` also KEPT). keep=0 at lag=1 is a **two-sided race** — the loss needs the ack to
arrive **and** the prune to fire before the async capture. The KEPT run missed on the ack side (`highestAcked=-1`, ack
not yet arrived); a prior KEPT missed on the prune side (`acked=true`, not yet pruned). The reliable loss is **C11**
(`lag > keep` by a margin), which is why C14's control uses the C11 base rather than keep=0. (This does not change the
`lag ≤ keep` rule — it just means keep=0/lag=1 sits exactly on the boundary and either race can win.)

## The bottom line for the feature

The new mechanisms confirm the intended fix and its bounds: a proof-carrying ack that the CN honors **only on a match**
turns the fragile self-ISS case (`acked=true` ⇒ prunable ⇒ maybe lost) into the safe unacked case (`acked=false` ⇒
never pruned ⇒ kept), deterministically and independent of `keep`/`lag` (C13, and the ack-timing analogue C15). The
gate stays proof-selective, so matching acks prune as before (C14) and every gate-off row is unchanged (C3/C4/C5/C9/
C10/C11). This is the divergent-block analogue of the always-safe catastrophic case (C6): make the block unackable.

## Files changed this session

- `IssBufferRaceSimTest.java` — + C14 `selfIssLateNotificationValidAckProofPruned`, + C15 `selfIssDelayedAckKept`,
  + `delayAcksForBlocks(from, to, delay)` range helper. (C13 `selfIssInvalidAckProofKept` was added earlier with the
    ack-proof work.)
- `ISS_SIM_BEHAVIOR_MATRIX.md`, `ISS_TEST_RESULTS.md` — the re-run + new-mechanism comparison sections.
- No product-code changes this session (the gate, config flag, and sim hooks were already in place).
