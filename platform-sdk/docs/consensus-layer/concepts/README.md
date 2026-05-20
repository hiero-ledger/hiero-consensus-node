# Concepts

Foundational definitions and canonical mental models. One file per concept — the ideas that need more than a glossary line to anchor, and that recur across multiple topics.

Each concept file gives a precise definition, the mechanics, a worked example, the current-code location, and cross-references to topics and catalog entries that build on it. When an idea fits in a sentence it stays in `../glossary.md`; when it needs a page, it lives here.

## Index

|           File            |       Concept        |                                    Summary                                    |
|---------------------------|----------------------|-------------------------------------------------------------------------------|
| `hashgraph-dag.md`        | Hashgraph DAG        | The two-parent event DAG that the hashgraph maintains in memory.              |
| `rounds-and-witnesses.md` | Rounds and witnesses | Round-created, round-received, and the witness predicate.                     |
| `strongly-seeing.md`      | Strongly-seeing      | Super-majority-of-weight visibility relation between events.                  |
| `birth-round.md`          | Birth round          | The creator-stamped round that drives ancient filtering and future buffering. |
| `coin-rounds.md`          | Coin rounds          | Periodic random-tiebreak rounds that preserve fame-vote liveness.             |
| `judges.md`               | Judges               | Per-creator unique famous witnesses that fix consensus order in a round.      |
| `voting.md`               | Voting               | Virtual fame voting computed from the DAG; first, counting, and coin votes.   |
| `event-lifecycle.md`      | Event lifecycle      | Admitted → ancient → expired, gated by two birth-round thresholds.            |
| `stale-events.md`         | Stale events         | Admitted events that aged out without reaching consensus.                     |
