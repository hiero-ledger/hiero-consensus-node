---
lesson_id: A.1-1-hashgraph-dag
cluster: A.1
title: The hashgraph DAG
prerequisites: [0-4-wiring-runtime-synthesis]
kb_refs:
  topics: [hashgraph]
  concepts: [hashgraph-dag]
  invariants: []
  glossary_terms: []
learning_objectives:
  - Name the vertices and edges of the hashgraph and the immutability rule on edges.
  - Distinguish the in-memory non-ancient DAG from the unbounded historical event log.
  - Identify in current code the class that represents a linked DAG node and the class that owns the in-memory map of non-ancient nodes.
estimated_read_minutes: 7
status: drafted
last_verified_against: 1978c2c357d1da3a30e2f870429b96d764ff18fc
---

# The hashgraph DAG

## Where we are

This is the first lesson in cluster A.1 (Hashgraph Algorithm), the concept-densest cluster in the curriculum. Cluster 0 left us with a vocabulary for the runtime fabric — schedulers, wires, soldering, back-pressure — but said nothing about *what* flows on those wires once we get to the consensus core. Pass 1 used the word "hashgraph" repeatedly without defining it; the [transaction-to-consensus walk-through](pass1-1-transaction-to-consensus.md), for instance, said events were "added to the hashgraph DAG" and "linked to their parents" without committing to what those words meant. This sub-lesson stops at the data structure itself before any algorithm runs over it. The next sub-lesson, [A.1-2 rounds and witnesses](A.1-2-rounds-and-witnesses.md), is the first one that does.

## Motivating problem

A Byzantine-fault-tolerant network of consensus nodes needs to agree on the order of transactions without a leader, without a shared clock, and without trusting any single peer's account of what happened. The first sub-problem is what *shape* the shared history takes. A simple ordered log forces a global serializer; a per-node log gives N parallel histories with no agreement on cross-node ordering. The hashgraph's answer is a single graph that every node grows independently from local gossip but converges on globally because each event hash-commits to the events it was built on.

A node's local copy of that graph is the substrate the consensus algorithm operates over. Before we can talk about rounds, witnesses, fame, or judges — the machinery cluster A.1 will spend the next seven sub-lessons unpacking — we need to be precise about the graph itself: what its vertices are, what its edges mean, what is mutable about it, and what is not. That precision is this lesson.

## Concept

The conceptual definition is at [`concepts/hashgraph-dag.md`](../../concepts/hashgraph-dag.md). Read the *Definition* and *Mechanics* sections there before continuing. The two structural facts to anchor on are:

- **Vertices are events; edges are parent references.** Each event carries up to two kinds of parent edges: a *self-parent* (the previous event by the same creator) and one or more *other-parents* (events by peers gossipped with at the moment of creation). Either kind may be absent for a creator's first event. The current code, as we will see in a moment, models *other-parents* as a list rather than a single reference; the concept file's singular framing is a simplification.
- **Edges are immutable.** An event commits to its parents by hash at construction time. A parent reference cannot be revised, repointed, or deleted. The DAG only grows — by appending new vertices, never by modifying existing ones — and once any honest node has gossiped an event, every other honest node will eventually receive a byte-identical copy with byte-identical parent edges.

Two consequences of these two facts shape everything cluster A.1 will build on. First, two nodes that have received the same set of events necessarily have the same DAG: the structure is fully determined by event content. Second, the DAG is the source of truth for the consensus algorithm — every algorithmic property the next seven lessons name (a round, a witness, what one event "sees" through another) is a graph-theoretic property defined over these vertices and edges, not derived from external state.

## How it works

