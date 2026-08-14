<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS ack-proof (mock proof-matching) — context & handoff

Continuation context for the "acknowledgement carries a block proof; the consensus node (CN) only marks a block
acknowledged if the proof matches" work, added on branch `iss-block-tests` on top of the ISS block-buffer
investigation (see `ISS_BUFFER_INVESTIGATION.md`, `ISS_TEST_RESULTS.md`, `ISS_SIM_BEHAVIOR_MATRIX.md`).

## Goal

On a self-ISS the diverging node currently marks its own **divergent** block acknowledged purely by block **number**
(the block node acks by number), which lets that block be pruned and lost from the in-memory buffer. This work makes
the acknowledgement carry the block's **real proof** and makes the CN honor an ack only if that proof matches the CN's
**own** buffered block proof — so a divergent block's honest ack no longer counts, the block stays unacked → never
pruned → kept.

The ack now carries the block's **real serialized `BlockProof`** (echoed by the simulated block node from the block it
received), and the CN extracts its own buffered block's proof and compares. It is still short of production in two
senses: the comparison is **byte-equality** rather than TSS-signature verification, and the simulator (not real
cryptography) supplies the proofs. The invalid-ack op corrupts the proof to force a mismatch deterministically.

## Status

- **Implemented + verified working (real-proof comparison).** The `bytes` proof field, the CN own-block proof-match
  gate, the simulator echoing the real proof + corrupting it, and the delay-by-blocks op are all in. All three
  mechanisms pass in the SIM: **C13** (`selfIssInvalidAckProofKept`, corrupted proof → kept — node1 accepts valid
  proofs then rejects the corrupted ISS-block proof, `highestAcked=20` `rej=1`, 2/2), **C14**
  (`selfIssRealAckProofGateKept`, real proof + **no injection** → node1 rejects the *honest* ack for its divergent
  block → kept; natural detection, `rej=2`), and **C15** (`selfIssDelayedAckKept`, deferred ack → kept, 2/2). All KEPT
  deterministically. (Caveat: whether the gate is *exercised* on a given run depends on acks arriving before detection
  — the same ack-arrival race that makes C10 flaky; the KEEP outcome is robust regardless.)
- **Backward-compatible:** the CN gate is behind `blockNode.requireAckProof` (default **false**, `@NodeProperty` —
  opt-in **per node**, not global); with it off the CN accepts acks by number exactly as before. Verified: C3/C4/C5/C9/
  C10/C11 re-run with the flag off, outcomes unchanged.
- **Nothing committed. `spotlessApply` not run.**

## Design & where it lives

1. **Proof field on the ack** — `bytes block_proof = 2` on `BlockAcknowledgement` (nested in
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
   `!requireAckProof || ackProofMatchesOwnBlock(n, acknowledgement.blockProof())`, where `ownBlockProof(n)` pulls the
   block's proof item from the buffer (`getBlockState(n)` → scan for `BufferedItem.isProof()`) and serializes its
   `BlockProof`; the gate compares those bytes to the ack's. Otherwise logs `Ignoring acknowledgement ... ack proof
   does not match this node's own block proof` and does nothing.
4. **Simulator** — `hedera-node/test-clients/.../junit/hedera/simulator/SimulatedBlockNodeServer.java`:
   - `buildAndSendBlockAcknowledgement` sets `block_proof` to the block's real serialized proof (captured per block in
     `blockProofByNumber` from the owning stream when the `BlockProof` item arrives), or deterministic garbage
     (`"invalid-proof-<n>"`) if `n` is flagged invalid.
   - State: `invalidAckBlocks` (Set), `ackDelayBlocks` (Map<block,delayBlocks>), `releasedDelayedAcks` (Set).
   - Setters: `sendInvalidAckForBlock(n)`, `delayAckForBlock(n, delayBlocks)`.
   - EndOfBlock handler: releases any delayed ack whose `n + delayBlocks <= currentBlock` (once), and defers block `n`'s
     own ack if `n` is in `ackDelayBlocks`.
5. **Test ops / verbs** — `BlockNodeController` (delegates), `BlockNodeOp` (enum `SEND_INVALID_ACK_FOR_BLOCK`,
   `DELAY_ACK_FOR_BLOCK` — the delay count is carried in the reused `rangeEnd` field, read as `(int) rangeEnd`;
   factories + `SendInvalidAckBuilder` / `DelayAckBuilder`), `BlockNodeVerbs`:
   `blockNode(i).sendInvalidAckForBlock(n)` and `blockNode(i).delayAckForBlock(n, delayBlocks)`.
6. **Tests** — `hedera-node/test-clients/.../suites/misc/IssBufferRaceSimTest.java`:
   - `selfIssInvalidAckProofKept` (**C13**, corrupted proof → kept; warm-up first so node1 accepts valid proofs, then
     rejects the corrupted ISS-block proof) + `invalidateAcksForBlocks(from, to)` helper.
   - `selfIssRealAckProofGateKept` (**C14**, real proof + no injection → node1 rejects the honest ack for its divergent
     block → kept; natural detection).
   - `selfIssDelayedAckKept` (**C15**, `delayAckForBlock` op: deferred ack → kept) + `delayAcksForBlocks(from, to, delay)` helper.

