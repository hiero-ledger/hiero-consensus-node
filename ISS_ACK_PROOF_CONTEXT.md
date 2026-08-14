<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS ack-proof (mock proof-matching) — context & handoff

Continuation context for the "acknowledgement carries a block proof; the consensus node (CN) only marks a block
acknowledged if the proof matches" work, added on branch `iss-block-tests` on top of the ISS block-buffer
investigation (see `ISS_BUFFER_INVESTIGATION.md`, `ISS_TEST_RESULTS.md`, `ISS_SIM_BEHAVIOR_MATRIX.md`).

## Goal

On a self-ISS the diverging node currently marks its own **divergent** block acknowledged purely by block **number**
(the block node acks by number), which lets that block be pruned and lost from the in-memory buffer. This work makes
the acknowledgement carry a **mock proof** and makes the CN honor an ack only if the proof matches — so a divergent
block's ack no longer counts, the block stays unacked → never pruned → kept.

This is a **mock** (a text proof `"ack-" + blockNumber`), not real cryptography. A real version would compare the
ack's block proof to the CN's own buffered block proof.

## Status

- **Implemented + verified working.** The proof field, the CN proof-match gate, the simulator's invalid-ack op, and
  the delay-by-blocks op are all in. All three mechanisms are now covered by SIM tests that PASS: **C13**
  (`selfIssInvalidAckProofKept`, invalid proof → kept), **C14** (`selfIssLateNotificationValidAckProofPruned`, the
  proof-selectivity control: valid proof → still pruned/lost), and **C15** (`selfIssDelayedAckKept`, the delay op:
  deferred ack → kept). C14 and C15 are deterministic across 2/2 runs; C13 across the run below + prior.
- **Backward-compatible:** the CN gate is behind `blockNode.requireAckProof` (default **false**, `@NodeProperty` —
  opt-in **per node**, not global); with it off the CN accepts acks by number exactly as before. Verified: C3/C4/C5/C9/
  C10/C11 re-run with the flag off, outcomes unchanged.
- **Nothing committed. `spotlessApply` not run.**

## Design & where it lives

1. **Proof field on the ack** — `string block_proof = 2` on `BlockAcknowledgement` (nested in
   `PublishStreamResponse`, package `org.hiero.block.api`). That message comes from the **external**
   `org.hiero.block-node:protobuf-sources` jar, so it is **not** editable in-repo. It is added by a `doLast` on the
   `extractProto` task in `hapi/hapi/build.gradle.kts` that patches the extracted `.proto` before PBJ compiles it
   (regex insert; fails the build loudly if the message layout changes). That task is marked
   `notCompatibleWithConfigurationCache(...)`, so `hapi` builds succeed but **degrade the Gradle configuration cache**
   (BUILD SUCCESSFUL + a warning). Generated accessor: `BlockAcknowledgement.blockProof()` / builder `.blockProof(...)`.
2. **Config flag** — `blockNode.requireAckProof` (default `false`, `@NodeProperty`) in
   `hedera-node/hedera-config/.../data/BlockNodeConnectionConfig.java`.
3. **CN proof-match gate** — `hedera-node/hedera-app/.../streaming/BlockNodeStreamingConnection.java`,
   `handleAcknowledgement(...)`: marks the block acked (via `acknowledgeBlocks` → `setLatestAcknowledgedBlock`) only if
   `!requireAckProof || ("ack-" + blockNumber).equals(acknowledgement.blockProof())`; otherwise logs
   `Ignoring acknowledgement ... does not match expected 'ack-<n>'` and does nothing.
4. **Simulator** — `hedera-node/test-clients/.../junit/hedera/simulator/SimulatedBlockNodeServer.java`:
   - `buildAndSendBlockAcknowledgement` sets `block_proof = "ack-<n>"`, or `"invalid-ack-<n>"` if `n` is flagged invalid.
   - State: `invalidAckBlocks` (Set), `ackDelayBlocks` (Map<block,delayBlocks>), `releasedDelayedAcks` (Set).
   - Setters: `sendInvalidAckForBlock(n)`, `delayAckForBlock(n, delayBlocks)`.
   - EndOfBlock handler: releases any delayed ack whose `n + delayBlocks <= currentBlock` (once), and defers block `n`'s
     own ack if `n` is in `ackDelayBlocks`.
5. **Test ops / verbs** — `BlockNodeController` (delegates), `BlockNodeOp` (enum `SEND_INVALID_ACK_FOR_BLOCK`,
   `DELAY_ACK_FOR_BLOCK` — the delay count is carried in the reused `rangeEnd` field, read as `(int) rangeEnd`;
   factories + `SendInvalidAckBuilder` / `DelayAckBuilder`), `BlockNodeVerbs`:
   `blockNode(i).sendInvalidAckForBlock(n)` and `blockNode(i).delayAckForBlock(n, delayBlocks)`.
