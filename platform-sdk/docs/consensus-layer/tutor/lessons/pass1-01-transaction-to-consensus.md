---
id: pass1-01-transaction-to-consensus
cluster: pass1
title: "A transaction's journey to consensus"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/event-intake.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/gossip.md
  - architecture/topics/hashgraph.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/wiring-framework.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/hashgraph-dag.md
  - concepts/birth-round.md
  - concepts/rounds-and-witnesses.md
  - concepts/judges.md
kb_glossary_terms:
  - Event
  - Self-event
  - Other-parent
  - Event creator
  - Event intake
  - Orphan buffer
  - PCES
  - Gossip
  - Broadcast
  - Sync
  - Hashgraph
  - Round
  - Witness
  - Judge
  - Consensus order
  - Consensus round
  - Consensus timestamp
  - Event window
  - Ancient
  - Birth round
  - Signed state
  - Tipset
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the consensus-layer components a transaction passes through from submission to a signed state, and state each one's role in a single sentence.
  - Describe, at role level, the order in which those components act and what each one hands to the next, including the fan-out that happens after an event is persisted.
  - Explain why a self-event is written to disk before it is gossiped, without yet knowing the PCES write-path mechanism.
threshold_concepts: []
estimated_session_minutes: 45
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# A transaction's journey to consensus

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. This is the first lesson in the curriculum; nothing precedes it to retrieve.

## Components in scope

