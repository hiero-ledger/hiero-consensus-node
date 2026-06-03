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

- **Determinism.** Consensus output is a function of the event *graph*, not of
  the order events are fed in: given the same DAG, the hashgraph produces the
  same rounds, consensus timestamps, and consensus order every time, for any
  valid topological input order. So the reconstructed graph yields identical
  consensus output, identical execution order, and identical round boundaries.
  (Stale events that never reached consensus are absent from the block stream,
  but they never affected consensus either, so their absence changes nothing —
  see the Conversion Mechanism notes.)
- **Why feed events in block-stream order.** Replaying in a valid topological
  order is a practical requirement of how PCES replay works and the bounded
  memory of the orphan buffer — *not* a consensus requirement. (In normal
  operation gossip delivers events out of order; the orphan buffer holds each
  event until its parents arrive and releases them in topological order before
  the hashgraph sees them.) Block-stream order is already a valid topological
  order, so it keeps orphan-buffer pressure low and needs no re-sorting.
- **Single node, production config.** The node mirrors the production setup
  (full roster, normal multi-node configuration) and is *not* run in single-node
  mode — the other roster members are simply absent. Consensus never actually
  starts: PCES replay completes before `platformCoordinator.startGossip()`, so
  all block production happens during replay with no peer communication. This is
  enforced at the network layer too — a host-level netfilter block drops all
  egress to the other nodes' IPs, since the production roster points at live
  hosts (see component 4). After replay the node enters `CHECKING`, which is
  fine — all needed blocks already exist.
- **Signing without gossip.** Block production does not require live TSS. Either
  a mock signer is used (Tier 1), or — with real hinTS (Tier 2) — the partial
  signatures originally produced in production are event transactions in the
  block stream, so replaying them feeds the same aggregation that produces the
  block proof. See component 3.

## Scope and Assumptions

This design assumes **complete, unredacted, unfiltered block streams**.
Reconstruction and replay are **not supported** for redacted or filtered streams
— this is a known limitation:

- A redacted transaction (`RedactedItem`) exposes only its hash, not its bytes.
  Two things follow. (1) The reconstructed `GossipEvent` cannot carry the real
  transaction payload, so the event cannot be **replayed** through execution to
  reproduce the original results. (2) The *production* event hasher
  (`PbjStreamHasher`) has no path for hashing a transaction from its hash alone,
  and `GossipEvent.transactions` (plain `repeated bytes`) has no representation
  distinguishing real bytes from a stand-in hash. The test-side
  `RedactedEventHasher` in `BlockStreamEventBuilder` can recompute the event hash
  from stored hashes for *validation* (`RedactingEventHashBlockStreamValidator`),
  but that does not carry over to the production replay path. Supporting redaction
  in replay would require encoding a "hash-only" marker into the transaction bytes
  and adding per-transaction detection on the replay path — explicitly out of
  scope.
- Filtered streams (dropped or substituted items, e.g. `FilteredItem` /
  `filtered_single_item`) break the contiguous, complete item sequence the
  converter relies on to group transactions per event and resolve in-block parent
  references.

Inputs must therefore be full block streams as produced by the network, not
redacted/filtered views intended for downstream consumers.

## Components

### 1. Block stream → PCES conversion tool

Reads `.blk.gz` files, reconstructs `GossipEvent` records, and writes PCES files.
Event reconstruction (block items → unsigned `PlatformEvent`s, with hashes) is
already implemented by `BlockStreamEventBuilder`; the remaining work is writing
those events out as PCES files (step 6 below) using `CommonPcesWriter` — the same
mechanism `SavedStateUtils.prepareStateForTransplant()` already uses — so the
normal `PcesFileTracker` replay path can consume them unchanged.

### 2. Unsigned-event intake path (required code changes — blocking)

Reconstructed events lack the creator's `GossipEvent.signature` (the block stream
does not carry it). PCES-sourced events carry `EventOrigin.STORAGE` and pass
through `DefaultEventSignatureValidator` in the intake pipeline, which drops
events with missing/invalid signatures. **There is no existing flag that bypasses
this**, so replay of reconstructed events cannot work today; it requires new code
— a dedicated intake path (or event origin) for unsigned events that skips
signature verification while keeping all other validation stages. Trust derives
from the block proof, not the per-event signature; and since the signature is not
part of the event hash, its absence does not affect the DAG or consensus.

Note: the `forceIgnorePcesSignatures` flag does **not** solve this. Despite its
name it does not touch event-signature validation — it feeds
`DefaultIssDetector` and only causes replayed *state signature transactions* to
be ignored so they don't raise false ISSes during replay. It is unrelated to the
creator's `GossipEvent.signature` and is not used in this scenario.

### 3. Block signing during replay

