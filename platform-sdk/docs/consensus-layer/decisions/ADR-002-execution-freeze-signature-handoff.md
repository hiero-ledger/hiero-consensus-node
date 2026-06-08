---
id: ADR-002
title: Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus
topics: [freeze-and-upgrade, execution-layer-interface]
related:
  invariants: []
  decisions: []
  scenarios: []
  heuristics: []
  rules: []
status: accepted
date: 2026-05-12
deciders:
  - Kelly Greco (@poulok)
  - Michael Tinker (@tinker-michaelj)
curated_by: Kelly Greco (@poulok)
---

# ADR-002 — Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus

## Context

At freeze time, the **execution layer** must collect a threshold of signatures on the **freeze
block** before the node shuts down. These are distinct from the signatures on the **state hash**
that the consensus layer collects for ordinary signed states — they are signatures over the freeze
block itself, produced by execution.

For execution to gather a threshold of freeze-block signatures, each node must gossip its own
freeze-block signature to its peers. That happens by way of a **signature transaction**: execution
adds the transaction to the transaction pool, and the consensus layer wraps it in an event,
gossips that event, and sends it to pre-handle.

### How event creation interacts with freeze

The consensus layer's event-creation behavior during freeze is governed by consensus layer status:

- In `FREEZING`, the consensus layer **continues to create events** as long as
  `SignatureTransactionCheck.hasBufferedSignatureTransactions()` returns `true`. This is what
  allows freeze-block signature transactions to be included in an event and gossiped.
- In `FREEZE_COMPLETE`, the consensus layer **stops creating events entirely**.

Event creation must stop at `FREEZE_COMPLETE` for memory safety. Once consensus stops advancing, the ancient boundary
also stops advancing,
which means events are no longer garbage-collected. Unbounded event creation in that state
would run the node out of memory. A second, nice to have reason to stop event creation is that it reduces the amount
of "in-flight" events and allows all nodes to receive all events prior to shutdown and write them to disk.

To participate in this mechanism, the execution layer also adds its freeze-block signature
transactions to the pool, and those transactions are included in the
`hasBufferedSignatureTransactions()` check — so the consensus layer is permitted to continue creating events while
execution still has freeze-block signatures to gossip.

### The race

The two operations — execution placing its freeze-block signature transaction into the pool, and
the consensus layer transitioning from `FREEZING` to `FREEZE_COMPLETE` — were previously
asynchronous with respect to each other.

If the consensus layer transitions to `FREEZE_COMPLETE` **before** execution has put its
signature transaction into the pool, event creation stops, the transaction is never gossiped, and
the node loses its chance to contribute its signature toward the threshold needed.

A **happens-before** guarantee is therefore required: the execution layer's freeze-block
signature transaction must be in the pool **before** the consensus layer is permitted to leave
`FREEZING`.

## Decision

**The execution layer blocks the return of
`ConsensusStateEventHandler.onSealConsensusRound(...)` until both of the following are true:**

1. **the node's own freeze-block signature transaction has been added to the transaction pool,
   and**
2. **a threshold of freeze-block signatures has been collected by the execution layer.**

The block is **bounded by a maximum timeout**. If the timeout elapses before both conditions
are met, `onSealConsensusRound` returns regardless. This prevents an indefinite block, which
would stop the freeze state from being written to disk and block the node from upgrading — an
outcome considered harder to recover from than some nodes missing the freeze-block signature
threshold.

`onSealConsensusRound` is the existing call from the consensus layer into the execution layer
that runs after all state modifications for the round have been made. It is already on the
critical path: once it returns, the signed state for that round is created and eventually
written to disk.

By having execution withhold the return from this call until the signature transaction is in the
pool, we establish the required ordering:

```
execution puts freeze-block signature in pool
        AND
execution collects threshold of freeze-block signatures
        │
        ▼
onSealConsensusRound returns
        │
        ▼
signed state created / written
        │
        ▼
consensus layer transitions FREEZING → FREEZE_COMPLETE
        │
        ▼
event creation ceases
```

The happens-before guarantee this establishes is: **the self node has both placed its
freeze-block signature in the transaction pool and collected a threshold of freeze-block
signatures before the consensus layer transitions to `FREEZE_COMPLETE`**. In other words,
once the local threshold is met, this node's `onSealConsensusRound` returns, the signed state
is written, and this node transitions to `FREEZE_COMPLETE` — at which point this node locally
holds a fully-signed freeze block.

