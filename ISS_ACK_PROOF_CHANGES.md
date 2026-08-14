<!-- SPDX-License-Identifier: Apache-2.0 -->

# ISS ack-proof — changed files & references (presentation aid)

A one-stop index of every file that makes up the **proof-matching acknowledgement** change, with clickable
`path:line` anchors, a talking point per file, the verified results, and external references. Branch:
`iss-block-tests`. Companion docs: `ISS_ACK_PROOF_SUMMARY.md` (the plain-language "why"),
`ISS_ACK_PROOF_CONTEXT.md` (detailed handoff), `ISS_TEST_RESULTS.md` / `ISS_SIM_BEHAVIOR_MATRIX.md` (results).

## The change in one sentence

On a self-ISS the diverging node marks its **own divergent** block acknowledged purely by block number, so the buffer
prunes it and the ISS-round block is lost; this change makes the acknowledgement carry the block's **real proof** and
has the consensus node honor an ack **only if that proof matches its own buffered block** — so the divergent block is
never acked, never pruned, and is kept for the debug upload. Behind `blockNode.requireAckProof` (default **off**).

## Suggested walkthrough order

1. The problem (buffer prunes the acked divergent block) → `ISS_ACK_PROOF_SUMMARY.md`
2. Product change: the CN gate + the flag
3. How it's tested: the proto field, the simulator, the ops/verbs
4. The three SIM tests + results
5. What's still a mock / next steps

---

## 1. Product code (the actual behavior change)

|                                                        File                                                        |                                                  Anchors                                                  |                                                                                                                                                                                      What / why                                                                                                                                                                                      |
|--------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/impl/streaming/BlockNodeStreamingConnection.java` | `:308` `handleAcknowledgement`, `:315` the gate, `:364` `ackProofMatchesOwnBlock`, `:375` `ownBlockProof` | The core gate: mark a block acked only if `!requireAckProof` **or** the ack's proof bytes equal this node's own buffered block proof (`ownBlockProof` pulls the block's proof item from the buffer and serializes it). Fail-closed if the proof can't be extracted.                                                                                                                  |
| `hedera-node/hedera-config/src/main/java/com/hedera/node/config/data/BlockNodeConnectionConfig.java`               | `:76` `requireAckProof` property, `:44` its doc                                                           | New `@NodeProperty boolean requireAckProof` (default `false`). Opt-in **per node**, not global — so production and all other block-node tests are unchanged.                                                                                                                                                                                                                         |
| `hapi/hapi/build.gradle.kts`                                                                                       | `:59`–`:77` `extractProto` patch (`:73` the field)                                                        | Adds a **test-only** `bytes block_proof = 2` to the external `BlockAcknowledgement` message by patching the extracted `.proto` before PBJ compiles it (the message is in the `org.hiero.block-node:protobuf-sources` jar, not editable in-repo). Marked `notCompatibleWithConfigurationCache` → builds succeed but the Gradle config cache is degraded (BUILD SUCCESSFUL + warning). |

## 2. Test harness (simulator + operations)

|                                                         File                                                          |                                                                                 Anchors                                                                                  |                                                                                                               What / why                                                                                                               |
|-----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/simulator/SimulatedBlockNodeServer.java` | `:109` `blockProofByNumber`, `:589` capture, `:1203` `buildAndSendBlockAcknowledgement` (`:1213` real proof), `:208` `sendInvalidAckForBlock`, `:213` `delayAckForBlock` | Captures each block's **real serialized `BlockProof`** (from the header-race-winning stream) and echoes it in that block's ack; sends deterministic garbage when a block is flagged invalid. Adds the invalid-ack and delay-ack hooks. |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/simulator/BlockNodeController.java`      | `:564` `sendInvalidAckForBlock`, `:575` `delayAckForBlock`                                                                                                               | Thin delegates from the controller to the simulator.                                                                                                                                                                                   |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/BlockNodeOp.java`                        | `:354`/`:356` enum values, `:155`/`:159` dispatch, `:689` `SendInvalidAckBuilder`, `:713` `DelayAckBuilder`                                                              | The `SEND_INVALID_ACK_FOR_BLOCK` / `DELAY_ACK_FOR_BLOCK` ops (delay count reuses the `rangeEnd` field).                                                                                                                                |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/spec/utilops/BlockNodeVerbs.java`                     | `:91` `sendInvalidAckForBlock`, `:102` `delayAckForBlock`                                                                                                                | The spec-facing verbs: `blockNode(i).sendInvalidAckForBlock(n)` / `blockNode(i).delayAckForBlock(n, delayBlocks)`.                                                                                                                     |

## 3. Tests

|                                                  File                                                  |                                                              Anchors                                                              |                            What / why                             |
|--------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/misc/IssBufferRaceSimTest.java` | `:709` **C13** `selfIssInvalidAckProofKept`, `:825` **C14** `selfIssRealAckProofGateKept`, `:925` **C15** `selfIssDelayedAckKept` | The three SIM tests that exercise the mechanisms (results below). |