Block production during replay needs neither gossip nor live TSS; behavior
depends on signer configuration. (Note: `DefaultStateSigner`'s `pcesRound`
suppression applies to *platform state-hash signatures* for signed states / ISS,
**not** to block proofs, so it does not block block production.)

**Tier 1 — block stream representation (works today, deterministic).** With
`tss.forceMockSignatures=true` (or hinTS/history disabled),
`TssBlockHashSigner.isReady()` is always true and `sign()` returns a
deterministic `SHA-384(blockHash)` as the signature, computed async with no
network. Blocks close at the normal boundaries, so the full block merkle tree,
block hashes, state hashes, boundaries, and file format are exercised and can be
compared against production. The block *proof* carries a mock signature rather
than a real TSS signature. No new signing work is required for this tier.

**Tier 2 — real TSS signature (confirmed via the partial-signature handler).**
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
blocks of the extraction window have no partials in-window and remain
pending/unsigned. The extraction window must therefore extend past the
comparison target (see Open Questions / Risks); trailing extraction-only blocks
are not compared.

### 4. Inputs and environment

- A production signed-state snapshot at the round immediately preceding the
  first reconstructed block.
- Block stream files covering the extraction window, contiguous from that round.
- The full production roster (all members' public keys), with the node running in
  its normal multi-node production configuration — **not** single-node mode. The
  other roster members are simply not started.
- **Host-level network isolation (required).** Because the node runs with the
  real production roster, it holds the actual IPs/endpoints of live production
  nodes. A blocking netfilter/iptables rule must drop all egress from the test
  host to those peer IPs, so that any stray connection attempt (gossip dial,
  reconnect probe, startup connection setup, etc.) goes nowhere and cannot reach
  a live production host. This enforces "no peer communication" at the network
  layer rather than relying solely on the application-level fact that gossip is
  never started.
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

### Reconstruction (steps 1–5 already implemented)

Steps 1–5 below are implemented today in `BlockStreamEventBuilder` (in
`test-clients`, used by `EventHashBlockStreamValidator`). It iterates the blocks,
groups transactions per event, resolves parents, recomputes the event hash, and
produces a `PlatformEvent` (a `GossipEvent` with `EventOrigin.STORAGE` and an
**empty signature**). Step 6 (writing PCES files) is the remaining converter
work; the rest can reuse this class.

1. **Iterate block items in order**, switching on item kind (`EVENT_HEADER`,
   `SIGNED_TRANSACTION`, `REDACTED_ITEM`); an `EventHeader` starts a new event and
   closes the previous one.
2. **Collect each event's transactions** between this `EventHeader` and the next.
   Only transactions that belong to the event hash are included: the rule is
   `TransactionID.nonce() == 0 && !scheduled` (`isTransactionInEvent`). Synthetic
   and scheduled transactions (non-zero nonce) are excluded. Redacted items
   contribute via `RedactedItem.signed_transaction_hash`. Note: during a network
   transplant, non-zero-nonce transactions can appear outside an event header and
   are ignored; a zero-nonce transaction outside a header is an error.
3. **Resolve parents.** `index` references resolve to the in-block event by
   position (its already-computed descriptor); `event_descriptor` references
   (out-of-block) are taken directly and recorded as `CrossBlockParentRef` for
   later chain validation.
4. **Compute the event hash** with the same algorithm as `PbjStreamHasher`
   (replicated as `RedactedEventHasher`): hash `EventCore` + parent
   `EventDescriptor`s + per-transaction hashes (using the redacted transaction's
   stored hash when bytes are absent). Events are processed in consensus order,
   which is also topological, so a parent's hash exists before any child
   references it. The hash excludes the signature.
5. **Assemble the `GossipEvent`**: `event_core` + resolved `parents` +
   `transactions`, with `signature = Bytes.EMPTY`, wrapped as a `PlatformEvent`
   with `EventOrigin.STORAGE`.
6. **Write PCES files** (remaining work) via `CommonPcesWriter`, following the
   existing `SavedStateUtils.prepareStateForTransplant()` idiom (which already
   writes `PlatformEvent`s to PCES files outside the live pipeline). Construct a
   `CommonPcesWriter` over a `PcesFileManager` seeded with the snapshot's starting
   round (so origin and sequence numbers are correct), call
   `beginStreamingNewEvents()` **once** up front — without it the writer treats
   every event as already-durable and writes nothing — then for each reconstructed
   event in block-stream order call `prepareOutputStream(event)` followed by
   `getCurrentMutableFile().writeEvent(event)`, and finish with
   `closeCurrentMutableFile()`. `CommonPcesWriter` / `PcesMutableFile` own file
   rotation (by size and birth-round bounds), the version header, and the
   length-delimited `GossipEvent` framing — the converter does not hand-roll any
   of that.

### Notes

- **Missing stale-event parents are harmless.** Stale events never reach
  consensus and are therefore never included in the block stream. They can,
  however, have child events that do reach consensus and are present in the block
  stream. That means there will be events in the block stream that reference a
  parent that is not in the block stream. This does not cause any problem because
  the child events will sit in the orphan buffer until the stale parent becomes
  ancient, then it will continue on. Stale events do not impact the result of
  consensus, so the output will be the same.
- **An existing validator already checks the reconstructed event-hash chain.**
  `EventHashBlockStreamValidator` builds events via `BlockStreamEventBuilder` and
  verifies cross-block parent hashes, using PCES event hashes
  (`PcesEventHashReader.PcesData`) as the source of truth. It tolerates a small
  fraction of cross-block parents that resolve only via PCES — exactly the stale
  parents described above — failing only above `MAX_PCES_ONLY_PERCENT`. This both
  demonstrates reconstruction works and quantifies the stale-event effect.
- **Byte-exact hashes are achievable for non-redacted streams.** `EventCore` and
  parent descriptors come straight from the stream and the transaction bytes are
  the `signed_transaction` items, so the production hasher (`PbjStreamHasher`)
  reproduces the original event hash exactly. This holds only when full
  transaction bytes are present — see the redaction limitation below.
- **Pre-snapshot events are harmless.** Events older than the snapshot's ancient
  threshold may be included; the `PcesFileIterator` lower-bound filter and the
  intake ancient gate drop them without error.

## Test Flow

1. Convert the extraction-window block files into PCES files (the extraction
   window extends past the comparison target — see Open Questions / Risks).
2. Place the PCES files and the matching state snapshot in the node's
   directories. Configure the production roster and the node's private key, and
   enable the unsigned-event intake path (component 2).
3. Start the node. `PcesReplayer` feeds reconstructed events through
   `intake → hashgraph → handler → block production`. Gossip is not started; the
   node enters `CHECKING` after replay completes.
4. Collect the block stream files and final state produced during replay.

## Validation

- For each block in the **comparison window**, compare the reconstructed block's
  hash and each round's state hash against the original production values (e.g.
  via the original block proofs and `BlockStreamInfo.startOfBlockStateHash`).
  Trailing extraction-only blocks (beyond the comparison target) are not compared.
