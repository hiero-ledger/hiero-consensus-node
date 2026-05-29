---
id: pass1-02-node-falls-behind
cluster: pass1
title: "A node falls behind and reconnects"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/gossip.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/reasons-not-to-gossip.md
  - architecture/topics/reconnect.md
  - architecture/topics/signed-state-management.md
  - architecture/topics/event-intake.md
  - architecture/topics/wiring-framework.md
kb_concepts:
  - concepts/event-lifecycle.md
kb_glossary_terms:
  - Gossip
  - Sync
  - Broadcast
  - Health monitor
  - Backpressure
  - Lagging behind
  - Fallen behind
  - Event window
  - Expired
  - Reconnect
  - Signed state
  - Platform status
  - Self-event
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the components involved when a node falls behind and recovers — gossip, the health monitor and backpressure, the reasons-not-to-gossip guards, reconnect, and signed-state management — and state each one's role in a single sentence.
  - Distinguish lagging behind (recoverable by gossip) from fallen behind (recoverable only by reconnect), and explain that the dividing line is whether the events the node still needs continue to exist on its peers.
  - Describe, at role level, the recovery arc from first strain to resumed consensus: self-throttling, peer-driven fallen-behind detection, the gossip halt, the signed-state transfer, and re-anchored resumption.
threshold_concepts: []
estimated_session_minutes: 45
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# A node falls behind and reconnects

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. This scenario stands on its own at role level and assumes no specific prior lesson.

## Components in scope