## 4. Docs

|             File             |                               What                                |
|------------------------------|-------------------------------------------------------------------|
| `ISS_ACK_PROOF_SUMMARY.md`   | Plain-language before/after — the "why" for the presentation.     |
| `ISS_ACK_PROOF_CONTEXT.md`   | Detailed handoff: design, where each piece lives, gotchas, TODOs. |
| `ISS_TEST_RESULTS.md`        | Full run results incl. the real-proof re-run tables.              |
| `ISS_SIM_BEHAVIOR_MATRIX.md` | The self-ISS SIM matrix + the post-ack-proof comparison rows.     |

## 5. Supporting infra (from the broader ISS investigation — context, not part of this change)

|                                                    File                                                    |                  Anchors                   |                                                 What                                                 |
|------------------------------------------------------------------------------------------------------------|--------------------------------------------|------------------------------------------------------------------------------------------------------|
| `hedera-node/hedera-app/src/main/java/com/hedera/node/app/blocks/cloud/uploader/IssBufferBlockReader.java` | `:71` the `ISS-DIAG` line                  | The greppable diagnostic every test reads (`issBlock/currentBlock/lag/highestAcked/inBuffer/acked`). |
| `hedera-node/test-clients/src/main/java/com/hedera/services/bdd/suites/misc/IssBufferTestSupport.java`     | `:33` `startS3Mock`, `:69` `configureNode` | In-JVM S3 mock + per-node reconnect config used by the SIM tests.                                    |

---

## Verified results (real-proof SIM runs, 2026-08-14)

|                 Test                  |                     Setup                      |                 ISS-DIAG (representative)                 |                                           Outcome                                            |
|---------------------------------------|------------------------------------------------|-----------------------------------------------------------|----------------------------------------------------------------------------------------------|
| **C13** `selfIssInvalidAckProofKept`  | keep=0, warm-up then **corrupt** the ISS range | `issBlock=21 highestAcked=20 acked=false` (`rej=1`)       | **KEPT** 2/2 — accepts valid proofs, rejects the corrupted ISS-block proof                   |
| **C14** `selfIssRealAckProofGateKept` | keep=10, **no injection** — real proofs        | `issBlock=21 highestAcked=20 acked=false` (`rej=2`)       | **KEPT** — **natural detection**: node1 rejects the *honest* ack for its own divergent block |
| **C15** `selfIssDelayedAckKept`       | keep=0, ack delayed 5 blocks, gate off         | `issBlock=21 highestAcked=16 acked=false` (`16 = 21 − 5`) | **KEPT** 2/2 — block still unacked at capture                                                |

Headline for the demo: **C14 needs no test injection** — the block node acks the divergent block with the honest
block's proof, node1's own proof doesn't match, so it rejects the ack and keeps the block. That is the real design's
behavior, shown on real proof bytes.

## How to run (each `@HapiBlockNode` test = its own gradle invocation)

```bash
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssInvalidAckProofKept"
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssRealAckProofGateKept"
./gradlew hapiTestIssGrpc :test-clients:testSubprocess --tests "*IssBufferRaceSimTest.selfIssDelayedAckKept"
```

## What's still a mock (say this up front)

- Flag **off by default**; a real block node sends no `block_proof`, so gate-on against a real BN would reject every
  ack and stall — hence flag-gated.
- The check is **byte-equality** of the serialized proof; production would **verify the TSS signature against the
  node's own root hash**, not compare bytes.
- The simulator keeps only the header-race winner's copy per block, so C14's natural detection relies on an honest node
  winning the divergent block's race (~3-in-4 at 4 nodes); C13's corrupt-proof op forces the mismatch deterministically.

## References

- **Branch:** `iss-block-tests`.
- **Commits (most recent first):** `8d7f3b9284` "ISS ack-proof: honor block acks only on a matching block proof
  (flag-gated, test-only)", `230c4c9f22` "PoC", `d4187cf3ce` "ISS buffer investigation: 4-CN/1-BN matrix tests +
  ISS-DIAG instrumentation".
- **Feature PR this supports:** #25943 (upload the ISS-round block to a bucket on a fatal ISS; `17267-iss-block-to-gcp`).
- **Related/reconciled PR:** #26701 (block-buffer rewrite; renamed `minAckedBlocksToBuffer` → `ackedBlocksToRetain`,
  unconditional acked-block retention).
- **Config flag:** `blockNode.requireAckProof` (default `false`).
- **Full diff vs main:** `git diff origin/main...HEAD -- <file>` for any file above.
