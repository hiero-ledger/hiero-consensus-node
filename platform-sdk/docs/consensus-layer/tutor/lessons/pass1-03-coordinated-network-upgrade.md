---
id: pass1-03-coordinated-network-upgrade
cluster: pass1
title: "A coordinated network upgrade"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/freeze-and-upgrade.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/hashgraph.md
  - architecture/topics/event-creator.md
  - architecture/topics/gossip.md
  - architecture/topics/event-intake.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/wiring-framework.md
kb_concepts:
  - concepts/birth-round.md
kb_glossary_terms:
  - Freeze and upgrade
  - Signed state
  - PCES
  - Platform status
  - Gossip
  - Sync
  - Self-event
  - Event creator
  - Consensus round
  - Birth round
  - Roster
  - Health monitor
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the components a coordinated network upgrade passes through — the freeze trigger from Execution, the hashgraph's freeze-round cutoff, the event creator and gossip during the freeze, signed-state management, and PCES replay at restart — and state each one's role in a single sentence.
  - Explain why a node keeps gossiping after it has stopped creating events during a freeze — it is circulating the signatures that make the shared freeze state trustworthy.
  - Describe, at role level, why the upgrade is safe: every node restarts from the identical saved freeze state, replays its PCES to rebuild consensus before it talks, and adopts the new roster at the round after the freeze — not because of any special upgrade code path.
threshold_concepts: []
estimated_session_minutes: 45
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# A coordinated network upgrade

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. This scenario stands on its own at role level and assumes no specific prior lesson; where it leans on an idea an earlier orientation scenario sketched (a signed state, persisting before sharing), it re-introduces it in place.

## Components in scope

