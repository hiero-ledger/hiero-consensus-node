---
id: pass1-04-event-creation-under-stress
cluster: pass1
title: "Event creation under stress"
pass: 1
depth: orientation
prerequisites: []
kb_topics_touched:
  - architecture/topics/event-creator.md
  - architecture/topics/health-monitor-and-backpressure.md
  - architecture/topics/reasons-not-to-gossip.md
  - architecture/topics/restart-and-pces.md
  - architecture/topics/event-intake.md
  - architecture/topics/gossip.md
  - architecture/topics/quiescence.md
  - architecture/topics/wiring-framework.md
kb_concepts:
  - concepts/event-lifecycle.md
  - concepts/stale-events.md
kb_glossary_terms:
  - Self-event
  - Event creator
  - Tipset
  - Other-parent
  - Platform status
  - Health monitor
  - Backpressure
  - Quiescence
  - PCES
  - Stale
  - Expired
  - Event window
  - Broadcast
  - Sync
kb_invariants: []
kb_deltas: []
kb_scenarios: []
learning_objectives:
  - Name the gate chain the event creator consults before authoring a self-event — rate cap, platform status, health, sync lag, and quiescence — and state that the health gate is the one that reacts to system load.
  - Explain, at role level, that the health monitor is a single detector whose unhealthy-duration signal fans out to several independent reaction sites — event-creation pause, gossip throttling, transaction-acceptance throttling, and PCES-replay pacing — rather than a controller that stops the node.
  - Describe why, under load, a node keeps sending its own self-events while it stops taking in peers' events, so its pending user transactions drain toward consensus instead of going stale.
threshold_concepts: []
estimated_session_minutes: 45
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Event creation under stress

## Prerequisites

> None — orientation scenario. Assumes only the high-level Hedera familiarity that the audience brings in.

## Incoming retrieval probes

None — orientation scenario, no incoming probes. This scenario stands on its own at role level and assumes no specific prior lesson; where it leans on an idea an earlier orientation scenario sketched (persisting a self-event before sharing it), it re-introduces it in place.

## Components in scope

