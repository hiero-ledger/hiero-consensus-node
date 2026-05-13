# Consensus Layer Tutor — Project Brief

## Intent

A structured, comprehensive learning tool covering the entirety of the consensus layer in depth. The Tutor takes a learner who already knows the high-level architecture and builds deep, intuitive understanding of how each part works *and* how the parts interact under normal, stressed, and adversarial conditions. It is the only tool in the system shaped as curriculum rather than exploration.

## Scope

**In scope** (11 topics):

- Wiring Framework
- Gossip
- Event Intake
- Event Creator
- Hashgraph Algorithm (with event birth-round)
- Health Monitor & Backpressure
- Reasons not to gossip
- Signed State Management
- Restart + PCES
- Freeze / Upgrade
- Reconnect

**Out of scope:**

- Execution layer internals (transaction handling, block production, TSS signing)
- Block Stream details
- Application / services semantics
- Legacy generation-based ancient — referenced in passing only

**Truth basis.** Lessons are anchored in the **current codebase as it actually runs today.** Where the proposed redesign differs materially from current behavior, this is captured in explicit *delta callouts* alongside the main lesson content. Migration steps are sidebars, not main content.

## Pedagogical approach

A hybrid: **cluster-based with a spiral overlay and scenario stitching.** Linear consumption assumed.

This is preferred over a strict "deep on A → deep on B → bridge" approach because, when interactions are the hard part — as they are here — the bridge approach teaches each topic on a deliberately incomplete model and asks the learner to retrofit corrections at the end. The spiral keeps the mental model always *complete* (just lower or higher fidelity), respects natural coupling, and saves the hardest material for when the learner is equipped for it.

### Pass 1 — Map *(lightweight)*

A handful of canonical end-to-end scenarios walked through, naming components and their roles but not going deep. Candidates: *transaction → consensus*, *node falls behind → reconnect*, *coordinated network upgrade*, *event creation under stress*.

The point is to plant a complete-but-low-fidelity mental sketch so every later detail lands in context.

### Pass 2 — Cluster deep-dives

Topics grouped by tight coupling. Each cluster taught as a *bundle*, not as isolated parts.

#### Cluster 0 — Wiring Framework Foundation

The substrate underneath everything else. Coverage is *deep enough to understand the rest of the curriculum*, no further:

- Task schedulers and queues
- Soldering between components
- Health Monitor (mechanics — its role in stress response is in Cluster B)
- Hard and soft backpressure modes
- Deterministic mode
- Exception handling
- JVM anchor

Self-contained. Taught first because schedulers, queues, and backpressure are visible throughout the rest of the curriculum.

#### Cluster A — Steady-state event flow

Subdivided into four sub-clusters plus a synthesis. Sub-cluster ordering: **Hashgraph → Event Intake → Gossip → Event Creator → Synthesis.** Hashgraph first because birth-round, ancient, and stale are needed for everything else; Event Creator last because it's the most coupled (tipset uses hashgraph state, output goes to Intake, Gossip distributes).

- **A.1 Hashgraph Algorithm.** Events, parents, the DAG; rounds and witnesses; strongly-seeing; famous witnesses, voting, coin rounds; judges; consensus order and timestamps; birth-round and ancient; stale events.
- **A.2 Event Intake.** Receiving events from Gossip and self-events from Event Creator; validation pipeline; deduplication; signature verification; birth-round filtering; branch detection; PCES persistence; topological emission; neighbor-discipline reporting.
- **A.3 Gossip.** Neighbor selection; event-oriented protocol; the **sync algorithm and its scheduling rules**; caching policy (non-ancient must, non-expired may); buffer management; falling-behind detection at the gossip level; neighbor-discipline reporting; roster handling.
- **A.4 Event Creator.** When to create an event; **tipset / Enhanced Other Parent Selection** (currently implemented); vetoes; filling events with transactions via Execution; max event creation frequency; interaction with Intake.
- **A.5 Steady-state synthesis.** Worked examples that exercise all four sub-clusters together. Where the *cluster* lives, not just the parts.

#### Cluster B — Stress, health, and self-throttling

How the system detects stress and how it responds. Two paired topics:

- **Health Monitor & Backpressure.** The Health Monitor's role and consequences (mechanics already covered in Cluster 0); hard backpressure behavior; Execution → Consensus pacing; dynamic throttling; network-wide throttling coordination.
- **Reasons not to gossip.** A cross-cutting view of all the conditions under which a node decides not to gossip. Includes (non-exhaustive): gossip sync-algorithm timing rules; lagging-behind self-suppression; fallen-behind suppression; squelching during reconnect; freeze-period suppression. Sheriff-driven shunning is a future addition (sidebar). **The full list needs explicit enumeration before authoring this lesson — see open decisions.**

#### Cluster C — State, persistence, and recovery

Signed State Management, Restart + PCES, Reconnect. Durability, resumption, and the dance between them. Tightly interlinked.

#### Cluster D — Coordinated network events

Freeze / Upgrade. Multi-node coordination; touches everything; taught last.

*Order rationale:* 0 underpins everything; A is foundational behavior; B presupposes A; C presupposes A and B (backpressure motivates state-saving rhythms); D presupposes everything.

### Pass 3 — Cross-cluster scenarios and edge cases