This scenario follows one node — call it **node N** — as it slips off the pace of the network and recovers. It touches five components plus the substrate that connects them. Each is named here with a one-sentence role so the vocabulary is set before the trace begins; the depth on each lives in the Pass 2 lessons named under [Forward pointers](#forward-pointers).

- **Gossip** — exchanges events with peers: it [broadcasts](../../glossary.md#broadcast) this node's fresh self-events and runs a [sync](../../glossary.md#sync) protocol to reconcile what each side is missing — and it can only ever hand a peer the events that peer still holds. See [gossip.md](../../architecture/topics/gossip.md).
- **Health monitor and backpressure** — the [health monitor](../../glossary.md#health-monitor) watches the depth of every component's work queue and publishes a single "unhealthy" signal; independent reaction sites read that signal and throttle themselves ([backpressure](../../glossary.md#backpressure)). It detects; they react. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).
- **The gossip guard catalog ("reasons not to gossip")** — the set of rules that decide when a node reduces or stops gossiping, ranging from graded backpressure under load to a hard halt once the node must reconnect. See [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md).
- **Reconnect** — the recovery path for a node that has [fallen behind](../../glossary.md#fallen-behind): it detects the condition from peer reports, halts gossip, and learns a recent [signed state](../../glossary.md#signed-state) from a healthy peer rather than catching up event by event. See [reconnect.md](../../architecture/topics/reconnect.md).
- **Signed state management** — produces the periodic, signed snapshots of the ledger; the latest fully-signed snapshot is what a reconnecting node receives from its peer. See [signed-state-management.md](../../architecture/topics/signed-state-management.md).

Two pieces of background plumbing matter here too. The work queues the health monitor watches belong to the **wiring substrate** that routes every component's output to the next; at orientation, treat it as the pipes between the boxes (it is taught first in Pass 2's Cluster 0). See [wiring-framework.md](../../architecture/topics/wiring-framework.md). And the node's [platform status](../../glossary.md#platform-status) — `ACTIVE`, `BEHIND`, `CHECKING`, and the like — is the lifecycle flag several of these rules consult to decide whether gossiping is even allowed.

## Scenario setup

One node in a healthy, running network — **node N** — starts in the `ACTIVE` [platform status](../../glossary.md#platform-status), gossiping normally with its peers and keeping its hashgraph in step with theirs. Then N hits trouble: a long garbage-collection pause, a slow disk, or a burst of load means it begins handling events more slowly than they arrive. Its internal queues start to grow.

Nothing here is a crash or a corruption — N is up and running the whole time. We are watching one node slide from "keeping pace" to "behind," and then back. Hold node N, its growing queues, and its still-healthy peers in mind as the starting state.

## Trace

The spine of the scenario, told at role level. Each stop names the component, links its topic file, and says what it does and what it hands on. There are no code anchors here — this is the map, not the mechanism. Two stops are marked as engagement-move **moments**; the moves themselves are in [Engagement moves](#engagement-moves) below.

### Stop 1 — Steady state: gossip keeps N in step with the network · `moment: opening-arc`

In normal operation node N is `ACTIVE` and gossiping with its peers. Gossip runs two ways at once: N [broadcasts](../../glossary.md#broadcast) its own fresh self-events to peers, and through [sync](../../glossary.md#sync) it and each peer compare what they hold and send each other whatever the other is missing. Because every node is doing this continuously, N's hashgraph comes to hold the same events as the rest of the network — which is what lets independent nodes reach the same consensus order without a coordinator. One detail to hold on to: sync can only ever hand over events a peer **still holds**. See [gossip.md](../../architecture/topics/gossip.md).

> **Load-bearing (exposition):** gossip is a *peer-to-peer exchange of events that peers still hold*. N stays current only as long as it can keep pulling in and processing what its peers send it. The whole scenario is about what happens when it can't.

### Stop 2 — N slows down, and the health monitor raises a flag

Something makes N fall off the pace, and it starts handling events slower than they arrive, so its work queues grow. The [health monitor](../../glossary.md#health-monitor) — which continuously watches the depth of every component's work queue (those queues live on the [wiring substrate](../../architecture/topics/wiring-framework.md)) — notices a queue staying over its limit and publishes a rising "unhealthy" signal. Crucially, the health monitor is only a *detector*: it raises the flag, but it does not itself slow anything down or block anything. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).

> **Load-bearing (exposition):** the health signal is an early warning, decoupled from any reaction. *Who* reacts to it, and *how*, is a separate decision made independently at each reaction site — which is the next stop.

### Stop 3 — N throttles itself — for now it is only *lagging*

The reaction sites read the health signal independently and back off: N pauses minting new [self-events](../../glossary.md#self-event) and eases off on how fast it pulls in and processes its peers' events, so its backlog can drain. (It keeps *sending* the events it has already made, so the rest of the network can keep building on them.) This is graceful self-throttling — [backpressure](../../glossary.md#backpressure) — and it is the system's first line of defense, catalogued among the [reasons not to gossip](../../architecture/topics/reasons-not-to-gossip.md). As long as the events N missed are **still held by its peers**, throttling is enough: N drains its backlog and catches back up through ordinary sync. A node in this state is [lagging behind](../../glossary.md#lagging-behind) — behind, but recoverable by gossip. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).

> **Load-bearing (exposition):** self-throttling is recovery *without leaving gossip* — it just buys the pipeline time to drain, and it works precisely while the events N needs still exist on its peers. Hold that condition; the next stop is what happens when it stops being true.

### Stop 4 — From lagging to fallen behind · `moment: why-not-gossip-harder`

Suppose N cannot drain in time — it was down too long, or the load persists. The network keeps deciding rounds and moving on, and the events N still needs grow old. Peers keep each event only for a bounded window; once an event ages past that window it is [expired](../../glossary.md#expired) and dropped from the peer's gossip window. The moment N needs events that no peer keeps anymore, sync has nothing left to give it — gossip cannot close the gap no matter how hard N tries. N has crossed the line from [lagging behind](../../glossary.md#lagging-behind) to [fallen behind](../../glossary.md#fallen-behind). See the [event lifecycle](../../concepts/event-lifecycle.md) for how events go ancient and then expired, and [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md) for why a far-behind node stops gossiping.

> **Load-bearing (exposition):** this is the pivot of the whole scenario. The symptom is identical on both sides — "behind the network" — but the recovery is completely different, and the line between them is one fact: do the events N still needs continue to exist on its peers?

### Stop 5 — Peers vote N behind, and its status flips

N does not diagnose this itself. As peers sync with N, they see how far back its [event window](../../glossary.md#event-window) is, and each peer that judges N to be behind reports it. N's fallen-behind monitor tallies those reports, and once a configured fraction of peers agree, it flips N's [platform status](../../glossary.md#platform-status) to `BEHIND`. Detection is a quorum of peer observations, not a local decision. See [reconnect.md](../../architecture/topics/reconnect.md).

> **Load-bearing (exposition):** "behind" is a conclusion the *network* reaches about N, not something N notices about itself first. This peer-driven detection is one of the parts most likely to differ from a first guess.

### Stop 6 — Gossip halts, and N learns a state from a healthy peer

Once N is `BEHIND`, it halts gossip — it stops starting and accepting syncs and lets any in flight drain — because, as Stop 4 established, more gossip cannot help it. N then acts as a *learner*: it offers the reconnect protocol to its peers, and the first peer that qualifies as a *teacher* — one that is `ACTIVE`, is not itself behind, and holds a recent fully-signed state — sends N that [signed state](../../glossary.md#signed-state). The transfer rides the very same peer connection gossip was already using. Before trusting the state, N checks that it carries a quorum of node signatures. See [reconnect.md](../../architecture/topics/reconnect.md) and [signed-state-management.md](../../architecture/topics/signed-state-management.md) for what a signed state is.

> **Load-bearing (exposition):** reconnect transfers a *whole recent state*, not a replay of the missed events. N jumps forward to a point a quorum of the network already vouched for, instead of trying to re-walk the history it lost.

### Stop 7 — N re-anchors to the new state and resumes

With a validated signed state in hand, N re-initializes every part of itself that tracks the current round — its hashgraph, its [event intake](../../architecture/topics/event-intake.md) pipeline, its gossip window — so they all start from the state's round rather than the stale position N was stuck at. Its [platform status](../../glossary.md#platform-status) then walks back up: from `BEHIND` to `RECONNECT_COMPLETE`, and then — once the new state is safely written to disk — to `CHECKING`, where N is allowed to create events again. Gossip resumes as soon as N's status is no longer `BEHIND`, and N returns to `ACTIVE` once its own new events are reaching consensus again. See [reconnect.md](../../architecture/topics/reconnect.md).

> **Load-bearing (exposition):** there is no separate "caught up!" message — being back to `ACTIVE`, creating events that reach consensus, *is* the recovered state. The journey ends where it began: N gossiping in step with the network.

## Engagement moves

Two moments along the trace carry a move inventory. The tutor selects exactly one move per moment by matching the diagnosis tag to what the learner has shown, and delivers the chosen prompt verbatim. Each answer-eliciting move supplies its canonical answer and a list of alternative correct answers; predictions also carry the shape the answer should take. Every prompt below is answerable from the trace text and the Components-in-scope list that appear above the moment it is attached to.

### Moment `opening-arc` — attached to Stop 1

*Why this moment is load-bearing (exposition):* this is the one chance to surface the learner's own model of failure-and-recovery before the trace supplies it. The audience are distributed-systems experts with strong intuitions about catch-up and snapshot recovery; making the prediction explicit lets the two genuine surprises land against something — that detection is peer-driven (Stop 5), and that *lagging* and *fallen behind* are two different regimes (Stop 4), not one severity scale.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is engaged and willing to commit to a guess, or is showing strong general distributed-systems intuition about failure recovery.

- `answer_shape`: a two-regime recovery arc — a graceful self-throttle phase, and, if that is not enough, a heavier state-transfer fallback — not a single linear pipeline.
- Prompt (verbatim): "Before we trace it: node N is healthy and gossiping, and then it starts processing events slower than they're arriving — it's beginning to fall behind. Using the components I just listed, what's your gut: what does the system do to cope, and can N recover on its own, or does it eventually need something from a peer? Rough is fine, and naming two different cases is fine too; we'll sharpen it as we go."
- `canonical_answer`: "Two regimes. First, N notices the strain and throttles itself — it pauses creating new self-events and eases off pulling in peers' events so its backlog can drain; if the slowdown is brief, gossip lets it catch back up and that is the end of it (it was only *lagging*). But if N falls far enough behind that the events it still needs have aged out on its peers, gossip can no longer help, and N has to *reconnect*: it gets a recent signed state from a healthy peer and resumes from there. So: self-throttle first, reconnect as the heavier fallback."
- `alternative_correct_answers`:
  - "It backs off (backpressure) to drain its queues and catches up by gossip if it can; if it's too far gone it pulls a whole recent state from a peer and resumes from that instead."
  - "Mild case: throttle and re-sync. Severe case: it can't sync the gap, so it learns a signed state from a healthy peer — a reconnect — rather than replaying every missed event."
  - "First it slows itself down and tries to catch up peer-to-peer; if the gap is too big it gives up on event-by-event catch-up and has a recent signed state transferred from a peer."
- `followup` (delivered verbatim when the learner names only one regime — only throttling, or only reconnect — without distinguishing them): "You've named one way N copes — now split it in two: is there a case where slowing down and re-syncing is enough, and a separate case where that can't work and N needs more? What single fact decides which case N is in?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner would rather be shown the arc than guess at it, or is hesitating to commit.

- Exposition (tutor states, not a question): the recovery has two gears — a graceful self-throttle that handles short slowdowns by catching up over gossip, and a heavier reconnect (learning a whole signed state from a peer) when N has fallen too far behind for gossip to help.
- Prompt (verbatim): "Here's the shape before we walk it: when N starts falling behind it first throttles itself to drain its backlog and catch up over gossip; if it has fallen too far behind for that, it reconnects — it learns a recent signed state from a healthy peer instead. Quick check: in the mild case, which channel does N use to catch back up — the same gossip it was already using, or something new?"
- `canonical_answer`: "The same gossip — sync (and broadcast) with its peers is the catch-up channel. Throttling just gives the pipeline room to drain so gossip can close a small gap."
- `alternative_correct_answers`:
  - "Gossip — the normal sync protocol it's already running; it just needs to pull in the events it missed."
  - "The ordinary peer-to-peer gossip it always uses; reconnect is only for when that can't work."

### Moment `why-not-gossip-harder` — attached to Stop 4

*Why this moment is load-bearing (exposition):* this is the scenario's conceptual pivot — the line between *lagging* (recoverable by gossip) and *fallen behind* (recoverable only by reconnect). An expert's first instinct is that any peer-to-peer gap closes if you just sync more aggressively; the codebase says no, because peers do not keep events forever. Landing this distinction is what makes the rest of the trace — the gossip halt, the whole-state transfer — read as necessary rather than as overkill.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on gossip and is likely to hold the confidently-wrong "just sync harder and it'll catch up" intuition.

- `answer_shape`: a causal "why-not" — gossip can only supply events peers still hold, the events N needs no longer exist on peers, therefore a different kind of recovery is required.
- Confidence elicitation: included, because "just sync harder" is a confidently-held wrong intuition here.
- Prompt (verbatim): "N has now been behind long enough that the network has moved well past where N is. Before I show you what happens next: can N still close the gap just by syncing harder with its peers — and how confident are you? If it can't, what's stopping it?"
- `canonical_answer`: "No. Sync can only hand N events that a peer still holds, and peers don't keep events forever — once an event is old enough it's dropped (it has *expired*) from their gossip windows. A node this far behind needs events that no peer keeps anymore, so there is simply nothing left to send it. That's the line between *lagging behind* (the missing events still exist on peers, so gossip recovers it) and *fallen behind* (the missing events have expired, so gossip cannot). N has fallen behind, so it needs a recent signed state instead — a reconnect."
- `alternative_correct_answers`:
  - "No — the events it's missing have aged out of its peers' retained windows, so there's nothing left to sync; it has to get a whole recent state instead."
  - "It can't: gossip only moves events peers still have, and N is past the retention window, so a peer-to-peer catch-up has no data to transfer. It needs a signed state."
  - "No, because peers prune old events; once N needs events older than anything peers keep, syncing has nothing to offer and reconnect is the only path."
- `followup` (delivered verbatim when the learner answers "no" but grounds it on speed or volume rather than retention): "You're right that it can't — but pin down *why* precisely. Is the problem that there are too many events to sync, or that some of the events N needs are no longer held by any peer at all? And what does that imply about how N has to recover?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the lagging/fallen-behind distinction and is hesitating rather than guessing; walk the retention fact and have them reason out the consequence.

- Exposition (tutor states, not a question): peers keep each event only for a bounded window — an old event is dropped from the gossip window once it ages past it — and sync can only ever send an event a peer still holds.
- Self-explanation prompt (verbatim): "Here's the fact the code relies on: a peer keeps each event only for a limited window and drops it once it's old enough, and sync can only send events a peer still holds. Given that, what goes wrong for a node that has fallen far enough behind to need events older than that window — and what kind of recovery would it need instead of more syncing?"
- `canonical_answer`: "The events it needs are no longer on any peer to send, so syncing can't close the gap no matter how often it runs. N has to recover by getting a whole recent signed state from a peer — a reconnect — rather than catching up event by event."
- `alternative_correct_answers`:
  - "There's nothing left to sync — the data aged out — so it must jump forward to a recent state wholesale instead of replaying missed events."
  - "Gossip is useless past the retention window, so the node needs a state transfer (reconnect), not more event exchange."
- `followup` (delivered verbatim when the learner restates the retention fact instead of drawing the consequence): "That's the fact — now finish the inference: if no peer still holds the events N needs, what does that make impossible, and what is the only way left for N to get current?"

## Consolidation

(The tutor phrases this in its own words; the substance and the canonical recap are below.)

Bring the trace back together as one arc: N gossips in step with the network; it strains and **throttles itself** to drain its backlog — still only *lagging*, and recoverable by gossip; it tips into *fallen behind* once the events it needs have **expired** on its peers; its peers **vote it behind** and its status flips; it **halts gossip** and **reconnects**, learning a recent **signed state** from a healthy teacher; it **re-anchors** to that state and resumes, ending where it began.

Name the three things most likely to have differed from a first guess:

- **Detection is peer-driven, not self-diagnosed** (Stop 5). N does not decide on its own that it has fallen behind; its peers observe it during sync and report it, and a threshold of agreement is what flips N's status to `BEHIND`.
- **Lagging and fallen behind are different regimes, not points on one severity scale** (Stop 4). The same symptom — "behind the network" — gets two completely different responses, and which one applies turns on a single fact: whether the events N still needs continue to exist on its peers.
- **Reconnect transfers a whole state, not a replay** (Stops 6–7). N does not re-fetch and re-process every missed event; it jumps forward to a recent signed state a peer already holds, validates it, and resumes from there.

If the learner ran a prediction move, name explicitly where their prediction matched and where it missed, against the canonical answers above.

## Close-out

**Free-recall summary.**

- Prompt (verbatim): "In your own words, walk node N from 'healthy and gossiping' all the way to 'fallen behind and recovered.' Name what N does first when it starts to strain, the moment its situation stops being recoverable by gossip and why, how it figures out it's behind, and how it finally gets back to current."
- `canonical_answer`: "N gossips normally with peers (sync and broadcast). When it starts falling behind on processing, it throttles itself — pausing new self-events and easing off inbound events — to drain its backlog; if the slowdown is short, gossip closes the gap and it was only *lagging*. If it falls far enough behind that the events it needs have *expired* on its peers, gossip can't help. Its peers, syncing with it, notice it's missing too much and report it behind; once enough of them agree, N's status flips to `BEHIND`, and it halts gossip. N then reconnects: a healthy peer (the teacher) sends it a recent *signed state*, which N validates and loads, re-anchoring its hashgraph, intake, and gossip window to the new round; its status walks back up to `ACTIVE` and gossip resumes. The load-bearing line: lagging is recoverable by gossip, fallen-behind is not — the difference is whether the events N needs still exist on its peers."
- `alternative_correct_answers`:
  - "Gossip normally → strain → self-throttle and try to re-sync (lagging) → if the needed events have aged out on peers, gossip can't help (fallen behind) → peers vote it behind → halt gossip → learn a recent signed state from a teacher (reconnect) → re-anchor and resume. The catch: peers must still hold the events for gossip to recover you."
  - "Healthy → backpressure when overloaded → catches up by sync if it can → otherwise it's fallen behind because peers expired the events it needs → detected by peer reports → gossip stops → reconnect transfers a whole signed state → node re-initializes and rejoins. Lagging vs fallen-behind is the whole point."

**Successive-relearning tags (exposition):** this orientation scenario establishes no threshold concept, so it adds nothing to the relearning queue. The two distinctions it gestures at — the lagging-vs-fallen-behind regime line, and peer-driven (rather than self-diagnosed) fallen-behind detection — are established and tagged for relearning in their Pass 2 lessons (Cluster B's `b-03-reasons-not-to-gossip` and Cluster C's `c-06-reconnect`).

## Forward pointers

Each component this scenario sketched is taught in depth later. The spiral: orientation plants the sketch, Pass 2 fills the mechanism, Pass 3 stitches the components back together under stress.

- **The wiring substrate** beneath the queues the health monitor watches → Cluster 0: `c0-01-schedulers-and-types`, `c0-03-backpressure-and-queue-health`, `c0-04-wiring-model-lifecycle`.
- **Gossip** (Stops 1, 3–7) → `a3-01-protocol-stack`, `a3-02-three-phase-sync`, `a3-03-simple-broadcast`, `a3-04-fair-sync-selector`, `a3-syn-gossip-synthesis`.
- **Health monitor and backpressure** (Stops 2–3) → `b-01-health-monitor-detection`, `b-02-reaction-sites`, `b-04-backpressure-under-load`, `b-syn-health-synthesis`.
- **The reasons-not-to-gossip catalog** (Stops 3, 6) → `b-03-reasons-not-to-gossip`.
- **The event lifecycle / ancient-then-expired** (Stop 4) → `a1-06-birth-round-and-ancient`, `a1-07-stale-events`.
- **Reconnect** (Stops 5–7) → `c-06-reconnect`, and the cluster synthesis `c-syn-state-lifecycle-synthesis`.
- **Signed state management** (Stop 6) → `c-01-signed-state-lifecycle`, `c-02-reservation-discipline`.
- **The whole fall-behind-and-recover arc, stitched** → revisited at full depth in `pass3-02-node-falls-behind-deep`, and stressed further in the edge cases `pass3-edge-01-reconnect-during-freeze` and `pass3-edge-02-fall-behind-during-heavy-gossip`.

## Open questions

None — at role level the current topic files fully cover this scenario's recovery path. (Structural details below this orientation altitude — the exact fallen-behind threshold proportion, the precise health-driven throttle behaviour under the default keep-sending-while-unhealthy setting, and the exact signal on which gossip un-halts after reconnect — are left to the Pass 2 lessons in Clusters B and C that need them.)
