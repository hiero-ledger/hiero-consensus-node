---
id: pass1-01-transaction-to-consensus
cluster: pass1
title: From a user transaction to a consensus round
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/event-intake.md
  - architecture/topics/gossip.md
  - architecture/topics/hashgraph.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/signed-state-management.md
kb_concepts:
  - concepts/hashgraph-dag.md
  - concepts/event-lifecycle.md
  - concepts/rounds-and-witnesses.md
  - concepts/judges.md
  - concepts/birth-round.md
kb_glossary_terms:
  - Event
  - Transaction
  - Self-parent
  - Other-parent
  - Round
  - Witness
  - Judge
  - Consensus order
  - Signed state
  - Birth round
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the six consensus-layer components a user transaction passes through on its way to consensus, in the order it touches them.
  - Describe the role each component plays in one sentence, without yet committing to the internal mechanism.
  - Identify the persist-before-gossip waypoint and explain at role level why it sits where it does.
  - Distinguish the "create and emit a self-event" path on the local node from the "receive and validate a peer event" path on every other node.
  - Name where in the trace a `ConsensusRound` becomes a `SignedState`, and what happens to the state after that.
threshold_concepts: []
estimated_session_minutes: 35
status: drafted
last_verified_against: 2de8e9b96551a9cefd0708ee18b1f3199e556173
---

# From a user transaction to a consensus round

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

The scenario passes through six consensus-layer components. Each gets a one-line role here; depth lives in the Pass 2 lessons linked under Forward pointers.

- **Event creator** ([`architecture/topics/event-creator.md`](../../architecture/topics/event-creator.md)) — decides when this node creates a self-event, fills it with transactions supplied by Execution, picks parents that maximise hashgraph advancement, signs the event, and emits it.
- **Event intake** ([`architecture/topics/event-intake.md`](../../architecture/topics/event-intake.md)) — owns hashing, validation, deduplication, and topological ordering for both self-events and peer events before they reach durability or consensus.
- **Preconsensus event stream (PCES)** ([`architecture/topics/restart-and-pces.md`](../../architecture/topics/restart-and-pces.md)) — owns the inline-write durability waypoint; an event is persisted to disk before gossip, the hashgraph, or the event creator can use it.
- **Gossip** ([`architecture/topics/gossip.md`](../../architecture/topics/gossip.md)) — owns event propagation between peers via the per-peer RPC sync protocol (a three-phase window/tip/event exchange) and a simple broadcast for fresh self-events.
- **Hashgraph** ([`architecture/topics/hashgraph.md`](../../architecture/topics/hashgraph.md)) — holds the in-memory non-ancient DAG and runs the consensus algorithm: rounds, witnesses, strongly-seeing, fame voting, judges, and the per-round consensus order. Emits `ConsensusRound`s.
- **Signed-state management** ([`architecture/topics/signed-state-management.md`](../../architecture/topics/signed-state-management.md)) — produces a `SignedState` from each `ConsensusRound`, collects signatures from peers up to quorum, reference-counts the state for in-memory use, and writes selected rounds to disk.

## Scenario setup

A small steady-state network of N nodes. Pick one node — call it node A — and assume the platform is healthy: gossip is running, the hashgraph is decided up to some recent round R, and the event creator's permission gates (health, platform status, sync lag, rate, quiescence) are all open.

A user transaction has arrived at the Execution layer on node A. The consensus layer has not yet seen it; it is waiting in the `EventTransactionSupplier` that the event creator will call the next time it builds a self-event.

The scenario follows that single transaction from this moment until the round that contains it has been signed by enough peers to count as the network's official history.

## Productive impasse

Before walking through the trace, predict, thinking-aloud style:

- Which of the six components above do you expect this transaction to touch, and in what order?
- Where in that order would you place the "this event is now durable" moment? Before gossip, after gossip, or somewhere else?
- The transaction is local to node A. Other nodes do not have it yet. At what point in the trace does that local-only-ness change, and what is the network state that ends it?

There is no quiz here; the goal is to surface the model you are bringing in so the trace can land against it. If you have a strong prior from Paxos, Raft, or PBFT about where durability sits in the pipeline, say so — the consolidation at the end of the trace will check it explicitly.

## Trace

The trace has two halves. The first half lives entirely on node A and ends when node A's freshly created event reaches stable storage. The second half is what every other node does with that event, and what node A does with every peer's events, until the round containing the transaction is decided and signed.

### Stop 1 — Event creator decides to create a self-event

The event creator on node A holds two pieces of state that drive the decision: a moving snapshot of recent tipsets, and the set of non-ancient peer events with no observed children. When it considers creating a self-event, it asks: is there a non-ancient peer event I can pick as the other parent that would meaningfully advance the snapshot? If yes, and all permission gates are open, it builds the event.

To fill the event with transactions, the creator calls Execution's `EventTransactionSupplier.getTransactionsForEvent()`. Our user transaction is returned here. The creator chooses a self-parent (its own last self-event), an other-parent (the peer event that maximises advancement), packs the transactions, hashes the event, signs it, and emits it on its output wire.

