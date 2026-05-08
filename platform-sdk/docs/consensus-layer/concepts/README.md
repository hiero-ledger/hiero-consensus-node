# Concepts

Foundational definitions and canonical mental models. One file per concept (hashgraph DAG, rounds and witnesses, strongly-seeing, birth-round, etc.). Used by the Tutor curriculum to ground later content.

## Index

|                        File                        |       Concept        |                                    Summary                                    |
|----------------------------------------------------|----------------------|-------------------------------------------------------------------------------|
| [hashgraph-dag.md](hashgraph-dag.md)               | Hashgraph DAG        | The two-parent event DAG that the hashgraph maintains in memory.              |
| [rounds-and-witnesses.md](rounds-and-witnesses.md) | Rounds and witnesses | Round-created, round-received, and the witness predicate.                     |
| [strongly-seeing.md](strongly-seeing.md)           | Strongly-seeing      | Super-majority-of-weight visibility relation between events.                  |
| [birth-round.md](birth-round.md)                   | Birth round          | The creator-stamped round that drives ancient filtering and future buffering. |
| [coin-rounds.md](coin-rounds.md)                   | Coin rounds          | Periodic random-tiebreak rounds that preserve fame-vote liveness.             |
| [judges.md](judges.md)                             | Judges               | Per-creator unique famous witnesses that fix consensus order in a round.      |
| [voting.md](voting.md)                             | Voting               | Virtual fame voting computed from the DAG; first, counting, and coin votes.   |
| [event-lifecycle.md](event-lifecycle.md)           | Event lifecycle      | Admitted → ancient → expired, gated by two birth-round thresholds.            |
| [stale-events.md](stale-events.md)                 | Stale events         | Admitted events that aged out without reaching consensus.                     |
