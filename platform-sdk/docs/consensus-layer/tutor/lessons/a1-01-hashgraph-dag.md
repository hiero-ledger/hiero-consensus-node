---
id: a1-01-hashgraph-dag
cluster: a1
title: "The hashgraph DAG"
pass: 2
prerequisites:
  - pass1-01-transaction-to-consensus
kb_topics_touched:
  - architecture/topics/hashgraph.md
  - architecture/topics/event-intake.md
kb_concepts:
  - concepts/hashgraph-dag.md
  - concepts/birth-round.md
  - concepts/event-lifecycle.md
kb_glossary_terms:
  - Event
  - Hashgraph
  - Self-parent
  - Other-parent
  - Birth round
  - Ancient
  - Ancient threshold
  - Event window
kb_invariants: []
kb_deltas:
  - delta-map/hashgraph.md
kb_decisions: []
learning_objectives:
  - Describe the hashgraph as a directed acyclic graph whose vertices are events and whose edges are self-parent and other-parent references, and state how many of each an event may carry under the current configuration.
  - Explain why an event is immutable once built and references its parents by hash, and what that immutability implies for the graph as a whole (append-only, tamper-evident, fixed ancestry).
  - Trace how an event enters the local DAG — arriving validated and in topological order, then linked to its already-present parents by ConsensusLinker into an EventImpl held in two parallel maps.
  - Explain the single way an event leaves the DAG — eviction when its birth round falls below the ancient threshold — and why the linker clears the evicted event's references.
  - State the parent birth-round invariant (a child's birth round is at least each parent's) and the eviction hazard it prevents.
threshold_concepts:
  - "Append-only immutability: an event is fixed the moment it is built — content and parent edges never change — and the in-memory hashgraph only ever grows by linking new events or shrinks by evicting ancient ones; it is never edited in place."
estimated_session_minutes: 30
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# The hashgraph DAG

## Prerequisites

> - **`pass1-01-transaction-to-consensus`** — you can picture the hashgraph as the in-memory structure, fed by gossip and intake, that the consensus algorithm runs over to order events.

Beyond that one orientation sketch, this lesson assumes only general distributed-systems and graph background — directed acyclic graphs, vertices and edges, and content-addressing by hash. It opens up the structure the rest of Cluster A.1 computes over; it deliberately stops short of the algorithm itself (rounds, witnesses, fame, judges, consensus order all come from `a1-02` onward).

## Incoming retrieval probes

This is the first mechanism lesson in Cluster A.1, so no prior Pass 2 lesson has taught a hashgraph mechanism for the tutor to consolidate against. There is no opening retrieval quiz here — that would contradict the trust-based entry.

One soft watch-for signal (an authorial note, not a prompt to read aloud):

- **The hashgraph's role**, from `pass1-01-transaction-to-consensus`. Canonical statement the tutor consolidates against if it resurfaces: *the hashgraph is the in-memory structure, fed by gossip and intake, that the consensus algorithm runs over.* If the learner reaches for this Pass 1 sketch, affirm it and build on it; do not quiz it at session open.

## Misconception watchlist

