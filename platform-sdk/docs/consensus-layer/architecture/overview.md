---
title: Architecture overview
kind: architecture-overview
last_reviewed: TBD
---

# Architecture overview

This file is the navigation map for the consensus-layer KB. It names the
modules, names the eleven topics, places them in the module structure, and
shows where the Consensus / Execution boundary runs in current code. It is
deliberately shallow: each topic gets a sentence or two and a forward link
to the per-topic file where the depth lives. Motivation and design
discussion live in the source proposal,
[`Consensus-Layer.md`](../../proposals/consensus-layer/Consensus-Layer.md).

## What is the consensus layer

A consensus node is split into two layers. The **Consensus layer** takes
transactions in and produces an ordered stream of consensus rounds out;
the **Execution layer** takes those rounds, executes the transactions
inside them, transitions state, and produces signed blocks. Each round
that comes out of Consensus is a list of events with consensus timestamps
plus metadata (round number, judges, roster).

Crossing the layer in the other direction, Consensus pulls from Execution.
The Event Creator asks Execution for the transactions to fill an outgoing
event; lifecycle (start, reconnect, shutdown) is driven from Execution.
Per the proposal, Consensus does not persist state in the merkle tree —
the merkle tree is Execution's. The Consensus layer is delivered as a set
of JPMS modules paired API + impl, designed to be reusable as a library
that an Execution implementation links against.

> [TBD: question for engineer — The proposal places state firmly under
> Execution, but `consensus-state` is currently a Consensus-side module
> and `consensus-platformstate` and similar exist alongside it. Is the
> rename / move planned, or is the boundary already drawn differently in
> current code?]

## Module map

The consensus layer is split across the modules below. Each module is a
JPMS API + implementation pair (e.g., `consensus-gossip` plus
`consensus-gossip-impl`); only the root names are listed here. Detail
about what lives inside each module belongs in the per-topic files.

- [`consensus-gossip`](../../../consensus-gossip) — peer-to-peer
  communication; the only Consensus module that talks to the network.
  See [`topics/gossip.md`](topics/gossip.md).
- [`consensus-event-intake`](../../../consensus-event-intake) — receive,
  validate, deduplicate, and topologically order events; emit them
  downstream. See [`topics/event-intake.md`](topics/event-intake.md).
- [`consensus-pces`](../../../consensus-pces) — Pre-Consensus Event
  Stream durability for events before they are handed to the hashgraph.
  See [`topics/restart-and-pces.md`](topics/restart-and-pces.md).
- [`consensus-event-creator`](../../../consensus-event-creator) — decide
  when to create a self-event, choose other-parents, fill the event with
  transactions pulled from Execution. See
  [`topics/event-creator.md`](topics/event-creator.md).
- [`consensus-hashgraph`](../../../consensus-hashgraph) — run the
  hashgraph consensus algorithm; emit consensus rounds with timestamps
  and round metadata. See [`topics/hashgraph.md`](topics/hashgraph.md).
