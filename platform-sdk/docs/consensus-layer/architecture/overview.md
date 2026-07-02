---
type: architecture-overview
title: Architecture overview
last_reviewed: 2026-06-12
---

# Architecture overview

This file is the navigation map for the consensus-layer KB. It names the
modules, names the topics, places them in the module structure, and
shows where the Consensus / Execution boundary runs in current code. It is
deliberately shallow: each topic gets a sentence or two and a forward link
to the per-topic file where the depth lives. Motivation and design
discussion live in the source proposal,
[`Consensus-Layer.md`](../../proposals/consensus-layer/Consensus-Layer.md).

## What is the consensus layer

A consensus node is split into two layers. The **Consensus layer** takes
transactions in and produces a deterministically ordered stream of transactions
out in the form of consensus rounds;
the **Execution layer** takes those rounds, executes the transactions
inside them, transitions state, and produces signed blocks. Each round
that comes out of Consensus is a list of events with consensus timestamps
plus metadata (round number, judges, roster). In general, the Consensus layer
works with events and rounds, while the Execution layer works with transactions.

The Consensus layer is delivered as a set of JPMS
modules paired API + impl. However, there will be a single API
module that the Execution layer interacts with. The remaining modules are
internal Consensus layer organization designed to allow the submodule
implementations to be swapped out as needed without affecting the API. There
are additional library modules utilities, data models, and shared structures.
The responsibility of interacting with the database itself belongs to Execution; some
state-adjacent modules (`consensus-state`, `consensus-platformstate`)
currently live on the Consensus side, as does reconnect orchestration in
`consensus-reconnect`.

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
  up; the orchestration entry point lives in
  [`swirlds-platform-core`](../../../swirlds-platform-core). See
  [`topics/reconnect.md`](topics/reconnect.md).
- [`consensus-state`](../../../consensus-state) — Consensus-side state
  structures shared at the boundary with Execution. See
  [`topics/signed-state-management.md`](topics/signed-state-management.md)
  and
  [`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md).
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

The topics below are the KB's main navigation axis. The grouping
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
- [`topics/quiescence.md`](topics/quiescence.md) — pausing self-event
  creation while the network has no work to do; Execution decides via a
  `QuiescenceCommand` and the consensus layer only obeys it.

**State and lifecycle**

- [`topics/signed-state-management.md`](topics/signed-state-management.md)
  — round signing, state hashing, signature collection.
- [`topics/iss-detection.md`](topics/iss-detection.md) — checking this
  node's state hash for each round against peer signatures, classifying
  any disagreement (self / other / catastrophic ISS), and the configured
  halt-or-restart response.
- [`topics/restart-and-pces.md`](topics/restart-and-pces.md) — PCES
  durability and replay across restarts.
- [`topics/freeze-and-upgrade.md`](topics/freeze-and-upgrade.md) —
  coordinated freeze for software upgrades.
- [`topics/reconnect.md`](topics/reconnect.md) — recovery for nodes
  that can no longer catch up through gossip.

## Boundaries

### Consensus / Execution boundary

In current code, the boundary is muddy and implemented in several shapes
rather than as a single API. The shapes below all carry traffic between
Consensus and Execution today.

**Per-event / per-round callbacks (Consensus → Execution).** Consensus
invokes Execution through
[`ConsensusStateEventHandler`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/ConsensusStateEventHandler.java)
— `onPreHandle` (per pre-consensus event), `onHandleConsensusRound` (per
consensus round), `onSealConsensusRound`, `onStateInitialized`.

**Data and state-related pulls/pushes (Consensus → Execution).** Consensus
pulls transactions from Execution through
[`ExecutionLayer`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ExecutionLayer.java)
— `getTransactionsForEvent`, and pushes data like status and health.

**Construction-time callback (Execution → Consensus).** Execution
supplies an optional stale-event consumer to the platform at build time
through
[`StaleEventConsumer`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/StaleEventConsumer.java),
wired in via
[`PlatformBuilder`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java)
(`withStaleEventConsumer`). Consensus invokes it when a self-event is
detected as stale.

**Notifications (Consensus → Execution).** Lifecycle events are
delivered through the notification engine rather than direct calls.
Examples:
[`StateWriteToDiskCompleteNotification`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/listeners/StateWriteToDiskCompleteNotification.java),
[`PlatformStatusChangeNotification`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/listeners/PlatformStatusChangeNotification.java),
[`ReconnectCompleteNotification`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/listeners/ReconnectCompleteNotification.java),
[`StateHashedNotification`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/state/notifications/StateHashedNotification.java),
and
[`IssNotification`](../../../consensus-model/src/main/java/org/hiero/consensus/model/notification/IssNotification.java).

**Shared state (bidirectional).** Some traffic crosses the boundary
through fields on the state itself rather than through a method call.
The most prominent examples are the freeze timestamps in
[`PlatformState`](../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateAccessor.java)
(written by Execution, read by Consensus) and the roster carried in
state and as round metadata
([`consensus-roster`](../../../consensus-roster)).

For the full method-by-method walk and direction-of-call discussion, see
[`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md).

