# Consensus Layer — Kickoff Decisions

Candidate architectural decisions for a 30-minute kickoff conversation with a departing senior engineer, ahead of a two-week deep-dive interview series. Each topic section lists structural choices the engineer should validate as worth a 1-hour deep-dive — or flag as owned elsewhere, vestigial, or already documented.

Topics, in order: **hashgraph**, **event-intake**, **reconnect**, **freeze-and-upgrade**, **event-creator**, **reasons-not-to-gossip**.

Sections are delimited by `KICKOFF:BEGIN` / `KICKOFF:END` markers; re-running the extraction prompt for a topic replaces only that section.

<!-- KICKOFF:BEGIN topic=hashgraph -->

## Hashgraph

The hashgraph topic owns the in-memory DAG of non-ancient events, the consensus algorithm itself (round computation, witness/judge classification, fame voting, consensus ordering within a round), and the round-emission boundary. The kickoff goal is to confirm which of these structural choices are worth a 1-hour deep-dive session. Algorithm correctness (the fundamental theorem, judge merge rules, voting paths) routes to the paper or to a separate session with the founder; cross-boundary contracts (event-intake validation, freeze cutoff, signed-state production, PCES replay) are owned by sibling topics and excluded here.

### Candidate decisions

#### HG-01 — Three-layer state split

- **Decision:** Hashgraph splits its mutable state across three classes: a wiring shell (`DefaultHashgraphModule`), a per-event driver (`DefaultConsensusEngine`), and the DAG/algorithm core (`ConsensusLinker` + `ConsensusImpl`).
- **Questions:** Why split the per-event driving from the algorithm core rather than folding both into `ConsensusImpl`? Why is the wiring shell separate from the driver?
- **Tribal-density signal:** The driver class carries the freeze/futureBuffer/linker orchestration loop while `ConsensusImpl` carries only the algorithm — an asymmetric responsibility split with no obvious from-the-code reason.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java` — `DefaultConsensusEngine` class + `addEvent` (lines 38, 104–196, verified 2026-05-11)

#### HG-02 — Two parallel parent lookups in the linker

- **Decision:** `ConsensusLinker` maintains two parallel structures over the same events: a `SequenceMap<EventDescriptorWrapper, EventImpl>` keyed by `birthRound` for windowed retention and a `Map<Hash, EventImpl>` for hash-based parent lookup.
- **Questions:** Why are these two structures kept separately rather than deriving one from the other? Why is hash the lookup key for parent resolution while `birthRound` is the retention key?
- **Tribal-density signal:** Field javadoc explicitly documents the two-structure pattern; the hash map is cleared in lockstep with the `birthRound` window shift inside `setEventWindow`.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/linking/ConsensusLinker.java` — fields + `linkEvent`/`setEventWindow` (lines 45, 53, 79–116, verified 2026-05-11)

#### HG-03 — Seven-step per-event pipeline

- **Decision:** `DefaultConsensusEngine.addEvent` orders its work as: freeze gate → future-event buffer → `linker.linkEvent` → `consensus.addEvent` → `linker.setEventWindow` → `futureEventBuffer.updateEventWindow` → `freezeRoundController.filterAndModify`, with an inner queue that re-feeds events released from the future buffer.
- **Questions:** Why this specific ordering of the seven steps? Why use a queue-based inner loop rather than processing one event without re-entry?
- **Tribal-density signal:** Ordering preserved across refactors; the inner queue accumulates rounds across multiple events released in a single window advance.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java` — `DefaultConsensusEngine.addEvent` (lines 104–196, verified 2026-05-11)

#### HG-04 — Aggressive all-member memoization

- **Decision:** On the first call for an event `x`, `stronglySeeP(x, m)` and `lastSee(x, m)` compute and store results for **all** member indices in a single traversal before returning the slot for `m`.
- **Questions:** Why fill all slots up front rather than computing one and lazily filling others? Why is this pattern repeated identically in both functions?
- **Tribal-density signal:** Asymmetric — the obvious lazy-per-`m` shape is rejected, and the pattern is duplicated across two memoized helpers.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java` — `stronglySeeP` (lines 1054–1104) and `lastSee` (lines 965–1017, verified 2026-05-11)

#### HG-05 — Timed wrapper around `stronglySeeP`

- **Decision:** A `timedStronglySeeP` wrapper exists around `stronglySeeP` solely to record a `dotProductTime` metric, with no semantic difference between wrapper and wrappee.
- **Questions:** Why is `stronglySeeP` singled out for a timing wrapper among the memoized helpers? Why is the timing kept as a separate method rather than inlined into the wrappee?
- **Tribal-density signal:** A semantically transparent wrapper — a candidate for dead-but-not-removed code, untouched across recent refactors; the `stronglySeeP` javadoc explicitly mentions the "generalized dot product" timing motivation that the wrapper records.
- **Confidence:** medium
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java` — `timedStronglySeeP` (lines 880–886, verified 2026-05-11)

#### HG-06 — Authoritative consensus-order key list

- **Decision:** `ConsensusSorter` orders events within a decided round by preliminary consensus timestamp → extended-median walk over `recTimes` → `cGen` → whitened hash; the class javadoc lists this four-step order while the `compare` method javadoc separately names `roundReceived` as the leading key.
- **Questions:** Why is `roundReceived` mentioned in the `compare` method javadoc when the sorter only ever runs per decided round? Why is this exact set of four keys the authoritative tie-break sequence?
- **Tribal-density signal:** The class javadoc and the `compare` method javadoc disagree on the leading sort key; the sorter is constructed once per decided round and discarded.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusSorter.java` — `sort` + `compare` (lines 22–75, verified 2026-05-11)

#### HG-07 — Init-judge gate around `consensus.addEvent`

- **Decision:** `DefaultConsensusEngine.addEvent` consults `consensus.waitingForInitJudges()` once before and once after calling `consensus.addEvent`, blocking output during the snapshot-reconstruction window and using the before/after pair to detect the transition out of waiting.
- **Questions:** Why is the flag re-checked after the add rather than only before? Why are pre-consensus events drained from `consensus.getPreConsensusEvents()` only at the boundary transition?
- **Tribal-density signal:** Two guards bracketing a single call, with branchy post-add handling that exists only at the transition; init-time contract diverges from steady-state and is anchored by an explicit "we cannot know that until we found all the init judges" comment.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java` — `addEvent` init-judge handling (lines 134–171, verified 2026-05-11)

#### HG-08 — Two implementation-only generation fields

- **Decision:** `EventImpl` carries two separate implementation-only generation values, `cGen` and `deGen`, with different assignment lifetimes (`deGen` set during linking, `cGen` set during round decision and cleared after sorting).
- **Questions:** Why two generation fields rather than one? Why are their assignment lifetimes different?
- **Tribal-density signal:** Both generations exist only in the implementation; the paper's NGen has no equivalent of either field, and `cGen` is explicitly assigned and cleared around each sort.
- **Confidence:** medium
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/EventImpl.java` — `cGen`/`deGen` fields (lines 76, 79, verified 2026-05-11); `LocalConsensusGeneration.assignCGen` / `clearCGen` callsites in `ConsensusSorter.sort` (lines 30–35).

### Considered but dropped