## How to run

Each `@HapiBlockNode` test needs its **own** Gradle invocation (only one shared network per launcher session — a
combined `--tests "*IssBufferRaceSimTest"` init-errors all but the first).

```bash
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssInvalidAckProofKept"
```

Run logs are saved under `.context/iss-runs/` (gitignored). node1 is the ISS node; grep its `ISS-DIAG` line and the
uploaded object key (`.iss.gz` = kept / `iss-round-*.txt` = lost).

## Verified results (real-proof re-run 2026-08-14; full tables in `ISS_TEST_RESULTS.md` / `ISS_SIM_BEHAVIOR_MATRIX.md`)

- **C13** ✅ (2/2) — keep=0, `requireAckProof=true` on node1, warm-up then corrupt the ISS-block range. node1 accepts
  the valid real proofs for its early blocks (`highestAcked=20`) then rejects the corrupted proof on the ISS block
  (`rej=1`): `ISS-DIAG issBlock=21 currentBlock=22 highestAcked=20 inBuffer=true acked=false` → ISS block never acked →
  **kept** (`…0021.iss.gz`). Deterministic — the garbage never matches, regardless of who won the block's header race.
- **C14** ✅ (2/2) — keep=10, `requireAckProof=true`, **no injection**: the block node echoes each block's real proof.
  node1 accepts the matching proofs for its non-divergent blocks and rejects the *honest* proof echoed for its own
  divergent block (`rej=2`, `acked=false`) → **kept** — natural detection, no test injection. keep=10 makes KEEP
  deterministic; whether a given run exercises the gate depends on acks arriving before detection (ack-arrival race).
- **C15** ✅ (delay op, 2/2) — keep=0, gate off, sim defers every ack by 5 blocks (`delayAckForBlock`). At detection the
  ISS block's ack is unreleased: `ISS-DIAG issBlock=21 highestAcked=16 inBuffer=true acked=false` (`16 = 21 − 5`) →
  unacked → **kept**. First runtime coverage of the delay op; a second, ack-timing lever that prevents the C10 loss.
- **C3/C4/C5/C9** ✅ (flag off) — no regression, all **kept**.
- **C11** ✅ — reliable **loss** re-confirmed. **C10** ×3 → LOST/KEPT/LOST — **flaky** (keep=0/lag=1 is a two-sided
  ack-arrival + prune race), confirming the gotcha below.

## Gotchas / decisions

- The CN gate **must** stay flag-gated. With it always-on, a real block node (which does not send the test-only
  `block_proof` field) and every other block-node test would present an empty proof → the CN's own (non-empty) proof
  never matches → rejected → buffer never prunes → backpressure stalls streaming.
- A log-based assert on the rejection WARN (`"ack proof does not match this node's own block proof"`) is unreliable via
  `assertHgcaaLogContainsText(byNodeId(ISS_NODE_ID), ...)` — a prior attempt reported node "node2" for node1's log (a
  node-selector / log-file mismatch). The tests rely on the kept/lost artifact outcome instead; the `rej` count in the
  run logs confirms the gate fired.
- C10's loss is **flaky (~50/50)** on a *pre-existing* ack-arrival race (`highestAcked=-1` → nothing acked → kept),
  independent of this work. So the matrix's `C10 → LOST` holds only when acks arrive before detection.

## Next steps / TODO

- [x] Add a runtime test for `delayAckForBlock` — done: **C15** `selfIssDelayedAckKept` (2/2 kept).
- [x] Broader regression: re-ran C3/C4/C5/C9/C10/C11 with these changes; gate-off rows unchanged (C10 flaky as noted).
- [x] Upgrade the string mock to a **real proof comparison** — done: the ack carries the block's real serialized proof
  and the CN compares its own buffered block's proof (`ownBlockProof`). C14 `selfIssRealAckProofGateKept` shows natural
  detection (2/2 kept, no injection); C13 uses a corrupted proof to force the mismatch deterministically.
- [ ] Before any real PR: replace the `extractProto` `doLast` patch with a config-cache-clean mechanism (a proper task
  or upstreaming `block_proof`); run `spotlessApply`; check whether a config-reference/coverage test needs the new
  `blockNode.requireAckProof` property documented.
- [ ] Finish the real (non-mock) design: the comparison is currently **byte-equality** of the serialized proof;
  production should have the real block node carry the proof and the CN **verify the TSS signature against its own
  block's root hash** (not byte-equality), and handle the no-proof case (today → reject → stall; hence flag-gated).
- [ ] Optionally fix C10's ack-arrival flakiness (separate from this work).

## Related docs

- `ISS_BUFFER_INVESTIGATION.md` — the overall investigation + the `lag ≤ keep` rule.
- `ISS_TEST_RESULTS.md` — full C1–C12 run results.
- `ISS_SIM_BEHAVIOR_MATRIX.md` — the SIM self-ISS baseline matrix (to re-run after this change to confirm rows flip to
  `acked=false → KEPT`).
