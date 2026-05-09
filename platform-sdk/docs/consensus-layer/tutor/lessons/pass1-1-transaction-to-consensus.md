---

lesson_id: pass1-1-transaction-to-consensus
cluster: pass1
scenario_kind: canonical
depth: lightweight
components_touched: [event-creator, event-intake, restart-and-pces, gossip, hashgraph]
kb_refs:
topics: [event-creator, event-intake, restart-and-pces, gossip, hashgraph]
concepts: [event-lifecycle, hashgraph-dag, rounds-and-witnesses, judges, birth-round]
invariants: []
scenarios: []
prerequisites: []
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---------------------------------------------------------------

# Transaction to consensus

## What we're tracing

A user transaction enters Consensus through the Execution boundary, lives
inside an event that crosses the network, and ends up sitting in a
delivered consensus round whose ordering every honest node has agreed
on. This is the steady-state happy path; backpressure, freeze, and
fall-behind are out of scope for this pass.

## Setup

Assume a healthy node operating at steady state: roster stable, no
freeze pending, the platform status is `ACTIVE`, and the node is keeping
up with its peers. The transaction has just been accepted into the
node's transaction pool through the
[Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md).

## Walkthrough

1. **Execution offers the transaction to Consensus.** When the
   [event creator](../../architecture/topics/event-creator.md) is ready
   to assemble a new self-event, it pulls transactions from the pool
   through the synchronous `getTransactionsForEvent` call — the only
   Execution-facing pull in the steady-state path. The transaction now
   has a candidate carrier.

2. **The event creator builds and signs a self-event.** The
   [event creator](../../architecture/topics/event-creator.md) chooses
   other-parents that maximise the partial weighted advancement score
   against a moving snapshot of recent tipsets, stamps the new event
   with a [birth round](../../concepts/birth-round.md) equal to the
   current pending consensus round, and signs it. The self-event leaves
   the creator on its `createdEventOutputWire`.

3. **The self-event is validated, ordered, and made durable.** The
   [event intake](../../architecture/topics/event-intake.md) pipeline
   validates the event, deduplicates against `(descriptor, signature)`
   pairs, runs signature verification on peer events (self-events bypass
   that step), and passes everything through the orphan buffer, which
   holds events whose non-ancient parents have not yet arrived and
   releases them in topological order. The orphan buffer's output flows
   into [inline PCES](../../architecture/topics/restart-and-pces.md);
   only after PCES has written the event to disk does it become visible
   to gossip, the hashgraph, and the event creator's loop-back input.
   The "persist before gossip" ordering is what keeps a self-event from
   ever appearing on the network as a parent of a later self-event that
   was lost in a crash.

4. **Gossip carries the persisted event to peers, and peer events arrive
   on the same return path.** Once persisted, the event leaves the local
   node through [gossip](../../architecture/topics/gossip.md) — first by
   simple broadcast over the RPC connection, then by sync as a fallback
   for peers that missed the broadcast. Symmetrically, peer events
   arriving from gossip flow back into the same intake pipeline, are
   persisted by PCES, and join the local DAG. From this step onward the
   node and its peers see the same set of events, modulo gossip
   latency.

5. **The hashgraph reaches consensus and emits a round.** The
   [hashgraph](../../architecture/topics/hashgraph.md) links each event
   into the in-memory [DAG](../../concepts/hashgraph-dag.md), assigns
   round-created and witness flags, runs fame voting on undecided
   witnesses, and — once every witness in a round has a fame verdict —
   identifies the round's [judges](../../concepts/judges.md): one famous
   witness per creator. Every event ancestral to all the judges of round
   *r* takes round-received *r*. The hashgraph emits a `ConsensusRound`
   carrying the consensus event list in agreed order, the new
   `EventWindow`, and the round's roster. The transaction now sits
   inside a delivered round, ordered identically on every honest node.

## Components met

- [Event creator](../../architecture/topics/event-creator.md) — assembles
  self-events. Pass 2 cluster: [A.4 synthesis](A.4-4-event-creator-synthesis.md).
- [Event intake](../../architecture/topics/event-intake.md) — validates,
  deduplicates, and topologically orders events. Pass 2 cluster:
  [A.2 synthesis](A.2-5-event-intake-synthesis.md).
- [Restart and PCES](../../architecture/topics/restart-and-pces.md) —
  persists every event before any downstream consumer sees it. Pass 2
  cluster: [C-3 inline PCES write path](C-3-inline-pces-write-path.md).
- [Gossip](../../architecture/topics/gossip.md) — propagates events
  between peers. Pass 2 cluster: [A.3 synthesis](A.3-5-gossip-synthesis.md).
- [Hashgraph](../../architecture/topics/hashgraph.md) — runs the
  consensus algorithm and emits rounds. Pass 2 cluster:
  [A.1 synthesis](A.1-8-hashgraph-algorithm-synthesis.md).

## Comprehension prompt

When in this path is the order of transactions inside the round actually
decided — which step turns a set of events into a sequence? And once
you have located that step, ask: would relaxing the "persist before
gossip" rule from step 3 break that ordering, or only break some other
property of the system, and which one?

## Open questions

> - [TBD] The KB does not yet have a populated
>   [`invariants.md`](../../invariants.md) catalog. The invariants
>   governing this scenario — durability before gossip, topological
>   order into the hashgraph, non-ancient admission — will be cited by
>   `INV-NNN` once the catalog is filled in.
> - [TBD] No per-topic [`delta-map`](../../delta-map/) entries exist for
>   the touched topics; the proposed-design alternatives (notably the
>   Execution-driven `nextRound` pull replacing the wired
>   `consensusRoundOutputWire`) will be referenced from the corresponding
>   delta callouts once written.
> - [TBD] No [`scenarios/`](../../scenarios/) catalog entries to
>   cross-link from this lesson — when SCN entries land for related edge
>   cases (self-event going stale, future-event buffering during
>   restart, fall-behind during heavy gossip), this lesson should
>   forward-reference them.
> - [TBD] The `last_verified_against` SHA is taken from
>   `origin/main` at bootstrap time. Pass 1 lessons stay at altitude and
>   carry no in-body code anchors, so there is nothing to drift; the SHA
>   is recorded for the curriculum's own audit trail.