### Public API surface

There is no single "Consensus public API" today; the surface is the
union of the shapes listed above:

- Consensus → Execution call-outs:
  [`ConsensusStateEventHandler`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/state/ConsensusStateEventHandler.java)
  (per-event/per-round callbacks) and
  [`ExecutionLayer`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ExecutionLayer.java)
  (data pulls plus state-signature, status, and health calls).
- Execution → Consensus call-ins at construction time:
  [`StaleEventConsumer`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/StaleEventConsumer.java),
  registered via
  [`PlatformBuilder`](../../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/PlatformBuilder.java).
- Consensus → Execution lifecycle signals via the notification engine
  (see notifications listed above).
- Shared state fields read or written across the boundary
  ([`PlatformState`](../../../consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateAccessor.java),
  rosters).
- Per-Consensus-module `initialize` and `destroy` hooks complete the
  lifecycle surface.

Method-by-method discussion in
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
- [`topics/quiescence.md`](topics/quiescence.md)
- [`topics/signed-state-management.md`](topics/signed-state-management.md)
- [`topics/iss-detection.md`](topics/iss-detection.md)
- [`topics/restart-and-pces.md`](topics/restart-and-pces.md)
- [`topics/freeze-and-upgrade.md`](topics/freeze-and-upgrade.md)
- [`topics/reconnect.md`](topics/reconnect.md)

**Interfaces**

- [`interfaces/consensus-execution-boundary.md`](interfaces/consensus-execution-boundary.md)

**Other catalogs**

- Concepts — [`../concepts/`](../concepts/) for foundational vocabulary
  (event, round, birth-round, judge, roster, hashgraph, etc.).
- Glossary — [`../glossary.md`](../glossary.md) (pending).
- Invariants — [`../invariants/`](../invariants/) (INV catalog:
  design-guaranteed properties).
- Rules — [`../rules/`](../rules/) (RUL catalog: implementation-true
  properties).
- Decisions — [`../decisions/`](../decisions/) (ADR catalog).
- Scenarios — [`../scenarios/`](../scenarios/) (SCN catalog).
- Heuristics — [`../heuristics/`](../heuristics/) (HEU catalog:
  symptom-keyed diagnostics).
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
> - **State under Execution.** The proposal places all state firmly
>   under Execution. `consensus-state`, `consensus-roster`, and `consensus-platformstate`
>   currently sit on the Consensus side and are scheduled to move.
> - **Reconnect under Execution.** The proposal makes reconnect wholly
>   Execution's responsibility. Today `consensus-reconnect` and
>   `consensus-reconnect-impl` live on the Consensus side, with the
>   orchestration entry point in `swirlds-platform-core`.
> - **Unified Consensus public API.** The proposal envisions a single
>   API interface on the Consensus module. Current code splits the
>   boundary across `ExecutionLayer` and `ConsensusStateEventHandler`.
> - **Execution-driven `nextRound` pull.** The proposal has Execution
>   pull each round from Consensus (carrying any new roster), giving
>   natural backpressure. Current code does not expose this exact pull
>   shape.
> - **`onBadNode` / `badNode`.** Named in
>   the proposal's public API; no direct counterpart in current code.
