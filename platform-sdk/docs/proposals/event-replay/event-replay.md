# Block Stream → PCES Replay Test — Design

## Goal

Validate **block stream equivalence**: that a consensus node running the current
software, when replaying historical production traffic, deterministically
reproduces the same block stream (block hashes and state hashes) as the original
production run. Correct consensus ordering and execution results are prerequisites
for this, but the property under test is the equivalence of the produced block
stream against real-world data.

## Approach

Reconstruct gossip events from production block stream files, write them as PCES
files, and replay them on a single node started from a production state snapshot.
The node **mirrors the production configuration** — it runs with the full
production roster (all peers' public keys) and is **not** configured in
single-node mode; the other roster members simply are not running. PCES replay
runs the complete `intake → hashgraph → transaction handling → block production`
pipeline *before* gossip starts, so this one node produces all blocks with no
peer communication. The output is compared against the original blocks/state by
hash.

Why this works:

- **Determinism.** The hashgraph derives rounds, consensus timestamps, and
  consensus order from the event DAG, not from arrival order. Block-stream order
  is a valid topological order, so the reconstructed DAG yields identical
  consensus output, identical execution order, and identical round boundaries.
- **Single node.** PCES replay completes before `platformCoordinator.startGossip()`,
  so all block production happens during replay. No inter-node communication is
  required; the other roster members simply do not run. After replay the node
  enters `CHECKING`, which is fine — all needed blocks already exist.
- **Signing without gossip.** Block-proof signature material (state signature
  transactions) is carried inside events and therefore in the block stream. It
  is harvested during replay rather than regenerated via live TSS.

## Components

### 1. Block stream → PCES conversion tool
Reads `.blk.gz` files, reconstructs `GossipEvent` records, and writes PCES files.
Detailed in the next section. Implemented as an enhancement to the existing PCES
tooling so the normal `PcesFileTracker` replay path can consume its output
unchanged.

### 2. Unsigned-event intake path
Reconstructed events lack the creator's `GossipEvent.signature`. PCES-sourced
events carry `EventOrigin.STORAGE` and pass through `DefaultEventSignatureValidator`,
which would drop unsigned events. Required: either the test-only
`forceIgnorePcesSignatures` flag (stopgap) or a dedicated unsigned-event intake
path that skips signature verification while keeping all other validation
stages. Trust derives from the block proof, not the per-event signature. The
signature is not part of the event hash, so its absence does not affect the DAG
or consensus.

### 3. Block signing during replay
Block production during replay needs neither gossip nor live TSS; behavior
depends on signer configuration. (Note: `DefaultStateSigner`'s `pcesRound`
suppression applies to *platform state-hash signatures* for signed states / ISS,
**not** to block proofs, so it does not block block production.)

**Tier 1 — block stream representation.** With
`tss.forceMockSignatures=true` (or hinTS/history disabled),
`TssBlockHashSigner.isReady()` is always true and `sign()` returns a
deterministic `SHA-384(blockHash)` as the signature, computed async with no
network. Blocks close at the normal boundaries, so the full block merkle tree,
block hashes, state hashes, boundaries, and file format are exercised and can be
compared against production. The block *proof* carries a mock signature rather
than a real TSS signature. No new signing work is required for this tier.

**Tier 2 — real TSS signature.**
With real hinTS enabled and a snapshot whose hinTS construction is already
complete, `isReady()` is satisfied from state. When a block closes during replay,
`hintsService.sign(blockHash)` registers a `HintsContext.Signing` for that block
hash in a shared `signings` map. The original `HintsPartialSignature`
transactions are event transactions in the block stream; as later rounds are
replayed, `HintsPartialSignatureHandler` (in pre-handle, or in handle when
`useDeterministicHintsSignatures` is set — both run during replay) looks up that
same signing and calls `incorporateValid(...)`. Once the incorporated weight
exceeds the threshold, the aggregate completes and `finishProofWithSignature`
writes the block proof. The node's own partial submission is skipped during
replay (gossip unavailable), which is correct because its original partial is
already in the stream. The RSA mechanism (`DualBlockHashSigner` / `RsaContext`)
works identically — RSA partials ride the same transaction body. Block proofs
therefore need no new signing work.

Two caveats:
- **Byte-reproducible proofs require `tss.useDeterministicHintsSignatures=true`**,
  which incorporates partials at handle in consensus order. Without it, the
  subset of partials that crosses the threshold depends on arrival order, so the
  aggregate is valid but may differ byte-for-byte. Block and state hashes are
  unaffected regardless.
- **Trailing blocks.** A block's partials appear in later blocks, so the last few
  blocks of the replay window may have no partials in-window and remain
  pending/unsigned. Compare up to the last fully-signable block, or extend the
  window past the comparison target.

### 4. Inputs and environment
- A production signed-state snapshot at the round immediately preceding the
  first reconstructed block.
- Block stream files covering the target window, contiguous from that round.
- The production roster (with all members' public keys).
- The replaying node's own private key. Peers' private keys are **not** needed —
  their signatures come from the stream and are verified with their public keys.

## Block Stream → PCES Conversion Mechanism

### What the block stream provides per event
- `EventHeader` block item → `EventCore` (creator, birth round, time created,
  version) and parent references (`ParentEventReference`: a full `EventDescriptor`
  for out-of-block parents, or an in-block `index`).
- The `signed_transaction` items following an `EventHeader`, up to the next
  header → that event's transactions, including system event transactions such
  as state signatures.
- `RedactedItem.signed_transaction_hash` → the hash of any redacted event
  transaction, sufficient to recompute the event hash.

The block stream is designed to support event reconstruction: all transactions
in an event appear in the stream, and the per-transaction double-hash in
`PbjStreamHasher` lets a redacted transaction still contribute its hash to the
event hash.

### Reconstruction steps
1. **Iterate block items in order**, grouping by round (`RoundHeader`) and event
   (`EventHeader`).
2. **Collect each event's transactions** — the event-transaction
   `signed_transaction` items between this `EventHeader` and the next header.
   Distinguish event transactions from synthetic transactions (synthetic ones
   are not part of any event hash); for redacted items use
   `signed_transaction_hash`.
3. **Resolve parents.** Use `event_descriptor` references directly; resolve
   `index` references to the in-block event by position. Build each
   `EventDescriptor` as `(hash, creatorId, birthRound)`, where `hash` is the
   reconstructed parent event hash.
4. **Compute the event hash** with the same algorithm as `PbjStreamHasher`:
   hash `EventCore` + parent `EventDescriptor`s + double-hashed transactions.
   Process events in topological order so parent hashes are available before
   their children. The hash excludes the signature.
5. **Assemble the `GossipEvent`**: `event_core` + `parents` + `transactions`,
   with the `signature` field left empty/placeholder.
6. **Write PCES files**: a 4-byte version header (`2` = `PROTOBUF_EVENTS`)
   followed by length-delimited `GossipEvent` records, emitted in block-stream
   order. Use the standard PCES filename convention (sanitized timestamp,
   sequence number, min/max birth round, origin). Set `origin` consistent with
   the snapshot's starting round and birth-round bounds to cover the events.
   Reuse `PcesFile` / `PcesMutableFile` for descriptor creation and writing.

### Notes
- **Byte-exact hashes are achievable.** `EventCore` and parent descriptors come
  straight from the stream, transaction bytes are the `signed_transaction`
  items, and the double-hash recovers redacted transactions from their hashes.
- **Pre-snapshot events are harmless.** Events older than the snapshot's ancient
  threshold may be included; the `PcesFileIterator` lower-bound filter and the
  intake ancient gate drop them without error.

## Test Flow
1. Convert the target block stream window into PCES files.
2. Place the PCES files and the matching state snapshot in the node's
   directories. Configure the production roster, the node's private key, and the
   unsigned-event path (or `forceIgnorePcesSignatures`).
3. Start the node. `PcesReplayer` feeds reconstructed events through
   `intake → hashgraph → handler → block production`. Gossip is not started; the
   node enters `CHECKING` after replay completes.
4. Collect the block stream files and final state produced during replay.

## Validation
- Compare each reconstructed block's hash and each round's state hash against the
  original production values (e.g. via the original block proofs and
  `BlockStreamInfo.startOfBlockStateHash`).
- A match proves the software reproduces historical consensus and execution
  exactly. A mismatch localizes a regression.
- Block proofs may be **valid but not byte-identical** to production, because
  threshold-signature aggregation is not bit-stable. Under Tier 1
  (`forceMockSignatures`) the proof is a deterministic mock signature, not a real
  TSS signature. Compare block and state hashes, not raw proof bytes.

## Open Questions / Risks
- **Trailing blocks of the window.** Block proofs depend on partial-signature
  transactions that appear in later blocks; the last few blocks of the replay
  window may remain pending/unsigned. Compare up to the last fully-signable
  block, or extend the window past the comparison target.
- Cleanly separating event transactions from synthetic transactions in the item
  stream is the main reconstruction subtlety.
- **The unsigned-event intake path is the only net-new platform work** (block
  signing in both tiers needs none). It is described as planned but not yet
  implemented.

## Relevant Configuration
- `tss.forceMockSignatures=true` — Tier 1: always-ready signer producing
  deterministic mock block signatures, enabling single-node block production
  without TSS. Omit (with hinTS enabled) for Tier 2 real-TSS validation.
- `tss.useDeterministicHintsSignatures=true` — Tier 2: incorporate partial
  signatures in consensus order so block proofs are byte-reproducible.
- `event.preconsensus.limitReplayFrequency=false` — replay as fast as the
  pipeline allows (removes the default 5000 events/s cap).
- `event.preconsensus.replayHealthThreshold` — tune backpressure for very long
  replay windows.