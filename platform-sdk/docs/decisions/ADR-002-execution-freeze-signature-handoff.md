# ADR: Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus

## Status

Pending

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
`ConsensusStateEventHandler.onSealConsensusRound(...)` until its freeze-block signature
transaction has been added to the transaction pool.**

`onSealConsensusRound` is the existing call from the consensus layer into the execution layer
that runs after all state modifications for the round have been made. It is already on the
critical path: once it returns, the signed state for that round is created and eventually
written to disk.

By having execution withhold the return from this call until the signature transaction is in the
pool, we establish the required ordering:

```
execution puts freeze-block signature in pool
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

The happens-before guarantee this establishes is narrow: **the signature is in the transaction
pool before the consensus layer transitions to `FREEZE_COMPLETE`**. It does **not** guarantee
that an event containing the signature is created or gossiped. Event creation during `FREEZING`
remains best-effort and is subject to the event creator's normal rules; see
[Limitations](#limitations) below.

## Limitations

This decision establishes ordering between the signature being placed in the pool and event
creation ceasing. It does **not** establish that an event containing the signature is actually
created and gossiped.

During `FREEZING`, event creation is driven by the event creator's heartbeat tick and is subject
to the same rules as in any other status:

- the heartbeat must fire while the consensus layer is still in `FREEZING`, and
- the event creator must be able to create an event with valid other parents.

On healthy nodes this is highly likely to occur before the consensus layer transitions to
`FREEZE_COMPLETE` — the heartbeat fires frequently and valid other-parents are typically
available. It is **not** guaranteed. If a node cannot select valid other-parents for the entire
duration of `FREEZING` (for example, due to peer unavailability or partition), no event is
created, and the signature transaction sitting in the pool is never gossiped despite being
present.

This residual risk is accepted. It is bounded by node and network health during the freeze
window, which a healthy network is expected to satisfy in practice. Eliminating it would require
either changing event-creation rules during freeze - a larger redesign out of scope for this decision.

## Consequences

### Positive

- **Uses an existing interface.** No new cross-layer API, no new future or callback object to
  thread between the layers.
- **Provides the required happens-before guarantee** between execution placing its signature and
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

### Neutral

- The condition that releases the block is owned by execution: it returns once the freeze-block
  signature transaction has been added to the pool. The consensus layer does not need to know
  the release condition.

## Alternatives Considered

### 1. Future gate provided by execution

Add a new interface where execution hands the consensus layer a `Future` (or similar gate) that
must complete before the `FREEZING → FREEZE_COMPLETE` transition is allowed. Execution would
complete the future when either:

- its own freeze-block signature was observed in `prehandle`, or
- a threshold of freeze-block signatures had been collected.

**Rejected because:**

- Adds another interface interaction between the consensus and execution layers purely to enforce
  ordering that an existing call site can already enforce.
- Considered messy: introduces a new object whose lifecycle (create / complete / cancel) must be
  reasoned about in addition to the status transition it gates.
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

## Authors / Deciders

- Kelly Greco (@poulok)
- Michael Tinker (@tinker-michaelj)