The hashgraph layer in current code does not hold the *entire* historical DAG in memory. It holds the **non-ancient slice** — the events whose birth round is at or above the current ancient threshold — plus the parent edges among them. Older events fall out of the slice once consensus has decided rounds past them; they continue to exist on disk in the pre-consensus event stream and inside signed states, but they are no longer DAG vertices the algorithm can walk. The mechanics of *which* events are in the slice belong to [A.1-6 event lifecycle and birth round](A.1-6-event-lifecycle-and-birth-round.md); for this lesson the relevant fact is that the in-memory DAG is bounded.

Three classes carry the structural shape of the DAG.

[`PlatformEvent`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java) is the wire-format event — what arrives over gossip and what travels on `consensusRoundOutputWire`. It carries parent *descriptors* (hashes plus birth-round metadata) via `getSelfParent()` and `getOtherParents()`, but no resolved pointers to peer events. A `PlatformEvent` is self-contained and serializable; it knows what its parents are by hash but not which Java objects represent them.

[`LinkedEvent<T>`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/LinkedEvent.java) is the base class that adds resolved parent pointers — `selfParent: T` (nullable) and `otherParents: List<T>` — to a wrapped `PlatformEvent`. The split between `selfParent` and `otherParents` is computed in the constructor by comparing creator IDs: the first entry of the supplied `allParents` list is treated as the self-parent if and only if its creator matches this event's creator. Note the plural on `otherParents` — the linked event holds a list, not a single reference.

[`EventImpl`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java) is the concrete linked DAG node the hashgraph algorithm operates on. It extends `LinkedEvent<EventImpl>`, so its parent pointers are themselves `EventImpl` instances — walking the graph is pointer-chasing through this type. On top of the linkage, `EventImpl` adds the per-event memoized algorithm state that later cluster-A.1 lessons will introduce one slot at a time: round, witness flag, fame flag, judge flag, the `DeGen`/`cGen` generations, and the `lastSee`/`stronglySeeP` slots. None of that state is part of the DAG *shape*; it is decoration the algorithm writes onto the vertex as it processes the event.

The DAG itself — the in-memory map of non-ancient `EventImpl` instances — is held by [`ConsensusLinker`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java). Two parallel structures back it:

- `parentDescriptorMap: SequenceMap<EventDescriptorWrapper, EventImpl>`, keyed by `birthRound`, supports the windowed retention policy. When the ancient threshold advances, the linker shifts this map's window to evict ancient entries in bulk.
- `parentHashMap: Map<Hash, EventImpl>` provides O(1) parent-resolution lookup by hash. When a new event arrives, the linker reads each descriptor on the incoming `PlatformEvent`, looks up the corresponding `EventImpl` in this map, and assembles the `allParents` list passed to the new `EventImpl`'s constructor.