This is a per-node guarantee, and it holds only if the maximum timeout does not fire first.
It does **not** guarantee that every other node in the network will also hold a fully-signed
freeze block before transitioning — see [Limitations](#limitations) below.

## Temporary Nature

This blocking mechanism is a **temporary solution** required only while blocks are signed with
**RSA signatures**, where each node contributes an independent signature and a threshold of
per-node signatures must be collected and gossiped before shutdown.

Once blocks are signed with **TSS (Threshold Signature Scheme) signatures**, the per-node
signature-gossip step that motivates this block goes away, and the `onSealConsensusRound`
blocking behavior introduced here **should be removed**.

## Limitations

This decision guarantees that **the self node** holds a fully-signed freeze block before
transitioning to `FREEZE_COMPLETE`. It does **not** guarantee the same for every other node in
the network.

The condition that releases the block — "threshold of freeze-block signatures collected by the
execution layer" — is evaluated locally. This node's own signature counts toward its local
threshold the moment it is applied (before it is added to the pool). The signatures received
from peers arrive via gossiped events processed in `prehandle`.

The residual risk is a **gossip partition / outgoing gossip buffer scenario**:

- The self event carrying this node's freeze-block signature gets stuck in this node's
  outgoing gossip buffer (or is otherwise not delivered to peers).
- This node continues to receive peers' events through gossip and counts their signatures
  toward its local threshold.
- Once the local threshold is met, this node's `onSealConsensusRound` returns, the signed
  state is written, transitions to `FREEZE_COMPLETE`, and is shut down — having locally observed
  a fully-signed freeze block that **no peer has seen in full**, because this node's own
  signature never reached them.

The network-level outcome of this scenario is that **at least one node** (the self node) ends
up with a fully-signed freeze block, but peers may end up short by one signature.

A second scenario is the **maximum timeout firing**. If, for any reason, this node cannot
collect a threshold within the timeout (extended partition, persistent inability to receive
peer events, etc.), `onSealConsensusRound` returns without the second condition having been
met. The signed state is still written and this node transitions to `FREEZE_COMPLETE` without
having locally observed a fully-signed freeze block. This is preferred over blocking
indefinitely, since failure to write the freeze state to disk would block the node from
upgrading — a harder failure mode to recover from than some nodes lacking the freeze-block
signature threshold.

This residual risk is accepted. Eliminating it would require a network wide consensus that
enough nodes have collected enough signatures
which is a
larger redesign out of scope for this decision.

## Consequences

### Positive

- **Uses an existing interface.** No new cross-layer API, no new future or callback object to
  thread between the layers.
- **Provides the required happens-before guarantee** between execution submitting its signature AND collecting enough signatures and
  consensus halting event creation.
- **Localized change.** The blocking behavior lives inside execution's implementation of
  `onSealConsensusRound`; the consensus layer is unchanged.

### Negative

- **`onSealConsensusRound` can now block for longer than its usual duration** during freeze
  rounds. This is acceptable because the consensus layer is intentionally winding down at that
  point, and signed state creation downstream of this call is already on a synchronous path.
- **Implicit contract.** The requirement that execution may block this call during freeze is a
  behavioral expectation on a method whose name does not advertise it. This must be documented
  alongside the method so future maintainers do not "fix" the blocking as a perceived bug.
- **Logic runs on the critical "handle" thread.** `onSealConsensusRound` is invoked on the handle
  thread, so the blocking logic sits directly on the critical path. The only
  round on which it is intended to block is the freeze round — which is acceptable because that
  is the last round handled before shutdown — but any bug or performance degradation that causes
  the block to engage on a non-freeze round could have severe consequences for node performance.

### Neutral

- The condition that releases the block is owned by execution: it returns once the freeze-block
  signature transaction has been added to the pool and a minimum threshold of signatures has been collected. The consensus layer does not need to know
  the release condition.

## Alternatives Considered

### 1. Future gate provided by execution

Add a new interface where execution hands the consensus layer a `Future` (or similar gate) that
must complete before the `FREEZING → FREEZE_COMPLETE` transition is allowed. Execution would
complete the future when both:

- its own freeze-block signature was observed in `prehandle`, and
- a threshold of freeze-block signatures had been collected.

**Rejected because:**

- Adds another interface interaction between the consensus and execution layers purely to enforce
  ordering that an existing call site can already enforce.
- Considered messy: introduces a new component that does something only once per upgrade and adds to an already complex wiring system.
- The same happens-before guarantee is achievable by blocking inside an existing call, with no
  new types crossing the layer boundary.

### 2. Status quo — keep the operations asynchronous

Leave execution's signature insertion and the consensus layer's status transition uncoordinated.

**Rejected because:**

- The race is real: under the wrong interleaving, the freeze-block signature is never gossiped,
  the node fails to contribute to the threshold, and a freeze can proceed without this node's
  signature.
- The consequences (failed orderly freeze participation) outweigh the cost of the small,
  localized block introduced by the chosen design.

### 3. Block inside `onSealConsensusRound` (selected)

See **Decision** above.

## References

- `ConsensusStateEventHandler.onSealConsensusRound(...)` — the call site where execution blocks.
- `SignatureTransactionCheck.hasBufferedSignatureTransactions()` — keeps event creation alive in
  `FREEZING` while signature transactions remain to be gossiped.
- Consensus layer statuses `FREEZING` and `FREEZE_COMPLETE` — define the event-creation window.