This scenario walks a single transaction across six consensus-layer components plus the substrate that connects them. Each is named here with a one-sentence role so the vocabulary is set before the trace begins; the depth on each lives in the Pass 2 lessons named under [Forward pointers](#forward-pointers).

- **Event creator** — decides when this node creates its own [event](../../glossary.md#event) (a "[self-event](../../glossary.md#self-event)"), pulls pending transactions from Execution to fill it, selects the [other-parents](../../glossary.md#other-parent) that best advance consensus, signs it, and emits it. See [event-creator.md](../../architecture/topics/event-creator.md).
- **Event intake** — takes events (its own and peers') and produces a validated, topologically [ordered](../../glossary.md#orphan-buffer) stream: it validates fields, drops duplicates, checks signatures, and holds an event back until its parents are known. See [event-intake.md](../../architecture/topics/event-intake.md).
- **PCES (Pre-Consensus Event Stream)** — writes each validated event to disk *before* any other component observes it, so the node can rebuild its in-memory state after a crash. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).
- **Gossip** — exchanges events with peers: it [broadcasts](../../glossary.md#broadcast) this node's persisted self-events to all neighbours and [syncs](../../glossary.md#sync) to reconcile what each side is missing. See [gossip.md](../../architecture/topics/gossip.md).
- **Hashgraph** — holds the directed-acyclic graph of recent events and runs the consensus algorithm that turns the unordered stream into ordered [consensus rounds](../../glossary.md#consensus-round), each carrying a [consensus order](../../glossary.md#consensus-order) and a [consensus timestamp](../../glossary.md#consensus-timestamp). See [hashgraph.md](../../architecture/topics/hashgraph.md).
- **Signed state management** — after Execution applies a round's transactions, it captures the resulting state at block boundaries, signs it, collects peer signatures to a quorum, and persists it as a [signed state](../../glossary.md#signed-state). See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

These components do not call each other directly: the **wiring framework** routes each component's output wire to the next component's input wire. At orientation you can treat it as the plumbing between the boxes; its mechanics (schedulers, queues, backpressure) are the substrate taught first in Pass 2's Cluster 0. See [wiring-framework.md](../../architecture/topics/wiring-framework.md).

## Scenario setup

One node in a healthy, running network — call it **node N** — is in the `ACTIVE` platform status and gossiping normally with its peers. A client submits a single transaction to node N. Execution holds that transaction in its pending pool, where the event creator can pull it the next time it builds an event (the event creator asks Execution for transactions through a supplier callback at the moment it assembles a new event — see [event-creator.md](../../architecture/topics/event-creator.md)).

Nothing about this is an error path: no node is behind, no freeze is pending, no queue is saturated. We are watching the *happy path* of one transaction from "a client handed it to us" to "it is part of a signed state." Hold node N, its pending transaction, and its peers in mind as the starting state.

## Trace

The spine of the scenario, told at role level. Each stop names the component, links its topic file, and says what it does and what it hands on. There are no code anchors here — this is the map, not the mechanism. Two stops are marked as engagement-move **moments**; the moves themselves are in [Engagement moves](#engagement-moves) below.

### Stop 1 — The event creator packs the transaction into a self-event · `moment: opening-flow`

The event creator decides this node should create a new self-event. It selects [other-parents](../../glossary.md#other-parent) — recent events from peers — using the [tipset](../../glossary.md#tipset) algorithm, whose goal is to pick parents that most advance the hashgraph. It calls back into Execution to fill the event with pending transactions (ours among them), then hashes and signs the event and emits it. The event creator does **not** gossip the event and does **not** persist it; it just hands the new self-event onward. See [event-creator.md](../../architecture/topics/event-creator.md).

> **Load-bearing (exposition):** the transaction stops being "a pending transaction" and becomes part of an immutable, signed *event* here. Everything downstream moves events, not loose transactions.

### Stop 2 — Event intake validates and orders the event

The self-event enters event intake. Because this node created and already hashed and signed it, intake skips the work it would do for a stranger's event — it does not re-hash, and it does not re-verify the signature (we trust ourselves). It still runs the cheap field validation, and it still passes the event through the [orphan buffer](../../glossary.md#orphan-buffer), which holds any event back until all of its parents are already known, then releases it. The output is a stream of fully validated events in topological order — no event appears before its parents. See [event-intake.md](../../architecture/topics/event-intake.md).

> **Load-bearing (exposition):** intake's promise to downstream is *topological order* — parents before children. The hashgraph relies on receiving events in that order.

### Stop 3 — PCES writes the event to disk before anyone else sees it · `moment: persist-before-gossip`

The validated event goes next to PCES, which writes it to disk. This is a deliberate waypoint: the persisted event — not the freshly validated one — is what the rest of the system receives. Only **after** the write completes does the event fan out (see Stop 4). The reason is crash-safety: if the node announced an event to its peers and then crashed before writing it down, on restart it would have no record of that event and could create a *different* event on the same self-parent (its own most recent event) — a fork in its own history. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

> **Load-bearing (exposition):** persist-*before*-observe is the rule. This is the first stop where the codebase does something a fast-path intuition would not predict, which is why it is a prediction moment below.

### Stop 4 — The persisted event fans out three ways

Once written, the same persisted event is handed to three places at once:

1. **Gossip** — to broadcast to peers (Stop 5).
2. **The hashgraph** — to be ordered into consensus (Stop 6).
3. **Back to the event creator** — as a fresh candidate other-parent for the *next* self-event it builds.

For this transaction's journey, the two branches that matter are gossip (so the rest of the network learns of the event) and the hashgraph (so the event heads toward consensus). It is one event going to several consumers in parallel — not a single line passing through each in turn. See [event-intake.md](../../architecture/topics/event-intake.md) (the "durability and handoff" boundary) and [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

> **Load-bearing (exposition):** the shape here is a fan-out, not a pipeline. A learner who pictures "gossip *then* consensus" (or the reverse) has the wrong shape; both happen from the same persisted event.

### Stop 5 — Gossip carries the event to peers (and brings theirs back)

Gossip broadcasts node N's persisted self-event to every connected peer, and in the other direction it receives peers' events. Those incoming peer events flow into node N's *own* intake (Stop 2) and on through the same path. Gossip is how every node's hashgraph comes to hold the same set of events, which is what lets independent nodes reach the same order without a coordinator. See [gossip.md](../../architecture/topics/gossip.md).

> **Load-bearing (exposition):** consensus is not reached by node N alone. It needs peers' events too, which is why gossip sits on the path even though our transaction was created locally.

### Stop 6 — The hashgraph orders the event into a consensus round

As events from N and its peers accumulate, the hashgraph builds them into a graph and runs the consensus algorithm. At role level: some events are [witnesses](../../glossary.md#witness); the graph effectively votes, using nothing but the event structure itself, on which witnesses are "famous"; the famous witnesses yield one [judge](../../glossary.md#judge) per creator per [round](../../glossary.md#round); and the judges fix a total [consensus order](../../glossary.md#consensus-order) and a [consensus timestamp](../../glossary.md#consensus-timestamp) for every event that round captures. The output is a [consensus round](../../glossary.md#consensus-round): the events in agreed order, ready for Execution. Our transaction now has a fixed, network-agreed position. See [hashgraph.md](../../architecture/topics/hashgraph.md) and the concept files [rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md) and [judges.md](../../concepts/judges.md).

> **Load-bearing (exposition):** this is where "consensus" actually happens. The mechanism — virtual voting, fame, judges — is a Pass 2 topic; at orientation the takeaway is *the events agree on an order among themselves, with no leader and no extra messages*.

### Stop 7 — Execution applies the round, and the state is signed

The consensus round is handed across the consensus/execution boundary to Execution, which applies the round's transactions in consensus order — this is the moment our transaction *takes effect*. At a block boundary (and at a freeze), the node takes an immutable copy of the resulting state, hashes it, and signs it, sending its signature back into the gossiped event stream. As peers' signatures arrive and agree, the state accumulates a quorum of signatures and becomes a [signed state](../../glossary.md#signed-state) — the durable, network-attested record of "the ledger after these transactions." See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

> **Load-bearing (exposition):** "in a signed state" means more than "executed on node N" — it means a quorum of the network signed the same resulting state. That quorum is the journey's destination.

## Engagement moves

Two moments along the trace carry a move inventory. The tutor selects exactly one move per moment by matching the diagnosis tag to what the learner has shown, and delivers the chosen prompt verbatim. Each answer-eliciting move supplies its canonical answer and a list of alternative correct answers; predictions also carry the shape the answer should take. Every prompt below is answerable from the trace text and the Components-in-scope list that appear above the moment it is attached to.

### Moment `opening-flow` — attached to Stop 1

*Why this moment is load-bearing (exposition):* this is the one chance to surface the learner's own end-to-end model before the trace supplies it. The audience are distributed-systems experts who can predict much of the path; making their prediction explicit lets the few genuine surprises (persist-before-gossip in Stop 3, leaderless ordering in Stop 6) land against something.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is engaged and willing to commit to a guess, or is showing strong general distributed-systems intuition.

- `answer_shape`: an ordered sequence of components (a pipeline) with a fan-out branch after persistence — not a strict single line.
- Prompt (verbatim): "Before we trace it: a client hands node N a transaction. Using the components I just listed, what's your gut ordering — which one touches the transaction first, which last, and roughly what's the path between them? Rough is fine, and a branch instead of a straight line is fine too; we'll sharpen it as we go."
- `canonical_answer`: "Event creator first (it packs the transaction into a self-event), then event intake (validate and order), then PCES (write to disk). From the persisted event it fans out — gossip carries it to peers, and the hashgraph orders it into a consensus round — and finally signed-state management produces a signed state once Execution applies the round. The natural shape is a pipeline up to persistence, then a fan-out."
- `alternative_correct_answers`:
  - "Create → validate/intake → persist → {gossip, hashgraph} → signed state — same pipeline, with the post-persist step named as a fan-out."
  - "The event creator builds the event, intake checks it, it gets written down, then it's both shared with peers and fed into consensus, and the consensus output becomes a signed state."
  - "Roughly creator → intake → gossip and consensus → signed state, with persistence somewhere before gossip — placement of PCES a little fuzzy."
- `followup` (delivered verbatim when the learner lists components but commits to no ordering, or hand-waves the middle): "You've named the components — now commit to the one ordering you're least sure of: on node N, does this event reach its peers before or after it reaches consensus? Say which and why."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner would rather be shown the path than guess at it, or is hesitating to commit.

- Exposition (tutor states, not a question): the path is a pipeline that fans out — event creator → intake → PCES, then the persisted event goes to both gossip and the hashgraph, whose output becomes a signed state.
- Prompt (verbatim): "Here's the path at a glance: the event creator builds the event, intake validates and orders it, PCES writes it to disk, and from there it goes to both gossip and the hashgraph, whose output becomes a signed state. Quick check before we walk it: which single component is responsible for turning the client's loose transaction into an *event* in the first place?"
- `canonical_answer`: "The event creator — it pulls pending transactions from Execution and packs them into a new self-event."
- `alternative_correct_answers`:
  - "The event creator (the component that creates this node's self-events)."
  - "The tipset event creator / the creator that selects parents and fills the event."

### Moment `persist-before-gossip` — attached to Stop 3

*Why this moment is load-bearing (exposition):* an expert in gossip protocols is likely to assume a node broadcasts an event the moment it has one — latency is everything in gossip. The codebase does the opposite: persist first, then gossip. The reason is anti-branching after a crash, a consequence that is reachable from a general durability schema but is not the first instinct. This is the scenario's best productive-failure point.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on gossip or eager, and is likely to hold the confidently-wrong "broadcast immediately" intuition.

- `answer_shape`: an ordering rule ("persist before gossip") plus a one-line justification.
- Confidence elicitation: included, because a high-confidence wrong answer is likely here.
- Prompt (verbatim): "Node N has just built and validated its own event. In gossip, speed usually wins — so here's the question before I show you what happens next: does node N send this event to its peers right away, or does it do something else first? What's your gut, and how confident are you?"
- `canonical_answer`: "It does something else first: PCES writes the event to disk *before* it is gossiped (and before it is fed into consensus). The reason is crash-safety against branching — if node N gossiped the event and then crashed before writing it down, on restart it would not know the event existed and could build a different event on the same self-parent — its own most recent event. That fork in its own history is a branch, which is a punishable attack on consensus, so honest nodes must avoid it."
- `alternative_correct_answers`:
  - "Persist first — durability before broadcast, so a crash can't make the node lose an event its peers have already seen."
  - "It writes the event to disk before telling anyone, so it can never 'forget' an event it already announced."
  - "Something first — the event has to be recoverable before it's shared (even without naming PCES or branching)."
- `followup` (delivered verbatim when the learner predicts "persist first" but cannot say why it matters): "You predicted it persists first — now name exactly what goes wrong if it gossiped first and then crashed. What would node N do on restart that it shouldn't?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to this part of the system and is hesitating rather than guessing; walk the rule and ask them to reason about the failure it prevents.

- Exposition (tutor states, not a question): PCES sits between intake and everyone else; the persisted-event output is what is handed to gossip and to consensus, so nothing observes an event before it is on disk.
- Self-explanation prompt (verbatim): "Here's the rule the code enforces: the event is written to disk first, and only the persisted event is handed to gossip and to the hashgraph. What failure does that ordering prevent — what could go wrong if node N shared the event with peers but crashed before it was written down?"
- `canonical_answer`: "Without persist-first, node N could gossip a self-event, crash, and lose it; on restart it would not know the event existed and could create a different event on the same self-parent — a branch. Persisting before gossip closes that gap."
- `alternative_correct_answers`:
  - "The node would branch — build a second event on a self-parent it had already used, because it forgot the first one across the crash."
  - "It prevents losing an already-announced event across a crash, which to peers would look like the node forking its history."
- `followup` (delivered verbatim when the learner restates the rule instead of explaining the failure): "That's what the rule says — I'm asking why it matters. What does the network lose, or what attack becomes possible, if honest nodes are allowed to branch like that?"

## Consolidation

(The tutor phrases this in its own words; the substance and the canonical recap are below.)

Bring the six stops back together as one sketch: a client's transaction becomes a **self-event** at the event creator; **intake** validates it and guarantees parents-before-children order; **PCES** writes it to disk; from that persisted event the path **fans out** to **gossip** (peers learn of it) and to the **hashgraph** (it gets ordered); the hashgraph's **consensus round** is applied by Execution and the result becomes a **signed state**.

Name the two things most likely to have differed from a first guess:

- **Persist before gossip** (Stop 3). The durability write is not bookkeeping that happens "eventually" — it is a gate the event must pass before anyone else, and the reason is anti-branching, not just not-losing-data.
- **Ordering is leaderless** (Stop 6). No node is in charge of the order; the events fix it among themselves through the hashgraph, which is why peers' events (via gossip) are on the path even for a locally created transaction.

If the learner ran a prediction move, name explicitly where their prediction matched and where it missed, against the canonical answers above.

## Close-out

**Free-recall summary.**

- Prompt (verbatim): "In your own words, trace one transaction from the moment a client submits it to the moment it's part of a signed state. Name each component you pass through and say, in a phrase, what it does — and call out the one point where the order of two steps matters."
- `canonical_answer`: "Event creator packs the transaction into a self-event → event intake validates and topologically orders it → PCES writes it to disk → (fan-out) gossip broadcasts it to peers and the hashgraph orders events into a consensus round → Execution applies the round and signed-state management produces a signed state once a quorum of signatures agrees. The order that matters: PCES persists the event *before* gossip sees it, to prevent branching after a crash."
- `alternative_correct_answers`:
  - "Create → validate/order → persist → share-and-order → execute-and-sign, with persist-before-gossip as the load-bearing ordering."
  - "Transaction → self-event (creator) → checked and ordered (intake) → saved (PCES) → spread to peers (gossip) and ordered (hashgraph) → applied and signed (signed state); the catch is it's saved before it's spread."

**Successive-relearning tags (exposition):** this orientation scenario establishes no threshold concept, so it adds nothing to the relearning queue. The threshold concepts it gestures at — leaderless consensus order via virtual voting, and persist-before-observe — are established and tagged for relearning in their Pass 2 lessons (A.1 and Cluster C respectively).

## Forward pointers

Each component this scenario sketched is taught in depth later. The spiral: orientation plants the sketch, Pass 2 fills the mechanism, Pass 3 stitches the components back together under stress.

- **The wiring substrate** beneath every handoff above → Cluster 0: `c0-01-schedulers-and-types`, `c0-02-wires-and-soldering`, `c0-03-backpressure-and-queue-health`, `c0-04-wiring-model-lifecycle`, `c0-syn-wiring-synthesis`.
- **Event creator** (Stop 1) → `a4-01-tipset-and-advancement`, `a4-02-snapshot-and-selfishness`, `a4-03-event-creation-rule-and-gates`, `a4-04-self-event-persistence`, `a4-syn-event-creator-synthesis`.
- **Event intake** (Stop 2) → `a2-01-validation-pipeline`, `a2-02-deduplication-and-signatures`, `a2-03-orphan-buffer`, `a2-04-birth-round-filter-and-durability`, `a2-syn-intake-synthesis`.
- **PCES / persist-before-observe** (Stops 3–4) → `c-04-pces-write-path`, `c-05-restart-and-replay`.
- **Gossip** (Stop 5) → `a3-01-protocol-stack`, `a3-02-three-phase-sync`, `a3-03-simple-broadcast`, `a3-04-fair-sync-selector`, `a3-syn-gossip-synthesis`.
- **Hashgraph / consensus order** (Stop 6) → `a1-01-hashgraph-dag` through `a1-07-stale-events`, then `a1-syn-hashgraph-synthesis`.
- **Signed state management** (Stop 7) → `c-01-signed-state-lifecycle`, `c-02-reservation-discipline`.
- **The whole steady-state loop, stitched** → `a5-syn-steady-state-synthesis`, and revisited at full depth in `pass3-01-transaction-to-consensus-deep`.

## Open questions

None — at role level the current topic files fully cover this scenario's happy path. (The `[TBD]` markers that exist in the source topics — e.g. whether `InternalEventValidator` should short-circuit on ancient events in [event-intake.md](../../architecture/topics/event-intake.md), and the orphan-buffer `clear()` question — concern structural detail below this orientation scenario's altitude and are left to the Pass 2 intake lessons that need them.)