Both maps are mutated in lockstep by the linker's two write paths. `linkEvent(event)` either drops the event (when `EventWindow.isAncient(event)` returns true) or inserts it into both maps after resolving its parents. `setEventWindow(eventWindow)` shifts the descriptor map's window forward, removes the evicted descriptors from the hash map, calls `EventImpl.clear()` on each evicted node so the algorithm-state slots and parent pointers can be garbage-collected, and returns the list of events that just became ancient. The architecture topic walks the per-event lifecycle in detail at [`architecture/topics/hashgraph.md#algorithm-in-current-code`](../../architecture/topics/hashgraph.md#algorithm-in-current-code) and [#birth-round-filtering](../../architecture/topics/hashgraph.md#birth-round-filtering); the code anchors above are sufficient for this lesson.

## Worked example

Suppose four nodes A, B, C, D are running. A creates `a₀`, its very first event. The `PlatformEvent` for `a₀` has `getSelfParent()` returning `null` and `getOtherParents()` returning an empty list. When this event reaches B's hashgraph layer (via gossip and event intake), `ConsensusLinker.linkEvent(a₀)` runs:

1. The window check: `EventWindow.isAncient(a₀)` returns false (the network is just starting; ancient threshold is below `a₀`'s birth round).
2. Parent resolution: the descriptor lists are both empty, so the resolved `allParents` list is empty.
3. Construction: `new EventImpl(a₀, [])`. Inside `LinkedEvent`'s constructor, `allParents` is empty, so both `selfParent` and `otherParents` are set such that `selfParent == null` and `otherParents == []`.
4. Insertion: the linker puts the new `EventImpl` into `parentDescriptorMap` keyed by `a₀`'s birth round and into `parentHashMap` keyed by `a₀`'s hash.

Now A and B gossip. B creates `b₃`, whose self-parent descriptor is B's previous event `b₂` and whose other-parents descriptor list contains `a₀`. When `b₃` reaches B's hashgraph, the linker looks up `b₂`'s hash in `parentHashMap` (it is there, as a previously linked `EventImpl`), looks up `a₀`'s hash (also there), assembles the `allParents` list `[b₂, a₀]` with `b₂` first because B's own previous event leads, and constructs `new EventImpl(b₃, [b₂, a₀])`. `LinkedEvent`'s constructor sees that the first entry's creator matches `b₃`'s creator and assigns `selfParent = b₂`, `otherParents = [a₀]`.

A round later, suppose B has gossiped with both C and D before its next event creation, so B's `b₄` has *two* other-parent descriptors — one event each from C and D — in addition to its self-parent `b₃`. The linker assembles `allParents = [b₃, c_k, d_j]`. `LinkedEvent`'s constructor sets `selfParent = b₃`, `otherParents = [c_k, d_j]`. This is the case the concept file's singular "*other-parent*" framing flattens; the code admits and handles arbitrarily many other-parents.

After several rounds of consensus, an ancient threshold advance causes `setEventWindow` to evict `a₀` and `b₂`. The descriptor map's `shiftWindow` call drops their entries; the hash map's removals follow; `EventImpl.clear()` is called on each, severing their parent pointers so the JVM can collect the no-longer-referenced subgraph. The DAG continues with a smaller in-memory footprint; `b₃`'s `selfParent` pointer to `b₂` is still set on the `b₃` object, but `b₂` itself has had `clear()` called and its own parents and algorithm state are gone. The hashgraph algorithm will not walk back through `b₃` to `b₂` because `b₃`'s own birth round will have advanced past the ancient threshold by the time the eviction touches it; the in-memory graph the algorithm sees is the non-ancient slice, full stop.

## Code anchor

- [`PlatformEvent.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java) — the wire-format event with parent descriptors. `getSelfParent()`, `getOtherParents()`, and `getAllParents()` are the descriptor accessors.
- [`LinkedEvent.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/LinkedEvent.java) — the generic base class adding resolved `selfParent: T` and `otherParents: List<T>` pointers. The constructor (lines 28–41) is the canonical statement of how the self/other split is computed from a flat `allParents` list.
- [`EventImpl.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java) — the concrete `LinkedEvent<EventImpl>` the hashgraph algorithm operates on. The class declaration is at line 25; the algorithm-state fields it adds are introduced incrementally in later cluster-A.1 lessons.
- [`ConsensusLinker.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java) — the in-memory non-ancient DAG. `parentDescriptorMap` (line 45) and `parentHashMap` (line 53) are the two backing maps; `linkEvent` (line 79) and `setEventWindow` (line 105) are the two write paths.

## Delta callout

No `delta-map/hashgraph.md` entry exists yet. The topic file's [Future state section](../../architecture/topics/hashgraph.md#future-state) describes proposal-level changes to the hashgraph topic — a single public `Consensus` API with a `nextRound(roster)` pull boundary, and a separate Sheriff module — but neither change touches the DAG's structural shape (vertices, edges, immutability, in-memory slice). For the data structure this lesson covers, current and proposed designs are aligned. Lessons later in cluster A.1 that touch the boundary of the hashgraph module — particularly [A.1-8 hashgraph algorithm synthesis](A.1-8-hashgraph-algorithm-synthesis.md) — will revisit the proposed API.

## Comprehension prompt

Two openings to take into the tutor chat. First: the lesson asserts that two honest nodes that have received the same set of events necessarily have the same DAG. What property of `PlatformEvent` and the linker's behaviour makes that true even though the two nodes received the events in different orders, and what would break if `EventImpl` allowed its `selfParent` or `otherParents` references to be reassigned after construction? Second: `ConsensusLinker` keeps two maps over the same set of `EventImpl` values — one keyed by descriptor (with windowed retention by birth round) and one keyed by hash. Why both? What goes wrong if you try to collapse them into a single map keyed by either key alone?

## Open questions

> - [TBD] No `glossary.md` exists at the consensus-layer KB root yet. Terms introduced or load-bearing in this lesson — *event*, *self-parent*, *other-parent*, *non-ancient slice*, *linker* — should be promoted there once the file is created, and this lesson updated to link to them rather than introducing them inline. The same gap is flagged in every cluster-0 lesson; this is a curriculum-level KB issue rather than a per-lesson fix.
> - [TBD] No `invariants.md` catalog exists yet, so `kb_refs.invariants` is empty. Three candidate invariants surface here and would benefit from `INV-NNN` registration: *parent edges are immutable from event construction onward*; *the in-memory hashgraph holds exactly the non-ancient slice — no events below the ancient threshold and no events above the next pending consensus round are vertices in it*; *`ConsensusLinker.parentDescriptorMap` and `ConsensusLinker.parentHashMap` are mutated in lockstep — every key set is the same `EventImpl` set under two different key types*.
> - [TBD] No `delta-map/hashgraph.md` exists. The Delta callout above leans on the topic file's own Future-state section, which is correct authority for now but should be replaced with a delta-map link once one lands.
> - [TBD] [`concepts/hashgraph-dag.md`](../../concepts/hashgraph-dag.md) describes other-parents in the singular ("an *other-parent*", "at most one to an event from another creator"), but the code in [`LinkedEvent.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/LinkedEvent.java) (lines 16, 60) and [`PlatformEvent.java`](https://github.com/hashgraph/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/PlatformEvent.java) (`getOtherParents()` returns a `List`) admits a list of other-parents per event. SME confirmation needed on whether the pluralisation is a recently-loosened invariant, an option that is reserved but not exercised by the event creator today (cluster A.4), or a permanent design choice the concept file should be revised to reflect. This lesson takes the code's plural framing as authoritative.
> - [TBD] [`concepts/hashgraph-dag.md`](../../concepts/hashgraph-dag.md) describes `EventImpl` as holding parent pointers "in an `allParents` array"; the field on `LinkedEvent` is a `List<T>`, not an array. Minor wording fix on the concept file.
> - [TBD] [`concepts/hashgraph-dag.md`](../../concepts/hashgraph-dag.md)'s glossary cross-reference points at `../../hashgraphGlossary.md` (a docs-tree-wide glossary at `platform-sdk/docs/hashgraphGlossary.md`). The consensus-layer KB layout the lesson-authoring prompt expects is a per-KB `glossary.md` at `platform-sdk/docs/consensus-layer/glossary.md`. SME decision needed on whether the consensus-layer KB inherits the platform-sdk-wide glossary or grows its own; this lesson assumes a future per-KB glossary will be created and links neither.

## Where we're going next

The next lesson, [A.1-2 rounds and witnesses](A.1-2-rounds-and-witnesses.md), takes the DAG defined here and writes the first piece of algorithm state onto each vertex: a *round*. Rounds are computed by walking parent pointers — the same `selfParent` and `otherParents` references introduced in this lesson — and counting how many witnesses of the previous round an event strongly sees. From rounds, the lesson singles out *witnesses* — the events that mark a round transition — as the vertices the rest of cluster A.1's machinery (strongly-seeing, voting, coin rounds, judges) will operate on. The DAG itself does not change between this lesson and the next; what changes is what we are allowed to ask about it.