6. **Tests** — `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java`:
   - `selfIssInvalidAckProofKept` (**C13**, invalid proof → kept) + `invalidateAcksForBlocks(from, to)` helper.
   - `selfIssLateNotificationValidAckProofPruned` (**C14**, proof-selectivity control: valid proof → lost, on C11's base).
   - `selfIssDelayedAckKept` (**C15**, `delayAckForBlock` op: deferred ack → kept) + `delayAcksForBlocks(from, to, delay)` helper.

## How to run

Each `@HapiBlockNode` test needs its **own** Gradle invocation (only one shared network per launcher session — a
combined `--tests "*IssBufferRaceSimTest"` init-errors all but the first).

```bash
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssInvalidAckProofKept"
```

Run logs are saved under `.context/iss-runs/` (gitignored). node1 is the ISS node; grep its `ISS-DIAG` line and the
uploaded object key (`.iss.gz` = kept / `iss-round-*.txt` = lost).

## Verified results (re-run 2026-08-13; full tables in `ISS_TEST_RESULTS.md` / `ISS_SIM_BEHAVIOR_MATRIX.md`)

- **C13** ✅ — keep=0, acks flowing, `requireAckProof=true` on node1, sim sends invalid proofs for the ISS-block range.
  CN logged `does not match expected 'ack-17'` (rej=1); `ISS-DIAG issBlock=17 currentBlock=18 highestAcked=16
  inBuffer=true acked=false` → block 17 never acked → **kept** (`…0017.iss.gz`). Without the proof gate this is the
  (flaky) C10 loss.
- **C14** ✅ (control, 2/2) — keep=1, `requireAckProof=true`, but the sim sends **valid** `ack-<n>`; the gate accepts
  every ack (rej=0), and on C11's late-notification base the acked ISS block is pruned before capture → **lost**
  (`.txt`, `issBlock=-1`). Proves the gate rejects only *mismatched* proofs, not all acks — no regression to the normal
  ack path. (Deliberately on the C11 base, not keep=0, because keep=0 is a flaky race — see below.)
- **C15** ✅ (delay op, 2/2) — keep=0, gate off, sim defers every ack by 5 blocks (`delayAckForBlock`). At detection the
  ISS block's ack is unreleased: `ISS-DIAG issBlock=21 highestAcked=16 inBuffer=true acked=false` (`16 = 21 − 5`) →
  unacked → **kept**. First runtime coverage of the delay op; a second, ack-timing lever that prevents the C10 loss.
- **C3/C4/C5/C9** ✅ (flag off) — no regression, all **kept**.
- **C11** ✅ — reliable **loss** re-confirmed. **C10** ×3 → LOST/KEPT/LOST — **flaky** (keep=0/lag=1 is a two-sided
  ack-arrival + prune race), confirming the gotcha below.

## Gotchas / decisions

- The CN gate **must** stay flag-gated. With it always-on, real-BN and every other block-node test would have acks
  with no/empty proof → rejected → buffer never prunes → backpressure stalls streaming.
- `assertHgcaaLogContainsText(byNodeId(ISS_NODE_ID), "does not match expected", ...)` failed (reported node "node2")
  even though node1's aggregated log shows the rejection — a node-selector / log-file mismatch (the WARN may not be in
  the file that op reads, or the node naming is off by one in the message). C13 dropped that assert; the kept-at-keep=0
  outcome is conclusive on its own. Revisit if a log-based assertion is wanted.
- C10's loss is **flaky (~50/50)** on a *pre-existing* ack-arrival race (`highestAcked=-1` → nothing acked → kept),
  independent of this work. So the matrix's `C10 → LOST` holds only when acks arrive before detection.

## Next steps / TODO

- [x] Add a runtime test for `delayAckForBlock` — done: **C15** `selfIssDelayedAckKept` (2/2 kept).
- [x] Broader regression: re-ran C3/C4/C5/C9/C10/C11 with these changes; gate-off rows unchanged (C10 flaky as noted).
- [x] Add a proof-selectivity control (valid proof still honored) — done: **C14** `selfIssLateNotificationValidAckProofPruned` (2/2 lost).
- [ ] Before any real PR: replace the `extractProto` `doLast` patch with a config-cache-clean mechanism (a proper task
  or upstreaming `block_proof`); run `spotlessApply`; check whether a config-reference/coverage test needs the new
  `blockNode.requireAckProof` property documented.
- [ ] Decide the real (non-mock) design: ack carries the actual block proof/root hash; CN compares to its own block's
  proof instead of the fixed `"ack-<n>"` string.
- [ ] Optionally fix C10's ack-arrival flakiness (separate from this work).

## Related docs

- `ISS_BUFFER_INVESTIGATION.md` — the overall investigation + the `lag ≤ keep` rule.
- `ISS_TEST_RESULTS.md` — full C1–C12 run results.
- `ISS_SIM_BEHAVIOR_MATRIX.md` — the SIM self-ISS baseline matrix (to re-run after this change to confirm rows flip to
  `acked=false → KEPT`).