- A match proves the software reproduces historical consensus and execution
  exactly. A mismatch localizes a regression.
- Block proofs may be **valid but not byte-identical** to production, because
  threshold-signature aggregation is not bit-stable. Under Tier 1
  (`forceMockSignatures`) the proof is a deterministic mock signature, not a real
  TSS signature. Compare block and state hashes, not raw proof bytes.

## Open Questions / Risks

- **Trailing blocks of the window.** A block's partial-signature transactions
  appear in *later* blocks, so signing a given block requires those later blocks'
  events to have been replayed. Distinguish two windows: the **comparison window**
  (blocks whose hashes/state you validate against production) and the
  **extraction window** (blocks you read events from to build the PCES input). To
  fully sign every block in the comparison window, the extraction window must
  extend *past* the comparison target — far enough to include the later blocks
  carrying the needed partial-signature transactions (roughly target + k, where k
  is how many blocks ahead partials appear). Replay will also produce proofs for
  those trailing extraction-only blocks; simply don't compare them. (Extending the
  comparison window instead would not help — it just moves the same problem to a
  new last block.)
- **Redacted / filtered streams are out of scope** (see Scope and Assumptions):
  reconstruction/replay requires complete, unredacted transaction bytes and a
  contiguous item sequence.
- Separating event transactions from synthetic/scheduled ones is already handled
  in `BlockStreamEventBuilder` via the `nonce == 0 && !scheduled` rule; the
  converter should preserve that logic when writing PCES files.
- **The unsigned-event intake path is the only net-new platform work** (block
  signing in both tiers needs none). It does not exist yet and is a blocking
  prerequisite (see component 2).

## Relevant Configuration

- `tss.forceMockSignatures=true` — Tier 1: always-ready signer producing
  deterministic mock block signatures, enabling single-node block production
  without TSS. Omit (with hinTS enabled) for Tier 2 real-TSS validation.
- `tss.useDeterministicHintsSignatures=true` — Tier 2: incorporate partial
  signatures in consensus order so block proofs are byte-reproducible.
- `event.preconsensus.limitReplayFrequency` — leave at its default (`true`,
  capping replay at `maxEventReplayFrequency`, 5000 events/s); do not change it
  unless replay is too slow or a problem arises during replay, in which case
  setting it to `false` lets the node replay as fast as the pipeline allows.
  Backpressure still applies regardless: `PcesReplayer` pauses feeding events
  while the node is unhealthy (`replayHealthThreshold`), which also need not be
  changed for this test regardless of replay-window length.