- **"The hashgraph is a chain / a blockchain."** *(Adjacent-protocol import.)* Sounds like: "so each event points at the previous block," "what's the head of the chain?", or drawing one linear sequence. Correction, in line: it is a DAG, not a chain — an event has **at most one self-parent** (its creator's own previous event) **and zero or more other-parents** (events by peers), so different creators' histories branch and merge into one shared graph; there is no single head ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)).
- **"Once an event reaches consensus it is removed from the hashgraph."** *(Plausible-but-wrong.)* Sounds like: "consensus pops it off," "after it's ordered it's gone." Correction: reaching consensus does not remove an event from the DAG. The *decided round* leaves as output, but the event stays in the non-ancient window until its **birth round** falls below the **ancient threshold**, at which point it is evicted. Aging out is the only exit ([event-lifecycle.md](../../concepts/event-lifecycle.md)).
- **"An event can be revised or re-parented after it is added."** *(Over-generalization from mutable graphs.)* Sounds like: "when does the event get updated with its round?", "can we re-link it?" Correction: the *event* — its transactions, parent hashes, birth round, signature — is immutable once built. The consensus algorithm stores computed scratch values on the in-memory node alongside the event; that is derived metadata, not an edit to the event, and the event's hash never changes ([hashgraph-dag.md](../../concepts/hashgraph-dag.md), [`EventImpl`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)).
- **"An event can reference many peers' events as parents."** *(Over-generalization from the data model.)* Sounds like: "it links to every peer it synced with this round." Correction: the data model allows several other-parents, but the current default configuration caps it at one (`maxOtherParents` defaults to `1`), so today an event carries at most one self-parent and one other-parent ([`EventCreationConfig.java#L38-L46`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java#L38-L46)).
- **"Birth round is the round the event reaches consensus in."** *(Round-conflation — the trap that recurs all through A.1.)* Sounds like: "its birth round is when it got ordered." Correction: **birth round is stamped at creation and is immutable**; it is *not* the round an event reaches consensus in (that is round-received, taught later). In this lesson birth round does exactly one job — it gates membership in the DAG ([birth-round.md](../../concepts/birth-round.md), [glossary: Birth round](../../glossary.md#birth-round)).

## Mechanism

The hashgraph module owns the in-memory DAG of non-ancient events and runs the consensus algorithm over it ([hashgraph.md](../../architecture/topics/hashgraph.md)). This lesson is only about the **structure** — what the vertices and edges are, how an event enters it, and how an event leaves it. The algorithm that reads the structure starts in `a1-02`.

**Pre-training — terms this lesson integrates** (definitions live in the glossary and concept files; named here to set vocabulary):

- **Event** — a signed record created by one node (transactions, parent hashes, a birth round); events are the *vertices* of the DAG ([glossary: Event](../../glossary.md#event)).
- **Hashgraph** — the in-memory directed acyclic graph of non-ancient events, linked by parent edges ([glossary: Hashgraph](../../glossary.md#hashgraph)).
- **Self-parent** — the edge to the creator's own previous event; at most one per event ([glossary: Self-parent](../../glossary.md#self-parent)).
- **Other-parent** — an edge to an event by a *different* creator; zero or more, default-capped at one ([glossary: Other-parent](../../glossary.md#other-parent)).
- **Birth round** — an immutable round number stamped on each event at creation; here, used only as the key for windowed retention. Its full definition and how it advances are `a1-06` ([glossary: Birth round](../../glossary.md#birth-round)).
- **Ancient threshold** — the moving birth-round boundary below which an event is evicted from the DAG ([glossary: Ancient threshold](../../glossary.md#ancient-threshold)).
- **Event window** — the `EventWindow` record carrying the current ancient threshold (among other bounds) ([glossary: Event window](../../glossary.md#event-window)).
- **`PlatformEvent`** — the event object that travels through gossip, intake, and consensus; it carries the parent descriptors and the birth round ([`PlatformEvent.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java)).
- **`EventImpl`** — the in-memory DAG *node*: it wraps a `PlatformEvent` together with resolved pointers to its parent `EventImpl`s (and consensus scratch fields used from `a1-02` on) ([`EventImpl.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)).
- **`ConsensusLinker`** — the component inside the hashgraph module that *holds* the non-ancient DAG and links each incoming event to its parents ([`ConsensusLinker.java`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L20-L29)).

### Chunk 1 — Vertices and two kinds of edge `{moment: m1-vertices-and-edges}`

The hashgraph is a directed acyclic graph: its vertices are events, its edges are parent references ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)). Each event has **at most one self-parent** — the previous event by the same creator — and **zero or more other-parents** — events by peers the creator just gossiped with. A `PlatformEvent` exposes the two edge kinds separately: `getSelfParent()` ([`#L416-L419`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L416-L419)) and `getOtherParents()` ([`#L426-L429`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L426-L429)), unified by `getAllParents()` ([`#L441-L444`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L441-L444)). The first event a creator ever makes may have no parents at all.

The data model permits several other-parents, but the current default configuration caps the count at one — `maxOtherParents` defaults to `1`, which "gives old behaviour of having one self parent and one other parent" ([`EventCreationConfig.java#L38-L46`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java#L38-L46)). So an event today carries at most two parent edges.

**Load-bearing line (signal):** the *other-parent* edge is what makes this a graph rather than a stack of separate per-creator chains. Self-parent edges alone would give each creator an isolated linear history; the other-parent edges weave those histories into one shared DAG. That weave is the whole reason the algorithm can later reason across every node from a single event's ancestry.

### Chunk 2 — Reference by hash, and immutability `{moment: m2-immutability}`

An event names its parents by **hash**: `getAllParents()` returns `EventDescriptorWrapper`s, each pinning a parent by its hash ([hashgraph-dag.md](../../concepts/hashgraph-dag.md), [`PlatformEvent.java#L441-L444`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L441-L444)). Once an event is built, its content and its parent edges are immutable; the hashgraph only grows, and "nothing in normal operation modifies, replaces, or retracts an event that has already been added" ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)).

Be precise about *what* is immutable. The **event** — the `PlatformEvent`, with its transactions, parent hashes, birth round, and signature — is fixed at build time. The in-memory node `EventImpl` wraps that event and *additionally* memoizes consensus scratch values (a round number and other fields) that the algorithm fills in later ([`EventImpl.java#L84-L95`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)). Those scratch fields are derived annotations, not edits to the event; the event's hash never changes.

**Load-bearing line (signal):** hash-references plus immutability make the graph **append-only and tamper-evident** — you cannot alter an event without changing its hash, and therefore its identity and every reference to it. This is the threshold concept of the lesson, and the property the rest of Cluster A.1 silently relies on: an event's ancestry is settled the moment it exists. *(See the Contrasting cases material below, which the tutor draws on at this moment.)*

### Chunk 3 — How an event enters: linking `{moment: m3-linking}`

Events arrive at the hashgraph already validated and in valid **topological order** — that is intake's job, not the hashgraph's; the hashgraph "does not validate, deduplicate, or topologically order events" ([hashgraph.md](../../architecture/topics/hashgraph.md), [event-intake.md](../../architecture/topics/event-intake.md)). The consensus engine hands each incoming event to `ConsensusLinker.linkEvent`, which builds the DAG node ([`ConsensusLinker.java#L78-L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97)):

1. If the event is ancient (`eventWindow.isAncient(event)`), return `null` — it never enters the DAG.
2. Otherwise resolve each parent descriptor to the parent `EventImpl` already held by the linker, build a `new EventImpl(event, parents)`, and store it in **two** maps: `parentDescriptorMap` (keyed by event descriptor wrapper, for windowed retention, [`#L39-L45`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L39-L45)) and `parentHashMap` (keyed by hash, for parent lookup, [`#L47-L53`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L47-L53)).

A claimed parent that is ancient, missing, or whose birth round does not match the child's claim is simply **not attached** — `getParentToLink` returns `null` for it and the child is still linked without that edge ([`ConsensusLinker.java#L151-L192`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L151-L192); the birth-round-match check is [`#L171-L175`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L171-L175)).

**Load-bearing line (signal):** the linker *looks parents up*; it never waits for one. That only works because non-ancient parents are guaranteed to have arrived (and been linked) before their child — the topological-order contract established upstream by intake's orphan buffer ([event-intake.md](../../architecture/topics/event-intake.md)). The DAG-building step trusts that ordering rather than re-establishing it.

### Chunk 4 — How an event leaves: ancient eviction `{moment: m4-eviction}`

There is exactly one way an event leaves the in-memory DAG: it **ages out**. An event is ancient when its birth round is below the ancient threshold — literally `event.getBirthRound() < ancientThreshold` ([`EventWindow.java#L86-L88`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/EventWindow.java#L86-L88)). When the ancient threshold advances, `ConsensusLinker.setEventWindow` shifts the birth-round window: for every event that just fell below the threshold, it removes the entry from `parentHashMap`, calls `event.clear()`, and collects it into the list of newly-ancient events ([`ConsensusLinker.java#L105-L116`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105-L116)).

`EventImpl.clear()` "erase[s] all references to other events within this event ... so other events can be garbage collected ... Calling this on every event assists the GC in clearing memory when events become ancient" ([`EventImpl.java#L398-L407`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L398-L407)). *(How the ancient threshold itself advances is driven by consensus and is the subject of `a1-06`; here it is enough that it moves.)*

**Load-bearing line (signal):** eviction is keyed on **birth round, not consensus status**. An event is removed because it aged out, regardless of whether it ever reached consensus — an event that ages out without reaching consensus is *stale*, the subject of `a1-07`. Reaching consensus is not what frees an event from the DAG; the advancing window is.

This is also where the one structural rule on birth round bites: **a child's birth round must be greater than or equal to every parent's** ([birth-round.md](../../concepts/birth-round.md)). If a child's were lower, the advancing ancient threshold could evict the child while leaving the parent non-ancient — severing the parent from that descendant. A parent that loses every descendant this way is stranded in the graph. The invariant is enforced upstream of the DAG (at event creation), so the linker can rely on it.

## Engagement moves

### Moment `m1-vertices-and-edges`

*Why load-bearing (exposition):* this is the first picture of the structure; getting the self-parent-versus-other-parent distinction right here is exactly what separates a correct DAG mental model from the chain import the watchlist warns about.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on DAGs and carries the Pass 1 sketch, and may be about to import a chain / blockchain shape.
- `answer_shape`: a vertex with two *kinds* of outgoing edge (a graph spanning creators) — not a single linear link.
- Framing + prompt (verbatim): "You know each node creates a stream of events and gossips them to its peers. Before I show you the structure: if you had to draw the edges out of a single event — to its own past, to other nodes, or both — what would you draw, and why?"
- Confidence elicitation (verbatim, optional): "How sure are you before I reveal it — low to high?"
- `canonical_answer`: An event has at most one edge to its creator's own previous event (the self-parent) and zero or more edges to events by other nodes (other-parents); together these make a directed graph that spans all creators, not a single per-creator chain ([hashgraph-dag.md](../../concepts/hashgraph-dag.md), [`PlatformEvent.java#L441-L444`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L441-L444)).
- `alternative_correct_answers`:
  - "Two kinds of edge: one back to its own previous event, and one (or more) to peers' events it just learned about."
  - "A self-link to its prior event plus cross-links to other creators — so the creators' chains interleave into one graph."
  - "Edges to both its own history and peers' events; that's what makes it a DAG rather than one chain."
- `followup` (if the learner names only the self-link — the chain answer): "You've got the edge to its own past. Now add the edge that connects one creator's history to another's — what is that edge, and where does its target come from?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to this codebase's event model and hesitates on what an event points at.
- Walk (exposition): an `EventImpl` wraps a `PlatformEvent`; its parents come from `getAllParents()` = the self-parent (if any) plus the other-parents ([`PlatformEvent.java#L441-L444`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L441-L444)). **Load-bearing line:** the self-parent and the other-parent play different structural roles.
- Self-explanation prompt (verbatim): "An event has at most one self-parent but may have other-parents to peers' events. Picture the graph if events had *only* self-parents — what would it look like, and what does adding the other-parent edge change about it?"
- `canonical_answer`: With only self-parents, each creator's events form an isolated linear chain and no information crosses between nodes. The other-parent edge links a creator's chain to another creator's event, merging the separate chains into one connected DAG so that ancestry — and therefore knowledge — crosses between nodes.
- `alternative_correct_answers`:
  - "Self-parents alone give N disconnected chains; the other-parent edge stitches them together into one graph."
  - "Without other-parents every node has its own private chain; the other-parent is the only cross-node link."
  - "It would be N parallel lines; other-parents are what make it a single shared structure."
- `followup` (if the learner restates the definition without the consequence): "That's what the edges are — now say what the other-parent edge *lets the system do* that disconnected chains could not."

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is clearly fluent on the structure and needs only a verify before moving on.
- Prompt (verbatim): "Under the current default configuration, what is the maximum number of parent edges a single event can have, and what are they?"
- `canonical_answer`: At most two — one self-parent and one other-parent, because `maxOtherParents` defaults to `1` ([`EventCreationConfig.java#L38-L46`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java#L38-L46)); a creator's first event may have fewer, even zero.
- `alternative_correct_answers`:
  - "Two: a self-parent and one other-parent (default cap of one other-parent)."
  - "One self-parent plus one other-parent today; the data model would allow more if `maxOtherParents` were raised."

### Moment `m2-immutability`

*Why load-bearing (exposition):* this is the threshold-concept moment. The learner must come away seeing the hashgraph as append-only and immutable — not a structure you update in place — because everything later treats an event's ancestry as fixed. Offers the contrasting cases (to surface the invariant), a worked example (for the novice), and a prediction (for the confident).

**Move A — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the three cases in the Contrasting cases material below.
- Prompt (verbatim): "Here are three ways to organize a growing set of records. (1) The hashgraph: events that name their parents by hash, immutable once built. (2) A blockchain: a linear chain of blocks, each hashing the one before it. (3) A mutable in-memory graph whose nodes you can edit or re-link in place. Across the three, what stays the same, what differs, and which property do (1) and (2) share that (3) does not?"
- `canonical_answer`: All three are graphs of records. (3) allows mutation and re-linking; (1) and (2) are append-only and hash-linked, so each record's content is pinned by its hash and cannot change without changing its identity. The hashgraph differs from the blockchain in being a branching, multi-parent DAG across many creators rather than a single linear chain ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)).
- `alternative_correct_answers`:
  - "All are linked records; the mutable graph can be edited, the other two can't; the hashgraph is the only branching/multi-creator one."
  - "Same: hash-linked records. Different: mutability, and chain-vs-DAG shape. (1) and (2) are both append-only and tamper-evident; (3) isn't."
  - "The shared property of (1) and (2) is immutability via hashing; the hashgraph's extra trait is many parents merging creators' histories."
- *Deep invariant (exposition for consolidation, not read as a question):* immutability plus hash-references make the structure append-only and tamper-evident, so an added event's content and identity are fixed — the foundation every later A.1 lesson relies on.

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new and is confused that the in-memory node seems to carry changing values.
- Walk (exposition): the `PlatformEvent`'s content and parent hashes are fixed at build; `EventImpl` wraps it and adds consensus scratch fields filled in later ([`EventImpl.java#L84-L95`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L84-L95)). **Load-bearing line:** the immutable event and the mutable scratch metadata are two different things stored together.
- Self-explanation prompt (verbatim): "The event's content and parent hashes are fixed once it is built, yet the consensus code later writes values onto the in-memory node. How can both be true — the event is immutable, but its node carries values that change?"
- `canonical_answer`: The immutable part is the `PlatformEvent` — transactions, parent hashes, birth round, signature. The `EventImpl` node wraps it and stores *separate*, computed consensus metadata that the algorithm fills in; those are derived annotations alongside the event, not edits to it, and the event's hash and identity never change.
- `alternative_correct_answers`:
  - "Two layers: the fixed event, and computed scratch fields on the node; only the latter change."
  - "The event itself isn't edited — the node just memoizes derived values next to it; the hash stays the same."
  - "Immutable event + mutable annotations on the wrapper; the annotations are computed, not part of the event."
- `followup` (if the learner only repeats "it's immutable"): "You've said it's immutable — be concrete: name what is fixed at build time, and name what is computed and stored separately afterward."

**Move C — prediction-and-reveal.** *Diagnosis tag:* the learner is confident and may assume events get updated in place.
- `answer_shape`: a single property statement (append-only / no in-place edits), with the *reason* being the hash.
- Framing + prompt (verbatim): "Before I state the rule: once an event has been built and added to the graph, can its contents or its parent edges ever change? What's your prediction, and what makes you say so?"
- `canonical_answer`: No — an event is immutable once built; content and parent edges are fixed, and the graph grows only by adding new events, never by editing existing ones. What pins it is the hash: parents reference an event by hash, so altering its content would change its hash and break every reference to it ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)).
- `alternative_correct_answers`:
  - "It can't change — it's immutable; the graph is append-only, and the hash is what fixes it."
  - "No edits; you can only add new events. Changing one would change its hash and invalidate the references to it."
  - "Fixed once built — content and edges both — because everything points at it by hash."
- `followup` (if the learner says "it can't change" without the mechanism): "You've got the rule — now say *what* makes altering an added event impossible without every other node noticing."

### Moment `m3-linking`

*Why load-bearing (exposition):* the entry path is where the lesson's one cross-component dependency lives — the linker's correctness rests on intake's topological-order guarantee. The worked example earns the "why doesn't it just wait for parents?" insight.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the linking step (the common case here).
- Walk (exposition): `linkEvent` drops the event if ancient, otherwise looks each parent up in `parentHashMap`, builds `new EventImpl(event, parents)`, and stores it in both maps ([`ConsensusLinker.java#L78-L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97)). **Load-bearing line:** parents are *looked up*, never waited for.
- Self-explanation prompt (verbatim): "The linker looks each parent up in a map and links to it, but it never waits for a parent to arrive — if a non-ancient parent were genuinely absent, something would be wrong. What must be true about the order in which events reach the linker for 'just look the parents up' to be correct?"
- `canonical_answer`: Events must arrive in topological order — every non-ancient parent must have already been delivered and linked before its child. Intake's orphan buffer establishes exactly that order, so the linker can rely on it instead of buffering out-of-order events itself ([event-intake.md](../../architecture/topics/event-intake.md), [hashgraph.md](../../architecture/topics/hashgraph.md)).
- `alternative_correct_answers`:
  - "Parents before children — topological order, which intake guarantees upstream."
  - "Every non-ancient parent is already in the map by the time the child arrives, because the stream is topologically ordered."
  - "The events come pre-ordered so a child never arrives before its parents; the linker assumes that."
- `followup` (if the learner just says "in order"): "Say which upstream component establishes that order, and what it does with an event whose parents haven't arrived yet."

**Move B — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify on the ancient-at-entry behavior.
- Prompt (verbatim): "An event reaches the linker and its birth round is below the current ancient threshold. What does `linkEvent` do with it?"
- `canonical_answer`: It drops it — `linkEvent` returns `null` for an ancient event, so it never enters the DAG ([`ConsensusLinker.java#L78-L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97)).
- `alternative_correct_answers`:
  - "Returns null and the event is not added."
  - "Nothing enters the DAG — ancient events are rejected at the door."

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and the tutor wants to confirm the two-map roles without a full walk.
- Prompt (verbatim): "The linker keeps each linked event in two maps — one keyed by hash, one keyed by birth round. Which of the two does it use to find a parent that a new child references by hash?"
- `canonical_answer`: The hash-keyed map (`parentHashMap`) ([`ConsensusLinker.java#L47-L53`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L47-L53)).
- `alternative_correct_answers`:
  - "The one keyed by hash."
  - "`parentHashMap` — the birth-round map is for windowing, not lookup."

### Moment `m4-eviction`

*Why load-bearing (exposition):* this is the single DAG exit and the place to correct the "leaves on consensus" misconception; it also introduces the GC-severing and the parent birth-round invariant. Offers a prediction (for the confident, against the misconception), a worked example (for the novice on `clear()`), and a prediction on the invariant.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is likely to assume consensus removes an event.
- `answer_shape`: a *when* (birth round drops below the ancient threshold) plus a *what happens* (evict + clear) — not "when it reaches consensus."
- Framing + prompt (verbatim): "An event has just reached consensus and been delivered downstream. Before I show you: does it leave the in-memory hashgraph at that moment? If not then, when is an event actually removed, and what triggers the removal?"
- `canonical_answer`: No — reaching consensus does not remove an event from the DAG. An event is removed only when the ancient threshold advances past it, i.e. when its birth round falls below the ancient threshold; at that point the linker evicts it from its maps and clears its parent pointers ([`ConsensusLinker.java#L105-L116`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105-L116), [`EventWindow.java#L86-L88`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/EventWindow.java#L86-L88)).
- `alternative_correct_answers`:
  - "It stays until it ages out — birth round below the ancient threshold — then the linker evicts it."
  - "Consensus doesn't remove it; the advancing ancient window does, by birth round."
  - "Removal is triggered by the window shift, not by ordering; it persists until it's ancient."
- `followup` (if the learner reaches "it ages out" without the mechanism): "You've got the trigger — now name what the linker does to the evicted event's links, and why that step matters for memory."

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new and unsure why eviction clears references rather than just dropping the event.
- Walk (exposition): `setEventWindow` shifts the window on `ancientThreshold`; for each evicted event it removes it from `parentHashMap`, calls `event.clear()`, and adds it to the ancient list ([`ConsensusLinker.java#L105-L116`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105-L116)). `clear()` erases the event's references to other events ([`EventImpl.java#L398-L407`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L398-L407)). **Load-bearing line:** clearing references is what lets the GC reclaim the ancient sub-graph.
- Self-explanation prompt (verbatim): "When an event is evicted, the linker doesn't just drop it from the maps — it also calls `clear()`, which erases that event's references to its parents. Why erase the links rather than only removing it from the maps? What does cutting the references buy you?"
- `canonical_answer`: Removing it from the maps drops the linker's references, but the evicted event can still hold pointers to (and be pointed at by) other events, which would keep an ancient sub-graph alive in memory. Clearing its outgoing references breaks those chains so the garbage collector can reclaim the whole ancient fragment.
- `alternative_correct_answers`:
  - "To break the pointer chains so the GC can collect the ancient events instead of them keeping each other alive."
  - "Map removal isn't enough — lingering inter-event pointers would pin the memory; `clear()` cuts them."
  - "So an ancient sub-graph becomes unreachable and collectable, not retained by leftover references."
- `followup` (if the learner restates "it helps GC" without the reason): "That's the goal — say *why* dropping it from the maps alone wouldn't free the memory."

**Move C — prediction-and-reveal.** *Diagnosis tag:* the learner has the eviction mechanism and can now reason about the constraint it imposes on birth rounds.
- `answer_shape`: an inequality between child and parent birth rounds, plus the failure it averts.
- Framing + prompt (verbatim): "We've seen eviction is by birth round: an event goes when its birth round drops below the ancient threshold. Given only that: what relationship would you expect the rules to enforce between a child's birth round and its parents' — and what could go wrong if a child's birth round were *lower* than a parent's?"
- `canonical_answer`: A child's birth round must be greater than or equal to every parent's. If a child's were lower, the advancing ancient threshold could evict the child while leaving the parent non-ancient, cutting the parent off from that descendant; a parent that loses all its descendants this way is stranded in the graph ([birth-round.md](../../concepts/birth-round.md)).
- `alternative_correct_answers`:
  - "Child birth round ≥ parent birth round; otherwise the child could age out before the parent and orphan it from below."
  - "Parents can't be younger than their children by birth round, or eviction would strand the parent."
  - "≥ for every parent; a lower child birth round lets the window remove the child first, isolating the parent."
- `followup` (if the learner gives the inequality without the hazard): "You've got the rule — now walk the failure: if a child's birth round were below its parent's, what does the advancing threshold do to each of them?"

## Contrasting cases material

The threshold concept is *append-only immutability*: an added event is fixed, and the graph only grows or evicts — it is never edited in place. The three cases hold "a growing set of linked records" constant and vary structure and mutability.

- **Case 1 — the hashgraph.** Events reference parents by hash and are immutable once built; each event has at most one self-parent and (today) at most one other-parent, so many creators' histories branch and merge into one DAG ([hashgraph-dag.md](../../concepts/hashgraph-dag.md), [`PlatformEvent.java#L441-L444`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java#L441-L444)). *Surface:* branching, multi-creator, append-only, hash-pinned.
- **Case 2 — a blockchain.** A single linear chain of blocks, each hashing its predecessor; immutable and append-only. *Surface:* one parent per block, one head, append-only, hash-pinned.
- **Case 3 — a mutable in-memory graph.** Nodes can be updated or re-linked in place. *Surface:* editable, no hash-pinning, no append-only guarantee.

**The deep invariant that survives the surface differences:** Cases 1 and 2 are both append-only and tamper-evident because their records are pinned by hash — a record's content cannot change without changing its identity. Case 3 lacks that. What sets the hashgraph apart *within* the append-only family is multi-parent branching: other-parent edges merge separate creators' histories into one shared graph, which is precisely what later lets the algorithm reason across all creators from a single event's fixed ancestry. *(This lesson scopes the invariant to structure and immutability; the algorithm that exploits it begins in `a1-02`.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "An honest creator's events, linked only by their self-parent edges, form a ______. Fill in the blank, then say in one sentence what the other-parent edge adds to the overall structure."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/hashgraph-dag.md` and read the Definition section — look at how self-parent differs from other-parent."
  - Rung 2 (verbatim): "If every event points only to its own creator's previous event, what shape does one creator's whole history take?"
  - Rung 3 (verbatim): "Each creator's self-parent edges form a single chain; the other-parent edges are the only edges that cross *between* creators."
  - Rung 4 (verbatim, gated on effort): "A chain (a single linear sequence). The other-parent edge links one creator's chain to another creator's event, merging the separate chains into one connected DAG."
- `canonical_answer`: A chain (a per-creator linear sequence); the other-parent edge connects different creators' chains into one shared DAG.
- `alternative_correct_answers`:
  - "A linear chain; other-parents cross-link creators into a single graph."
  - "A single line of events; the other-parent edge is what joins separate creators' lines together."
- *Invariant exercised (exposition):* the two edge kinds play distinct structural roles — self-parent makes a chain, other-parent makes it a graph.
- `followup` (if the learner fills "chain" but gives a vague second clause): "Be specific about the other-parent edge — which two things does it connect that a self-parent edge never does?"

**Problem 2** *(more blanked — two cases from a scenario).*
- Statement (verbatim): "Two events reach the linker. Event X's birth round is below the current ancient threshold. Event Y is non-ancient, but one of the parents Y claims is itself already ancient. For each event, state what the linker does."
- Hint ladder:
  - Rung 1 (verbatim): "Open `ConsensusLinker.java` and read `linkEvent`, then `getParentToLink` — note what each returns in the cases above."
  - Rung 2 (verbatim): "What does `linkEvent` return when `eventWindow.isAncient(event)` is true? And inside `getParentToLink`, what happens to a parent that is ancient?"
  - Rung 3 (verbatim): "`linkEvent` short-circuits to `null` for an ancient event. For a non-ancient event, an ancient claimed parent is simply not attached — `getParentToLink` returns `null` for that parent — and the event is still linked."
  - Rung 4 (verbatim, gated on effort): "X is dropped: `linkEvent` returns `null` and X never enters the DAG. Y is linked, but without an edge to the ancient parent — the linker attaches only the non-ancient parents it can resolve."
- `canonical_answer`: X → dropped; `linkEvent` returns `null` for an ancient event so it never enters the DAG. Y → still linked, but the ancient parent is not attached (`getParentToLink` returns `null` for it); the child is added with only its resolvable non-ancient parents ([`ConsensusLinker.java#L78-L97`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L78-L97), [`#L151-L192`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L151-L192)).
- `alternative_correct_answers`:
  - "X: rejected (null), not added. Y: added, minus the ancient parent edge."
  - "X never enters; Y enters but the linker skips the ancient parent when wiring its edges."
- *Invariant exercised (exposition):* the ancient threshold gates entry at two granularities — the whole event, and each individual parent edge.
- `followup` (if the learner handles X but says Y is buffered or dropped): "Y is non-ancient, so it isn't dropped — and the linker never buffers. Say exactly what happens to Y's edge toward the ancient parent."

**Problem 3** *(most faded — produce the full mechanism from an observation).*
- Statement (verbatim): "You observe that the in-memory hashgraph stays roughly bounded in size over days of operation, even though events are only ever added to it. Explain the mechanism that bounds it. Name three things: (a) what triggers an event's removal, (b) which method performs the removal, and (c) what it does to each removed event so the memory is actually reclaimed."
- Hint ladder:
  - Rung 1 (verbatim): "Open `ConsensusLinker.java` and read `setEventWindow`, then open `EventImpl.java` and read the `clear()` method's Javadoc."
  - Rung 2 (verbatim): "What value advancing causes events to be removed? Which `ConsensusLinker` method shifts on that value, and what does it call on each removed event?"
  - Rung 3 (verbatim): "Removal is triggered by the ancient threshold advancing past an event (birth round below it). `setEventWindow` shifts the birth-round window and, for each evicted event, removes it from the maps and calls `clear()`, which severs the event's references."
  - Rung 4 (verbatim, gated on effort): "(a) The ancient threshold advancing past the event — its birth round falls below the threshold. (b) `ConsensusLinker.setEventWindow`, which shifts the birth-round-keyed window and evicts below-threshold events from both maps. (c) It calls `EventImpl.clear()` on each, erasing that event's references to other events so the ancient sub-graph can be garbage-collected."
- `canonical_answer`: (a) the ancient threshold advancing past the event (`event.getBirthRound() < ancientThreshold`); (b) `ConsensusLinker.setEventWindow` shifts the birth-round window and evicts the below-threshold events from `parentDescriptorMap` and `parentHashMap`; (c) it calls `EventImpl.clear()` on each evicted event, severing its references so the ancient fragment can be garbage-collected ([`ConsensusLinker.java#L105-L116`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java#L105-L116), [`EventImpl.java#L398-L407`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java#L398-L407), [`EventWindow.java#L86-L88`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/hashgraph/EventWindow.java#L86-L88)).
- `alternative_correct_answers`:
  - "(a) birth round below the ancient threshold; (b) `setEventWindow`'s window shift; (c) `clear()` cuts the event's references for GC."
  - "Triggered by the window advancing, performed by `setEventWindow`, reclaimed by `EventImpl.clear()` severing inter-event pointers."
- *Invariant exercised (exposition):* the DAG is bounded not by deleting consensus-reached events but by a birth-round window that evicts and unlinks ancient ones.
- `followup` (if the learner names (a) and (b) but omits (c)): "You've got the trigger and the method — now name what's done to each removed event so its memory is freed, and why map removal alone wouldn't do it."

## Delta callout

`[TBD: delta-map/hashgraph.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for the hashgraph DAG to summarize.]` Status: **not started** (per the [delta-map index](../../delta-map/README.md)). When that delta lands it will be linked here as `../../delta-map/hashgraph.md`. The hashgraph topic's own forward note concerns a future single Consensus public API and a Sheriff module ([hashgraph.md](../../architecture/topics/hashgraph.md)); neither changes the DAG structure this lesson covers. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `a1-02`, introduces *rounds* — a number the algorithm computes for each event from its ancestry. The question below is answerable now, purely from the DAG's edges and immutability.

- Prompt (verbatim): "You've seen that other-parent edges weave every creator's history into one shared DAG, and that an event's content and parent edges are fixed the moment it is built. Suppose you wanted to compute some property of an event purely from the set of events it can reach by following parent edges — its ancestors. Using only what you know about the DAG's edges and immutability: once an event exists, can its set of ancestors ever change? Explain why or why not."
- `canonical_answer`: No — because an event's parent edges are immutable and the graph is append-only, the set of events reachable from it by parent edges (its ancestors) is fixed at creation. New events added later can only reference existing events as parents, so they become *descendants*; nothing can be inserted below an existing event. Any property computed purely from an event's ancestry is therefore stable once the event exists ([hashgraph-dag.md](../../concepts/hashgraph-dag.md)).
- `alternative_correct_answers`:
  - "It can't change — parent edges are fixed, so ancestry is frozen at creation; later events only add descendants."
  - "No; immutability + append-only means you can only add events on top, never below, so an event's ancestor set is permanent."
  - "Ancestors are settled when the event is built — new events become descendants, never new ancestors."
- `followup` (if the learner asserts "it's fixed" without the reason): "Say why adding new events later can never add to an existing event's ancestors — what direction can new edges only point?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what makes the hashgraph a DAG rather than a chain, and what are the only two ways the in-memory graph changes over time?"
  - `canonical_answer`: It is a DAG because each event has at most one self-parent and zero or more other-parents, so different creators' chains branch and merge into one graph — a chain would allow only a single parent and a single head. The in-memory graph changes in exactly two ways: events are *added* (linked to their already-present parents on arrival), and ancient events are *evicted* (when their birth round falls below the ancient threshold, with their references cleared for GC). Events are never edited once built.
  - `alternative_correct_answers`:
    - "Multiple parents (self + other) make it a branching graph, not a line; it only ever grows by linking or shrinks by ancient eviction."
    - "Self- and other-parent edges across creators make it a DAG; the two changes are adding new events and aging out old ones — never in-place edits."
    - "DAG because of cross-creator other-parent edges; the graph only gains linked events and loses ancient ones."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *append-only immutability* —
  - Day 1: recall the two edge kinds (self-parent ≤ 1, other-parents) and that an event is immutable once built.
  - Day 3: apply it — say what happens to an event in the DAG when it reaches consensus (nothing leaves on consensus; events leave only by aging out).
  - ~2 weeks: state the invariant — an added event is fixed, its ancestry never changes, and it enters only by linking and leaves only by ancient eviction.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/hashgraph.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for the hashgraph DAG to summarize. Deferred until the hashgraph delta-map entry is authored; the callout links the file once it lands.