- [`consensus-roster`](../../../consensus-roster) — roster representation
  and lookup; rosters are carried as round metadata so every module agrees
  on which roster applies to which round. See
  [`topics/hashgraph.md`](topics/hashgraph.md) (round metadata) and
  [`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md)
  (where rosters cross the boundary).
- [`consensus-reconnect`](../../../consensus-reconnect) — recover a node
  that has fallen behind to the point where gossip alone cannot catch it
  up. See [`topics/reconnect.md`](topics/reconnect.md). [TBD: question
  for engineer — Proposal §Lifecycle says reconnect is wholly
  Execution's responsibility, but `consensus-reconnect` and
  `consensus-reconnect-impl` exist on the Consensus side and the entry
  point lives in `swirlds-platform-core`. Is reconnect migrating to
  Execution, or has the boundary already been re-drawn?]
- [`consensus-state`](../../../consensus-state) — state structures used
  at the Consensus boundary. See note above and the boundary file.
- [`swirlds-platform-core`](../../../swirlds-platform-core) — the wiring
  root: where Consensus modules are composed into a running platform and
  where the Consensus / Execution boundary is drawn today
  (`ExecutionLayer`, `ConsensusStateEventHandler`, `ReconnectModule`).
- [`swirlds-component-framework`](../../../swirlds-component-framework)
  — the wiring framework itself: components, wires, soldering. See
  [`topics/wiring-framework.md`](topics/wiring-framework.md).

Supporting modules — [`consensus-model`](../../../consensus-model),
[`consensus-utility`](../../../consensus-utility),
[`consensus-metrics`](../../../consensus-metrics),
[`consensus-event-stream`](../../../consensus-event-stream),
[`consensus-platformstate`](../../../consensus-platformstate),
[`consensus-concurrent`](../../../consensus-concurrent) — provide the
shared data model, helpers, metrics, event streaming, platform-state
structures, and concurrency primitives consumed by the modules above. The
topic files cite them where relevant.

GUI, otter, and sloth modules (`consensus-gui`,
`consensus-otter-docker-app`, `consensus-otter-tests`, `consensus-sloth`)
are intentionally omitted: they are tooling, not part of the runtime
path.

## Topic map

The eleven topics below are the KB's main navigation axis. The grouping
is for orientation only — it is not a structural ontology, and the
topics are not strictly disjoint.

**Wiring**

- [`topics/wiring-framework.md`](topics/wiring-framework.md) — how
  components, wires, and soldering in
  [`swirlds-component-framework`](../../../swirlds-component-framework)
  compose the Consensus runtime.

**Ingress and output**

- [`topics/gossip.md`](topics/gossip.md) — peer communication,
  event-oriented gossip, neighbor discipline, falling-behind detection,
  buffering for catch-up.
- [`topics/event-intake.md`](topics/event-intake.md) — validation,
  deduplication, topological ordering, branch detection, birth-round
  filtering, emission to Hashgraph / Gossip / Execution / Event Creator.
- [`topics/event-creator.md`](topics/event-creator.md) — Tipset-style
  other-parent selection, event creation cadence, filling events with
  transactions pulled from Execution.
- [`topics/hashgraph.md`](topics/hashgraph.md) — the consensus
  algorithm, round production with judges and timestamps,
  roster-and-config changes carried as round metadata.

**Health and flow control**

- [`topics/health-monitor-and-backpressure.md`](topics/health-monitor-and-backpressure.md)
  — keeping Consensus bounded under load: bounded event memory, the
  Execution-driven round-pull backpressure, lagging-vs-fallen-behind
  thresholds.
- [`topics/reasons-not-to-gossip.md`](topics/reasons-not-to-gossip.md) —
  the conditions under which a node refuses to gossip events
  (lagging-behind, fallen-behind, freeze, etc.).

**State and lifecycle**

- [`topics/signed-state-management.md`](topics/signed-state-management.md)
  — round signing, state hashing, signature collection.
- [`topics/restart-and-pces.md`](topics/restart-and-pces.md) — PCES
  durability and replay across restarts.
- [`topics/freeze-and-upgrade.md`](topics/freeze-and-upgrade.md) —
  coordinated freeze for software upgrades.
- [`topics/reconnect.md`](topics/reconnect.md) — recovery for nodes
  that can no longer catch up through gossip.

## Boundaries

### Consensus / Execution boundary

In current code, the boundary is drawn across two interfaces in
`swirlds-platform-core`:

- Consensus invokes Execution callbacks through
  [`ConsensusStateEventHandler`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/ConsensusStateEventHandler.java)
  — `onPreHandle` (per pre-consensus event), `onHandleConsensusRound`
  (per consensus round), `onSealConsensusRound`, `onStateInitialized`.
- Consensus pulls data and state-related callbacks from Execution
  through [`ExecutionLayer`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ExecutionLayer.java)
  — `getTransactionsForEvent` and related state-signature, status, and
  health calls.

At the module level, [`HashgraphModule`](../../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/HashgraphModule.java)
exposes its inputs and outputs as wires rather than direct method calls;
the rest of the platform is wired against those.

For the full method-by-method walk and direction-of-call discussion, see
[`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md).

> [TBD: question for engineer — The proposal envisions a single
> Consensus public API surface; current code has the boundary split
> across `ExecutionLayer` and `ConsensusStateEventHandler`. Is
> unification planned, and which one is the intended canonical surface?]

