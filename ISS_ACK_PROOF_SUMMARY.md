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

1. **The ack carries a proof.** A `block_proof` field is added to the acknowledgement (a test-only mock: the text
   `"ack-<blockNumber>"`).
2. **The consensus node checks it.** A new flag `blockNode.requireAckProof` (default **off**): when on, a block is
   marked acknowledged only if the ack's proof matches; otherwise the ack is ignored (and logged).
3. **Simulator controls** so tests can drive it: send an **invalid** ack proof for a block, or **delay** a block's ack.
4. **Three new simulator tests** that exercise these.

## After — the block is kept

With the check on and the divergent block's proof not matching, the ack is rejected → the block stays unacknowledged →
the buffer never prunes it → it is saved as the real block file.

|                       Test                       |             What it does             |                      Result                       |
|--------------------------------------------------|--------------------------------------|---------------------------------------------------|
| C13 `selfIssInvalidAckProofKept`                 | `keep = 0`, **invalid** proof        | **KEPT** — flips C10's loss                       |
| C15 `selfIssDelayedAckKept`                      | `keep = 0`, ack **delayed** 5 blocks | **KEPT** — block still unacked at save time (2/2) |
| C14 `selfIssLateNotificationValidAckProofPruned` | late detection, **valid** proof      | **LOST** — control (2/2)                          |

**C14 is the control that keeps the story honest:** with a *matching* proof the ack is still accepted and the block is
still pruned/lost — proving the check rejects only *wrong* proofs, not all acks. So the change does not break normal
acknowledgement.

## What this proves — and what is still a mock

**Proves:** if an ack's proof fails to match the diverging node's own block, that block is never acknowledged, never
pruned, and is reliably saved — **deterministically**, no matter the retention window or how late detection is. That is
the fix, and it is the same reason a *catastrophic* ISS block is always safe today: it is never acknowledged.

**Still a mock — not production-ready as-is:**

- The flag is **off by default**, so production behavior is unchanged today.
- The proof is a stand-in string (`"ack-<n>"`), not real cryptography. In the tests the block is kept only because the
  simulator is *told* to send a bad proof. A **real** block node does not send this field at all — so turning the check
  on against a real block node today would reject **every** ack and stall streaming. That is why it must stay behind the
  flag.
- The real version still to build: the ack carries the **actual block proof / root hash**, and the node compares it to
  its own buffered block's hash. On a genuine self-ISS the diverging block's hash really is different from the honest
  one the block node acked, so the mismatch happens on its own — no test injection needed.

## Where the changes live

- Consensus-node check: `BlockNodeStreamingConnection.handleAcknowledgement` (the gate) and
  `BlockNodeConnectionConfig.requireAckProof` (the flag).
- Simulator: `SimulatedBlockNodeServer` (sets the proof; adds the invalid-ack and delay-ack hooks).
- Mock proof field: `hapi/hapi/build.gradle.kts` (patches the external ack message to add `block_proof`).
- Tests + full results: `IssBufferRaceSimTest.java`, `ISS_TEST_RESULTS.md`, `ISS_SIM_BEHAVIOR_MATRIX.md`.