- *Topic ownership statement* — boundary fact, not a rationale-bearing decision (criterion 2).
- *EventImpl memoized-slot list* — list of fields is fact; the rationale-bearing decision (aggressive fill) is HG-04 (criterion 2).
- *ConsensusImpl ownership fields including pcesMode* — facet of HG-01; `pcesMode` rationale shared with restart-and-pces (criterion 2 + routing).
- *Round computation rule (parents agree → count strongly-seen)* — algorithm correctness, owned by paper (criteria 2 + 5).
- *Witness definition (round > parent's round)* — definitional, owned by paper (criteria 2 + 5).
- *Three voting paths (firstVote / countingVote / coin round)* — multi-clause and each path is paper-anchored (criteria 3 + 5).
- *Fame voting iterates earlier undecided witnesses* — algorithm correctness (criterion 2).
- *Judge collapse via deterministic merge on branched creators* — paper-anchored merge rule (criterion 5).
- *Whitening for tie-break* — correctness mechanic owned by paper (criterion 5).
- *Extended-median walking pattern (`-1, 1, -2, 2…`)* — tactical numeric walk, not structural (criterion 1).
- *ConsensusSorter constructed-per-round lifetime* — weak tribal signal; minor lifetime fact (criterion 2).
- *ConsensusRound emission tuple shape* — multi-clause; downstream contract owned by signed-state-management (criterion 3 + routing).
- *FutureEventBuffer `PENDING_CONSENSUS_ROUND` option* — facet of HG-03 (criterion 2).
- *Future-event buffer lifecycle (addEvent returns null, updateEventWindow releases)* — facet of HG-03 (criterion 2).
- *Ancient drop responsibility on the linker* — facet of HG-03 (criterion 2).
- *Stale event reporting on `staleEventOutputWire`* — mechanical reporting hook; weak rationale (criterion 2).
- *ConsensusImpl 130-line theorem header* — theory, owned by paper (criteria 2 + 5).

### Routing notes

- *Round computation, witness definition, voting paths, fame iteration, judge merge, whitening, extended-median walk, theorem header* — rationale lives in the Hashgraph paper / SWIRLDS-TR-2020-01; route to the paper or to a separate session with the founder.
- *`pcesMode` flag rationale* — shared with restart-and-pces; let that sibling claim the "what flips it / who reads it" contract.
- *ConsensusRound emission tuple* — downstream contract owned by signed-state-management; producer-side shape can be re-asked there.

### Anchor drift

- `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java` — topic doc cites `addEvent` at "lines ~102–194"; current location is lines 104–196 (same file, +2 line drift on both ends).
- `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java` — all line citations into this file are uniformly shifted by ~+9 lines from the topic doc to current `main`: `stronglySeeP` cited at line 1045, actual line 1054; `lastSee` cited at line 956, actual line 965; `timedStronglySeeP` cited at line 871, actual line 880; `voteInAllElections` cited at line 497, actual line 506; `getCountingVote` cited at line 578, actual line 587; `isCoinRound` cited at line 604, actual line 613; `roundDecided` cited at line 697, actual line 706. All symbols resolve; only the line numbers drift, consistent with a uniform pre-doc-refresh insertion.
- All other anchors (`DefaultHashgraphModule:29`, `ConsensusLinker:45,53,79–116`, `ConsensusSorter:22–75`, `EventImpl` field offsets, `RoundElections:29+`, `InitJudges:16,36`, `FutureEventBuffer`) resolve at the cited locations or within a one-or-two-line tolerance.

<!-- KICKOFF:END topic=hashgraph -->

<!-- KICKOFF:BEGIN topic=event-intake -->

## Event Intake

The event-intake topic owns the ingress pipeline that turns unordered `PlatformEvent`s (from gossip, PCES replay, or the local event creator) into a topologically-ordered stream feeding the hashgraph: hashing (peer events), four validation stages (internal-field, deduplication, signature, orphan-buffer linking), and the multi-stage birth-round ancient filter. The kickoff goal is to confirm which of these structural choices warrant a 1-hour deep-dive — or to flag decisions whose rationale lives in `restart-and-pces` (inline-PCES durability), `event-creator` (self-event precondition), `reasons-not-to-gossip` (silent-drop feedback), or `health-monitor-and-backpressure` (scheduler observation). The PCES handoff itself is excluded; intake's role there is wiring only.

### Candidate decisions

#### EI-01 — Two-input-wire surface

- **Decision:** `EventIntakeModule` exposes two distinct input wires — `unhashedEventsInputWire` for peer events (gossip + PCES replay) and `nonValidatedEventsInputWire` for self-events from the local event creator — so self-events bypass the hashing stage by entering the module after it.
- **Questions:** Why is the hasher bypass exposed as a separate input wire on the module's public surface rather than as an internal dispatch inside the hasher?
- **Tribal-density signal:** The asymmetric wire naming ("unhashed" — named by the stage skipped; "non-validated" — named by the stage entered) makes the bypass visible on the module's public API, not just inside it.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/EventIntakeModule.java` — `EventIntakeModule` interface (line 24, verified 2026-05-11); soldering at `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformWiring.java` lines 65–68, 129–132, 197–200.

#### EI-02 — Orphan buffer module placement

- **Decision:** `OrphanBuffer` and `DefaultOrphanBuffer` live in the `consensus-utility` module under `org.hiero.consensus.orphan`, not in `consensus-event-intake-impl` alongside the other intake stages, even though the buffer is the last stage of the intake pipeline and consumes intake's `eventWindowInputWire`.
- **Questions:** Why is the orphan buffer placed in `consensus-utility` rather than co-located with the rest of the intake pipeline in `consensus-event-intake-impl`?
- **Expected design (optional):** Most plausible rationale is reuse by another module, or a deliberate "shared, no-deps-on-intake" namespace; either way the kickoff goal is to learn which.
- **Tribal-density signal:** The topic doc records an explicit Delta vs. `orphan-buffer.md`: "the buffer also lives in `consensus-utility` (`org.hiero.consensus.orphan`), not under the gossip module as the source doc implies" — placement has shifted but the doc records *what*, not *why*.
- **Confidence:** medium
- **Anchor:** `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java` — class declaration and fields (lines 59, 65, 78, 91, 105, verified 2026-05-11).

#### EI-03 — Deduplication before signature verification

- **Decision:** The five-stage pipeline orders deduplication *before* signature verification, so a duplicate descriptor carrying a forged signature is dropped at the deduplicator without the signature ever being checked.
- **Questions:** Why is deduplication placed before signature verification rather than after? What invariant carries the trust that a duplicate-descriptor event whose signature was never checked is still safe to drop?
- **Tribal-density signal:** Adjacent ordering visible in `DefaultEventIntakeModule.initialize` (lines 103–131); the topic doc flags it as a TBD with the security-adjacent framing on lines 133–138.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java` — `handleEvent` (lines 96–116, verified 2026-05-11); `platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java` — `validateSignature` (line 157, verified 2026-05-11).

#### EI-04 — Birth-round ancient filter at multiple stages

- **Decision:** The birth-round ancient filter (`eventWindow.isAncient(event)`) is applied as a door drop at the deduplicator, the signature validator, and the orphan-buffer entry; the orphan buffer additionally re-checks at release and at window-shift eviction; and the hashgraph linker applies the same predicate defensively at link time.
- **Questions:** Why is the ancient filter repeated at multiple stages rather than applied once at the pipeline entry? Why is the internal-field validator the only intake stage without the gate?
- **Tribal-density signal:** Five intake-side check sites plus one hashgraph-side defensive gate, with the internal-field validator (the cheapest stage) deliberately excluded — an asymmetric placement the topic doc enumerates as a table (lines 308–314) and flags as TBD on lines 110–113.
- **Confidence:** strong
- **Anchor:** five intake-side anchors in `StandardEventDeduplicator.java:97`, `DefaultEventSignatureValidator.java:158`, and `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java` lines 106, 132, 220 (all verified 2026-05-11).

#### EI-05 — RUNTIME bypass in signature validator

- **Decision:** `DefaultEventSignatureValidator.validateSignature` short-circuits on `event.getOrigin() == EventOrigin.RUNTIME` and returns the event unverified, even though self-events (origin RUNTIME) enter the module via the separate `nonValidatedEventsInputWire` and should therefore never reach this stage.
- **Questions:** Why does the validator carry a RUNTIME bypass given that the input-wire split should prevent any RUNTIME event from arriving at the validator? What code path does the bypass currently cover?
- **Tribal-density signal:** Defensive guard for a condition the module topology should already prevent — the rubric's "guards whose order is preserved across refactors" pattern.
- **Confidence:** medium-strong
- **Anchor:** `platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/signature/DefaultEventSignatureValidator.java` — RUNTIME bypass at line 164 (verified 2026-05-11); input-wire split at `PlatformWiring.java:129-132`.

#### EI-06 — Sequential orphan buffer behind a concurrent validator

- **Decision:** The orphan buffer's scheduler is SEQUENTIAL with capacity 500, immediately downstream of a CONCURRENT signature validator; this makes the orphan buffer the narrowest link in the intake pipeline and the dominant backpressure point on intake throughput.
- **Questions:** Why is the orphan buffer scheduler SEQUENTIAL while the upstream signature validator is CONCURRENT? What property of the buffer's work — ordering, single-writer state mutation, lock reduction, something else — makes SEQUENTIAL the right shape here?
- **Tribal-density signal:** Asymmetric scheduler shapes at a pipeline narrowing; buffer capacity 500 is dwarfed by the 5000-capacity sequential deduplicator immediately upstream — the narrow point of the intake pipeline.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/config/EventIntakeWiringConfig.java` (scheduler shape config, verified 2026-05-11); orphan buffer wired in `DefaultEventIntakeModule.java:103-131`.

#### EI-07 — `eventsWithDisparateSignature` accumulator

- **Decision:** When the deduplicator sees a descriptor it has seen before paired with a *new* signature, it increments the `disparateSignatureAccumulator` and lets the event continue through the pipeline rather than dropping it as a duplicate.
- **Questions:** Why does the deduplicator differentiate "same descriptor, new signature" from a plain duplicate at all? What component, if any, depends on the accumulator's value, and on the let-continue behaviour?
- **Tribal-density signal:** The "disparate signature" name reads as a branching/equivocation indicator, but no downstream consumer is documented; the topic doc flags this as TBD on lines 140–143.
- **Confidence:** medium
- **Anchor:** `platform-sdk/consensus-event-intake-impl/src/main/java/org/hiero/consensus/event/intake/impl/deduplication/StandardEventDeduplicator.java` — accumulator increment at line 107 (verified 2026-05-11).

#### EI-08 — `assignNGen` at orphan-buffer release

- **Decision:** `DefaultOrphanBuffer.eventIsNotAnOrphan` calls `assignNGen(nonOrphan, eventsWithParents)` at release time, alongside a monotonic `eventSequenceNumber.getAndIncrement()` that becomes the topological-order contract for downstream consumers.
- **Questions:** What downstream component currently consumes the NGen value assigned here, and what contract does NGen express that the monotonic sequence number alone does not? Given that `hashgraph.md` documents `minNGen` as legacy and `event-creator.md` states "no NGen terminology remains," what role does `assignNGen` play in the current model?
- **Tribal-density signal:** `hashgraph.md` documents minNGen as legacy and `event-creator.md` states "no NGen terminology remains" — yet the orphan buffer still assigns NGen on every release. Strong dead-but-not-removed candidate.
- **Confidence:** medium-strong
- **Anchor:** `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java` — `assignNGen` call at line 228, sequence-number assignment at line 229 (verified 2026-05-11).

### Considered but dropped

- *Topic ownership statement (SDS-01)* — boundary fact, not a rationale-bearing decision (criterion 2).
- *`validatedEventsOutputWire` forks to PCES and BranchDetector (SDS-05)* — BranchDetector ownership not documented in any topic doc; rationale-anchor gap (criterion 2 weak).
- *Pass-through-on-success / null-on-failure pattern (SDS-08)* — facet of EI-03 pipeline structure; rationale weak (criterion 2).
- *Two-bypass paths in sig validator (SDS-11)* — folded into EI-05; the RUNTIME bypass is the load-bearing half.
- *Non-recursive stack walk in release (SDS-13)* — tactical implementation choice with the rationale already inline as a code comment (criterion 1).
- *Sequence-map birth-round keying with lockstep shift (SDS-15)* — facet of EI-04's multi-stage filter placement (criterion 2 weak).
- *Two release paths in orphan buffer (SDS-16)* — internal release mechanism; weaker tribal signal than other candidates (criterion 2 weak).
- *Silent drop policy on intake (SDS-18)* — feedback-contract rationale owned by `reasons-not-to-gossip.md`.
- *`isAncient` re-check at orphan release (TBD-6)* — defensive race-guard with a deterministic likely answer; weaker than the eight kept.
- *`clear` does not reset `eventSequenceNumber` (TBD-8)* — local invariant question; weaker tribal density than the eight kept (criterion 3 marginal).

### Routing notes

- *Durability split via PCES (SDS-19, SDS-20)* — rationale owned by `restart-and-pces`; this engineer can describe intake-side wiring but the contract belongs there.
- *Event-creator feedback loop via PCES (SDS-21)* — producer-side rationale owned by `event-creator`; intake-side enforcement is wiring only.
- *`inlinePcesSyncOption` default-mismatch question (TBD-9, SDS-22)* — owned by `restart-and-pces`; the multi-clause TBD as written also fails criterion 3.
- *BranchDetector ownership* — not documented in any topic doc surveyed (hashgraph, gossip, event-creator, restart-and-pces, reasons-not-to-gossip, health-monitor-and-backpressure); deep-dive prep should clarify who owns it before the engineer leaves.

### Anchor drift

None. All 44 anchors cited in `event-intake.md` resolve at their stated locations in current `main` within ±2 lines (verified 2026-05-11). The topic doc is in sync with the codebase — no line-shift updates needed.

<!-- KICKOFF:END topic=event-intake -->

<!-- KICKOFF:BEGIN topic=reconnect -->

## Reconnect

The reconnect topic owns the recovery path for a node that has fallen too far behind to catch up via gossip alone: fallen-behind detection, paired learner/teacher state transfer over the shared gossip connection, and re-anchoring of consensus-side components after a state swap. The kickoff goal is to confirm which of these structural choices warrant a 1-hour deep-dive — or to flag decisions whose rationale lives in `gossip` (protocol-stack multiplexing), `signed-state-management` (state production / signing internals), `event-intake` (post-reconnect window re-anchoring), `event-creator` (post-reconnect pause via `PlatformHealthRule`), or the `consensus-execution-boundary` (C/E API surface).

### Candidate decisions

#### RC-01 — Module split across consensus/execution boundary

- **Decision:** The reconnect orchestration entry point (`ReconnectModule`) lives in `swirlds-platform-core` on the Execution side of the consensus/execution boundary, while the implementation classes (`DefaultReconnectModule`, `ReconnectController`, learner, teacher, throttle, protocols) live in `consensus-reconnect-impl` on the Consensus side.
- **Questions:** Why is the entry-point/implementation split placed at the consensus/execution boundary rather than collocated in one module? Why this specific division of classes across the boundary?
- **Tribal-density signal:** The topic doc flags this as a two-place boundary crossing for reconnect; the `Boundary handoffs` section in `reconnect.md` names this split as the structurally interesting fact, with cross-link to `consensus-execution-boundary.md`.
- **Confidence:** medium
- **Anchor:** `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/reconnect/ReconnectModule.java` — `ReconnectModule` interface (line 26, verified 2026-05-11); `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/DefaultReconnectModule.java` — `DefaultReconnectModule` class (line 27).

#### RC-02 — Five-phase per-attempt loop in ReconnectController

- **Decision:** `ReconnectController.run()` orders each reconnect attempt as five phases: blocks on `FallenBehindMonitor.awaitFallenBehind()` → submits the fallen-behind status and pauses gossip → clears queues → loops attempting reconnect under a retry cap → resumes gossip on success or exits via `SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE)` after configured maximum failures.
- **Questions:** Why decompose each attempt into these five phases rather than fewer or more? Why does the loop own the full lifecycle — detection through resumption — rather than splitting these stages across multiple components?
- **Tribal-density signal:** Phase boundaries are encoded as method calls without inline phase labels; the retry-cap exit converges four distinct failure conditions (max retries, unexpected exception, unexpected interruption, reconnect-window timeout) onto a single `SystemExitCode`.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java` — `run()` (lines 134–183, verified 2026-05-11); `SystemExitCode.RECONNECT_FAILURE` (exit code 203) at `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/system/SystemExitCode.java:23`, used at `ReconnectController.java` lines 171, 180, 267, 301.

#### RC-03 — Two-clause fallen-behind trigger formula

- **Decision:** `FallenBehindMonitor.checkAndNotify` computes `isBehind` as a two-clause OR: `peersSize * fallenBehindThreshold < reportFallenBehind.size()` OR `(peersSize > 0 && reportFallenBehind.size() == peersSize)`. The second clause covers the case where `fallenBehindThreshold` is configured at `1.0` (where the first clause cannot fire, because `reportFallenBehind.size()` cannot exceed `peersSize`).
- **Questions:** Why is the trigger condition expressed as two OR-ed clauses rather than a single comparison? Why is the second clause necessary?
- **Tribal-density signal:** The second clause is the kind of edge-case belt-and-braces that survives refactors because removing it would silently break the threshold=1.0 case; the field default in `FallenBehindConfig` is `0.50` so the second clause is dormant in the default configuration.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindMonitor.java` — `checkAndNotify` formula (lines 80–81, verified 2026-05-11); `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/config/FallenBehindConfig.java` — `fallenBehindThreshold` field (lines 15–16, default `0.50`).

#### RC-04 — FallenBehindMonitor owns the gossip-pause handshake

- **Decision:** `FallenBehindMonitor` carries a second responsibility beyond detection: `notifySyncProtocolPaused()` and `awaitGossipPaused()`, backed by a separate condition variable `gossipSyncPausedCondition` distinct from the fallen-behind condition. The detection signal and the gossip-pause rendezvous live in the same class and share the same lock domain.
- **Questions:** Why does the same class own both detection and the gossip-pause rendezvous rather than splitting them into separate coordination objects? What relationship between detection and gossip-pause led to their colocation?
- **Tribal-density signal:** The class name implies detection only, but `awaitGossipPaused`'s javadoc explicitly documents that the rendezvous can be called before or after `notifySyncProtocolPaused` — a one-way rendezvous wired into the same class as the fallen-behind signal.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-utility/src/main/java/org/hiero/consensus/monitoring/FallenBehindMonitor.java` — `notifySyncProtocolPaused` (lines 217–225), `awaitGossipPaused` (lines 233–243), `gossipSyncPausedCondition` field (line 36, verified 2026-05-11).

#### RC-05 — Asymmetric learner/teacher throttle

- **Decision:** Reconnect provides a dedicated teacher-side throttle class (`ReconnectStateTeacherThrottle`) that bounds the rate of teacher sessions, but no analogous learner-side throttle class exists; learner-side retry caps and shutdown logic live inline in `ReconnectController.exitIfMaxRetriesOrWait`.
- **Questions:** Why is the teacher-side rate limit expressed as a separate class while the learner-side retry logic is loop-integrated into `ReconnectController`? Why these different structural shapes for the two sides?
- **Tribal-density signal:** The reconnect-refactor proposal explicitly lists a `ReconnectLearnerThrottle` separate from the teacher throttle; the topic doc records the absence as a current-vs-proposal delta — asymmetric structure where symmetry would be the obvious choice.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateTeacherThrottle.java` — class declaration (line 23) with `initiateReconnect` (lines 84–109), `reconnectAttemptFinished` (lines 114–116), `getNumberOfRecentReconnects` (lines 121–123, verified 2026-05-11); learner-side retry at `ReconnectController.java` `exitIfMaxRetriesOrWait` (lines 257–284).

#### RC-06 — Five-phase clear ceremony with "do not alter" comment

- **Decision:** `ReconnectCoordinator.clear()` enforces a five-phase ordering of platform quiescence before swapping the state: (0) flush status state machine, (1) squelch event creation / hashgraph / transaction handler and flush, (2) flush intake / state hasher / signature collector / transaction handler / branch detector / branch reporter pipelines, (3) stop squelching, (4) clear internal data of event intake / gossip / signature collector / event creator / branch detector / branch reporter. The method carries an explicit "the order of the lines within this function are important. Do not alter the order of these lines without understanding the implications of doing so." comment.
- **Questions:** Why is the ordering of the five phases load-bearing? Why is the canonical order specifically: status flush → squelch → pipeline flush → stop squelching → clear data?
- **Tribal-density signal:** Strongest tribal-density signal in the topic — an explicit code comment marking the ordering as load-bearing across refactors, with five phases enumerated as inline comments inside the method.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectCoordinator.java` — `clear()` with explicit ordering comment at lines 66–68 and the five-phase body at lines 65–114 (verified 2026-05-11).

#### RC-07 — First-come peer selection via BlockingResourceProvider

- **Decision:** Peer selection for state transfer is first-come-first-served via a `BlockingResourceProvider<ReservedSignedStateResult>`: `ReconnectController` blocks waiting for a state on the provider while per-peer `ReconnectStatePeerProtocol.shouldInitiate()` instances race to acquire the single provide permit. There is no peer ranking by latency, reputation, health, or any other metric inside `ReconnectController`.
- **Questions:** Why is peer selection first-come rather than ranked? Why this strategy at this point in the design space?
- **Tribal-density signal:** Asymmetric structure where ranked selection would be the obvious choice in a distributed-systems setting where peer quality varies; the topic doc flags this as a TBD (`reconnect.md:62-65`) asking what prevents a single slow teacher from monopolising the slot.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectController.java` — `attemptReconnect` blocking on `peerReservedSignedStateResultProvider.waitForResource` (lines 194–195, verified 2026-05-11); `platform-sdk/base-concurrent/src/main/java/org/hiero/base/concurrent/BlockingResourceProvider.java` — permit acquisition (lines 60–118); `ReconnectStatePeerProtocol.java` — `shouldInitiate` permit race (lines 155–170).

#### RC-08 — State ownership flips at validation

- **Decision:** During a reconnect, the local signed state belongs to Execution until the received state passes `SignedStateValidator`; once validation succeeds, the loaded replacement is installed via `StateLifecycleManager.initWithState` before Consensus modules re-anchor. Ownership transitions at the validation step, not at the start of state transfer or after wiring resume.
- **Questions:** Why does state ownership flip at the validation step specifically — rather than at the start of state transfer or at the moment wiring resumes? Why is validation the right ownership-flip point?
- **Tribal-density signal:** Explicit boundary statement in the topic doc; `DefaultSignedStateValidator.throwIfOld` enforces a not-older-than-current check on both `round` and consensus timestamp, with an explicit "slow/malicious teacher" rationale comment.
- **Confidence:** medium
- **Anchor:** `platform-sdk/docs/consensus-layer/architecture/topics/reconnect.md` — `Boundary handoffs` section (lines 229–239); `platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/DefaultSignedStateValidator.java` — `throwIfOld` (lines 29–64) with rationale comment (lines 34–39, verified 2026-05-11); `ReconnectController.attemptReconnect` validation callsite at line 206 then `loadState` at line 246 invoking `StateLifecycleManager.initWithState`.

### Considered but dropped

- *Topic ownership statement* — boundary fact, not a rationale-bearing decision (criterion 2).
- *DefaultReconnectModule.initialize launches ReconnectController on a dedicated thread* — wiring fact; weak rationale signal (criterion 2).
- *Per-phase facts (detection / peer selection / transfer / load / resumption)* — facets of RC-02 (criterion 2).
- *Single SystemExitCode for four exhaustion paths* — medium-rationale shape subsumed by RC-02 lifecycle.
- *FallenBehindMonitor collects peer reports* — collection fact (criterion 2).
- *`fallenBehindThreshold` proportion default `0.50`* — tactical numeric value (criterion 1).
- *Reconnect rides shared gossip connection* — protocol-stack rationale owned by `gossip.md` (routing).
- *Learner / teacher execute() symmetric exchange* — facets of the protocol; rationale weak (criterion 2).
- *`ReconnectStateSyncProtocol` factory pattern* — implementation pattern; weak rationale (criterion 2).
- *Per-connection peer protocol* — facet of the factory pattern (criterion 2).
- *`ReconnectCompleteAction` submission* — wiring fact (criterion 2).
- *`shouldInitiate` / `shouldAccept` gate cascades* — structural but secondary to RC-07 (selection strategy); can be raised in deep-dive prep.
- *`DefaultSignedStateValidator`'s three checks (round, consensus timestamp, signature threshold)* — secondary to RC-08 (ownership flip); the role is the structural decision, specific checks are tactical.
- *`ReservedSignedStateResult` AutoCloseable success-or-error shape* — tactical container.
- *Spurious threshold trip operator symptom (TBD-5)* — operational, not rationale (criterion 2).
- *Teacher session cap + production scenario (TBD-6)* — operational + tactical + multi-clause (criteria 1, 2, 3).
- *`ReconnectConfig` field name + `SystemExitCode` value (TBD-3)* — tactical fact-gathering (criteria 1, 3).
- *Learner connection source (TBD-8)* — answered by code: `Connection` is passed in as a constructor parameter from the gossip-protocol-framework's `runProtocol()` callback.

### Routing notes

- *Event-intake re-anchor after reconnect (TBD-9)* — owned by `event-intake`. Reconnect TRIGGERS the window reset; intake OWNS the birth-round-window-shift and ancient-filter mechanics.
- *Event-creator pause post-reconnect (TBD-10)* — multi-hop via `health-monitor-and-backpressure` (signal publication) and `event-creator` (PlatformHealthRule gate). No reconnect-specific pause logic in the creator.
- *Caught-up signal beyond exit-from-BEHIND (TBD-11)* — currently UNDEFINED. `FallenBehindMonitor.clear()` resets the boolean, but no explicit "caught-up" callback exists at the C/E boundary; deep-dive prep should clarify whether this gap is intentional.
- *Shared gossip connection multiplexing three protocols* — rationale owned by `gossip.md`; reconnect-side fact (one of three protocols on the shared socket) is wiring only.

### Anchor drift

None. All 17 cited symbols in `reconnect.md` resolve at their stated locations in current `main` within ±1 line (verified 2026-05-11). The topic doc is in sync with the codebase — no line-shift updates needed.

<!-- KICKOFF:END topic=reconnect -->

<!-- KICKOFF:BEGIN topic=freeze-and-upgrade -->

## Freeze and Upgrade

The freeze-and-upgrade topic owns how a `freezeTime` write on the platform state becomes a same-consensus-point pause across all nodes: the trigger propagation, the per-topic behaviour changes during a freeze, the freeze-state save trigger, and the `FREEZING → FREEZE_COMPLETE` status transition that closes the procedure. The kickoff goal is to confirm which of these structural choices warrant a 1-hour deep-dive — or to flag decisions whose rationale lives in `signed-state-management` (on-disk layout), `restart-and-pces` (PCES write during freeze and freeze-state boot path), `event-creator` (the `PlatformStatusRule` implementation), `gossip` and `reasons-not-to-gossip` (the sync allow-set), `health-monitor-and-backpressure` (no freeze-aware change anchored), or outside the consensus layer (the freeze transaction taxonomy, operator-orchestrated JVM shutdown after `FREEZE_COMPLETE`).

### Candidate decisions

#### FU-01 — Distributed freeze ownership

- **Decision:** The freeze procedure has no single orchestrator class; responsibility is split across five modules — `consensus-platformstate` (freeze fields on platform state and the `isInFreezePeriod` predicate), `consensus-hashgraph-impl` (the round-level cutoff in `FreezeRoundController`), `consensus-event-creator-impl` (the `FREEZING` guard in `PlatformStatusRule`), `consensus-gossip-impl` (status-driven sync gating), and `swirlds-platform-core` (the trigger handler at round handling, the save controller, the snapshot manager, and the status state machine).
- **Questions:** Why is the freeze procedure split across five modules rather than consolidated under one orchestrator? Why this specific division of responsibilities?
- **Tribal-density signal:** The topic doc's opening paragraph foregrounds the distribution as the structurally interesting fact; the `Consensus-Layer.md` proposal names consolidation under Execution as the anticipated direction, confirming the current distributed shape is deliberate (or historical-contingent) rather than incidental.
- **Confidence:** strong
- **Anchor:** `platform-sdk/docs/consensus-layer/architecture/topics/freeze-and-upgrade.md` — opening paragraph (lines 9–20) and procedure (lines 163–221); entry points at `platform-sdk/consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateUtils.java` (lines 89–104), `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java` (line 16), `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java` (line 15), `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java` (line 17), and the save controller / snapshot manager / status state machine in `platform-sdk/swirlds-platform-core` (verified 2026-05-11).

#### FU-02 — Two-field freeze-window predicate with re-arm semantics

- **Decision:** `PlatformStateUtils.isInFreezePeriod(consensusTime, freezeTime, lastFrozenTime)` defines "are we in a freeze period" as `freezeTime != null && consensusTime >= freezeTime && (lastFrozenTime == null || lastFrozenTime < freezeTime)` — a two-timestamp relation where `lastFrozenTime` acts as the "consumed" marker that re-arms when `freezeTime` advances.
- **Questions:** Why is the freeze-window predicate expressed as a relation between two timestamps rather than as a single boolean or a monotonic round number? Who writes `lastFrozenTime`, when relative to the freeze-state save, and is the post-restart write of `lastFrozenTime` the same path as the in-freeze write?
- **Tribal-density signal:** Two-clause asymmetric null handling — null on `freezeTime` is bail-out, null on `lastFrozenTime` is permit — the kind of edge-case structure that survives refactors because removing either clause would silently misfire; `updateLastFrozenTime` writes `lastFrozenTime = freezeTime` (not the current consensus time), preserving the re-arm condition.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-platformstate/src/main/java/org/hiero/consensus/platformstate/PlatformStateUtils.java` — `isInFreezePeriod` (lines 89–104, verified 2026-05-11); writer `updateLastFrozenTime` (lines 212–214).

#### FU-03 — Freeze-round birth-round rewrite

- **Decision:** `FreezeRoundController.modifyFreezeRound` rewrites the freeze round's `EventWindow.birthRound` to equal `latestConsensusRound`, with a code comment stating "in case some migration logic is needed."
- **Questions:** What migration logic concretely depends on the rewritten birth round? Why is the rewrite anchored in the round controller rather than at state-save time or at boot of the post-upgrade process?
- **Tribal-density signal:** Explicit rationale-deferred comment "in case some migration logic is needed" duplicated in both the method javadoc and an inline comment — preserved across refactors; the comment names "events created pre-upgrade and post-upgrade can be distinguished" as the purpose but does not name the consumer.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java` — `modifyFreezeRound` (lines 70–86) with rationale comment at lines 73–75 (verified 2026-05-11).

#### FU-04 — `FREEZING` permits event creation only with a non-empty signing buffer

- **Decision:** `PlatformStatusRule.isEventCreationPermitted` special-cases the `FREEZING` status to permit creation iff `signatureTransactionCheck.hasBufferedSignatureTransactions()`; otherwise blocks. Creation is unconditionally permitted only in `ACTIVE` and `CHECKING`; all other statuses block.
- **Questions:** Why is the `FREEZING` exception expressed as "permit creation iff a signing transaction is buffered" rather than as a counter (e.g., permit exactly one signing event) or as a fixed terminal-event protocol? Is the contract "one signing event after the freeze round" or "as many signing events as the buffer holds before the freeze state is signed"?
- **Tribal-density signal:** Asymmetric branch — `FREEZING` is the only status with a conditional permit (`ACTIVE` / `CHECKING` permit unconditionally; all others block); the buffer check is delegated to a separate `SignatureTransactionCheck` interface, suggesting the gating was extracted from the rule's body across a refactor.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/rules/PlatformStatusRule.java` — `isEventCreationPermitted` (lines 37–45, verified 2026-05-11); intent documented at `platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/status/PlatformStatus.java` lines 23–25 (`FREEZING` javadoc).

#### FU-05 — Sync permitted in both `FREEZING` and `FREEZE_COMPLETE`

- **Decision:** `SyncStatusChecker.STATUSES_THAT_PERMIT_SYNC` includes both `FREEZING` and `FREEZE_COMPLETE`; `RpcPeerProtocol.shouldSwitchToRpc` gates only on that set, with no separate freeze branch.
- **Questions:** Why are both `FREEZING` and `FREEZE_COMPLETE` treated identically by the gossip layer? Is there any observable behavioural difference between gossip in `FREEZING` and gossip in `FREEZE_COMPLETE` (events sent, peer scoring, fallen-behind detection), or are the two statuses operationally indistinguishable from gossip's perspective?
- **Tribal-density signal:** The two statuses share the sync allow-set with no separate freeze branch — an asymmetric absence of differentiation given that the two statuses are semantically distinct elsewhere in the codebase (creation gate, exit conditions); the rationale for continuing sync into `FREEZE_COMPLETE` (distribute signatures to laggards) does not by itself require the symmetric `FREEZING` inclusion.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java` — `STATUSES_THAT_PERMIT_SYNC` set (line 17, verified 2026-05-11); gating callsite at `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java` lines 244, 251.

#### FU-06 — `FREEZE_STATE` first-branch short-circuit in save controller

- **Decision:** `DefaultSavedStateController.shouldSaveToDisk` checks `if (signedState.isFreezeState()) return FREEZE_STATE` as the very first branch, ahead of all periodic-snapshot logic, the genesis-state branch, and the periodic-snapshots-enabled config check.
- **Questions:** Why is the freeze-state check placed first in the save-controller decision tree rather than as a parallel branch alongside the periodic-snapshot checks? What does the first-branch placement protect against?
- **Tribal-density signal:** First-branch short-circuit pattern; the periodic-snapshot path could in principle also fire on the freeze round when the periodic timer aligns, and the first-branch position prevents the freeze save from being mis-labelled `PERIODIC_SNAPSHOT` or skipped when periodic saves are disabled (`saveStatePeriod <= 0`).
- **Confidence:** strong
- **Anchor:** `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/components/DefaultSavedStateController.java` — `shouldSaveToDisk` (lines 108–140), with the first branch at line 111 (verified 2026-05-11).

#### FU-07 — `freezeState` persisted in `SavedStateMetadata`

- **Decision:** `SavedStateMetadata` carries a `@Nullable Boolean freezeState` field that is persisted alongside the saved state; next-boot detection that a state was the result of a freeze relies on this persisted flag rather than on reconstructing the predicate from the state's `freezeTime` / `lastFrozenTime` / round number.
- **Questions:** Why persist the freeze flag in metadata rather than reconstruct it at boot from the state's `freezeTime` and `lastFrozenTime`? What does the persisted flag promise that the predicate could not, and where in the boot path does the consumer of this flag live?
- **Expected design (optional):** The boot-path consumer is not anchored in the topic doc (the doc itself says "not anchored cleanly") — part of the kickoff goal is to learn whether the consumer lives in PCES replay gating, event-creation gating, reconnect availability, or somewhere the topic doc does not yet trace.
- **Tribal-density signal:** `Nullable Boolean` (three-valued) rather than `boolean` — suggests an "unknown / pre-freeze-flag-existence" state was a real consideration; introduces a second source of truth for freeze-state identity where in principle the platform-state's `freezeTime` + `lastFrozenTime` already determine it.
- **Confidence:** medium
- **Anchor:** `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/SavedStateMetadata.java` — `freezeState` record parameter at line 104 (verified 2026-05-11); read in `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/state/snapshot/DefaultStateSnapshotManager.java` `saveStateTask` at line 139.

#### FU-08 — Three parallel stops across three layers

- **Decision:** Stopping the consensus pipeline at the freeze cutoff is achieved by three separate filters across three layers: (a) event-level — `DefaultConsensusEngine.addEvent` ignores events once `freezeRoundController.isFrozen()`; (b) round-level inside hashgraph — `FreezeRoundController.filterAndModify` discards later rounds in the same batch as the first freeze round; (c) round-level downstream of hashgraph — `DefaultTransactionHandler.handleConsensusRound` sets a `freezeRoundReceived` flag after the first freeze round and ignores subsequent rounds.
- **Questions:** Why three filter points where one might suffice (e.g., stop accepting events at the engine and let the absence of new events naturally drain downstream)? What does each filter protect against that the others do not — i.e., what scenario produces an event or round that the other two filters would let through?
- **Tribal-density signal:** Three filters at three layers, with `isFrozen` (controller) and `freezeRoundReceived` (handler) as independent boolean states rather than a shared barrier; asymmetric depth where a single barrier would be the simpler shape.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/DefaultConsensusEngine.java` — `addEvent` `isFrozen` check at line 107 (verified 2026-05-11); `platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/FreezeRoundController.java` — `filterAndModify` (lines 35–62), `isFrozen` field at line 22; `platform-sdk/swirlds-platform-core/src/main/java/com/swirlds/platform/eventhandling/DefaultTransactionHandler.java` — `handleConsensusRound` (line 191), `freezeRoundReceived` field at line 82, ignore-subsequent-rounds at lines 202–209.

### Considered but dropped

- *Topic ownership statement* — boundary fact, not a rationale-bearing decision (criterion 2).
- *Freeze trigger originates on Execution side* — boundary fact; consensus side only sees a `freezeTime` write (criterion 2).
- *Five freeze transaction kinds all write same `freezeTime`* — Execution-side taxonomy fact (criterion 2).
- *Two parallel state writes (`WritableFreezeStore` + `WritablePlatformStateStore`)* — Execution-side wiring fact (criterion 2).
- *`FreezePeriodChecker` interface separation* — mechanical interface boundary; weak rationale (criterion 2).
- *`PlatformBuilder` lambda live-binding for `FreezePeriodChecker`* — wiring fact (criterion 2).
- *`PlatformStatus.FREEZING` javadoc as the documented intent* — definitional facet of FU-04 (criterion 2).
- *Synchronous `DefaultStateSnapshotManager.saveStateTask`* — implementation choice; rationale weaker than the eight kept.
- *`FreezeCompleteStatusLogic` terminal status* — terminal-state fact (criterion 2).
- *Gossip continues in `FREEZE_COMPLETE` for signature distribution* — folded into FU-05 (rationale for `FREEZE_COMPLETE` inclusion).
- *`StartupStateUtils.loadStateFile` loads latest saved state* — boot fact (criterion 2).
- *7-step freeze procedure as a whole* — multi-clause; facets covered by FU-01 + FU-03 + FU-08 (criterion 3).
- *Status transition driven by `StateWrittenToDiskAction(isFreezeState=true)` in `FreezingStatusLogic`* — interesting structural coupling, but secondary to FU-06 + FU-07 which cover the freeze-save specialness; the coupling itself is the terminal step in FU-08's stop sequence.
- *Boot-path branch on `SavedStateMetadata.freezeState`* — the rationale is unanchored in the topic doc itself ("not anchored cleanly"); routes as a flag-for-engineer note rather than a kept decision.
- *Crash-recovery ordering between `lastFrozenTime` write, freeze-state save, and `FREEZE_COMPLETE` (TBD-8)* — multi-clause; the ordering aspect is partially covered by FU-06 + FU-07 + FU-08 (criterion 3).

### Routing notes

- *PCES write behaviour during freeze* — owned by `restart-and-pces`. The sibling does not currently anchor this either; deep-dive prep should clarify whether PCES is rolled at the freeze round and what ordering between PCES roll and freeze-state save guarantees for upgrade replay.
- *Backpressure during freeze* — owned by `health-monitor-and-backpressure`. The sibling has no freeze ownership; freeze-and-upgrade explicitly records "no freeze-specific behaviour anchored" so this may simply be confirmation that no freeze-aware backpressure exists.
- *JVM exit / shutdown after `FREEZE_COMPLETE`* — **not anchored in the consensus layer**. The topic doc explicitly flags that the shutdown trigger is not visible in consensus-layer or platform-core code. Likely owned by Hedera node modules or operator orchestration; flag for the engineer to clarify origin.
- *Boot-path branch on `SavedStateMetadata.freezeState` (consumer side)* — **not anchored in either `freeze-and-upgrade` or `restart-and-pces`**. A real documentation gap; flag for the engineer to identify where the boot path consumes the flag (PCES replay gate, event-creation gate, reconnect availability).
- *On-disk layout of saved state* — owned by `signed-state-management`; freeze-and-upgrade keeps only the decision to mark the state as a freeze.
- *Execution-side freeze transaction taxonomy* (FREEZE_ONLY / FREEZE_UPGRADE / TELEMETRY_UPGRADE / PREPARE_UPGRADE / FREEZE_ABORT) — outside the consensus layer; consensus only sees the `freezeTime` write.

### Anchor drift

None. All 18 cited symbols in `freeze-and-upgrade.md` resolve at their stated locations in current `main` within ±1 line (verified 2026-05-11). The topic doc is in sync with the codebase — no line-shift updates needed.

<!-- KICKOFF:END topic=freeze-and-upgrade -->

<!-- KICKOFF:BEGIN topic=event-creator -->

## Event Creator

The event-creator topic owns the decision of when this node should mint a new self-event, the tipset-based selection of other parents, the transaction-fill via a synchronous Execution call, signing, and emission on `createdEventOutputWire`. The kickoff goal is to confirm which of these structural choices warrant a 1-hour deep-dive — or to flag decisions whose rationale lives in `event-intake` (the asymmetric self-event input wire), `freeze-and-upgrade` (the `FREEZING` conditional permit), `restart-and-pces` (PCES-before-gossip ordering), `health-monitor-and-backpressure` (the unhealthy-duration signal), or the tipset source doc / paper (algorithm correctness, snapshot-replacement threshold, no-advancement-no-event gate).

### Candidate decisions

#### EC-01 — Five-layer state architecture

- **Decision:** Event-creator state is split across five classes: a wiring shell (`DefaultEventCreatorModule`), an orchestration manager (`DefaultEventCreationManager`), an algorithm driver (`TipsetEventCreator`), and three algorithm-internal state collaborators (`TipsetTracker`, `TipsetWeightCalculator`, `ChildlessEventTracker`).
- **Questions:** Why split the algorithm driver from the manager rather than folding both into `TipsetEventCreator`? Why three state collaborators inside the algorithm rather than one (or six)?
- **Tribal-density signal:** Mirrors hashgraph's three-layer split (HG-01) but with two extra layers; the manager carries the rule chain, the future-event buffer, the sync-lag calculator, and the phase timer while `TipsetEventCreator` carries only the tipset algorithm; the three state collaborators have non-overlapping responsibilities maintained as parallel structures.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreatorModule.java` — `DefaultEventCreatorModule` class (verified 2026-05-11); `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java:44`; `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java:47`; `tipset/TipsetTracker.java:26`; `tipset/TipsetWeightCalculator.java:31`; `tipset/ChildlessEventTracker.java:23`.

#### EC-02 — FutureEventBuffer at the orchestration layer (second instance)

- **Decision:** `DefaultEventCreationManager` holds its own `FutureEventBuffer` instance (constructed with `FutureEventBufferingOption.EVENT_BIRTH_ROUND`, named `"eventCreator"` for metrics), separate from the `FutureEventBuffer` inside hashgraph's `DefaultConsensusEngine`. Events on `orderedEventInputWire` pass through this buffer before reaching `TipsetEventCreator#registerEvent`.
- **Questions:** Why does event-creator carry its own `FutureEventBuffer` instance rather than consuming events already filtered by hashgraph's buffer? What does each buffer's independent window-shift protect against?
- **Tribal-density signal:** Two independent instances of the same class at adjacent pipeline stages, each with its own lifecycle hooks (`updateEventWindow`, `clear`); the buffer is named `"eventCreator"` to distinguish its metrics from the hashgraph instance.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java` — `futureEventBuffer` field (line 70), construction (lines 116–117), use in `registerEvent` / `setEventWindow` / `clear` (lines 164, 174–176, 196–198, verified 2026-05-11).

#### EC-03 — Self-event tipset asymmetry (`addSelfEvent` does not advance)

- **Decision:** `TipsetTracker.addSelfEvent` constructs the tipset by merging parent tipsets only (`new Tipset(roster).merge(parentTipsets)`) — it does *not* call `advance()` — while `TipsetTracker.addPeerEvent` calls both `merge()` and `advance()` to raise the creator's tip sequence number.
- **Questions:** Why is the self-event's own tip sequence number not advanced in its tipset entry when a peer event's is? What interaction with the orphan buffer's later sequence-number assignment makes this asymmetry necessary?
- **Tribal-density signal:** Load-bearing inline comment with two numbered reasons (*"Do not advance the self generation in the tipset for two reasons: 1. Self advancement does not contribute to the advancement score 2. We just created this event, and it does not yet have a generation to use because it will be assigned by the orphan buffer later. Furthermore, we do not want to assign it here because the orphan buffer might disagree about the value given that event windows are process[ed] asynchronously."*); the asymmetry between paired methods is preserved across refactors.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetTracker.java` — `addSelfEvent` (lines 97–116) with rationale comment at lines 105–110, contrasted with `addPeerEvent` (lines 124–138, verified 2026-05-11).

#### EC-04 — Dual-field `TipsetAdvancementWeight`

- **Decision:** Advancement score is represented as a record with two parallel fields — `advancementWeight` (sum over non-zero-weight nodes; counts toward the snapshot-replacement threshold) and `zeroWeightAdvancementCount` (count of advancing zero-weight nodes; does *not* count toward the threshold). Comparisons treat `advancementWeight` as primary and `zeroWeightAdvancementCount` as tie-breaker.
- **Questions:** Why split the score into two fields rather than fold zero-weight nodes into a single weighted sum (or omit them entirely)? What property of the snapshot-replacement contract makes the separate tracking necessary?
- **Tribal-density signal:** Load-bearing record-level comment (*"If zero weight nodes were not a thing, we could use a long as a tipset advancement score. Or, if it was ok to ignore zero weight nodes, we could do the same as well. But since we don't want to allow zero stake nodes to get stale events, we need to have a mechanism for separately tracking when zero weight nodes have advanced."*) — explicitly documents the asymmetric dual-field design and preserves the rationale with the record.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetAdvancementWeight.java` — record declaration with rationale comment (lines 6–20); `compareTo` tie-break (lines 82–89, verified 2026-05-11).

#### EC-05 — Probabilistic anti-selfishness (pity-pick)

- **Decision:** When the selfishness score is greater than 1, the event creator probabilistically swaps the highest-weight parent for a "pity" parent from an ignored peer, with `beNiceChance = (selfishness - 1) / antiSelfishnessFactor`. Selection among ignored peers is itself probabilistic, weighted by each peer's selfishness score.
- **Questions:** Why is anti-selfishness expressed as a probability gate rather than a deterministic rule (e.g., every Nth event must use an ignored peer)? What property of network behaviour or of consensus progress motivated random sampling here?
- **Tribal-density signal:** Two layers of defensive null-checks inside `selectParentToReduceSelfishness`, each commented as guarding a condition the surrounding logic should already prevent (*"if selfishness score is greater than 1, it is mathematically not possible for the advancement score to be zero. But in the interest in extreme caution, we check anyway, since it is very important never to create events with an advancement score of zero."*; *"this should be impossible, since we will not enter this method in the first place if there are no ignored nodes. But better to be safe than sorry."*).
- **Confidence:** medium-strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java` — selfishness branch of `createEventCombinedAlgorithm` (lines 314–337); `selectParentToReduceSelfishness` (lines 374–439) with defensive comments at lines 403–406 and 418–420 (verified 2026-05-11).

#### EC-06 — Five-rule chain ordering

- **Decision:** `DefaultEventCreationManager` constructs `AggregateEventCreationRules` in fixed order: `MaximumRateRule` → `PlatformStatusRule` → `PlatformHealthRule` → `SyncLagRule` → `QuiescenceRule`. `platformStatusRule` and `quiescenceRule` are also retained as direct fields, not only referenced through the list.
- **Questions:** Why this specific chain order — is it shortest-circuit-first (rate-limit cheapest), severity-based, or driven by some other ordering invariant? Why are `PlatformStatusRule` and `QuiescenceRule` kept as named fields when the other three rules are only list-anchored?
- **Tribal-density signal:** Ordering preserved across refactors; the two singled-out rules (`platformStatusRule` and `quiescenceRule`) have direct callsites (`updatePlatformStatus`, `quiescenceCommand`), so dual references (list + field) suggest those two rules have lifecycle methods the others do not.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/DefaultEventCreationManager.java` — rule-list construction (lines 106–115), direct-field setters at `quiescenceCommand` (lines 183–187) and `updatePlatformStatus` (lines 205–207, verified 2026-05-11); short-circuit at `rules/AggregateEventCreationRules.java` `isEventCreationPermitted` (lines 42–52).

#### EC-07 — `EventTransactionSupplier` as the only synchronous Execution-facing call

- **Decision:** Transactions are pulled from Execution via a functional-interface call (`EventTransactionSupplier.getTransactionsForEvent()`) made *synchronously* inside `TipsetEventCreator.assembleEventObject` at event-build time, in an otherwise fully wire-driven module.
- **Questions:** Why is the transaction supply synchronous rather than wire-driven like every other input on the module? What about transaction supply — timing, ordering, transaction-pool ownership, or something else — led to the synchronous shape?
- **Tribal-density signal:** Topic doc explicitly highlights this as "the only synchronous Execution-facing call"; the supplier is passed at `initialize`-time, not bound at wiring time — a different lifecycle than the wire-driven inputs.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/transaction/EventTransactionSupplier.java` (line 11, verified 2026-05-11); synchronous call at `platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java:480` (`assembleEventObject`); module surface at `platform-sdk/consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/EventCreatorModule.java:28`.

### Considered but dropped

- *`antiSelfishnessFactor` default `10` (TBD-1)* — tactical magic number; explicit anti-pattern in the rubric (criterion 1).
- *Super-majority snapshot-replacement threshold (TBD-2)* — rationale likely paper-anchored in `core/tipset-algorithm.md`; "vs simple-majority" framing also marginal on criterion 4 (criteria 2, 4).
- *No-advancement-no-event creation gate* — algorithm-correctness fact owned by the tipset source doc / paper (criterion 2).
- *`networkSize == 1` bypass* — tactical edge-case (criterion 1).
- *Genesis-event two-clause guard* — tactical edge-case (criterion 1).
- *Clock-malfunction `+1ns` fallback* — tactical defensive adjustment (criterion 1).
- *`QuiescenceCommand` dual state (command + `breakQuiescenceEventCreated`)* — rationale already documented inline in code ("we want to allow creation of only one event to break quiescence until normal events starts flowing through"); kickoff value lower (criterion 2 marginal).
- *Pre-hashing + signing of self-events inside the creator* — covered by **EI-01** (consumer side); routed.
- *`FREEZING` conditional permit in `PlatformStatusRule`* — covered by **FU-04**; routed.
- *`PhaseTimer` event-creation phases* — observability, not structural (criterion 1).
- *Median sync-lag aggregation with linear interpolation in `SyncLagCalculator`* — tactical aggregation choice (criterion 1).
- *Ancient-event canary log in `TipsetTracker.logIfAncient`* — defensive log; weak rationale (criterion 2).
- *Dual maps in `ChildlessEventTracker`* — mechanical multi-index pattern; weak rationale (criterion 2).
- *`AggregateEventCreationRules.getEventCreationStatus()` undefined-return contract* — contract trap; weak structural rationale (criterion 2).
- *`previousAdvancementWeight` carry pattern in `TipsetWeightCalculator`* — mechanical accumulator subsumed by the (dropped) snapshot-replacement decision.
- *`signEvent` inside the creator* — subsumed by **EI-05** (RUNTIME bypass on intake).

### Routing notes

- *Self-event hashing / signing emission pattern* — producer side; **EI-01** covers the consumer-side two-input-wire surface on intake.
- *`FREEZING` conditional permit* — already covered as **FU-04**; the rule lives in event-creator-impl but the rationale is freeze-procedure-driven.
- *PCES-before-gossip ordering for self-events* — rationale owned by `restart-and-pces`; event-creator only emits on `createdEventOutputWire`.
- *Health-signal feed* — `unhealthyDuration` is published by `health-monitor-and-backpressure`; event-creator only consumes it via `PlatformHealthRule`.
- *`antiSelfishnessFactor = 10` and `tipsetSnapshotHistorySize = 10`* — tactical tunables; if the engineer has war-stories about either, raise at deep-dive prep, not at the kickoff.
- *Super-majority snapshot-replacement threshold* — `core/tipset-algorithm.md` is the likely answer source; confirm with the engineer at kickoff, then route to the doc rather than to a deep-dive.

### Anchor drift

None. All 28 anchors cited in `event-creator.md` resolve at their stated locations in current `main` within ±2 lines (verified 2026-05-11). The topic doc is in sync with the codebase — no line-shift updates needed.

<!-- KICKOFF:END topic=event-creator -->

<!-- KICKOFF:BEGIN topic=reasons-not-to-gossip -->

## Reasons Not To Gossip

The reasons-not-to-gossip topic catalogues the *categorical* guards that suppress gossip in current code — global, message-class, and per-peer rules whose intervention shape is all-or-nothing (a condition flips, gossip stops; the condition flips back, gossip resumes). The kickoff goal is to confirm which of these structural choices warrant a 1-hour deep-dive, and to flag decisions whose rationale lives across the seam in `health-monitor-and-backpressure` (graded counterparts and the `communicationOverload` term), `reconnect` (fallen-behind detection), `restart-and-pces` (durability ordering enforcement), `gossip` (fair-sync-selector, RPC lifecycle), or `freeze-and-upgrade` (status transitions). The future-state Sheriff module is out of scope.

### Candidate decisions

#### RG-01 — Self-event durability before gossip

- **Decision:** Self-events traverse the PCES writer's persistence path before they can reach the gossip emission path; gossip suppresses any self-event whose PCES write has not yet been acknowledged, and this ordering is enforced cross-module by the wiring rather than within the gossip module alone.
- **Questions:** Why is the durability ordering enforced as a cross-module wiring invariant rather than as a guard inside gossip itself? What property of restart recovery requires gossip to wait specifically for the PCES writer's acknowledgment of a self-event?
- **Tribal-density signal:** Cross-module ordering with a dedicated config option (`inlinePcesSyncOption`) controlling writer synchrony; rationale is named in [`platform-sdk/docs/core/inlinePces/inlinePces.md`](../../core/inlinePces/inlinePces.md) (branch-on-restart prevention); the current default has shifted from the topic doc's stated `EVERY_SELF_EVENT` to `DONT_SYNC` — see anchor drift.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java:91` — `inlinePcesSyncOption` field default `DONT_SYNC` (verified 2026-05-11); enforcement (wiring INJECT order, writer sync contract) owned by [`topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md).

#### RG-02 — `gossipHalted` as one shared flag with asymmetric exit semantics

- **Decision:** A single `AtomicBoolean gossipHalted` is set by `RpcProtocol.stop()` and `RpcProtocol.pause()` and read at three independent call sites in `RpcPeerProtocol` (the protocol-switch decision, the dispatch loop, and the write-path exit predicate) to bring all gossip activity to a halt before reconnect — and the dispatch and write paths distinguish `gossipHalted` from the remote-side end-of-conversation signal `processMessages` so that the two flags drive different exit speeds.
- **Questions:** Why is gossip-halt expressed as a single global flag read at three call sites rather than per-peer flags or per-call-site logic? What does the asymmetry between `gossipHalted` (exit ASAP to free permits and connection) and `processMessages` (drain gracefully) protect against during reconnect?
- **Tribal-density signal:** Load-bearing multi-paragraph comment at `RpcPeerProtocol.java:406-412` documenting *"Why 2 different rules for exiting?"* — the asymmetric exit-semantics rationale is preserved in code; the flag is read at three independent call sites that compose it differently (alone in `shouldSwitchToRpc`; AND-ed with permit health to form `wantToExit` in `dispatchInputMessages`; AND-ed with `processMessages` in `shouldContinueProcessingMessages`); both set sites call `permitProvider.waitForAllPermitsToBeReleased()`.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcProtocol.java` — `stop()` (lines 211–222) and `pause()` (lines 236–242, verified 2026-05-11); `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java` — `shouldSwitchToRpc()` (lines 244–264), dispatch-loop read at line 340 (`dispatchInputMessages` lines 326–351), and `shouldContinueProcessingMessages()` (lines 416–418) with the *"Why 2 different rules"* comment immediately above at lines 406–412.

#### RG-03 — Allow-list of platform statuses, not deny-list

- **Decision:** Sync initiation and acceptance are gated by an explicit hand-curated constant `STATUSES_THAT_PERMIT_SYNC = Set.of(ACTIVE, FREEZING, FREEZE_COMPLETE, OBSERVING, CHECKING, RECONNECT_COMPLETE)`, rather than by a deny-list of statuses or a class-of-status predicate; the encoding makes "new status defaults to deny" the safe default.
- **Questions:** Why is the permit-set hand-curated as an allow-list rather than encoded as a deny-list or a status-class predicate (e.g., "all post-init statuses except SHUTDOWN")? What property of platform lifecycle made these six the safe subset?
- **Tribal-density signal:** The constant is an explicit `Set.of(...)` of six values exposed both as the raw `Collection<PlatformStatus>` and as a helper predicate `doesStatusPermitSync(...)`; the asymmetric defaulting (adding a new `PlatformStatus` enum value silently denies sync) is preserved by the encoding choice.
- **Confidence:** medium-strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/sync/protocol/SyncStatusChecker.java:17-23` — `STATUSES_THAT_PERMIT_SYNC` (verified 2026-05-11); read by `RpcPeerProtocol.shouldSwitchToRpc()` at line 251.

#### RG-04 — `peerIsBehind` as per-peer suppression, not local reconnect trigger

- **Decision:** When `FallenBehindStatus.OTHER_FALLEN_BEHIND` is observed mid-sync, `state.peerIsBehind` is set on the per-peer handler and consumed both by `checkForPeriodicActions` (to stop initiating syncs to that peer) and by `isBroadcastRunning` (to stop broadcasting self-events to that peer); the local node does *not* enter reconnect itself, and gossip with other peers proceeds unchanged.
- **Questions:** Why is the response to a peer falling behind a per-peer gossip suppression rather than a local-side reconnect trigger or a network-wide signal? What does the local node owe the lagging peer's reconnect flow — i.e., what would break if we kept gossiping to it?
- **Tribal-density signal:** Load-bearing comment at `RpcPeerHandler.java:264-266` — *"don't spam remote side if it is going to reconnect or if we haven't completed even a first sync, as it might be a recovery phase for either for us"*; the flag is set in a single asymmetric branch of `maybeBothSentSyncData` (only the `OTHER_FALLEN_BEHIND` arm, not the `SELF_FALLEN_BEHIND` arm) and read from two independent call sites that gate different channels (sync vs. broadcast).
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java` — set at `maybeBothSentSyncData` line 415; read at `checkForPeriodicActions` line 194 and `isBroadcastRunning` line 541; comment at lines 264–266 (all verified 2026-05-11).

#### RG-05 — Composite `isBroadcastRunning` mixes categorical and graded terms

- **Decision:** `RpcPeerHandler.isBroadcastRunning()` is a four-term AND composite of three *categorical* terms (`broadcastConfig.enableBroadcast()`, `!state.peerIsBehind`, `state.lastSyncFinishedTime != Instant.MIN`) and one *graded* term (`!communicationOverload`). The graded term is explicitly disclaimed from this catalog ("belongs to backpressure"), yet the composite that gates broadcast lives inside the categorical-side handler and includes it.
- **Questions:** Why is the broadcast-permission decision composed by AND-ing three categorical guards with one graded guard inside the same predicate, rather than separating the categorical gate from a graded backpressure gate at distinct layers? What would break if `communicationOverload` were dropped from this composite and only the three categorical terms controlled broadcast?
- **Tribal-density signal:** The composite explicitly straddles the architectural seam the topic doc itself draws between categorical and graded — the topic doc carves the graded term out ("`communicationOverload` overlaps with the backpressure boundary and is treated there") while keeping the composite in this catalog; method-level comment at `RpcPeerHandler.java:530-537` notes the read is unsynchronized and informative-only (*"we won't break if it is slightly delayed in reporting the status"*), an acknowledgment that the composite's truth is approximate.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java:538-543` — `isBroadcastRunning()` (verified 2026-05-11); `communicationOverload` field set at `setCommunicationOverloaded` line 253, fed from `RpcOverloadMonitor` (owned by [`topics/health-monitor-and-backpressure.md`](../architecture/topics/health-monitor-and-backpressure.md)).

#### RG-06 — Dual-channel sync-broadcast preference for fresh self-events

- **Decision:** Fresh self-events are deferred from sync whenever broadcast is running for a peer: the sync send-list filter is parameterised with positive `selfFilterThreshold` / `ancestorFilterThreshold` so the broadcast channel carries them first, while `Duration.ZERO` thresholds are used when broadcast is not running so sync ships them immediately.
- **Questions:** Why is the channel preference expressed as a time-threshold deferral inside the sync filter, rather than as a binary "use broadcast OR sync per peer" routing decision higher up? Given that the filter already defers fresh self-events while broadcast runs, what additional concern motivated extending the same channel-aware shape to the per-peer sync cooldown duration?
- **Tribal-density signal:** Load-bearing comment at `SyncUtils.java:89-92` — *"if it is related to self event or its parent, use shorter time limit. In particular, if broadcast is disabled, that limit will be zero, so all self events and their recursive parents will be sent immediately"*; the same channel preference is also preserved at the cooldown layer (`isSyncCooldownComplete` selects `rpcSleepAfterSyncWhileBroadcasting` vs `rpcSleepAfterSync` depending on `isBroadcastRunning`), so the channel-aware shape appears at two independent code sites rather than as a one-off optimisation.
- **Confidence:** strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/SyncUtils.java:70-122` — `filterLikelyDuplicates` (verified 2026-05-11) with rationale comment at lines 89–92; threshold plumbing at `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/ShadowgraphSynchronizer.java:186-195` (zero-on-broadcast-off at lines 191–192); channel-aware cooldown at `RpcPeerHandler.java:509-516`; threshold configs at `platform-sdk/consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/SyncConfig.java:88-89`.

#### RG-07 — Per-peer serial protocol invariant

- **Decision:** For each peer connection at most one sync exchange is allowed to be in progress at a time, enforced by two cooperating guards in `checkForPeriodicActions`: `state.mySyncData != null` blocks starting a second outgoing sync, and `state.peerStillSendingEvents == true` blocks starting a new sync while the previous receive phase has not yet finished. The two flags clear at distinct lifecycle points (`cleanup` and `sendSyncData` for `mySyncData`; `receiveEventsFinished` and `cleanup` for `peerStillSendingEvents`).
- **Questions:** Why is the per-peer protocol made strictly serial (one sync at a time, receive phase drained before the next can start) rather than allowing pipelined or overlapping exchanges? What state — shared `mySyncData`, intake-counter ordering, or something else — does the invariant protect?
- **Tribal-density signal:** Two independent flags jointly enforce the same invariant with different clearing paths — `peerStillSendingEvents` cleared in `cleanup` (line 243) AND in `receiveEventsFinished` (line 362); `mySyncData` cleared in `cleanup` then re-set in `sendSyncData`; the two guards even differ from sibling guards in return semantics within the chain (they `return true` to keep processing, while neighboring guards `return !wantToExit`).
- **Confidence:** medium-strong
- **Anchor:** `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java` — `checkForPeriodicActions` guard chain lines 199–202 (`peerStillSendingEvents`) and 209, 225–228 (`mySyncData`); flag clears at lines 243, 362, 503 (verified 2026-05-11).

### Considered but dropped

- *Broadcast disabled by configuration (`broadcastConfig.enableBroadcast()`)* — feature flag; tactical, criterion 1. Rationale for the broadcast *channel* is captured by RG-05 and RG-06.
- *Sync cooldown after last sync (`rpcSleepAfterSync`, topic-doc TBD-1)* — pacing decision; the structural channel-aware-duration half is folded into RG-06; the "why pace at all" half shades into operational (criterion 2 marginal).
- *Catalog-vs-narrative documentation shape* — author's doc-organisation choice; not a code-structural decision (criterion 5).
- *Two-entry-points design (`shouldSwitchToRpc` vs `checkForPeriodicActions`)* — the split is a consequence of distinct guard sets already captured in RG-02 (init-time, gossipHalted + status + permits) and RG-07 (dispatch-time, serial-protocol chain), not a separable decision (criterion 3).
- *`peerStillSendingEvents` as a standalone decision (topic-doc TBD-2)* — folded into RG-07 (criterion 3).
- *`mySyncData != null` as a standalone decision (topic-doc TBD-3)* — folded into RG-07 (criterion 3).
- *Fair selector (`SyncGuard.isSyncAllowed`, topic-doc TBD-4)* — routed; see routing notes.

### Routing notes

- *Self-event PCES-before-gossip enforcement (RG-01 partial)* — rationale partly owned by [`topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) (the wiring INJECT order and the inline-PCES writer contract). Confirm at kickoff whether the deep-dive home is RG, `restart-and-pces`, or split.
- *`communicationOverload` graded term inside RG-05* — owned by [`topics/health-monitor-and-backpressure.md`](../architecture/topics/health-monitor-and-backpressure.md); the *composite shape* in `isBroadcastRunning` stays with RG.
- *Fallen-behind detection (counterpart to RG-04)* — `FallenBehindMonitor.report()` and the threshold trigger are owned by [`topics/reconnect.md`](../architecture/topics/reconnect.md); RG owns the gossip-side application of `peerIsBehind`.
- *Fair selector / `SyncGuard.isSyncAllowed` (topic-doc TBD-4)* — gossip.md owns the fair-sync-selector decision (`LruSyncGuard`); RG references it but does not own the rationale. Skip as an RG kickoff candidate.
- *`FREEZING` / `FREEZE_COMPLETE` membership in the RG-03 allow-list* — already covered by FU-04; confirm at kickoff that the freeze-specific rationale lives with [`topics/freeze-and-upgrade.md`](../architecture/topics/freeze-and-upgrade.md) while the allow-list *shape* stays with RG.
- *Sync cooldown rationale (TBD-1, soft routing)* — if the engineer wants to discuss per-peer pacing at the kickoff, raise as a follow-up after RG-06; the structural half (channel-aware cooldown duration) is already in RG-06's scope.

