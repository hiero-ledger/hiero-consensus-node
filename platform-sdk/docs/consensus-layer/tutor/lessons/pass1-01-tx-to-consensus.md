---
id: pass1-01-tx-to-consensus
cluster: pass1
title: "Transaction to consensus — orientation walkthrough"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/event-intake.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/hashgraph.md
  - architecture/topics/gossip.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/hashgraph-dag.md
kb_glossary_terms: []
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the consensus-layer components a transaction passes through on its way from an execution-side supplier to a decided consensus round, in order.
  - State the rule that self-events must be persisted before they are gossiped, and locate the component that enforces it on the flow path.
  - Describe at role level how the same persisted self-event reaches the hashgraph, gossip, and the event creator's own parent-selection input.
threshold_concepts: []
estimated_session_minutes: 30
status: drafted
last_verified_against: 1b26efc7698950c1f1d52c9e1a32213c8d422b12
---

# Transaction to consensus — orientation walkthrough

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes.

## Components in scope

Five components carry the transaction from arrival to a decided round. Each is named with one-sentence semantics here so the trace can integrate them without further explanation.

- **Event creator** — decides when this node creates a new self-event, picks the other-parent that best advances the hashgraph, fills the event with user transactions, signs it, and emits it ([`event-creator.md`](../../architecture/topics/event-creator.md)).
- **Event intake** — receives unordered events from gossip, PCES replay, or the local event creator, validates them, deduplicates, and emits a topologically ordered stream ([`event-intake.md`](../../architecture/topics/event-intake.md)).
- **PCES writer** — the synchronous persistence stage that writes each event to disk before exposing it to gossip or the consensus engine ([`restart-and-pces.md`](../../architecture/topics/restart-and-pces.md)).
- **Hashgraph** — holds the in-memory DAG of non-ancient events ([`hashgraph-dag.md`](../../concepts/hashgraph-dag.md)) and runs the consensus algorithm that turns a topologically ordered event stream into decided `ConsensusRound`s ([`hashgraph.md`](../../architecture/topics/hashgraph.md)).
- **Gossip** — exchanges events with peers over a per-connection protocol stack; pushes each new self-event to every connected peer as soon as it is allowed to do so ([`gossip.md`](../../architecture/topics/gossip.md)).

A sixth participant sits outside the consensus layer but supplies the trace's starting input: the **execution layer** provides transactions to the event creator through an `EventTransactionSupplier`, a one-method functional interface the event creator calls synchronously while assembling each new event ([`event-creator.md` — Inputs and outputs](../../architecture/topics/event-creator.md#inputs-and-outputs)).

## Scenario setup

The node has been running steadily. The hashgraph has been decided up through some recent round, the PCES files are healthy, and gossip connections with the other nodes are up. A new user transaction has just arrived at the execution layer's transaction queue on this node. It has not yet been embedded in any event, has no round assignment, and is unknown to every other node in the network. We are about to follow it.

## Trace

The trace is six stops. Each stop names the component the transaction sits in, what happens there, and links to the topic file that owns the mechanism for readers who want to go deeper after the session.

### Stop 1 — The execution layer hands the transaction over

**moment_id**: `moment-pre-trace` (before this stop)

The transaction is sitting in an execution-side queue. It does not move on its own. When the event creator next assembles an event, it calls the supplier's `getTransactionsForEvent` method and the supplier hands back the batch of transactions that should ride in that event ([`event-creator.md` — Inputs and outputs](../../architecture/topics/event-creator.md#inputs-and-outputs)). The transaction is now embedded in a new self-event being built, but the event has not yet been signed, validated, persisted, gossiped, or fed to consensus.

This is a load-bearing transition: it is the only synchronous Execution-facing call the event creator makes. Every other side of the consensus / execution boundary is wire-driven.

### Stop 2 — The event creator decides to build a self-event