The Pass 2 lessons in cluster A.4 cover the tipset advancement score, the snapshot baseline, the anti-selfishness pity-pick, and the permission rules.

### Stop 2 — Local intake of the self-event

The self-event enters event intake on node A, but through a different door than peer events would. Self-events are already hashed and signed, so they bypass the hasher and signature validator and enter at the internal-field validator. They still pass through deduplication and the orphan buffer.

The orphan buffer is the linking stage. For node A's self-event, both parents are already linked in node A's own state (it just emitted its previous self-event, and the other-parent is an already-known peer event), so the event releases immediately and emerges on the validated-events output wire.

The Pass 2 lessons in cluster A.2 cover the five intake stages and the orphan buffer's linking semantics.

### Stop 3 — Inline PCES write (the durability waypoint)

The validated event flows next into the PCES writer. The writer is synchronous: it accepts the event on its input wire and only emits it on its output wire after the event is written to the on-disk preconsensus event stream.

This is the persist-before-gossip waypoint. Until the PCES writer's output fires, three downstream consumers do not see the event:

- Gossip cannot broadcast or sync it to peers.
- The hashgraph cannot link it into the consensus DAG.
- The event creator cannot use it as a self-parent for the next self-event.

The reason the order matters: if node A gossipped a self-event and then crashed before it reached stable storage, on restart node A would not know the event existed and could create a new self-event on the same self-parent. Two self-events from one creator on the same self-parent is a hashgraph branch, and branches are punishable. The PCES write enforces that node A's local store always knows about every self-event it has shown to the network.

The Pass 2 lesson in cluster C (`c-03-inline-pces-write-path`) covers the wiring soldering that enforces this, and the sync-option configuration that controls how strict the fsync is.

### Stop 4 — Fan-out: gossip, hashgraph, and self-loop

Once the PCES write completes, the event leaves the writer's output wire and the wiring framework forwards it to three places at once:

- **Gossip** receives the event for broadcast and for inclusion in subsequent sync responses to peers.
- **The hashgraph** receives the event to link into the DAG and to run the consensus pipeline on.
- **The event creator** receives the event back on its `orderedEventInputWire`, so the next time it considers creating a self-event, it knows this one is durable and can be used as a self-parent.

From node A's local point of view, the event is now durable, in flight to peers, and queued for consensus. The transaction it carries has not yet been ordered — that depends on what other events reach consensus alongside it.

### Stop 5 — Peer reception via gossip

On each peer node, gossip's RPC sync layer holds an open conversation with node A. New self-events from node A reach peers by one of two mechanisms:

- **Simple broadcast** — node A pushes the event to every connected peer as soon as it is created, on the RPC connection. This is the common case in a healthy network.
- **Sync** — periodically, peers exchange windows, tips, and the events each side is missing. Sync is the fallback for nodes that were temporarily disconnected, were overloaded, or missed the broadcast.

A peer that has not seen the event receives it via one of these paths and hands it to its own event intake. From the peer's point of view, this is just another peer event entering the intake pipeline.

The Pass 2 lessons in cluster A.3 cover the RPC protocol stack, the three-phase sync, broadcast and the overload monitor, and the fair sync selector.

### Stop 6 — Peer intake and PCES write

On the peer, the event enters intake at the unhashed-events input wire. It passes through five stages in series:

1. **Hashing** — the event hash is computed (self-events skip this; peer events do not).
2. **Internal validation** — field non-null, length, transaction byte limits, parent-descriptor uniqueness, birth-round consistency.
3. **Deduplication** — the peer drops events it has already accepted.
4. **Signature verification** — the creator's signature is checked against the current roster.
5. **Orphan buffer** — the event is held until its non-ancient parents are linked, then released in topological order.

After the orphan buffer releases the event, the peer's PCES writer persists it before the event proceeds to gossip onward and into the peer's hashgraph. The same persist-before-gossip rule applies on every node, not just node A.

### Stop 7 — Hashgraph: link, round, witness, vote

Inside the hashgraph on every node, the event is linked into the non-ancient DAG. The hashgraph then computes the event's round.

A round-level mental sketch, at orientation altitude: an event's round is one more than the round of its parents, unless this event is the first by its creator to "strongly see" a super-majority of the witnesses in the parents' round, in which case its round bumps further. The first event by each creator in a new round is a *witness*. The hashgraph then votes on whether each witness is *famous* — visible to a super-majority of subsequent witnesses — and once fame is decided across a round, the famous witnesses collapse to one per creator: the *judges*.

The conceptual mental models for round, witness, strongly-seeing, voting, coin rounds, and judges live in the `concepts/` files; the Pass 2 lessons in cluster A.1 deliver them at depth.

### Stop 8 — Consensus order and `ConsensusRound` emission

Once a round's judges are known, every event whose round is at or below the freshly decided round and which has not yet reached consensus is placed into a deterministic order: by preliminary consensus timestamp, then by extended median of received-times, then by an internal generation, then by a whitened hash tie-break.

The hashgraph emits a `ConsensusRound` carrying the consensus event list (in consensus order), the new `EventWindow` (which advances the ancient threshold), the consensus roster for the round, the consensus snapshot, and the judges.

The transaction we have been following rides inside one event in that consensus event list, at the position determined by this ordering.