### Public API surface

The proposal names a public API on the Consensus module —
`initialize`, `destroy`, `nextRound`, `onBehind`, `onPreHandleEvent`,
`getTransactionsForEvent`, `onRound`, `onStaleEvent`, `onBadNode`,
`badNode`. Some of these have direct counterparts in current code:
`onPreHandleEvent` is `ConsensusStateEventHandler.onPreHandle`, `onRound`
is `ConsensusStateEventHandler.onHandleConsensusRound`,
`getTransactionsForEvent` already lives on `ExecutionLayer`, and
per-module `initialize` / `destroy` lifecycle hooks exist throughout.
Others (`nextRound` as an Execution-driven pull, `onBehind`,
`onStaleEvent`, `onBadNode`, `badNode`) are proposal-only and have no
counterpart yet. The full mapping is in
[`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md).

## Wiring overview

The runtime is composed in
[`swirlds-component-framework`](../../../swirlds-component-framework)
style: each module exposes named **components** with input and output
**wires**; the platform builder **solders** outputs to inputs to form the
event flow. Backpressure is applied at the wire level — a slow consumer
naturally throttles its upstream producers. The wiring root is
[`swirlds-platform-core`](../../../swirlds-platform-core), where the
Consensus modules and the Execution boundary are wired together.
Detail in [`topics/wiring-framework.md`](topics/wiring-framework.md);
the framework itself is documented in
[`../../components/componentFramework.md`](../../components/componentFramework.md).

## Cross-references

**Topics**

- [`topics/wiring-framework.md`](topics/wiring-framework.md)
- [`topics/gossip.md`](topics/gossip.md)
- [`topics/event-intake.md`](topics/event-intake.md)
- [`topics/event-creator.md`](topics/event-creator.md)
- [`topics/hashgraph.md`](topics/hashgraph.md)
- [`topics/health-monitor-and-backpressure.md`](topics/health-monitor-and-backpressure.md)
- [`topics/reasons-not-to-gossip.md`](topics/reasons-not-to-gossip.md)
- [`topics/signed-state-management.md`](topics/signed-state-management.md)
- [`topics/restart-and-pces.md`](topics/restart-and-pces.md)
- [`topics/freeze-and-upgrade.md`](topics/freeze-and-upgrade.md)
- [`topics/reconnect.md`](topics/reconnect.md)

**Interfaces**

- [`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md)

**Other catalogs**

- Concepts — [`../concepts/`](../concepts/) for foundational vocabulary
  (event, round, birth-round, judge, roster, hashgraph, etc.).
- Glossary — [`../glossary.md`](../glossary.md) (pending).
- Invariants — [`../invariants.md`](../invariants.md) (pending).
- Decisions — [`../decisions/`](../decisions/) (ADR catalog).
- Scenarios — [`../scenarios/`](../scenarios/) (SCN catalog).
- Delta map — [`../delta-map/`](../delta-map/) for current-vs-proposed
  status per topic.

## Future state

> **Future state.** The items below are described in the source proposal
> but are not yet present in current code. They are listed here only so
> a reader of the codebase is not surprised by their absence; main prose
> above describes the layer as it stands.
>
> - **Sheriff module.** The proposal introduces a separate Sheriff module
>   that aggregates misbehavior reports from Gossip and Event Intake and
>   decides when to "shun" or "welcome" a neighbor. No `Sheriff` module
>   or class exists in current code; neighbor-discipline routing is
>   distributed across Gossip and Event Intake today.
> - **Unified Consensus public API.** The proposal envisions a single
>   API interface on the Consensus module. Current code splits the
>   boundary across `ExecutionLayer` and `ConsensusStateEventHandler`.
> - **Execution-driven `nextRound` pull.** The proposal has Execution
>   pull each round from Consensus (carrying any new roster), giving
>   natural backpressure. Current code does not expose this exact pull
>   shape.
> - **`onBehind`, `onStaleEvent`, `onBadNode` / `badNode`.** Named in
>   the proposal's public API; no direct counterparts in current code.