Revisit Pass 1 scenarios with full depth. Add edge cases that span clusters: reconnect during a freeze; fall-behind triggered by Health Monitor signals during heavy gossip; roster change during reconnect; PCES replay after upgrade; squelching interactions during a freeze. The hardest content lives here — and the learner is now equipped for it.

## Lesson shape

Within Pass 2 and Pass 3, lessons follow a consistent shape:

- *Motivating problem* — what does this solve, what goes wrong without it?
- *Concept* — with diagrams where applicable (hashgraph diagrams via the existing tool; sequence / state diagrams generated inline; module diagrams from the KB)
- *Worked example or scenario* — concrete and small, anchored in actual test runs where possible
- *Code anchor* — pointer to the real codebase as it exists today
- *Delta callout* — what the proposal changes about this concept, and what's currently in flight
- *Comprehension prompt* — Socratic question or scenario

Each cluster ends with a *cluster worked example* — a scenario showing the cluster's components collaborating, not just describing each in turn.

## Length expectations

Comprehensive coverage. Rough order-of-magnitude estimates, not commitments:

- Pass 1 (Map): ~4–6 hours
- Pass 2: ~37–57 hours total, distributed roughly:
  - Cluster 0: ~3–5 hours
  - Cluster A: ~20–30 hours (4 sub-clusters + synthesis)
  - Cluster B: ~5–8 hours
  - Cluster C: ~8–12 hours
  - Cluster D: ~3–5 hours
- Pass 3: ~6–10 hours

Total: roughly 47–73 hours of curriculum. Calibrate against actual depth as authoring proceeds.

## Inputs

- KB sections for all 11 topics, plus interconnection patterns. Authored alongside the Tutor.
- **Implementation / delta map** — load-bearing under the current-code-canonical policy. Each lesson's anchors and delta callouts source from here.
- Hashgraph diagram tool, integrated where it applies.
- Code anchors into the real codebase (current state).
- Existing glossary.
- Hashgraph paper as reference.

## Outputs

- A curriculum experience in a dedicated Claude project.
- Diagrams: existing tool for hashgraphs; inline-generated (Mermaid / SVG) elsewhere.
- Pointers to specific code paths and existing tests per concept.
- Optional learner-maintained progress notes.

## Dependencies

- KB sections for all 11 topics — the largest dependency. Multi-month undertaking; co-authored with the Tutor.
- Implementation / delta map — promoted from supporting infrastructure to a hard prerequisite, given current-code-canonical.
- Hashgraph diagram tool, integrated.
- Stable code anchors per concept; managed via the implementation / delta map.

## Success criteria

A learner who completes the curriculum can:

- Walk through the canonical scenarios in depth, naming what every component is doing and why.
- Predict behavior under perturbations (slow execution, neighbor branching, mid-reconnect freeze, Health Monitor flagging a queue, PCES replay) without help.
- Read and follow the real code paths of any of the 11 topics.
- Articulate the load-bearing invariants per topic and the cross-topic interactions.
- Distinguish current behavior from proposed behavior on any topic touched by the migration.
- Self-report — feel they understand interactions they previously didn't.

## Key risks

- **Curriculum / implementation drift.** With current-code-canonical and active migration work, lessons rot fast. *Mitigation:* lessons reference KB sections rather than restating; KB updates propagate; delta callouts make migration changes visible during authoring.
- **Sub-scope of umbrella topics.** "Reasons not to gossip" is acknowledged incomplete; "Health Monitor & Backpressure" spans Cluster 0 mechanics and Cluster B role. *Mitigation:* explicit sub-scope agreed before authoring each.
- **Over-abstraction.** The default failure mode for AI-driven learning material. Stronger here than at narrower scopes. *Mitigation:* every lesson includes a concrete worked example; "no hand-waving" is an authoring rule.
- **Pass 1 swelling into a textbook.** Tempting to turn the lightweight map into a full overview. *Mitigation:* hard length cap; Pass 1 covers *roles and interactions*, not mechanisms.
- **Cluster A still too big even subdivided.** Cluster A is the longest part of Pass 2 by far. *Mitigation:* sub-clusters are designed to be independently consumable units with explicit handoffs; pace can flex per sub-cluster.
- **Build effort.** With 11 topics and comprehensive depth, this is a multi-month undertaking. *Mitigation:* build incrementally — Cluster 0 and Pass 1 first; cluster-by-cluster afterward.

## Open decisions

1. **Full enumeration of "Reasons not to gossip."** The current list — gossip sync-algorithm timing rules, lagging-behind, fallen-behind, squelching during reconnect, freeze-period suppression — is acknowledged incomplete. Needs explicit enumeration as a small KB authoring task before Cluster B authoring.
2. **Hashgraph diagram tool integration mechanics.** Does Claude generate event traces and instruct the learner to run the tool locally? Or do we set up a tighter integration so rendered output appears in-conversation? Lighter option faster to build; tighter option better experience. Affects how diagram-heavy we let the curriculum become.

## Build order

1. KB skeleton for all 11 topics (even shallow stubs) and the implementation / delta map.
2. Pass 1 (Map) — smallest content, highest leverage.
3. Cluster 0 (Wiring Framework Foundation) — substrate; benefits all later clusters.
4. Cluster A, sub-cluster by sub-cluster: A.1 → A.2 → A.3 → A.4 → A.5.
5. Cluster B, then C, then D.
6. Pass 3 (Cross-cluster scenarios) once all clusters are done.