This scenario follows one node — call it **node N** — at the moment it must decide whether to author a new [self-event](../../glossary.md#self-event) while the system is under load. It touches four components plus the substrate that connects them. Each is named here with a one-sentence role so the vocabulary is set before the trace begins; the depth on each lives in the Pass 2 lessons named under [Forward pointers](#forward-pointers).

- **Event creator** — decides when this node authors a [self-event](../../glossary.md#self-event), and before it builds anything it runs the candidate through a chain of independent permission gates; only if every gate agrees does its [tipset](../../glossary.md#tipset) algorithm pick the [other-parents](../../glossary.md#other-parent) that best advance consensus, fill the event, sign it, and emit it. See [event-creator.md](../../architecture/topics/event-creator.md).
- **The event-creation gates** — a chain of independent vetoes the creator consults first: a rate cap, platform status, health, sync lag, and quiescence. Any one of them can block creation, and the chain permits an event only when all agree. See [event-creator.md](../../architecture/topics/event-creator.md) (the "Permission gates" boundary).
- **Health monitor and backpressure** — the [health monitor](../../glossary.md#health-monitor) watches the depth of every component's work queue and publishes one rising "unhealthy-duration" signal; it is a *detector*, not an enforcer. Independent reaction sites — event creation, gossip, transaction acceptance, and PCES replay — read that signal and throttle themselves ([backpressure](../../glossary.md#backpressure)). It detects; they react. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).
- **The gossip guard catalog ("reasons not to gossip")** — the set of rules that decide when a node reduces or stops gossiping, including the load-driven throttle and the rule that a self-event must be persisted before it is ever shared. See [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md).
- **PCES (the durability waypoint)** — writes each validated event to disk before any other component observes it; its on-disk write queue is one of the queues the health monitor watches. See [restart-and-pces.md](../../architecture/topics/restart-and-pces.md).

Two pieces of background plumbing matter here too. The work queues the health monitor watches belong to the **wiring substrate** that routes every component's output to the next; at orientation, treat it as the pipes between the boxes (it is taught first in Pass 2's Cluster 0). See [wiring-framework.md](../../architecture/topics/wiring-framework.md). And the node's [platform status](../../glossary.md#platform-status) — `ACTIVE`, `CHECKING`, `FREEZING`, and the like — is the lifecycle flag several of these gates consult to decide whether creating events is even allowed.

## Scenario setup

One node in a healthy, running network — **node N** — starts in the `ACTIVE` [platform status](../../glossary.md#platform-status), gossiping normally with its peers. A client has handed N some transactions, and they sit pending, waiting for N to pack them into its next [self-event](../../glossary.md#self-event). But N is also under load right now: a burst of traffic or a slow disk means it is processing events slower than they arrive, and its internal queues — event intake, and the [PCES](../../glossary.md#pces) write queue behind it — are filling.

Nothing here is a crash or a corruption — N is up and running the whole time. We are watching one node at the *decision point*: should it mint a new self-event while it is already struggling to keep up, and how does the system keep N useful through the squeeze without making the overload worse? Hold node N, its pending transactions, and its growing queues in mind as the starting state.

## Trace

The spine of the scenario, told at role level. Each stop names the component, links its topic file, and says what it does and what it hands on. There are no code anchors here — this is the map, not the mechanism. Two stops are marked as engagement-move **moments**; the moves themselves are in [Engagement moves](#engagement-moves) below.

### Stop 1 — N has transactions pending and reaches the decision to create a self-event · `moment: opening-decision`

A client's transactions are sitting pending, and node N's [event creator](../../glossary.md#event-creator) reaches the moment where it would normally build a new [self-event](../../glossary.md#self-event) to carry them toward consensus. But creating an event is not automatic. Before the [tipset](../../glossary.md#tipset) algorithm ever runs — the part that picks parents and shapes the event — the event creator asks a chain of independent permission gates whether creation is allowed right now. The decision *whether* to make an event comes before the decision of *what* the event should look like. See [event-creator.md](../../architecture/topics/event-creator.md).

> **Load-bearing (exposition):** event creation is gated, not reflexive. The whole scenario turns on the gates that can answer "not now" — most of all the one that watches system load — so hold the two questions apart: first *whether* to create, then *what* to create.

### Stop 2 — The gate chain: five independent vetoes, any one blocks

The event creator consults a chain of independent rules, and the event is created only if *every* gate agrees. At role level they are: a **rate cap** (an upper bound on how fast N mints events), a **platform-status** gate (creation only in run states like `ACTIVE` and `CHECKING`, with a narrow allowance during `FREEZING` to flush outstanding signatures), a **health** gate (blocks creation while N has been unhealthy for too long), a **sync-lag** gate (blocks if N trails its peers by too many rounds, because events it makes while far behind would likely go [stale](../../glossary.md#stale) before reaching consensus), and a **quiescence** gate (an opt-in idle pause `[TBD: quiescence.md is a stub; gate behaviour and default unconfirmed]`). Each guards a different concern; under load, the one that fires is the health gate. See [event-creator.md](../../architecture/topics/event-creator.md) and [quiescence.md](../../architecture/topics/quiescence.md).

> **Load-bearing (exposition):** the gates are independent vetoes, not a priority list — any single one blocks creation, and they cover different reasons. The health gate is the one that reacts to *load*; the others react to rate, lifecycle, lag, or idleness.

### Stop 3 — When the gates pass, tipset advancement picks parents — and may still decline

If every gate allows creation, the event creator's tipset algorithm chooses which peers' recent events to use as [other-parents](../../glossary.md#other-parent), favouring the ones that most advance consensus, fills the event with pending transactions, signs it, and emits it. One subtlety to hold lightly: if no available parent would actually advance consensus — for example when too much of the network is unreachable and the algorithm's advancement baseline has frozen — the event creator declines to create an event even though the gates passed. Passing the gates is necessary, not sufficient. See [event-creator.md](../../architecture/topics/event-creator.md).

> **Load-bearing (exposition):** two different things can stop an event — a gate vetoing creation (Stop 2), and the advancement algorithm finding no parent worth building on. This scenario is about the first; the second is named only so the picture is complete.

### Stop 4 — N is overloaded, and the health monitor raises one rising signal · `moment: health-fans-out`

N's load does not ease, so its work queues — [event intake](../../architecture/topics/event-intake.md), and the [PCES](../../glossary.md#pces) write queue behind it — stay over their limits. The [health monitor](../../glossary.md#health-monitor) continuously watches the depth of every component's queue (those queues live on the [wiring substrate](../../architecture/topics/wiring-framework.md)) and publishes a single rising "unhealthy-duration" signal: how long the worst queue has been continuously over capacity. Crucially the health monitor is only a *detector* — it raises the number, but it does not itself stop creation, throttle gossip, or block anything. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).

> **Load-bearing (exposition):** there is one detector and one signal. What that signal *causes* is decided separately and independently at each place that reads it — which is the next stop, and the reason this is a prediction moment.

### Stop 5 — The signal fans out: the health gate pauses creation (and other sites react too)

Several reaction sites read the same unhealthy-duration signal independently, each with its own threshold. At the event creator, the health gate from Stop 2 closes once N has been unhealthy past a short configured duration, so N stops minting new self-events — giving the slow downstream queues room to drain. In parallel, the other sites back off too: the transaction-acceptance path starts refusing new application transactions, and (on a restarting node) [PCES](../../glossary.md#pces) replay paces itself against the same signal. No site waits for the others, and the health monitor coordinates none of it. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md) and [event-creator.md](../../architecture/topics/event-creator.md).

> **Load-bearing (exposition):** the pause on event creation is one reaction among several to a shared signal, not a command from the health monitor. Same signal, several independent responses — the shape is a fan-out from one detector, not a chain of control.

### Stop 6 — Gossip throttles too — but N keeps *sending* while it stops *receiving*

[Gossip](../../architecture/topics/gossip.md) is one of those reaction sites, and under sustained load N's gossip throttles back — with an important asymmetry. By default N keeps **sending** its own self-events to peers while it stops **receiving and processing** peers' events. Sending its events lets the rest of the network keep building on them, which is what keeps N's pending user transactions from going [stale](../../glossary.md#stale) and [expiring](../../glossary.md#expired) before they reach consensus; pausing inbound is what lets N's queues actually drain. This sits alongside the other [reasons not to gossip](../../architecture/topics/reasons-not-to-gossip.md) that apply under stress — a self-event must be durably persisted in [PCES](../../glossary.md#pces) before it is shared at all, gossip halts entirely if N falls far enough behind to need a reconnect, and some platform statuses disallow [sync](../../glossary.md#sync) outright `[TBD: full "reasons not to gossip" catalog not yet enumerated — see brief.md]`. See [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md) and [reasons-not-to-gossip.md](../../architecture/topics/reasons-not-to-gossip.md).

> **Load-bearing (exposition):** "throttle gossip under load" is not symmetric. Keep [broadcasting](../../glossary.md#broadcast) your own events so your transactions don't go stale; stop taking peers' events in so your queues can drain. That asymmetry is the part most likely to differ from a first guess.

### Stop 7 — Queues drain, the gates reopen, and N resumes

As inbound pressure eases, N's queues drain, the unhealthy-duration signal falls back below the thresholds, and each reaction site reverses on its own. The health gate reopens, so the event creator resumes minting self-events; gossip permits are restored gradually, so a brief recovery does not immediately re-overload N; and transaction acceptance reopens. N is back to authoring events that carry its pending transactions toward consensus — ending where it began, but having stayed safe and useful throughout the squeeze. See [event-creator.md](../../architecture/topics/event-creator.md) and [health-monitor-and-backpressure.md](../../architecture/topics/health-monitor-and-backpressure.md).

> **Load-bearing (exposition):** recovery is not a switch the health monitor throws — it is each site independently noticing the signal fall and easing off its own throttle. There is no "all clear" message; resumed event creation that reaches consensus *is* the recovered state.

## Engagement moves

Two moments along the trace carry a move inventory. The tutor selects exactly one move per moment by matching the diagnosis tag to what the learner has shown, and delivers the chosen prompt verbatim. Each answer-eliciting move supplies its canonical answer and a list of alternative correct answers; predictions also carry the shape the answer should take. Every prompt below is answerable from the trace text and the Components-in-scope list that appear above the moment it is attached to.

### Moment `opening-decision` — attached to Stop 1

*Why this moment is load-bearing (exposition):* this is the one chance to surface the learner's own model of "when does a node decide to make an event?" before the trace supplies it. The audience are distributed-systems experts who will reach for "you create an event whenever you have transactions and a parent"; making that explicit lets the genuine surprise — that creation is gated, and that under load a health gate deliberately suppresses it (Stops 2, 5) — land against something.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is engaged and willing to commit to a guess, or is showing strong general distributed-systems intuition about when a node should produce work.

- `answer_shape`: a set of independent gating conditions (an unordered list of checks that must all pass), with at least one of them tied to system load — not a single trigger, and not an ordered sequence.
- Prompt (verbatim): "Before we trace it: node N has user transactions waiting, and it's the kind of moment where N would normally build one of its own events to carry them. But N is also under load right now — its queues are filling. Using the components I just listed, what's your gut: should N go ahead and make the event, and what conditions would you check before letting it? Rough is fine, and naming a few separate checks rather than one rule is fine too; we'll sharpen it as we go."
- `canonical_answer`: "Creation isn't automatic — the event creator checks a set of independent conditions first, and makes the event only if all of them allow it. Several conditions guard different concerns: how fast N is already minting events, whether N's lifecycle status permits it, whether N is keeping up or lagging its peers, and whether the node has been told to stay idle. The one that matters here is health: because N is under load, a health-driven check can say 'not now' and hold off creation until N's queues drain, so N doesn't pile more work on a system that's already behind."
- `alternative_correct_answers`:
  - "Don't create unconditionally — gate it. Under load especially, N should back off and not add a fresh event while it's already struggling to keep up; let the backlog drain first."
  - "Check a few preconditions (status is right, N isn't already too far behind, N isn't overloaded), and build the event only if they all pass; the overload check is the one that fires here."
  - "It depends on N's health and lifecycle state, not just on having transactions — if N is overloaded it should pause creating its own events even though transactions are waiting."
- `followup` (delivered verbatim when the learner says "create whenever there are transactions" with no gating, or names gates but not the load/health one): "You've said when N *would* create — now turn it around. Given that N is overloaded right this moment, name one reason the system might *stop* N from creating the event even though transactions are waiting. What would adding another event cost a node that's already behind?"

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner would rather be shown the decision than guess at it, or is hesitating to commit.

- Exposition (tutor states, not a question): before the event creator builds anything, it runs a chain of independent permission gates — rate cap, platform status, health, sync lag, quiescence — and creates an event only if every gate agrees; under load the health gate is the one that fires.
- Prompt (verbatim): "Here's the shape before we walk it: when N has transactions pending, the event creator doesn't just build an event — it first asks a chain of independent gates whether creation is allowed, and only proceeds if they all say yes. Quick check before we start: we said N is under heavy load right now. Of the things a node might check before creating an event, which *kind* of check is the one that would react to that load — something about N's rate, its lifecycle status, or its health?"
- `canonical_answer`: "Its health — the gate that reacts to how overloaded N is (how long its queues have been over capacity). The rate cap and the lifecycle-status check don't respond to load; the health check is the one that closes when N is struggling to keep up."
- `alternative_correct_answers`:
  - "The health check — the one tied to N's queue backlog / how overloaded it is."
  - "The load/health gate, not the rate limit or the platform-status gate."

### Moment `health-fans-out` — attached to Stop 4

*Why this moment is load-bearing (exposition):* this is the scenario's conceptual crux. An expert reads "the health monitor throttles the node" as a single controller acting on the node; the codebase does the opposite — one detector publishes one signal, and several independent sites each decide to throttle themselves. Landing the fan-out shape here is what makes Stops 5 and 6 read as several independent reactions rather than one orchestrated shutdown. The prediction is answerable now because the Components-in-scope list already named the reaction sites and Stop 4 just established the detector-not-enforcer split.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is engaged and willing to commit, or is likely to hold the confidently-wrong "the health monitor stops the node" controller intuition.

- `answer_shape`: a fan-out — one signal from the health monitor branching to several independent reaction sites — not a single action and not an ordered pipeline.
- Confidence elicitation: included, because "the monitor throttles the node" is a confidently-held wrong shape here.
- Prompt (verbatim): "Node N is overloaded and the health monitor has raised its unhealthy-duration signal — but remember it only *detects*, it doesn't act. Before I show you what happens next: when that one signal goes up, which parts of N do you expect to react to it, and does one place act on the signal or several? Sketch it however the shape feels right — and how confident are you? If it's several, name the ones you'd expect."
- `canonical_answer`: "It's a fan-out, not a single action: the one signal goes out to several independent reaction sites, and each decides for itself. From the components named so far — event creation pauses (the health gate closes, so N stops minting new self-events), gossip throttles (N backs off exchanging events with peers), transaction acceptance throttles (N starts refusing new application transactions), and PCES replay paces itself against the same signal on a restarting node. The health monitor coordinates none of this; it publishes one number and each site reads it independently."
- `alternative_correct_answers`:
  - "Several places react to the same signal in parallel — at least event creation pausing and gossip throttling, plus the transaction-acceptance and PCES-replay sites — each on its own, with the monitor just detecting."
  - "One detector, many reactors: the signal branches out to event creation, gossip, transaction intake, and PCES, and each throttles itself rather than being told to by the monitor."
  - "It fans out — creation stops and gossip slows (and transaction acceptance backs off too); the monitor doesn't stop the node, the individual sites do, independently."
- `followup` (delivered verbatim when the learner names a single reaction, or describes the monitor as stopping or controlling the node): "You've named the effect — now pin down the shape. Is the health monitor itself stopping these things, or is it publishing one signal that several places each react to on their own? Name a second site besides the first one you gave, and say who actually does the throttling."

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the detector-versus-reactor separation and is hesitating rather than guessing; give them the fact and have them reason out the consequence.

- Exposition (tutor states, not a question): the health monitor is only a detector — it watches every component's queue depth and publishes one unhealthy-duration signal — and it never blocks, throttles, or stops anything itself; the components named in scope (event creation, gossip, transaction acceptance, PCES) each read that signal and decide their own response.
- Self-explanation prompt (verbatim): "Here's the fact the design rests on: the health monitor only *detects* — it publishes a single 'how overloaded are we' signal and never acts on it itself. Given that, when N is overloaded, how does anything actually slow down — what has to happen at the components that read the signal, and does that make the response one action or several?"
- `canonical_answer`: "Because the monitor only detects, the slowing-down has to happen at the reaction sites themselves: each component that reads the signal applies its own threshold and throttles itself. So the response is several independent reactions to one shared signal — event creation pausing, gossip backing off, transaction acceptance closing, PCES replay pacing — not a single action the monitor takes. It's a fan-out from one detector."
- `alternative_correct_answers`:
  - "Each reaction site does its own throttling when it sees the signal cross its threshold, so it's many independent responses to one detector — a fan-out, not one switch."
  - "Nothing slows down at the monitor; the components reading the signal each back off on their own, so several things react in parallel rather than one place acting."
- `followup` (delivered verbatim when the learner restates "the monitor detects" without drawing the multi-site consequence): "That's the fact about the monitor — now finish the inference. If the monitor itself never throttles anything, then *who* does, and does that give you one reaction to the signal or several happening at once? Name two of them."

## Consolidation

> (The tutor phrases this in its own words; the substance and the canonical recap are below.)

Bring the seven stops back together as one sketch: N has transactions pending and reaches the point of creating a **self-event**, but creation runs a **chain of independent gates** first — rate cap, platform status, health, sync lag, quiescence — and proceeds only if all agree; if they do, the **tipset** algorithm picks parents that advance consensus (and may still decline if none would). Under load, the **health monitor** — a *detector*, not an enforcer — publishes one rising **unhealthy-duration** signal, and that signal **fans out** to several independent reaction sites: the health gate **pauses creation**, **gossip throttles**, transaction acceptance backs off, and PCES replay paces itself. The gossip reaction is **asymmetric**: N **keeps sending** its own events so its transactions don't go **stale**, while it **stops receiving** so its queues can drain. When the queues drain the signal falls, the gates reopen, and N **resumes** — each site easing off on its own.

Name the three things most likely to have differed from a first guess:

- **Event creation is gated, and one gate watches load** (Stops 1–2, 5). Having pending transactions and a valid parent is not enough; a health-driven gate deliberately suppresses creation while N is overloaded, so the node doesn't pile work onto a system that's already behind.
- **The health monitor detects; it does not control** (Stops 4–5). The likely first guess is one component that throttles the node. In fact one detector publishes one signal and several sites each throttle themselves — a fan-out, not a chain of command.
- **Throttling gossip is asymmetric under load** (Stop 6). Keep *sending* (so your transactions reach consensus before they go stale); stop *receiving* (so your queues drain). The instinct to "just slow gossip down" misses that the two directions are treated differently, and for opposite reasons.

If the learner ran a prediction move, name explicitly where their prediction matched and where it missed, against the canonical answers above.

## Close-out

**Free-recall summary.**

- Prompt (verbatim): "In your own words, walk node N from 'it has transactions pending and is under load' to 'it's creating events normally again.' Name what N checks before it's even allowed to create an event, what the health monitor does when N is overloaded and who actually reacts to it, the one thing N keeps doing on the wire under load and why, and how N gets back to normal."
- `canonical_answer`: "Before building an event, N's event creator runs a chain of independent gates — rate cap, platform status, health, sync lag, quiescence — and creates only if all agree; if they do, the tipset algorithm picks parents that advance consensus. Under load, the health monitor (a detector only) publishes one rising unhealthy-duration signal, and several sites react to it independently: the health gate pauses event creation, gossip throttles, transaction acceptance backs off, and PCES replay paces itself — the monitor coordinates none of it. The gossip reaction is asymmetric: N keeps sending its own self-events so its pending transactions don't go stale and expire before reaching consensus, while it stops receiving peers' events so its queues can drain. When the backlog clears the signal falls, each site eases its own throttle, the health gate reopens, and N resumes creating events normally. The load-bearing idea: one detector, many independent reactions — and keep-sending-while-not-receiving is what keeps N's transactions alive while it recovers."
- `alternative_correct_answers`:
  - "N gates creation behind several independent checks (the health one fires under load) → health monitor just detects and publishes a signal → event creation, gossip, transaction acceptance, and PCES each throttle themselves off that one signal → N keeps sending its own events (so its transactions don't go stale) but stops taking peers' events in (so it can drain) → queues clear, signal drops, gates reopen, N resumes. The catch: the monitor doesn't stop the node, the sites do, each on its own."
  - "Pending transactions don't mean automatic events — a chain of gates decides, and under load a health gate pauses creation. The health monitor detects and one signal fans out to many reactors. N keeps broadcasting its own events to avoid going stale while it stops processing inbound to recover, then everything reopens as the backlog drains."

**Successive-relearning tags (exposition):** this orientation scenario establishes no threshold concept, so it adds nothing to the relearning queue. The ideas it gestures at — the event-creation gate chain, the health monitor as a single detector whose signal fans out to independent reaction sites, and the keep-sending-while-unhealthy asymmetry — are established and tagged for relearning in their Pass 2 lessons (Cluster A.4's `a4-03-event-creation-rule-and-gates` and Cluster B's `b-01-health-monitor-detection`, `b-02-reaction-sites`, and `b-03-reasons-not-to-gossip`).

## Forward pointers

Each component this scenario sketched is taught in depth later. The spiral: orientation plants the sketch, Pass 2 fills the mechanism, Pass 3 stitches the components back together under stress.

- **The wiring substrate** beneath the queues the health monitor watches → Cluster 0: `c0-01-schedulers-and-types`, `c0-03-backpressure-and-queue-health`, `c0-04-wiring-model-lifecycle`.
- **The event creator and its gate chain** (Stops 1–3) → `a4-01-tipset-and-advancement`, `a4-02-snapshot-and-selfishness`, `a4-03-event-creation-rule-and-gates`, `a4-04-self-event-persistence`, `a4-syn-event-creator-synthesis`.
- **Health monitor and the reaction sites** (Stops 4–5) → `b-01-health-monitor-detection`, `b-02-reaction-sites`, `b-04-backpressure-under-load`, `b-syn-health-synthesis`.
- **The reasons-not-to-gossip catalog and the keep-sending asymmetry** (Stop 6) → `b-03-reasons-not-to-gossip`.
- **PCES as the durability waypoint and a watched queue** (Stops 5–6) → `c-04-pces-write-path`, `c-05-restart-and-replay`.
- **Quiescence** (the idle-pause gate named at Stop 2) → `a4-05-quiescence`.
- **The whole steady-state loop, stitched** → `a5-syn-steady-state-synthesis`, and this scenario revisited at full depth in `pass3-04-event-creation-under-stress-deep`, with the related edge case `pass3-edge-02-fall-behind-during-heavy-gossip`.

## Open questions

This scenario sits at role level, but three of the topic files it draws on are themselves incomplete, so the lesson carries the gaps forward rather than papering over them:

- **`[TBD]` Quiescence behaviour.** The quiescence gate is named at role level in Stop 2, but [quiescence.md](../../architecture/topics/quiescence.md) is a near-empty placeholder, so the gate's actual behaviour, its default-enabled state, and which networks run it are not yet documented. Deferred to `a4-05-quiescence`.
- **`[TBD]` "Reasons not to gossip" full enumeration.** Stop 6 uses a documented subset of the catalog (persist-before-gossip, the load throttle, the gossip halt when far behind, and status-disallows-sync). The brief flags the full catalog as acknowledged-incomplete and needing explicit enumeration before Cluster B; deferred to `b-03-reasons-not-to-gossip`.
- **`[TBD]` Exact health thresholds and rates.** The trace states the health gate closes after "a short configured duration" and that gossip permits return "gradually." The precise defaults (the maximum-permissible-unhealthy-duration, the gossip grace period, and the permit revoke/return rates) are structural detail below this orientation altitude and are deferred to the Pass 2 lessons `b-01-health-monitor-detection` and `b-02-reaction-sites`.