The event creator does not build an event for every available transaction. It runs the tipset algorithm: a new self-event is built only when there is a peer event that, used as the other-parent, would advance the hashgraph against the current snapshot ([`event-creator.md` — Algorithm](../../architecture/topics/event-creator.md#algorithm)). A permission chain (rate, platform status, sync lag, health, quiescence) gates the decision before the algorithm even runs ([`event-creator.md` — Backpressure interaction](../../architecture/topics/event-creator.md#backpressure-interaction)).

When the gates pass and a qualifying other-parent exists, the event creator assembles the event, calls the transaction supplier for its payload, hashes the event, signs it, and emits it on `createdEventOutputWire` ([`event-creator.md` — Inputs and outputs](../../architecture/topics/event-creator.md#inputs-and-outputs)). The event creator itself does not call gossip, persistence, or the hashgraph — the wiring framework routes the event onward.

### Stop 3 — Event intake validates and orders

The self-event arrives at event intake on the `nonValidatedEventsInputWire`, which routes it past the hashing stage — self-events are already hashed by the event creator, so a second hash would be wasted work ([`event-intake.md` — Inputs and outputs](../../architecture/topics/event-intake.md#inputs-and-outputs)). It passes through internal field validation, the deduplicator, the signature validator (which short-circuits for `RUNTIME`-origin events — the local creator's own events — without checking the signature again), and the orphan buffer ([`event-intake.md` — Validation pipeline](../../architecture/topics/event-intake.md#validation-pipeline)).

The orphan buffer is the topologically-ordering stage: it holds an event until each of its non-ancient parents has been seen, then releases it on a monotonic sequence number ([`event-intake.md` — Orphan buffer](../../architecture/topics/event-intake.md#orphan-buffer)). For a freshly created self-event whose parents are already in the buffer's `eventsWithParents` map, release is immediate. The released event flows out on `validatedEventsOutputWire`.

### Stop 4 — PCES persists the event

**moment_id**: `moment-pces-waypoint`

This is the load-bearing waypoint in the trace. The validated event does not go directly to the hashgraph or to gossip. Instead it is routed to the PCES writer, which writes the event to disk and only then emits it on its own output wire ([`event-intake.md` — Durability and handoff](../../architecture/topics/event-intake.md#durability-and-handoff); [`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path)).

The reason is the no-branch invariant. If a node gossiped a self-event and crashed before the event reached stable storage, the node would not know the event existed on restart. It might create another self-event on the same self-parent, producing a hashgraph branch. Branches are punishable misbehavior. The wiring framework solders the PCES writer's output to gossip with an `INJECT` ordering specifically to prevent gossip from observing a self-event the local store has not yet persisted ([`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path)).

The PCES writer is synchronous: it does not return until the write is complete. Whether the underlying buffer is `fsync`-ed to disk depends on the `event.preconsensus.inlinePcesSyncOption` configuration ([`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path)). At orientation altitude the load-bearing point is the placement, not the fsync policy: PCES sits between intake and everything downstream so that nothing downstream sees an unpersisted self-event.

### Stop 5 — PCES output fans out three ways

The PCES writer's output wire is soldered to three destinations:

1. **The hashgraph** — so consensus only computes on persisted events ([`event-intake.md` — Durability and handoff](../../architecture/topics/event-intake.md#durability-and-handoff)).
2. **Gossip** — so peers only ever receive persisted events ([`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path)).
3. **The event creator's own `orderedEventInputWire`** — so the next self-event the creator builds can use this one as a parent only after it has been persisted ([`event-intake.md` — Durability and handoff](../../architecture/topics/event-intake.md#durability-and-handoff); [`event-creator.md` — Inputs and outputs](../../architecture/topics/event-creator.md#inputs-and-outputs)).

The third wire is the back-edge: the event creator does not build a successor self-event on top of its just-emitted event until that event has come back through PCES on `orderedEventInputWire`. The inline-PCES rule is binding on parent selection as well as on gossip ([`event-intake.md` — Durability and handoff](../../architecture/topics/event-intake.md#durability-and-handoff)).

We follow two of the three branches in the next stops. The back-edge to the event creator is bookkeeping for the next event in the loop; the consensus and gossip branches are where this transaction's fate gets decided.

### Stop 6 — The hashgraph reaches a round

On the hashgraph side, the persisted event enters the consensus engine ([`hashgraph.md` — Algorithm in current code](../../architecture/topics/hashgraph.md#algorithm-in-current-code)). The engine links the event into its DAG, may classify it as a witness, and — if the event is a witness — has it vote on undecided witnesses in earlier rounds. When enough strongly-seeing weight has accumulated on the right witnesses, the engine decides one or more rounds.

When a round is decided, the engine emits a `ConsensusRound` on `consensusRoundOutputWire`, containing the round's events in consensus order, the round's roster, a `ConsensusSnapshot`, and the new `EventWindow` that will gate ancient and expired events going forward ([`hashgraph.md` — State](../../architecture/topics/hashgraph.md#state); [`event-lifecycle.md`](../../concepts/event-lifecycle.md) for the ancient/expired staircase). The transaction's home event is somewhere in that round's event list, in a consensus-determined position. From here the round leaves the consensus layer toward signed-state handling — out of scope for this orientation but visible as the trace's exit.

On the gossip side, simple broadcast pushes the same persisted self-event to every connected peer as a `BROADCAST_EVENT` message over the already-established RPC pipeline ([`gossip.md` — Simple broadcast](../../architecture/topics/gossip.md#simple-broadcast)). Each peer receives the event through its own gossip side, runs it through its own intake and PCES, and feeds it into its own hashgraph. Their hashgraphs reach the same consensus rounds because the hashgraph algorithm is deterministic given the same event DAG.

## Engagement moves

Two moments along the trace are load-bearing enough to warrant a choice of teaching technique. The tutor picks contingent on what the learner shows and varies move type across the two moments so the session does not become monotonous.

### Moment `moment-pre-trace` — eliciting the role-level prediction

Sits before Stop 1. Load-bearing because the orientation's first job is to surface the learner's existing mental model of the consensus-layer pipeline before walking the canonical version on top of it.

**Move A — prediction-and-reveal (role level).**

- **Diagnosis tag**: opening of an orientation session; the learner has general consensus-layer familiarity and can produce a sketch.
- **Framing**: "A transaction has just arrived at the execution layer on this node, and a few minutes later the network has reached consensus on a round that contains it. Before we walk the canonical pipeline, what's your gut prediction — which components do you think the transaction passes through, and roughly in what order?"
- **Confidence elicitation (optional)**: "On a one-to-five scale, how confident are you in the order?" Useful if the learner has implementation history on one of the subsystems and may be over-extending intuitions from that subsystem to the others.
- **`answer_shape`**: fan-out graph from a single waypoint. The canonical shape is `transaction → event creator → event intake → PCES writer → { hashgraph, gossip, event creator (back-edge) }`. Three branches from PCES, not a linear chain.
- **`alternative_correct_answers`**:
  - Linear list with the back-edge omitted: `transaction → event creator → event intake → PCES → hashgraph → gossip` (or hashgraph and gossip swapped). Credit as correct on the spine; consolidation surfaces the back-edge as the missing fourth wire that closes the self-event feedback loop.
  - Fan-out without the back-edge: `transaction → event creator → intake → PCES → { hashgraph, gossip }`. Credit as correct; consolidation adds the third wire.
  - Persistence step placed inside the event creator rather than as a separate downstream component. Treat as a half-correct answer — the learner has the right invariant but a wrong locus. Consolidation moves the persistence stage to its actual location between intake and the fan-out.
- **Canonical answer**: the fan-out form, walked through quickly so the learner can see the gap before the trace replaces gut with mechanism. Anchored to [`event-intake.md` — Durability and handoff](../../architecture/topics/event-intake.md#durability-and-handoff).
- **Consolidation**: name three structural choices the canonical pipeline makes that a typical first prediction misses or compresses — (a) intake is a real stage with its own validation and ordering, not a pass-through; (b) PCES sits between intake and everything downstream, not parallel to gossip; (c) the event creator sees its own event back on `orderedEventInputWire` after persistence, closing the loop for the next event's parent selection.

**Move B — direct walk.**

- **Diagnosis tag**: the learner shows fluency on the consensus layer's component vocabulary; eliciting a prediction would be redundant.
- **Move**: skip the prediction and walk Stop 1 through Stop 6 directly, with the cued check at Moment `moment-pces-waypoint` providing the only check on the trace.

### Moment `moment-pces-waypoint` — the persisted-before-gossiped insight

Sits at Stop 4. Load-bearing because this is the orientation insight that distinguishes the canonical pipeline from a naïve gossip-after-intake model. A learner who internalises this stop has the spine of every subsequent topic that touches restart, reconnect, or branch detection.

**Move A — prediction-and-reveal.**

- **Diagnosis tag**: the learner has distributed-systems background — replication logs, write-ahead durability — and can be expected to predict why persistence sits where it does once prompted.
- **Framing**: "We just walked a validated self-event out of intake. Before I tell you where it goes next, what's your gut prediction — does it go directly to gossip and to the hashgraph in parallel, or is something between intake and those two?"
- **`answer_shape`**: single component name, plus a one-sentence reason. The canonical answer is "PCES writer, between intake and the fan-out, with the reason that gossip never sees a self-event the local store has not persisted."
- **`alternative_correct_answers`**:
  - "Some persistence step / write-ahead log, between intake and gossip, so we cannot send something we cannot reproduce on restart." Correct in substance; consolidate by naming the component (PCES) and the specific failure mode it prevents (branch on restart after gossip-before-persist).
  - "Persistence happens but in parallel with gossip, not before." Incorrect; consolidate against the synchronous-gate placement at [`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path).
  - "Persistence happens after gossip — gossip is the broadcast, persistence is local bookkeeping." Incorrect, and instructively so: this is the prediction the branch invariant exists to refute.
- **Canonical answer**: PCES sits between intake and the fan-out as a synchronous gate. The wiring uses an `INJECT` solder from PCES output to gossip input, recorded with the in-source comment "Make sure events are persisted before being gossipped. This prevents accidental branching in the case where an event is created, gossipped, and then the node crashes before the event is persisted." Anchored to [`restart-and-pces.md` — Inline PCES (write path)](../../architecture/topics/restart-and-pces.md#inline-pces-write-path).
- **Consolidation**: name the no-branch invariant explicitly — honest nodes do not branch — and tie the placement of PCES to its enforcement. Note that the same wire fans out to the hashgraph and back to the event creator for the same reason.

**Move B — direct walk with cued check.**

- **Diagnosis tag**: the learner has not encountered inline-PCES before and is unlikely to predict the placement; a worked walk is more useful than a prediction that would just frustrate.
- **Move**: walk Stop 4 directly, naming PCES and the synchronous placement. Then cue: "Given that placement, what failure mode does it prevent? Try to name the scenario in one or two sentences." Canonical answer for the cued check: a node creates a self-event, gossips it, crashes before the event is persisted, and on restart builds a new self-event on the same self-parent — a branch.

## Consolidation

The orientation succeeds when the learner can hold three things at once: the components in order, the fan-out shape at PCES, and the no-branch invariant that justifies that fan-out's placement. The tutor consolidates explicitly against the predictions made during the two moments — naming, in particular, any prediction that placed gossip parallel to PCES rather than downstream of it, and any prediction that omitted the back-edge to the event creator.

The trace also surfaces, by what it does not say, the boundary the orientation respects: how the hashgraph algorithm actually decides a round, what the tipset algorithm trades off when it picks an other-parent, how the orphan buffer behaves under sustained out-of-order arrival, how gossip's sync protocol fills gaps the simple broadcast does not cover — these are the topics Pass 2 deepens.

## Close-out

A brief mental-sketch consolidation. The tutor asks: "If a colleague asked you in the hallway where a transaction goes after it reaches the execution layer on a node, what would you draw on a whiteboard?" The canonical sketch is the fan-out graph from above, with PCES called out as the load-bearing waypoint and the no-branch invariant named as its justification. The tutor consolidates against whatever the learner draws and names the components the sketch should reach by way of Pass 2.

No threshold concepts; no successive-relearning tags for this lesson.

## Forward pointers

The Pass 2 lessons that deepen each component in this scenario:

- **Wiring framework** (the substrate every component sits on) — `c0-01-components-and-schedulers`, `c0-02-wires-and-soldering`, `c0-03-backpressure-modes`, `c0-syn-wiring-synthesis`.
- **Event creator** — `a4-01-when-to-create`, `a4-02-tipset-other-parent-selection`, `a4-03-filling-with-transactions`, `a4-04-vetoes-and-self-event-persistence`, `a4-syn-event-creator-synthesis`.
- **Event intake** — `a2-01-intake-overview-and-inputs` through `a2-syn-intake-synthesis`, with `a2-05-pces-handoff-and-backpressure` covering the wiring boundary this scenario crossed at Stop 4.
- **PCES** — `c-03-pces-write-and-replay`.
- **Hashgraph** — `a1-01-hashgraph-dag` through `a1-syn-hashgraph-synthesis`, with `a1-05-consensus-order` covering what determines a transaction's position within its decided round.
- **Gossip** — `a3-01-protocol-stack-and-neighbors` through `a3-syn-gossip-synthesis`, with `a3-03-rpc-sync-and-simple-broadcast` covering the broadcast leg at Stop 5.
- **Steady-state stitch** — `a5-01-steady-state-event-flow-trace` revisits this trace at mechanism depth, and `a5-02-self-event-feedback-loop` deepens the back-edge through the event creator.

This is the spiral the curriculum exists to walk: orientation here, mechanism in Pass 2, full-depth stitch in Pass 3.

## Open questions

- `[TBD: glossary path]` — the authoring prompt names `platform-sdk/docs/consensus-layer/glossary.md` as a canonical input, but that file does not exist; the term-definition source for this layer is `hashgraphGlossary.md` one directory up. Confirm whether the consensus-layer KB intends to host its own glossary or to keep linking to the parent one; in either case, populate `kb_glossary_terms` on this lesson's frontmatter once decided.
