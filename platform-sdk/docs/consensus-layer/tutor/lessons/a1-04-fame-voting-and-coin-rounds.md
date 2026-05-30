---
id: a1-04-fame-voting-and-coin-rounds
cluster: a1
title: "Fame voting and coin rounds"
pass: 2
prerequisites:
  - a1-02-rounds-and-witnesses
  - a1-03-strongly-seeing
kb_topics_touched:
  - architecture/topics/hashgraph.md
kb_concepts:
  - concepts/voting.md
  - concepts/coin-rounds.md
  - concepts/strongly-seeing.md
  - concepts/rounds-and-witnesses.md
kb_glossary_terms:
  - Virtual voting
  - Coin round
  - Famous witness
  - Witness
  - Strongly seeing
  - Super-majority
kb_invariants:
  - INV-001
kb_deltas:
  - delta-map/hashgraph.md
kb_decisions: []
learning_objectives:
  - Explain why hashgraph voting is "virtual" — each witness's vote on an earlier witness's fame is computed deterministically from the DAG (first votes from seeing, counting votes from the previous round's votes among strongly-seen witnesses) and is never exchanged as a message, so every node computes the same votes from the same graph.
  - Trace the per-voter pass in voteInAllElections — for a candidate witness in election round r, a witness in round r+d casts a first vote when d == 1 (does it see the candidate) and a counting vote when d > 1 (a weighted YES/NO tally over the round-(r+d-1) witnesses it strongly sees), with fame decided the moment either side reaches a super-majority.
  - Define a coin round (d a multiple of coinFreq, default 12) and state its two differences from a counting vote — a coin vote never decides fame, and a voter that does not strongly see a super-majority votes the parity of its event's secure-random coin field instead of the strongly-seen majority.
  - Explain why an unpredictable, per-event coin is what gives the election liveness — an adaptive asynchronous adversary could otherwise hold an election split below a super-majority forever, and the secure-random coin (fixed per event, identical for every honest node that has the event, unpredictable before the event exists) is the property the termination guarantee rests on.
  - State where "fame is decided" ends and the next stage begins — once every witness in the election round has a fame verdict the round is decided, and the collapse of famous witnesses into judges and the fixing of consensus order are a1-05; a famous witness is not yet a judge.
threshold_concepts:
  - "Virtual voting: a witness's vote on an earlier witness's fame is a deterministic function of the hashgraph — computed from seeing (first votes) and from the previous round's votes among the witnesses it strongly sees (counting votes), never sent as a message — and a fame decision is always a super-majority of those computed votes. Coin rounds add an unpredictable per-event bit that never decides fame on its own but guarantees the election cannot be stalled forever."
estimated_session_minutes: 40
status: drafted
last_verified_against: eb37e5b6cd4d4388065f79ed4a9d91867bd92cc2
---

# Fame voting and coin rounds

## Prerequisites

> - **`a1-02-rounds-and-witnesses`** — you can identify a **witness** as the first event by a creator in a round-created; you know only witnesses take part in elections; and you treat a **super-majority** as more than two-thirds of total roster weight, counted across distinct creators, the Byzantine quorum that survives up to one-third faulty weight.
> - **`a1-03-strongly-seeing`** — you can distinguish **seeing** (a single branch-aware ancestry path to a witness) from **strongly-seeing** (a super-majority of distinct creators' paths agreeing on the same canonical witness), and you know strong-seeing is the branch-tolerant relation that makes virtual voting safe.

Beyond that, this lesson assumes the general distributed-systems background the audience brings — in particular the **FLP impossibility** (no deterministic asynchronous protocol can guarantee both agreement and termination under a single fault) and the standard escape of **randomized agreement** (a coin that an adversary cannot predict restores termination). This lesson is where the strong-seeing relation `a1-03` built gets *used*: witnesses vote on each other's fame. It stops short of how the resulting **famous witnesses** are merged into **judges** and how those judges fix **consensus order** — both are `a1-05`, named here only as where a decided election hands off.

## Incoming retrieval probes

This is the fourth mechanism lesson in Cluster A.1. Three things from `a1-02` and `a1-03` resurface and the tutor should consolidate against them when they come up — these are authorial watch-for signals, not an opening quiz.

- **Seeing**, from `a1-03-strongly-seeing`. Canonical statement the tutor consolidates against: *x sees witness y when y is an ancestor of x on a single branch-aware path.* First votes (d == 1) rest directly on seeing; if the learner reaches back to it, affirm and connect the first vote to the relation they already built.
- **Strongly-seeing**, from `a1-03-strongly-seeing`. Canonical statement: *x strongly sees y when a super-majority of distinct creators' paths agree y is the canonical witness — the branch-tolerant relation.* Counting votes (d > 1) tally the votes of the previous round's witnesses the voter *strongly sees*; this is the whole engine of the lesson, so consolidate explicitly if the learner is shaky when the counting first appears.
- **Witness and super-majority**, from `a1-02-rounds-and-witnesses`. Canonical statement: *a witness is a creator's round-opening event; a super-majority is more than two-thirds of roster weight across distinct creators.* Only witnesses vote and are voted on, and every fame decision is a super-majority — a firm grip here keeps the election legible.

## Misconception watchlist

- **"Votes are messages the witnesses send each other."** *(Adjacent-protocol import — PBFT/Raft prepare/commit.)* Sounds like: "when does a witness broadcast its vote?", "where's the vote message in the gossip protocol?" Correction, in line: nothing is sent. Each vote is **computed** from the DAG — a first vote from whether the voter sees the candidate, a counting vote from the votes of the previous round's witnesses the voter strongly sees — so every honest node derives the same votes from the same graph with no extra traffic ([voting.md](../../concepts/voting.md), [glossary: Virtual voting](../../glossary.md#virtual-voting), [`voteInAllElections` #L506-L564](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L506-L564)).
- **"A coin round flips one network-shared coin, or runs a shared-randomness protocol."** *(Adjacent-protocol import — common-coin randomized BFT, e.g. Ben-Or / threshold-signature coins.)* Sounds like: "who generates the round's coin?", "is there a coin-agreement sub-protocol?" Correction: there is no shared coin and no protocol. Each voter uses the **parity of its own event's `coin` field** — a secure-random value the creator stamped on that event at creation — so the "coin" is local to each voting event, not negotiated ([coin-rounds.md](../../concepts/coin-rounds.md), [`ConsensusUtils.coin` #L28-L31](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java#L28-L31)).
- **"A coin round decides the fame."** *(Over-generalization from how counting rounds work.)* Sounds like: "so the coin breaks the tie and settles it," "the coin round picks famous-or-not." Correction: a coin vote **never decides** — the decide step is skipped in a coin round. The coin only *sets votes* (random when no super-majority is strongly seen) so that a *later* counting round can reach a super-majority and decide ([coin-rounds.md](../../concepts/coin-rounds.md), [`coinVote` #L630-L641](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L630-L641), [`voteInAllElections` #L533-L547](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L533-L547)).
- **"A famous witness is the same thing as a judge."** *(Over-generalization — collapsing two stages.)* Sounds like: "so the famous witnesses are the judges," using the words interchangeably. Correction: fame is a per-witness yes/no verdict, and a branched creator can have **more than one** famous witness in a round; a judge is the *one* famous witness per creator after the deterministic merge, and turning famous witnesses into judges (and into consensus order) is `a1-05`. This lesson ends at "famous" ([voting.md](../../concepts/voting.md), [judges.md](../../concepts/judges.md), [`RoundElections` #L35-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L35-L39)).

## Mechanism

`a1-03` built the strong-seeing relation but deferred what it is *for*. This is what: witnesses vote on whether earlier witnesses are **famous**, and the votes are computed from the DAG rather than exchanged. The lesson walks the per-voter pass, the coin round that guarantees the vote terminates, and where a decided election hands off ([hashgraph.md](../../architecture/topics/hashgraph.md), [voting.md](../../concepts/voting.md)).

**Pre-training — terms this lesson integrates** (definitions live in the glossary and concept files; named here to set vocabulary):

- **Virtual voting** (= *fame voting*) — the algorithm by which witnesses in later rounds determine the fame of witnesses in earlier rounds; "virtual" because each vote is a deterministic function of the DAG, never sent as a message ([glossary: Virtual voting](../../glossary.md#virtual-voting), [voting.md](../../concepts/voting.md)).
- **Famous witness** — a witness voted *famous* by virtual voting ([glossary: Famous witness](../../glossary.md#famous-witness)).
- **Election round / candidate** — the round *r* whose witnesses are currently having their fame voted on; each such witness is a *candidate* in that election ([`RoundElections` #L25-L39](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L25-L39)).
- **Round difference `d`** — `round(voter) − r`, the gap between a voting witness's round and the election round; `d` selects which kind of vote is cast ([`voteInAllElections` #L509](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L509)).
- **First vote / counting vote** — the `d == 1` vote (does the voter see the candidate) and the `d > 1` vote (tally the previous round's votes among strongly-seen witnesses); covered in Chunks 1–2 ([voting.md](../../concepts/voting.md)).
- **Coin round / coin field** — the round where `d` is a multiple of `coinFreq`, and the per-event `EventCore.coin` value whose parity supplies a vote there; covered in Chunk 3 ([glossary: Coin round](../../glossary.md#coin-round), [coin-rounds.md](../../concepts/coin-rounds.md)).
- **Super-majority** — more than two-thirds of total roster weight; the threshold a fame decision requires ([glossary: Super-majority](../../glossary.md#super-majority)).

### Chunk 1 — Virtual voting and the first vote `{moment: m1-first-vote}`

Fame is decided by an **election**, one per round. When a witness is added to round *r + d*, it votes on every still-undecided candidate witness in earlier election round *r*, in a single pass through `ConsensusImpl.voteInAllElections` ([#L506-L564](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L506-L564)). The vote is never transmitted: it is a function of the voter's position in the DAG, so every node that holds the same events computes the same vote ([voting.md](../../concepts/voting.md), [glossary: Virtual voting](../../glossary.md#virtual-voting)).

The pass begins by computing the round difference `d = round(voter) − r` ([#L509](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L509)). Votes only flow from strictly-later rounds to earlier ones (`d > 0`); this is well-defined precisely because round-created is monotonic along ancestry — **INV-001** from `a1-02` — so a voter is genuinely in a later round than its candidates ([rounds-and-witnesses.md](../../concepts/rounds-and-witnesses.md), [INV-001](../../invariants/INV-001-roundcreated-monotonic-along-ancestry.md)).

When `d == 1`, the voter casts a **first vote**: it votes YES on the candidate exactly when it *sees* the candidate — that is, when the candidate is the round-*r* witness by its creator that the voter resolves to. `firstVote` walks `firstSee` down to the witness by the candidate's creator in the voter's previous round and checks identity with the candidate ([`firstVote` #L661-L673](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L661-L673)). First votes do not decide anything — the `d == 1` branch only records votes and returns ([#L514-L521](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L514-L521)).

**Load-bearing line (signal):** a first vote is the **seeing** relation from `a1-03`, reused as a yes/no. The voting machinery does not introduce a new primitive here — it reads the DAG the learner already knows how to read. Everything richer is the counting vote, next.

### Chunk 2 — Counting votes and the super-majority decision `{moment: m2-counting-vote}`

When `d > 1`, the voter casts a **counting vote**. It does not look at the candidate directly; it looks at the witnesses of its *own previous round* (`r + d − 1`) that it **strongly sees**, reads how each of *them* voted on the candidate, and tallies that by creator weight ([voting.md](../../concepts/voting.md)). The strongly-seen previous-round witnesses are gathered one per member by `getStronglySeenInPreviousRound` ([#L683-L692](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L683-L692)), then `getCountingVote` sums YES weight and NO weight and reports two facts: which way the majority went, and whether either side is a super-majority ([#L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605)).

The voter adopts the majority side as its own vote. And **if either side is a super-majority, the candidate's fame is decided that way on the spot** — `candidateWitness.fameDecided(...)` ([#L549-L562](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L549-L562)). So votes propagate forward one round at a time — round *r+1* witnesses first-vote, round *r+2* witnesses count those first votes, round *r+3* count the round *r+2* votes — and the earliest a candidate can be decided is `d == 2`, the first counting round.

**Structural detail (grounded in code, not paraphrase):** the tally in `getCountingVote` sums roster **weight per creator** over the strongly-seen witnesses, and the super-majority test is `Threshold.SUPER_MAJORITY` applied to the YES sum and the NO sum independently ([#L599-L601](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L599-L601), [`Threshold.java#L71-L94`](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/base-utility/src/main/java/org/hiero/base/utility/Threshold.java#L71-L94)). Fame can therefore be decided **famous** (YES super-majority) or decided **not famous** (NO super-majority) — both are decisions; an undecided candidate is one where neither sum has yet cleared two-thirds.

**Load-bearing line (signal):** a counting vote is the **strong-seeing** relation from `a1-03` applied to the *previous round's votes*. Strong-seeing is what makes the copied votes safe under branching, and the super-majority is what makes a decision Byzantine-tolerant — the two `a1-02`/`a1-03` ideas combine here into the actual fame verdict.

### Chunk 3 — Coin rounds: keeping the election alive `{moment: m3-coin-round}`

Counting votes alone are not guaranteed to terminate. Against an adversary that controls message timing and up to one-third of weight, the strongly-seen votes can be kept split so neither side ever reaches a super-majority — the FLP wall every asynchronous agreement protocol hits. The escape is randomization, and in the hashgraph it is the **coin round** ([coin-rounds.md](../../concepts/coin-rounds.md)).

A round is a coin round when the round difference is a multiple of the configured coin frequency: `isCoinRound(diff)` is `diff % config.coinFreq() == 0`, with `coinFreq` defaulting to **12** ([`isCoinRound` #L613-L615](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L613-L615), [`ConsensusConfig#coinFreq` #L23](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/config/ConsensusConfig.java#L23)). So with the default, voters at `d = 1..11` vote normally, `d = 12` is a coin round, `d = 13..23` normal, `d = 24` a coin round, and so on until fame decides. A coin round differs from a counting round in exactly two ways ([`coinVote` #L630-L641](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L630-L641), [#L533-L547](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L533-L547)):

1. **It never decides fame.** The coin branch calls `coinVote` and `continue`s — the `fameDecided` step is reached only in the normal-counting branch.
2. **It falls back to the coin when there is no super-majority.** If the voter *does* strongly see a super-majority it still votes that way; otherwise its vote is set to the parity of its own event's coin field rather than the plain majority — `countingVote.isSupermajority() ? countingVote.getVote() : ConsensusUtils.coin(event)`.

The coin bit is `event.getEventCore().coin() % 2 == 0` — the parity of a `long` carried in the event ([`ConsensusUtils.coin` #L28-L31](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java#L28-L31), [`EventCore.coin` field 5 #L70-L75](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/hapi/hedera-protobuf-java-api/src/main/proto/platform/event/event_core.proto#L70-L75)).

**Structural detail (grounded in code, not paraphrase):** the coin value is stamped on the event by its creator at creation, from a **cryptographically-secure** source — `TipsetEventCreator` passes `random.nextLong(0, rosterSize + 1)`, where `random` is a `SecureRandom`, into the event ([`TipsetEventCreator` #L486-L492](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-event-creator-impl/src/main/java/org/hiero/consensus/event/creator/impl/tipset/TipsetEventCreator.java#L486-L492), [`UnsignedEvent` #L51-L63](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-model/src/main/java/org/hiero/consensus/model/event/UnsignedEvent.java#L51-L63)). It does not need to be unbiased — the `coin` javadoc notes an attacker may even predict the bit 90% of the time — it only needs to be *unpredictable in advance*, so that no epsilon of uncertainty can be driven to zero by an adversary ([`ConsensusUtils.coin` #L17-L31](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java#L17-L31)). Because the value is fixed on the event and travels with it through gossip, every honest node computes the *same* bit for that voter — so one coin round is enough to nudge a stuck election toward agreement.

**Load-bearing line (signal):** the coin's job is **liveness, not the decision**. It exists only to defeat the adversary who would keep the tally split; the actual famous/not-famous verdict always comes from a *counting* round reaching a super-majority. Remove the coin and safety is untouched but the election can be stalled forever; remove its unpredictability and the same adversary walks back in.

### Chunk 4 — When the election decides, and what comes next `{moment: m4-decided}`

A single super-majority decides one candidate. The election round as a whole is **decided** when *every* witness in it has a fame verdict: `RoundElections.isDecided()` is `numUnknownFame == 0 && !elections.isEmpty()` — note an empty round is not decided, because a round must have witnesses to decide ([`isDecided` #L99-L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L99-L101)). The voting pass checks this after each fame decision and stops early once the round is fully decided ([#L553-L562](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L553-L562)).

At that point the election's product is a set of **famous witnesses** for the round. What happens to them — collapsing a creator's multiple famous witnesses into a single **judge**, and using the judges to fix consensus order and timestamps — is driven by `roundDecided` and belongs to `a1-05` ([`roundDecided` #L706-L711](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L706-L711)). For this lesson the boundary is sharp: voting ends at *famous*; a famous witness is not yet a judge ([voting.md](../../concepts/voting.md), [judges.md](../../concepts/judges.md)).

**Load-bearing line (signal):** every fame verdict in the election is a **super-majority of computed votes** — first votes seeded from seeing, propagated by counting votes over strong-seeing, unstuck (never decided) by coin rounds. That single sentence is the whole lesson; the contrasting cases below place it against the protocols the learner already knows. *(See the Contrasting cases material, which the tutor draws on at this moment.)*

## Engagement moves

### Moment `m1-first-vote`

*Why load-bearing (exposition):* the lesson's framing is that votes are computed, not sent, and the first vote is the gentlest instance — it is just `a1-03`'s seeing relation reused. If the learner leaves expecting vote messages, every later mechanic is mis-shaped.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner is fluent on `a1-03`'s seeing relation and on BFT voting from other protocols, and may expect votes to be exchanged.
- `answer_shape`: a basis-for-the-vote plus a "no message" claim — *what a first vote is computed from, and that it is computed rather than sent.*
- Framing + prompt (verbatim): "In the protocols you know, a node votes by sending a vote message. Here, a witness one round above a candidate casts its 'first vote' on that candidate — but no message is ever sent. Using only the seeing relation from last lesson: what do you think that first vote is computed from, and what does the voter have to determine about the candidate to vote yes?"
- Confidence elicitation (verbatim, optional): "How sure are you, low to high, before I reveal it?"
- `canonical_answer`: The first vote is computed from the DAG, not sent: the voter votes YES on the candidate exactly when it **sees** the candidate — when the candidate is the round-*r* witness by its creator that the voter resolves to. It is the seeing relation from `a1-03` reused as a yes/no, so every node computes the same vote from the same graph ([voting.md](../../concepts/voting.md), [`firstVote` #L661-L673](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L661-L673)).
- `alternative_correct_answers`:
  - "Whether the voter sees the candidate — it's the seeing relation as a vote, computed from ancestry, nothing transmitted."
  - "It's derived from the graph: yes iff the candidate is the witness the voter sees by that creator; no message changes hands."
  - "From what the voter sees in the DAG — yes when it sees the candidate witness — so all honest nodes get the same vote."
- `followup` (if the learner describes a vote being sent or received): "Where would that message come from — name the gossip step that carries it. If there is none, what in the voter's own ancestry could it read instead to get the same answer?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to the idea of a computed vote and wants to see one concretely.
- Walk (exposition): candidate *w* is a round-5 witness. A round-6 witness *v* (so `d == 1`) is about to first-vote on *w*. *v* resolves, by seeing, the round-5 witness by *w*'s creator that lies in its ancestry; its first vote is YES iff that witness is *w* itself ([`firstVote` #L661-L673](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L661-L673)). **Load-bearing line:** the vote reads the DAG; it is not received.
- Self-explanation prompt (verbatim): "*v* casts a first vote on *w* without any node sending *v* a vote. Which property of computing the vote from *v*'s ancestry, rather than transmitting it, guarantees that a different honest node looking at *w* arrives at the same first vote *v* did?"
- `canonical_answer`: Because the vote is a deterministic function of the DAG and the DAG is the same set of immutable events on every honest node, two nodes computing *v*'s first vote from *v*'s ancestry get identical results — there is no separate message that could be dropped, reordered, or forged to make them disagree. That determinism is exactly what "virtual" means ([voting.md](../../concepts/voting.md), [glossary: Virtual voting](../../glossary.md#virtual-voting)).
- `alternative_correct_answers`:
  - "The vote is a function of fixed ancestry, identical everywhere, so every honest node computes the same bit — no message to lose or fake."
  - "Determinism: same DAG in, same vote out; transmitting could diverge, computing can't."
  - "Because *v*'s ancestry is immutable and shared, the computed vote is reproducible by anyone who has *v*."
- `followup` (if the learner restates that the vote is computed without saying why that forces agreement): "That is *how* it is produced — I am asking why that makes two nodes agree. What is true of *v*'s ancestry on every honest node that a vote message would not guarantee?"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and needs only a verify before the counting vote.
- Prompt (verbatim): "In one sentence: at round difference `d == 1`, what relation does a first vote reduce to, and does casting a first vote ever decide a candidate's fame?"
- `canonical_answer`: A first vote reduces to seeing — yes iff the voter sees the candidate — and it never decides fame; the `d == 1` branch only records votes ([`voteInAllElections` #L514-L521](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L514-L521)).
- `alternative_correct_answers`:
  - "It's the seeing relation as a yes/no; first votes never decide, they only seed the election."
  - "d == 1 means 'do I see the candidate'; no decision happens at the first vote."

### Moment `m2-counting-vote`

*Why load-bearing (exposition):* the counting vote is the lesson's central new mechanism and the point where strong-seeing and the super-majority combine into an actual fame verdict. This is new material, so the worked example leads.

**Move A — worked example with self-explanation.** *Diagnosis tag:* the learner is new to how a counting vote reads the previous round (the common case here).
- Walk (exposition): four equal-weight nodes (super-majority = 3 of 4). Candidate *w* is a round-5 witness; voter *v* is a round-7 witness, so `d == 2`. *v* gathers the round-6 witnesses it strongly sees — say those by B, C, D — reads each one's vote on *w*, and tallies by weight ([`getStronglySeenInPreviousRound` #L683-L692](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L683-L692), [`getCountingVote` #L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605)). If B, C, D all voted YES, that is 3 of 4 — a super-majority — so *w* is decided **famous**. **Load-bearing line:** the voter copies the *previous round's* votes, weighted, and a super-majority decides.
- Self-explanation prompt (verbatim): "*v* does not look at *w* directly — it tallies how the round-6 witnesses it *strongly sees* voted on *w*. Why does the algorithm require *v* to strongly see those round-6 witnesses, rather than merely see them, before counting their votes?"
- `canonical_answer`: Because strong-seeing is the branch-tolerant relation from `a1-03` — it guarantees a super-majority of distinct creators agree on which round-6 witness by each creator is canonical, so a branched or equivocating creator cannot feed *v* a forged vote. Merely seeing one witness would let a single Byzantine creator's path determine a counted vote; strong-seeing forces a Byzantine quorum behind every vote *v* tallies ([strongly-seeing.md](../../concepts/strongly-seeing.md), [voting.md](../../concepts/voting.md)).
- `alternative_correct_answers`:
  - "Strong-seeing carries the distinct-creator super-majority, so the votes *v* copies can't be forged by a fork; plain seeing has no such guarantee."
  - "It's the branch-tolerant relation — counting only strongly-seen votes keeps an equivocating creator from injecting a fake vote."
  - "Because a counted vote has to rest on a Byzantine quorum agreeing, which is exactly what strong-seeing provides and seeing does not."
- `followup` (if the learner says strong-seeing is just "more reliable" without naming branching): "Say what specifically it defends against — what could a creator do, that you saw last lesson, that merely seeing its witness would not catch but strong-seeing would?"

**Move B — prediction-and-reveal.** *Diagnosis tag:* the learner has the counting mechanism and can predict the outcome of a clean tally.
- `answer_shape`: a verdict plus the deciding count — *whether and which way fame decides, and the weight that settled it.*
- Framing + prompt (verbatim): "Four equal-weight nodes, super-majority 3 of 4. A round-7 witness is counting votes on a round-5 candidate *w*. The round-6 witnesses it strongly sees are those by B, C, and D, and all three voted YES on *w*. Does *w*'s fame decide on this vote, and if so, which way and on what count?"
- `canonical_answer`: Yes — the YES weight is B, C, D = 3 of 4, which clears the super-majority, so *w* is decided **famous** on this vote ([#L549-L562](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L549-L562)).
- `alternative_correct_answers`:
  - "Famous — three of four (B, C, D) voted yes, a super-majority, so fame decides yes."
  - "Yes, decided famous; the deciding count is 3/4 YES weight, over two-thirds."
- `followup` (if the learner says "decided" without the direction or the count): "Name both: which way did it decide — famous or not — and what was the exact weight that crossed the threshold?"

**Move C — direct walk with cued check.** *Diagnosis tag:* the learner is fluent and the tutor wants to confirm the two outcomes a counting vote can reach.
- Prompt (verbatim): "A counting vote produces a decision only when a super-majority is reached. Name the two different decisions a super-majority can produce, and say what state the candidate is in when neither is reached."
- `canonical_answer`: A YES super-majority decides the candidate **famous**; a NO super-majority decides it **not famous**; when neither sum clears two-thirds the candidate stays **undecided** and voting continues into later rounds ([`getCountingVote` #L599-L604](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L599-L604)).
- `alternative_correct_answers`:
  - "Famous (yes super-majority) or not famous (no super-majority); otherwise undecided."
  - "Either side can win by super-majority — famous or not-famous — and short of that it remains undecided."

### Moment `m3-coin-round`

*Why load-bearing (exposition):* the threshold-adjacent moment — why a deterministic vote needs a coin at all, and the precise sense in which the coin gives liveness without ever deciding. The learner's BFT background makes the FLP framing answerable, so a prediction can open it; the coin mechanism itself is new and gets a worked example.

**Move A — prediction-and-reveal.** *Diagnosis tag:* the learner knows FLP and randomized agreement and can predict why pure counting is not enough.
- `answer_shape`: an impossibility plus its standard fix — *what can stall pure counting forever, and the general mechanism that restores termination.*
- Framing + prompt (verbatim): "Counting votes only decide when a super-majority forms. Suppose an adversary controls message timing and up to one-third of the weight, and works to keep the strongly-seen votes split so neither side ever reaches a super-majority. From your background: what classical result says a purely deterministic version of this can be kept undecided forever, and what is the standard escape that restores termination?"
- Confidence elicitation (verbatim, optional): "How sure are you, low to high, before I reveal it?"
- `canonical_answer`: The **FLP impossibility** — no deterministic asynchronous agreement protocol can guarantee termination once an adversary controls scheduling — so a purely deterministic counting vote can be held undecided indefinitely. The standard escape is **randomization**: a source of bits the adversary cannot predict, which is exactly what the coin round introduces ([coin-rounds.md](../../concepts/coin-rounds.md)).
- `alternative_correct_answers`:
  - "FLP — async agreement can't be deterministic and live under an adaptive adversary; you add randomness to break it, which is the coin round."
  - "It's the FLP result; the fix is a random coin the adversary can't anticipate, so progress can't be blocked forever."
  - "Deterministic async consensus can be stalled (FLP); randomized agreement escapes it, and the coin round is that randomness."
- `followup` (if the learner names randomness but not why the adversary is the problem): "Say what the adversary is exploiting in the deterministic case — if the votes were a fixed function the adversary could read, what could it always arrange?"

**Move B — worked example with self-explanation.** *Diagnosis tag:* the learner is new to how the coin round actually votes and how it avoids deciding.
- Walk (exposition): with `coinFreq` 12, the round at `d == 12` is a coin round ([`isCoinRound` #L613-L615](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L613-L615)). A voter there still computes its counting vote; if it strongly sees a super-majority it votes that way, otherwise it votes the parity of its own event's coin field — and either way it does **not** decide, it just records the vote and moves on ([`coinVote` #L630-L641](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L630-L641), [#L533-L547](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L533-L547)). **Load-bearing line:** the coin sets a vote, it does not settle the fame.
- Self-explanation prompt (verbatim): "In a coin round the vote can come from the event's secure-random coin field, and the round is forbidden from deciding fame. What would go wrong if a coin round *were* allowed to decide — why must the random bit only set a vote that a later counting round consumes, rather than settle the verdict itself?"
- `canonical_answer`: If a random bit could decide fame, two honest nodes could be driven to opposite verdicts on the same candidate — safety would depend on a coin flip. By forbidding the coin round from deciding and only letting it *align* votes, a decision still requires a later **counting** round to observe a strongly-seen super-majority, so every actual verdict rests on a Byzantine quorum, never on randomness. The coin buys liveness without ever putting safety on the coin ([coin-rounds.md](../../concepts/coin-rounds.md)).
- `alternative_correct_answers`:
  - "Deciding on a coin would risk two honest nodes disagreeing; keeping the decision in a counting round means a super-majority, not luck, settles fame."
  - "Safety can't rest on randomness — the coin only nudges votes so a later super-majority decides; otherwise a flip could split honest nodes."
  - "Letting the coin decide would make the verdict non-deterministic across nodes; restricting it to vote-setting keeps every decision quorum-backed."
- `followup` (if the learner says the coin "breaks the tie" without separating liveness from the decision): "Be precise about what the coin settles and what it doesn't — does the candidate become famous in the coin round, or only later? What still has to happen for the verdict?"

### Moment `m4-decided`

*Why load-bearing (exposition):* the threshold-concept moment — the whole election reduces to "a fame verdict is a super-majority of computed votes," and the contrasting cases place that against the protocols the learner knows. Also the sharp boundary with `a1-05`.

**Move A — contrasting cases with comparison prompt.** *Diagnosis tag:* threshold concept; transfer is the goal. Uses the cases in the Contrasting cases material below.
- Prompt (verbatim): "Compare three ways a witness's fame could be agreed. (1) Classical asynchronous BFT: nodes exchange explicit vote and commit messages, and a value commits on a super-majority. (2) Hashgraph counting vote: each witness's vote is computed from the previous round's votes among the witnesses it strongly sees, never sent, and fame decides on a super-majority. (3) Hashgraph coin round: when the votes stay split, a voter takes the parity of its own unpredictable coin field instead of the majority. Across the three, what is the same, what differs, and which single property do (1) and (2) share that the coin round (3) deliberately gives up?"
- `canonical_answer`: All three reach agreement on a fame/commit verdict and all rest, for an actual decision, on a **super-majority**. They differ in how votes are produced: (1) sends them as messages, (2) computes them from the DAG, (3) seeds them from an unpredictable per-event coin when computation deadlocks. The property (1) and (2) share and (3) gives up is that the vote is a **determined value that decides** — a commit message and a counting vote can each settle the verdict, whereas the coin round's vote is random and is forbidden from deciding; it only restores progress so a later super-majority can decide ([voting.md](../../concepts/voting.md), [coin-rounds.md](../../concepts/coin-rounds.md)).
- `alternative_correct_answers`:
  - "Same: agreement by super-majority. Different: messages vs computed-from-DAG vs random-coin. (1) and (2) both decide on a determined vote; the coin round's vote is random and never decides."
  - "All three need a super-majority to decide; (3) alone uses randomness and is barred from settling fame — it only unsticks the tally for a later counting round."
  - "The shared property is a decision-bearing determined vote (sent in (1), computed in (2)); the coin round trades that away for liveness and cannot decide."
- *Deep invariant (exposition for consolidation, not read as a question):* every fame verdict is a super-majority of votes; whether those votes are sent (classical), computed from the DAG (virtual counting), or seeded by an unpredictable per-event coin (coin round), the >2/3 agreement requirement is constant — and termination in an asynchronous Byzantine setting requires randomness the adversary cannot pre-compute, which the per-event coin supplies locally the way a classical common coin does globally. The DAG stands in for the vote messages; the coin stands in for the common coin.

**Move B — free recall.** *Diagnosis tag:* the learner has worked the mechanism and the tutor wants it consolidated in their own words before the boundary with `a1-05`.
- Prompt (verbatim): "Without looking back: walk the life of one fame election from the first vote to a decision — name what a first vote reads, what a counting vote tallies, what a coin round contributes, and what finally makes the candidate famous."
- `canonical_answer`: A first vote (d == 1) reads whether the voter sees the candidate. Counting votes (d > 1) tally the previous round's votes among the witnesses the voter strongly sees, weighted by creator. A coin round (d a multiple of coinFreq) contributes only liveness — it sets votes (random when no super-majority is strongly seen) but never decides. The candidate becomes famous when a counting round observes a YES super-majority (or not-famous on a NO super-majority); the election as a whole is decided when every witness in it has a verdict ([voting.md](../../concepts/voting.md), [`isDecided` #L99-L101](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/RoundElections.java#L99-L101)).
- `alternative_correct_answers`:
  - "First vote: do I see it. Counting vote: tally the strongly-seen previous round's votes. Coin round: random vote, no decision, just liveness. Famous: a YES super-majority in a counting round."
  - "Seeing seeds it, strong-seeing propagates it, the coin keeps it alive, and a super-majority of counting votes decides famous-or-not."

## Contrasting cases material

The threshold concept is *virtual voting: fame is a super-majority of votes computed from the DAG, with a coin round for liveness only.* The three cases hold "agreeing on a witness's fame" constant and vary how the votes are produced.

- **Case 1 — classical asynchronous BFT (e.g. PBFT).** Nodes exchange explicit vote/commit messages; a value commits when a super-majority of nodes have sent matching votes ([voting.md](../../concepts/voting.md)). *Surface:* an extra message round per decision; agreement is transmitted. *What decides:* a determined vote that a node sends.
- **Case 2 — hashgraph counting vote.** Each witness's vote on a candidate is computed from the votes of the previous round's witnesses it strongly sees; fame decides on a super-majority of that weighted tally ([`getCountingVote` #L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605)). *Surface:* votes read from the DAG; no messages. *What decides:* a determined vote that is computed, not sent.
- **Case 3 — hashgraph coin round.** When the votes stay split, a voter takes the parity of its own secure-random coin field instead of the majority, and the round is forbidden from deciding ([`coinVote` #L630-L641](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L630-L641)). *Surface:* a random per-event bit. *What decides:* nothing — it only realigns votes for a later counting round.

**The deep invariant that survives the surface differences:** in all three, an actual decision is a **super-majority** — more than two-thirds of distinct weight agreeing. Cases 1 and 2 differ only in whether the decision-bearing vote is sent or computed; the DAG stands in for the messages, which is what makes hashgraph voting *virtual*. Case 3 is the liveness escape both classical randomized BFT and the hashgraph need against FLP: termination under an adaptive asynchronous adversary requires randomness the adversary cannot pre-compute. Classical protocols get it from a global common coin; the hashgraph gets it from each event's local, unpredictable coin field — and crucially the coin never decides, so safety still rests entirely on the super-majority. *(How famous witnesses become judges, and how judges fix consensus order, is `a1-05`. This case set is only about how a fame verdict is reached and why the coin is needed.)*

## Completion problems

**Problem 1** *(small step blanked).*
- Statement (verbatim): "Candidate *w* is a round-5 witness. A witness *v* in round 6 is about to vote on *w*. The round difference `d` is ______, so *v* casts a ______ vote, and it votes YES exactly when ______ about *w*. Then say, in one phrase, whether casting this vote can decide *w*'s fame."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/voting.md` and read the Mechanics paragraph — note what distinguishes a first vote (d == 1) from a counting vote, and what a first vote depends on."
  - Rung 2 (verbatim): "`d = round(v) − round(w) = 6 − 5`. At that value, is it a first or counting vote, and does the first-vote branch ever decide fame?"
  - Rung 3 (verbatim, gated on effort): "`d = 1`, so a first vote; *v* votes YES exactly when it *sees* *w*; and a first vote never decides — the d == 1 branch only records votes."
- `canonical_answer`: `d = 1`; a **first** vote; *v* votes YES exactly when it **sees** *w*; and no — a first vote never decides fame, it only seeds the election ([`firstVote` #L661-L673](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L661-L673), [#L514-L521](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L514-L521)).
- `alternative_correct_answers`:
  - "1; first vote; YES iff *v* sees *w*; it cannot decide."
  - "d is 1, so a first vote based on whether *v* sees *w*; first votes never decide fame."
- *Invariant exercised (exposition):* at d == 1 the vote is the seeing relation as a yes/no, and the first-vote branch records without deciding.
- `followup` (if the learner says the first vote can decide): "Re-read the d == 1 branch — does it ever call the fame-decision step, or only set votes and return? What is the earliest `d` at which a decision can happen?"

**Problem 2** *(more blanked — a counting decision).*
- Statement (verbatim): "Four equal-weight nodes; super-majority is 3 of 4. Candidate *w* is a round-5 witness. A round-7 witness *v* counts votes on *w*. The round-6 witnesses *v* strongly sees are those by B, C, and D, and B and C voted YES on *w* while D voted NO. Work the YES weight and the NO weight, then state whether *w*'s fame decides on this vote, and what *v*'s own vote is."
- Hint ladder:
  - Rung 1 (verbatim): "Open `ConsensusImpl.java` and read `getCountingVote` — note that it sums YES weight and NO weight separately and tests each against the super-majority."
  - Rung 2 (verbatim): "Add the weight voting YES (B and C) and the weight voting NO (D). Does either sum reach 3 of 4? And which side is the majority *v* adopts?"
  - Rung 3 (verbatim, gated on effort): "YES weight is B + C = 2 of 4; NO weight is D = 1 of 4. Neither reaches the 3-of-4 super-majority, so *w* does not decide; *v* votes the majority, YES."
- `canonical_answer`: YES weight = B + C = 2 of 4; NO weight = D = 1 of 4. Neither clears the 3-of-4 super-majority, so *w*'s fame does **not** decide on this vote; *v* adopts the majority side and votes **YES** (it carries that vote forward to be counted next round) ([`getCountingVote` #L586-L605](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L586-L605)).
- `alternative_correct_answers`:
  - "YES 2/4, NO 1/4; no decision (neither is a super-majority); *v* votes YES, the majority."
  - "Two yes, one no — below 3/4 either way, so undecided; *v*'s vote is YES."
- *Invariant exercised (exposition):* a counting vote decides only when a side reaches a super-majority; short of that the voter still adopts and forwards the majority side.
- `followup` (if the learner says *w* is decided famous because YES led): "Leading is not the test — what is? Is 2 of 4 *more than two-thirds*? If not, what is *w*'s state, and what does *v* still do with its vote?"

**Problem 3** *(most faded — produce the mechanism from a scenario).*
- Statement (verbatim): "A colleague from a PBFT background asks why the hashgraph needs a 'coin round' and cannot just keep counting votes until one side wins. Answer with three things: (a) what makes a round a coin round; (b) what a voter does in a coin round when it does *not* strongly see a super-majority, and whether that decides fame; and (c) why the coin must be *unpredictable in advance* — what an adversary could do to the election if it were predictable."
- Hint ladder:
  - Rung 1 (verbatim): "Open `concepts/coin-rounds.md` and read the Definition and Mechanics; then open `ConsensusImpl.java` and read `isCoinRound` and `coinVote`, and the `coin` javadoc in `ConsensusUtils.java`."
  - Rung 2 (verbatim): "(a) What arithmetic on the round difference makes a coin round? (b) In `coinVote`, what supplies the vote when there is no strongly-seen super-majority, and does the coin branch ever call the decide step? (c) The `coin` javadoc says the bit just needs to be unpredictable before the event exists — what could an adversary controlling timing arrange if it could predict every voter's coin?"
  - Rung 3 (verbatim): "(a) `diff % coinFreq == 0` (coinFreq default 12). (b) The vote is the parity of the voter's own secure-random `coin` field, and it never decides — the coin branch sets the vote and continues. (c) A predictable coin lets an adaptive adversary keep arranging the split and the fallback bits so no super-majority ever forms; an unpredictable coin can't be dodged, so a coin round eventually aligns enough votes for a later counting round to decide."
  - Rung 4 (verbatim, gated on effort): "(a) A round is a coin round when the round difference is a multiple of `coinFreq` (default 12): `isCoinRound(diff)` is `diff % config.coinFreq() == 0`. (b) When the voter does not strongly see a super-majority, its vote is set to the parity of its event's secure-random `coin` field (`coin() % 2 == 0`); the coin round never decides — it only records votes so a later counting round can reach a super-majority. (c) The coin must be unpredictable before the event exists because an adversary that controls scheduling and up to one-third of weight could otherwise keep the strongly-seen votes split below a super-majority forever (the FLP wall); a coin it cannot predict means it cannot keep dodging convergence, so with probability 1 a coin round eventually aligns the votes and a counting round decides."
- `canonical_answer`: (a) A coin round is one where the round difference is a multiple of `coinFreq` (default 12): `diff % config.coinFreq() == 0`. (b) When the voter does not strongly see a super-majority, its vote is the parity of its own secure-random `coin` field; the coin round never decides fame — it only sets votes so a later counting round can reach a super-majority. (c) The coin must be unpredictable in advance because an adversary controlling message timing and up to one-third of weight could otherwise hold the strongly-seen votes split below a super-majority indefinitely (FLP); an unpredictable, per-event coin — identical for all honest nodes once they have the event — cannot be steered, so a coin round eventually unsticks the tally and a counting round decides ([`isCoinRound` #L613-L615](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L613-L615), [`coinVote` #L630-L641](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusImpl.java#L630-L641), [`ConsensusUtils.coin` #L17-L31](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java#L17-L31)).
- `alternative_correct_answers`:
  - "(a) round difference a multiple of coinFreq (12 by default); (b) parity of the voter's secure-random coin field, and it never decides — it only sets votes for a later counting round; (c) a predictable coin lets an adaptive adversary keep the split below super-majority forever, so unpredictability is what guarantees the election terminates."
  - "(a) `diff % coinFreq == 0`; (b) the event's random coin parity, no decision; (c) without unpredictability the adversary defeats liveness à la FLP — the coin it can't predict is what forces eventual agreement."
- *Invariant exercised (exposition):* the coin gives liveness, not the decision — it is the FLP escape, and only its unpredictability (not its fairness) is load-bearing; the verdict still comes from a super-majority counting vote.
- `followup` (if the learner says the coin "breaks the tie" and decides, or omits the adversary in (c)): "Separate the two: does the coin round decide fame, or only set votes for later? And in (c), name what the adversary is exploiting that a predictable coin would hand it."

## Delta callout

`[TBD: delta-map/hashgraph.md is not yet authored — the delta-map/ directory currently holds only README.md, so there is no current-versus-proposed entry for fame voting or coin rounds to summarize.]` Status: **not started** (per the [delta-map index](../../delta-map/README.md)). When that delta lands it will be linked here as `../../delta-map/hashgraph.md`. One historical note that is *not* a pending change: the coin bit is now derived from the dedicated `EventCore.coin` field, where earlier code used the middle bit of the voting event's signature — current code is the `coin`-field form ([coin-rounds.md](../../concepts/coin-rounds.md)). The hashgraph topic's own forward notes concern a future single Consensus public API and a Sheriff module ([hashgraph.md](../../architecture/topics/hashgraph.md)); neither changes how votes or coin rounds are computed. *(Exposition; no learner-facing prompt.)*

## Transfer prompt

Forward framing (motivational only): the next lesson, `a1-05`, takes the famous witnesses this election produces and collapses them into **judges** that fix consensus order. The question below is answerable now, purely from the coin mechanism you just worked.

- Prompt (verbatim): "You've seen that a voter falls back to the parity of its event's coin field only when it does not strongly see a super-majority, and that the coin value is a secure-random number the creator stamps on its event at creation. Suppose a creator instead derived its coin deterministically from public event content — say the hash of its parents — so anyone could compute it before the event was gossiped. Walk through how an adversary controlling message timing could use that to keep one candidate's fame undecided indefinitely, and name the exact property of the coin field that the liveness guarantee depends on."
- `canonical_answer`: If the coin were a public function of event content, an adversary that controls scheduling (and up to one-third of weight) could compute every honest voter's fallback bit in advance and arrange which witnesses each voter strongly sees so that the votes — including the now-predictable coin fallbacks — stay split below a super-majority on every round, forever; nothing would ever force convergence. The property the guarantee depends on is that the coin is **unpredictable before the event exists** (a secure-random value chosen at creation), combined with being **fixed per event and identical for all honest nodes** once gossiped — so the adversary cannot anticipate or steer the fallback bits, and a coin round eventually aligns enough votes for a counting round to decide ([coin-rounds.md](../../concepts/coin-rounds.md), [`ConsensusUtils.coin` #L17-L31](https://github.com/hiero-ledger/hiero-consensus-node/blob/main/platform-sdk/consensus-hashgraph-impl/src/main/java/org/hiero/consensus/hashgraph/impl/consensus/ConsensusUtils.java#L17-L31)).
- `alternative_correct_answers`:
  - "A predictable coin lets the timing-adversary precompute the fallbacks and keep the tally split forever; liveness depends on the coin being unpredictable-in-advance and the same for all honest nodes — so it can't be dodged."
  - "If the bit were public the adversary schedules around it indefinitely (FLP returns); the load-bearing property is secure-random-at-creation plus fixed-per-event, which the adversary can neither predict nor steer."
  - "Knowing the coins ahead of time, the adversary arranges strong-seeing so no super-majority forms; the guarantee rests on the coin being unpredictable before the event and identical everywhere afterward."
- `followup` (if the learner says "it breaks randomness" without the timing/scheduling mechanism): "Be concrete about *how* the adversary exploits knowing the coins — what does it get to choose each round, and what would it arrange the strongly-seen votes and fallbacks to avoid?"

## Close-out retrieval

- Free-recall summary (verbatim): "In your own words: what makes hashgraph voting 'virtual,' how does a counting vote decide a witness's fame, and what is the coin round for?"
  - `canonical_answer`: Voting is virtual because every vote is computed deterministically from the DAG and never exchanged — a first vote from whether the voter sees the candidate, a counting vote from the previous round's votes among the witnesses it strongly sees. A counting vote decides a candidate's fame when the weighted YES or NO tally reaches a super-majority (more than two-thirds of distinct weight) — famous on a YES super-majority, not famous on a NO. The coin round exists only for liveness: when votes stay split it sets a voter's vote to the parity of its unpredictable per-event coin, never deciding itself, so an adversary cannot keep the election undecided forever.
  - `alternative_correct_answers`:
    - "Virtual = computed from the DAG, not sent; a counting vote tallies the strongly-seen previous round's votes and decides on a super-majority; the coin round is the FLP escape that keeps the election live without ever deciding."
    - "Votes are a function of the graph (seeing for first votes, strong-seeing for counting); fame decides on a >2/3 tally; the coin round injects unpredictable randomness only to guarantee termination."
- Successive-relearning tags (exposition; added to the learner's relearning queue): threshold concept *virtual voting with a coin round for liveness* —
  - Day 1: recall that votes are computed from the DAG (not sent) and that a fame verdict is always a super-majority.
  - Day 3: apply it — work a d == 1 first vote and a d == 2 counting vote in a four-node graph, and say when fame decides.
  - ~2 weeks: explain the coin round — what triggers it, why it never decides, and why an unpredictable per-event coin is what gives the election liveness against an adaptive adversary.

## Open questions

- **`[TBD]` Delta callout.** `delta-map/hashgraph.md` does not yet exist (the `delta-map/` directory holds only `README.md`), so there is no current-versus-proposed difference for fame voting or coin rounds to summarize. Deferred until the hashgraph delta-map entry is authored; the callout links the file once it lands.
- **`[TBD]` No catalogued invariant for the voting liveness/termination guarantee.** The property this lesson's coin-round material rests on — every fame election eventually decides — is a liveness guarantee, but `invariants/` currently catalogs only INV-001 (round-created monotonicity, a safety property). There is no INV-NNN stating "fame is eventually decided for every witness." The hashgraph topic raises the same gap as `[TBD: INV-NNN]` ([hashgraph.md](../../architecture/topics/hashgraph.md)). Deferred until the invariants catalog is extended.