This scenario follows one node — call it **node N** — but it is really about the *whole network* moving together: a planned, orderly [freeze and upgrade](../../glossary.md#freeze-and-upgrade), not a crash. Each component is named here with a one-sentence role so the vocabulary is set before the trace begins; the depth on each lives in the Pass 2 lessons named under [Forward pointers](#forward-pointers).

- **The freeze trigger (from Execution)** — the upgrade is scheduled from *outside* the consensus layer: the Execution layer writes a *freeze time* onto the shared state, and the consensus layer's job is only to recognize the agreed stopping point and act on it. See [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md) and the [Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md).
- **The hashgraph** — orders events into [consensus rounds](../../glossary.md#consensus-round); during a freeze it seals one final round — the *freeze round* — and stops, so every node halts at the same point. See [hashgraph.md](../../architecture/topics/hashgraph.md).
- **The [event creator](../../glossary.md#event-creator)** — decides when this node authors its own [self-events](../../glossary.md#self-event); during a freeze it is allowed to emit just enough events to flush its outstanding signatures on the freeze state, then it goes quiet. See [event-creator.md](../../architecture/topics/event-creator.md).
- **[Gossip](../../glossary.md#gossip)** — the peer-to-peer [sync](../../glossary.md#sync) of events; during the freeze it keeps running, carrying the nodes' signature transactions on the freeze state. See [gossip.md](../../architecture/topics/gossip.md).
- **Signed-state management** — produces, signs, and saves the per-round [signed state](../../glossary.md#signed-state) snapshots; a saved state becomes trustworthy only once a threshold of the network's nodes have signed it, and the freeze round's state is the snapshot the upgrade hands off. See [signed-state-management.md](../../architecture/topics/signed-state-management.md).
- **Restart and PCES** — at startup, loads the saved state from disk and replays the [PCES](../../glossary.md#pces) (the on-disk log of validated events) to rebuild the in-memory hashgraph before the node gossips or creates anything. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

Two pieces of background plumbing matter here too. PCES replay reuses the node's ordinary [event intake](../../architecture/topics/event-intake.md) pipeline and is paced by the [health monitor](../../glossary.md#health-monitor) so the replaying node does not flood its own queues — the same detector that watches every component's work queue (see [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md)). Those queues live on the **wiring substrate** that routes each component's output to the next; at orientation, treat it as the pipes between the boxes (it is taught first in Pass 2's Cluster 0). See [wiring-framework.md](../../architecture/topics/wiring-framework.md). And the node's [platform status](../../glossary.md#platform-status) — `ACTIVE`, `FREEZING`, `FREEZE_COMPLETE`, and the like — is the lifecycle flag several of these rules consult to decide whether creating events or gossiping is even allowed.

## Scenario setup

A whole network of nodes is running healthily — follow one of them, **node N**, `ACTIVE` and gossiping in step with its peers. The operators want to roll out a new software version to every node at once, without losing the ledger and without letting any two nodes disagree about where they stopped. To coordinate it, the Execution layer has written a *freeze time* onto the shared platform state.

Nothing here is a crash or a failure — every node is up and healthy the whole time. We are watching an orderly, planned halt: the network will stop together at one agreed round, each node will save a matching state, and each will restart on the new binary. Hold node N, its peers, and that scheduled freeze time in mind as the starting state.

## Trace

The spine of the scenario, told at role level. Each stop names the component, links its topic file, and says what it does and what it hands on. There are no code anchors here — this is the map, not the mechanism. Two stops are marked as engagement-move **moments**; the moves themselves are in [Engagement moves](#engagement-moves) below.

### Stop 1 — The upgrade is scheduled from outside consensus · `moment: opening-sketch`

The decision to upgrade does not originate inside the consensus layer. The Execution layer decides an upgrade is due and writes a *freeze time* onto the shared platform state; the consensus layer only reads it back and watches for the moment consensus crosses it. The first [consensus round](../../glossary.md#consensus-round) whose timestamp falls in the freeze period is the signal that the freeze has begun. See [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md) and the [Consensus / Execution boundary](../../architecture/interfaces/consensus-execution-boundary.md).

> **Load-bearing (exposition):** the consensus layer does not *decide* to upgrade — it recognizes a boundary set for it from outside. Its whole job in this scenario is to bring every node to a clean, identical stop and back up again.

### Stop 2 — The hashgraph seals one final round — the freeze round

Consensus does not just stop wherever each node happens to be. The hashgraph keeps the first round that falls in the freeze period — the *freeze round* — discards any later rounds that were about to be decided in the same batch, and stops feeding new events into the algorithm. That freeze round is the last round every node handles before the restart, and because the freeze time is shared, every node selects the same one. See [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md) and [hashgraph.md](../../architecture/topics/hashgraph.md).

> **Load-bearing (exposition):** the freeze round is a single agreed cutoff, identical on every node. That shared stopping point is what makes the states the nodes save comparable — and is the root of why the upgrade is safe.

### Stop 3 — Creation winds down, but gossip keeps running · `moment: gossip-continues-mid-freeze`

With the freeze round reached, node N stops minting ordinary new [self-events](../../glossary.md#self-event): the [event creator](../../glossary.md#event-creator) is permitted to emit only enough events to flush its outstanding *signature transactions* on the freeze state, then it goes quiet. [Gossip](../../glossary.md#gossip), though, keeps running exactly as before. N keeps [syncing](../../glossary.md#sync) with its peers — to send its own signature on the freeze state, to collect theirs, and to relay signatures onward to any peer still missing them. See [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md), [event-creator.md](../../architecture/topics/event-creator.md), and [gossip.md](../../architecture/topics/gossip.md).

> **Load-bearing (exposition):** "freeze" stops new work, not communication. A node that has stopped creating events still has a job on the wire — circulating the signatures that turn the freeze state from a local snapshot into one the whole network has signed off on.

### Stop 4 — The freeze state is created, signed, and saved to disk

Signed-state management takes the freeze round's state, hashes it, signs it with N's key, and writes it to disk — together with the [PCES](../../glossary.md#pces) files needed to replay from it. The peer signatures N is collecting over gossip (Stop 3) accumulate on this same [signed state](../../glossary.md#signed-state). The freeze state is special: it is saved *unconditionally*, even when it is written before a full quorum of signatures has arrived — which is exactly why gossip keeps circulating them afterward. Once the freeze state is on disk, N's [platform status](../../glossary.md#platform-status) reaches `FREEZE_COMPLETE`. See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

> **Load-bearing (exposition):** this saved freeze state is the handoff artifact — the single thing the new software will boot from. Everything before this stop exists to produce it; everything after it exists to load it.

### Stop 5 — The node shuts down and restarts on the new software

With the freeze state safely on disk and status at `FREEZE_COMPLETE`, the consensus node has done its part. An external operator tool — outside the consensus layer — swaps in the new binary and restarts the process. The new software comes up and loads the latest signed state from disk: the freeze state N just wrote. There is no special "upgrade" entry path — starting from a freeze state runs exactly the same startup code as starting from any other saved state. See [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md) and [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

> **Load-bearing (exposition):** the upgrade's safety does not come from special boot logic. It comes from the fact that every node saved the same freeze state at the same round — so every node restarts from an identical starting point, even on new code that might interpret transactions or state differently.

### Stop 6 — PCES replay rebuilds the hashgraph before N talks to anyone

The loaded state fixes *where* N is, but its in-memory hashgraph starts out empty. Before N gossips or creates a single event, it replays its [PCES](../../glossary.md#pces) — the on-disk log of validated events — back through the ordinary [event-intake](../../architecture/topics/event-intake.md) pipeline, rebuilding the hashgraph to where it stood at the freeze. Replay runs to completion first; only then does gossip start. While replaying, N is paced by the [health monitor](../../glossary.md#health-monitor) so it does not flood its own queues faster than it can drain them. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

> **Load-bearing (exposition):** nobody — not gossip, not N's own event creator — sees a half-rebuilt node. Rebuild-before-observe is the same discipline that makes a node persist an event before sharing it: get the durable state right before anyone can act on it.

### Stop 7 — The network resumes on the new version

Once replay completes, gossip starts, N's [platform status](../../glossary.md#platform-status) climbs back toward `ACTIVE`, and event creation resumes. The events N now creates take a [birth round](../../glossary.md#birth-round) past the freeze round and are validated against the *new* [roster](../../glossary.md#roster) the network adopts at the round after the freeze — while the pre-freeze events stay valid against the old roster they were created under. The network is running again, on the new software, every node having started from the same state. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md), [freeze-and-upgrade.md](../../architecture/topics/freeze-and-upgrade.md), and the [birth-round](../../concepts/birth-round.md) concept.

> **Load-bearing (exposition):** the roster change lands exactly at the freeze boundary. Birth round is what lets old and new events coexist — each is judged against the membership that was valid when it was made — so an upgrade can add or drop nodes without invalidating history.

## Engagement moves

Two moments along the trace carry a move inventory. The tutor selects exactly one move per moment by matching the diagnosis tag to what the learner has shown, and delivers the chosen prompt verbatim. Each answer-eliciting move supplies its canonical answer and a list of alternative correct answers; predictions also carry the shape the answer should take. Every prompt below is answerable from the trace text and the Components-in-scope list that appear above the moment it is attached to.

### Moment `opening-sketch` — attached to Stop 1

*Why this moment is load-bearing (exposition):* this is the one chance to surface the learner's own model of a coordinated upgrade before the trace supplies it. The audience are distributed-systems experts with strong intuitions about snapshots and rolling restarts; making the prediction explicit lets the two genuine surprises land against something — that a freezing node keeps gossiping (Stop 3), and that there is no special upgrade boot path (Stop 5).

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is engaged and willing to commit to a guess, or is showing strong general distributed-systems intuition about coordinated restarts and snapshots.

- `answer_shape`: a phased lifecycle — stop together → save a matching state → restart on new code → rebuild → resume — not a single component handoff.
- Prompt (verbatim): "Before we trace it: the operators want to roll a new software version out to the whole network at once, without losing the ledger and without letting nodes disagree about where they stopped. Using the components I just listed, what's your gut — what does the network have to do, roughly in order, to stop together and come back up on the new code? Rough is fine, and naming phases instead of exact components is fine too; we'll sharpen it as we go."
- `canonical_answer`: "Roughly five phases. (1) Stop together: the nodes agree on one common round to halt at — the freeze round — so nobody stops in a different place. (2) Save a matching state: each node writes a signed snapshot of that freeze round to disk. (3) Restart: an operator swaps in the new binary and restarts each node, which loads that saved state. (4) Rebuild: each node replays its on-disk event log to reconstruct its in-memory hashgraph before it talks to anyone. (5) Resume: gossip and event creation start again, now on the new version. The shape is a coordinated stop → save → restart → rebuild → resume, not one handoff."
- `alternative_correct_answers`:
  - "They pick a common stopping point, everyone snapshots the same state, then each node restarts on the new binary from that snapshot and rejoins. The key is that all nodes stop at the same place so the snapshots match."
  - "Quiesce consensus at an agreed round, save state to disk, shut down, bring up the new version which loads that state, replay whatever is needed to rebuild, and resume gossiping."
  - "Halt the network at one round, take matching signed states, upgrade and restart each node from its state, reconstruct consensus, then start producing events again on the new code."
- `followup` (delivered verbatim when the learner describes restarting on new code but names no shared stopping point): "You've described restarting on new code — now pin down the coordination. What stops two nodes from halting at different rounds and saving states that don't match, and why would that matter for the upgrade?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner would rather be shown the arc than guess at it, or is hesitating to commit.

- Exposition (tutor states, not a question): the upgrade is a coordinated stop-and-restart — the network halts at one agreed round (the freeze round), every node saves a matching signed state, restarts on the new binary and loads that state, replays its event log to rebuild, and resumes.
- Prompt (verbatim): "Here's the shape before we walk it: the network halts at one agreed round, every node saves a matching signed state of that round, each node restarts on the new software and loads that state, rebuilds its in-memory consensus from its on-disk event log, and then resumes gossiping. Quick check before we start: which side decides that an upgrade should happen and when to freeze — the consensus layer itself, or the Execution layer on top of it?"
- `canonical_answer`: "The Execution layer. It writes a freeze time onto the shared state; the consensus layer only reads it back and recognizes when consensus has reached it. Scheduling the upgrade is not the consensus layer's decision."
- `alternative_correct_answers`:
  - "Execution — it sets the freeze time; the consensus layer just reacts to it."
  - "The application / Execution side on top of consensus decides; the consensus layer's job is only to recognize and act on the freeze point it's handed."

### Moment `gossip-continues-mid-freeze` — attached to Stop 3

*Why this moment is load-bearing (exposition):* this is the scenario's conceptual crux. An expert reads "freeze" as "the node goes silent," but a freezing node keeps gossiping — and *why* it does (to circulate the signatures that make the shared freeze state trustworthy) is what makes the rest of the trace, especially the saved-and-signed handoff artifact at Stop 4, read as necessary rather than as busywork.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on gossip and is likely to hold the confidently-wrong "freeze means the node stops talking" intuition.

- `answer_shape`: a yes/no — it keeps gossiping — plus a causal reason grounded in what a signed state needs (peer signatures), not a list of components.
- Confidence elicitation: included, because "a freezing node goes quiet" is a confidently-held wrong intuition here.
- Prompt (verbatim): "Node N has reached the freeze round and stopped creating ordinary new events. Before I show you what happens on the network next: does N now go silent and stop gossiping with its peers, or does it keep talking — and how confident are you? If it keeps gossiping, what would it still have to say?"
- `canonical_answer`: "It keeps gossiping. Stopping event *creation* does not stop *gossip*. The freeze state each node saved becomes trustworthy only once a threshold of peers have signed it, so N keeps syncing with its peers to send its own signature on the freeze state, collect theirs, and relay signatures on to any peer still missing them. The node has gone quiet on new work, but it is still circulating the signatures that turn a local snapshot into one the whole network has vouched for."
- `alternative_correct_answers`:
  - "It keeps gossiping — it still needs to exchange signatures on the freeze state so enough of the network signs the same snapshot; it just isn't making new events anymore."
  - "No, it doesn't go silent. Gossip continues so the freeze-state signatures can be collected and spread; it's creation that stops, not communication."
  - "It keeps talking to finish collecting and distributing the signatures that make the shared freeze state usable; circulating those signatures is the remaining job."
- `followup` (delivered verbatim when the learner answers "keeps gossiping" but grounds it on generic catch-up or new events rather than the freeze-state signatures): "You're right that it keeps gossiping — now pin down what it's actually exchanging. It has stopped making ordinary events, so what specific thing is it still sending and collecting, and why does the saved freeze state need it?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the freeze path and is hesitating rather than guessing; give them the fact and have them reason out the consequence.

- Exposition (tutor states, not a question): a saved signed state is only trustworthy once a threshold of the network's nodes have signed it, and the freeze state is written the instant consensus halts — before all of those signatures have necessarily been gathered.
- Self-explanation prompt (verbatim): "Here's the fact the freeze relies on: a saved state is only trustworthy once a threshold of the network's nodes have signed it, and the freeze state is written the instant consensus halts — before all of those signatures have necessarily arrived. Given that, why can't a node simply stop talking the moment it has saved its freeze state — what would the network be left missing?"
- `canonical_answer`: "If every node went silent at save time, the signatures wouldn't finish circulating, and nodes could be left holding a freeze state that not enough peers have vouched for. So a node keeps gossiping past the freeze round specifically to send its own signature, collect its peers', and relay them onward — completing the network-wide signing of the shared state even though no new events are being made."
- `alternative_correct_answers`:
  - "The signatures that make the state trustworthy are still in flight; going silent would strand nodes with an under-signed snapshot. Gossip continues just to finish spreading those signatures."
  - "It would be missing the peer signatures on the freeze state — the thing that makes the saved snapshot trustworthy — so it keeps gossiping to exchange them."
- `followup` (delivered verbatim when the learner restates the fact instead of drawing the consequence): "That's the fact — now finish the inference: if signing has to happen across the network and the state is already saved, what is the only work left for a node that's done creating events, and on which channel does it happen?"

## Consolidation

(The tutor phrases this in its own words; the substance and the canonical recap are below.)

Bring the trace back together as one arc: Execution sets a **freeze time**; the hashgraph seals one shared **freeze round** and stops; the **event creator** winds down while **gossip** keeps circulating **signatures** on the freeze state; **signed-state management** saves that signed **freeze state** to disk and the node reaches `FREEZE_COMPLETE`; an operator restarts it on the **new software**, which **loads the freeze state**; **PCES replay** rebuilds the hashgraph before the node talks; and the network **resumes**, with new events validated against the **new roster** at the round after the freeze.

Name the things most likely to have differed from a first guess:

- **A freezing node keeps gossiping** (Stop 3). "Freeze" stops new event creation, not communication; the node stays on the wire to circulate the signatures that make the shared freeze state trustworthy.
- **There is no special upgrade boot path** (Stop 5). Restarting on the new version runs the same startup code as any restart; the upgrade is safe because every node saved the identical freeze state at the same round, not because of upgrade-specific logic.
- **Rebuild happens before anyone observes the node** (Stop 6). PCES replay reconstructs consensus to the freeze point before gossip or event creation start — the same persist-/rebuild-before-observe discipline a transaction's journey to consensus already showed.

If the learner ran a prediction move, name explicitly where their prediction matched and where it missed, against the canonical answers above.

## Close-out

**Free-recall summary.**

- Prompt (verbatim): "In your own words, walk the network through a coordinated upgrade from 'an upgrade is scheduled' to 'running again on the new version.' Name where the freeze point comes from, what each node saves and why it keeps gossiping after it stops making events, what the new software boots from, and what a node does before it starts talking again."
- `canonical_answer`: "The Execution layer schedules the upgrade by setting a freeze time on the shared state. The hashgraph seals one common freeze round and stops, so every node halts at the same point. Each node winds down event creation but keeps gossiping to send, collect, and relay signatures on the freeze state — the signatures that make the shared snapshot trustworthy. Signed-state management writes that signed freeze state, with the PCES files needed to replay it, to disk, and the node reaches `FREEZE_COMPLETE`. An external tool restarts the node on the new binary, which loads that saved state — the same startup path as any restart. Before gossiping or creating events, the node replays its PCES to rebuild its in-memory hashgraph. Then gossip and event creation resume; new events take birth rounds past the freeze round and validate against the new roster, while pre-freeze events stay valid against the old one. The upgrade is safe because every node restarts from the identical saved freeze state."
- `alternative_correct_answers`:
  - "Execution sets a freeze time → the hashgraph picks one shared freeze round and halts → nodes stop creating events but keep gossiping to finish signing the freeze state → each saves that signed state to disk → restart on new code loads it → replay PCES to rebuild consensus before talking → resume, with the new roster taking effect after the freeze round. Same state on every node is what makes it safe."
  - "Scheduled from Execution; everyone stops at the same round; save a matching signed state (gossip keeps running to gather its signatures); shut down; the new binary loads that state; rebuild the hashgraph from the on-disk event log; then start gossiping and creating again, on the new roster. No special upgrade path — just an ordinary restart from a coordinated snapshot."

**Successive-relearning tags (exposition):** this orientation scenario establishes no threshold concept, so it adds nothing to the relearning queue. The ideas it gestures at — the freeze-round cutoff and the roster boundary, the signed-state handoff, and PCES rebuild-before-observe — are established and tagged for relearning in their Pass 2 lessons (Cluster D's `d-01-freeze-trigger` … `d-syn-freeze-upgrade-synthesis`, and Cluster C's `c-01-signed-state-lifecycle`, `c-04-pces-write-path`, and `c-05-restart-and-replay`).

## Forward pointers

Each component this scenario sketched is taught in depth later. The spiral: orientation plants the sketch, Pass 2 fills the mechanism, Pass 3 stitches the components back together under stress.

- **The wiring substrate** beneath every handoff above → Cluster 0: `c0-01-schedulers-and-types`, `c0-03-backpressure-and-queue-health`, `c0-04-wiring-model-lifecycle`.
- **The freeze trigger, the freeze-round cutoff, and upgrade startup** (Stops 1–2, 5, 7) → Cluster D: `d-01-freeze-trigger`, `d-02-freeze-time-behaviour`, `d-03-upgrade-startup`, `d-syn-freeze-upgrade-synthesis`.
- **Event-creation gating during the freeze** (Stop 3) → `a4-03-event-creation-rule-and-gates`.
- **Gossip** (Stop 3) → `a3-01-protocol-stack`, `a3-02-three-phase-sync`, `a3-03-simple-broadcast`, `a3-syn-gossip-synthesis`.
- **Signed-state management and the freeze state** (Stop 4) → `c-01-signed-state-lifecycle`, `c-02-reservation-discipline`.
- **The PCES write path and restart replay** (Stops 4, 6) → `c-04-pces-write-path`, `c-05-restart-and-replay`.
- **Birth round and the roster boundary** (Stops 2, 7) → `a1-06-birth-round-and-ancient`.
- **The whole freeze-and-upgrade arc, stitched** → revisited at full depth in `pass3-03-coordinated-network-upgrade-deep`, and stressed further in the edge cases `pass3-edge-01-reconnect-during-freeze`, `pass3-edge-04-pces-replay-after-upgrade`, and `pass3-edge-05-squelching-during-freeze`.

## Open questions

None — at role level the current topic files fully cover this scenario's path. (Two notes for the reviewer, not gaps in this lesson: "freeze round" and the `FREEZING` / `FREEZE_COMPLETE` statuses are used by the topic files but are not yet standalone `glossary.md` entries, so this scenario describes them in place rather than linking them; and the freeze-time signing the trace describes covers signatures on the freeze *state* — the freeze *block* signatures the same gossip also carries are deliberately left below this orientation altitude, with the distinction deferred to Cluster D's Pass 2 lessons.)
