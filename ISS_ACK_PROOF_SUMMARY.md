<!-- SPDX-License-Identifier: Apache-2.0 -->

# Saving the ISS block: the problem, the change, and what we proved

A short, plain-language summary. For the full detail see `ISS_ACK_PROOF_CONTEXT.md`, `ISS_TEST_RESULTS.md`, and
`ISS_SIM_BEHAVIOR_MATRIX.md`.

## The point, in one paragraph

When a node hits a fatal **self-ISS** (its state diverges from the rest of the network), it tries to save that round's
block to a cloud bucket for debugging. In pure gRPC mode that block only lives in memory. The problem: the block node
acknowledges blocks **by number**, the diverging node treats that ack as "the block node has my block," and the memory
buffer then throws the block away — so by the time the save runs, the real block is often already gone and only a small
text pointer is saved. **Our change makes the acknowledgement carry a proof, and the diverging node accepts an ack only
if the proof matches its own block.** On a self-ISS the divergent block's proof won't match, so the block is never
marked acknowledged, never thrown away, and is reliably saved.

## Before — why the block is lost

- The block node acks by block **number** and broadcasts that to every node.
- The diverging node applies that ack to its **own divergent** block and marks it acknowledged.
- The buffer prunes acknowledged blocks once they age out of a small retention window (`ackedBlocksToRetain`). We
  confirmed in the code that the stock buffer closes and prunes the ISS block through exactly this path — **nothing
  protects it**.
- So the block survives only by a **timing race**: it is kept only if it is still unacknowledged, or if detection is
  fast enough that the block is still inside the retention window (`lag ≤ keep`). Make detection late, or the window
  small, and the block is lost.

Measured outcomes before the change (simulator tests):

|                                  Setup                                   |                                           Outcome                                            |
|--------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `keep = 0` (C10 `selfIssRetain0Pruned`)                                  | **LOST** — flaky: the block is acked then pruned before the save (occasionally kept by luck) |
| normal `keep = 1` but **late** detection (C11 `selfIssLateNotification`) | **LOST** — reliably: the block ages out of the window                                        |
| fast detection, `keep ≥ lag` (C3 / C5 / C9)                              | kept — but only because the timing race happened to win                                      |

## The change

1. **The ack carries the block's real proof.** A `block_proof` field is added to the acknowledgement, and the simulated
   block node fills it with the **actual serialized proof** of the block it received — or deterministic garbage when a
   test flags a block "invalid".
2. **The consensus node compares it to its own block.** A new flag `blockNode.requireAckProof` (default **off**): when
   on, the node extracts its own buffered block's proof and marks the block acknowledged only if the ack's proof
   matches; otherwise the ack is ignored (and logged).
3. **Simulator controls** so tests can drive it: corrupt a block's ack proof, or **delay** a block's ack.
4. **Three simulator tests** that exercise these.

## After — the block is kept

With the check on and the divergent block's proof not matching, the ack is rejected → the block stays unacknowledged →
the buffer never prunes it → it is saved as the real block file. All three verified in the simulator:

|               Test                |               What it does                |                                                    Result                                                     |
|-----------------------------------|-------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| C13 `selfIssInvalidAckProofKept`  | `keep=0`, **corrupted** proof (warm-up)   | **KEPT** — accepts valid proofs, then rejects the corrupted ISS-block proof (`highestAcked=20`, `rej=1`, 2/2) |
| C14 `selfIssRealAckProofGateKept` | `keep=10`, **no injection** — real proofs | **KEPT** — node1 rejects the *honest* ack for its own divergent block (natural detection, `rej=2`)            |
| C15 `selfIssDelayedAckKept`       | `keep=0`, ack **delayed** 5 blocks        | **KEPT** — block still unacked at save time (`highestAcked=issBlock−5`, 2/2)                                  |

**C14 shows the mechanism working with no test injection at all:** the block node acks node1's divergent block with the
*honest* block's proof, which does not match node1's own copy, so node1 rejects it and keeps the block — the real
design's behavior. node1 still accepts every matching proof for its non-divergent blocks (observed: `highestAcked`
advancing), so the gate is not a blanket reject.

## What this proves — and what is still a mock

**Proves:** the consensus node now extracts its **own** block's proof and compares it, byte-for-byte, to the proof
carried by the ack. On a self-ISS the block node's ack carries the *honest* block's proof, which does not match the
diverging node's own divergent block → the ack is rejected → the block stays unacknowledged → never pruned → saved.
This was verified in the simulator **with no test injection** (C14): node1 accepted the valid proofs for its normal
blocks (`highestAcked` advanced) and rejected the honest ack for its divergent block, keeping it. It is the same reason
a *catastrophic* ISS block is always safe today — it is never acknowledged — now extended to the self-ISS case.

**Still short of production:**

- The flag is **off by default**, so production behavior is unchanged today.
- The check is **byte-equality** of the serialized proof (echoed by the simulator). A real block node would carry the
  actual proof and the node would **verify the TSS signature against its own block's root hash**, not compare bytes —
  so this is now a genuine proof comparison, but not yet signature verification.
- The simulator keeps only one copy of each block (the first publisher wins the block; the others are told to skip), so
  it echoes that copy's proof to every node. Natural detection therefore relies on an honest node winning the divergent
  block's race (~3-in-4 at four nodes); when the diverging node itself wins, it accepts its own proof (the block is
  still kept by the retention window in the test). The **corrupt-proof** control (C13) forces the mismatch
  deterministically regardless of who won the race.
- The mock TSS signatures here are derived from block content — identical across nodes for the same block, different
  for a divergent one — which is exactly why valid proofs match and the divergent one does not. Real TSS gives the same
  property via the signed root hash.

## Where the changes live

- Consensus-node check: `BlockNodeStreamingConnection.handleAcknowledgement` (the gate) + `ownBlockProof(...)` (extracts
  this node's own block proof from the buffer to compare), and `BlockNodeConnectionConfig.requireAckProof` (the flag).
- Simulator: `SimulatedBlockNodeServer` (echoes the received block's real serialized proof, corrupts it for a flagged
  block, plus the delay-ack hook).
- Mock proof field: `hapi/hapi/build.gradle.kts` (patches the external ack message to add `bytes block_proof`).
- Tests + full results: `IssBufferRaceSimTest.java`, `ISS_TEST_RESULTS.md`, `ISS_SIM_BEHAVIOR_MATRIX.md`.