### Anchor drift

- `platform-sdk/consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java` — topic doc cites `inlinePcesSyncOption` default `EVERY_SELF_EVENT`; current default is `DONT_SYNC` at line 91. Authoritative source: [`topics/restart-and-pces.md`](../architecture/topics/restart-and-pces.md) (which already flags this as stale in RG).
- `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/protocol/rpc/RpcPeerProtocol.java` — topic doc cites `shouldSwitchToRpc()` "around lines 261–276"; current location is lines 244–264 (same method, shifted earlier).
- `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/RpcPeerHandler.java` — multiple references with line drift within the same file:
  - `checkForPeriodicActions()` cited 189–192 (cooldown) / 194–197 (peerIsBehind) / 199–202 (peerStillSendingEvents) / 212–215 (fair selector) → current 188–191 / 193–196 / 198–201 / 211–214. Lines 209 and 225–228 (`mySyncData`) unchanged.
  - `broadcastEvent` cited 264–273 → current 263–272 (rationale comment at 264–266).
  - `receiveEventsFinished` flag clear cited 363 → current 362.
  - `isBroadcastRunning()` cited 538–544 → current 538–543.
- `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/SyncUtils.java` — `filterLikelyDuplicates` cited 70–115 → current 70–122 (method body extended).
- `platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/gossip/shadowgraph/ShadowgraphSynchronizer.java` — threshold plumbing cited 191–192 → current span 186–195; the specific `Duration.ZERO` lines remain at 191–192 within that block, so the cite still resolves.

<!-- KICKOFF:END topic=reasons-not-to-gossip -->