### Stop 9 — `SignedState` production

Downstream of the hashgraph, `DefaultTransactionHandler` applies the round's events to the state machine and, at the end of the round, captures the resulting `State` into a fresh `SignedState`. The state is the platform's verifiable view of what happened up to and including this round.

The Pass 2 lessons in cluster C (`c-01`, `c-02`) cover the runtime types — `SignedState`, `ReservedSignedState`, `SignedStateReference` — and the six-phase lifecycle (create, sign, decide-to-save, dump, write, reclaim).

### Stop 10 — State signing and disk persistence

Each peer hashes the freshly produced state and emits a `StateSignatureTransaction` carrying its signature. These signature transactions ride inside subsequent events, reach consensus, and feed into a per-state signature collector. Once the collected signatures exceed the signing-weight threshold, the state has quorum and counts as part of the official history.

Not every state is written to disk. A saved-state controller marks freeze states unconditionally and decides on others based on a save-period boundary. When a state is marked to save, a dump request hands it to the file writer, which writes the round directory atomically (built under a temporary path, renamed into place) and hard-links the PCES files needed to replay from this round.

States that are not marked to save still exist in memory while live callers hold reservations; once the last reservation is released, the state is eligible for asynchronous deletion.

## Consolidation

The trace had two pivots worth naming explicitly, in case your prediction missed them:

- **Durability sits before gossip, not after.** The PCES write at Stop 3 is a synchronous gate: gossip, the hashgraph, and the event creator's self-parent slot all read from the *output* of the PCES writer, not the output of intake. If you predicted durability sat after consensus or after gossip, the gap is that the local node has to know about its own self-event before anyone else does, or it can branch on restart.
- **The local-only-ness of the transaction ends at Stop 4, not Stop 8.** As soon as the PCES write completes, the event fans out to gossip and to the hashgraph. Consensus order on the transaction lands later, at Stop 8, but the event itself stops being local-only well before that.

The transaction is not "in consensus" until the `ConsensusRound` is emitted at Stop 8. It is not "part of the official network history" until enough peers have signed the resulting state at Stop 10. Those are two distinct durability surfaces — one for the event, one for the resulting state — and the Pass 2 clusters separate them deliberately.

## Close-out

You should now be able to:

- Sketch the trace from transaction submission to signed state on paper, naming each component as a box and the wires between them.
- Point to the persist-before-gossip waypoint and say what would go wrong without it, at role level.
- Distinguish, on a peer node, the path of a peer event (hash → validate → dedup → sigcheck → orphan buffer → PCES → hashgraph) from the path of a self-event on its originating node (skip hash and sigcheck, enter at internal validation).
- Name the moment a `ConsensusRound` becomes a `SignedState`, and where signatures and disk writes fit afterward.

The Pass 2 lessons listed under Forward pointers below take each component apart in turn. The Pass 3 deep version of this scenario (`pass3-01-transaction-to-consensus-deep`) returns to the trace with code anchors, invariants, deltas, and the cross-cluster stitch points the Pass 2 lessons could not teach in isolation.

## Forward pointers

Each component touched in this trace has a dedicated Pass 2 cluster. After you have a steady sketch from this lesson, walk through them in roughly this order:

- **Event creator** — cluster A.4: `a4-01-tipset-and-advancement-score`, `a4-02-snapshot-and-event-creation-rule`, `a4-03-selfishness-and-anti-selfishness`, `a4-04-creation-rules-and-health-gates`, `a4-syn-event-creator-synthesis`.
- **Event intake** — cluster A.2: `a2-01-validation-pipeline`, `a2-02-deduplication-and-signature-validation`, `a2-03-orphan-buffer`, `a2-04-birth-round-filter-and-pces-handoff`, `a2-syn-event-intake-synthesis`.
- **Gossip** — cluster A.3: `a3-01-protocol-stack-and-rpc`, `a3-02-three-phase-sync`, `a3-03-simple-broadcast-and-overload`, `a3-04-fair-sync-selector`, `a3-syn-gossip-synthesis`.
- **Hashgraph** — cluster A.1: `a1-01-hashgraph-dag` through `a1-08-ancient-and-stale-events`, then `a1-syn-hashgraph-synthesis`.
- **PCES (write path and replay)** — cluster C: `c-03-inline-pces-write-path`, `c-04-restart-and-pces-replay`.
- **Signed-state management** — cluster C: `c-01-signed-state-runtime-types`, `c-02-signed-state-lifecycle-and-on-disk-layout`.
- **Steady-state synthesis** — `a5-syn-steady-state-synthesis` integrates A.1–A.4 once each is established.
- **Deep return** — `pass3-01-transaction-to-consensus-deep` revisits this same trace at mechanism level once Pass 2 is complete.

Cluster 0 (Wiring Framework Foundation) underlies every wire mentioned in this trace and is the natural prerequisite for the Pass 2 mechanism lessons. If "wire", "scheduler", or "solder" felt under-defined here, take cluster 0 first.

## Open questions

None. The KB material this lesson rests on is present at orientation depth; mechanism-level gaps surface in the Pass 2 lessons rather than here.
