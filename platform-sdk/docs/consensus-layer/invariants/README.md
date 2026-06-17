# Invariants — Index

Permanent properties of the consensus protocol that hold by design and
**must never change**. A violation is always a defect, never a redesign.

These are the durable truths — they follow from the protocol, the paper, or
the proposal, not from any implementation choice. The implementation may be
rewritten freely underneath them; the property does not move. If a code
change makes the system stop upholding an invariant, the code is wrong, not
the invariant.

- Entry format: see `FORMAT.md`.
- Allowed `topics` values: see top-level `topics.md`.

Pair this catalog with `rules/` (implementation-dependent properties that
*may* legitimately change). When unsure where an entry belongs, apply the
test: "if a correct reimplementation broke this, would that be a bug?"
Yes → invariant, file it here. No → rule.

An invariant's `status` records whether the current implementation upholds it
(`enforced`), has not yet implemented it (`proposed`), or currently violates
it (`divergent`). An invariant is never `retired` — a permanent truth cannot
be superseded, only failed. Treat `status: divergent` as load-bearing: it
marks a known correctness gap, not a stale entry.

## Index

|                                       ID                                       |                                           Title                                           |   Class   |         Topics          |  Status  |
|--------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|-----------|-------------------------|----------|
| [INV-001](INV-001-roundcreated-monotonic-along-ancestry.md)                    | Voting round is monotonic along ancestry — a parent's round never exceeds its child's     | safety    | hashgraph               | enforced |
| [INV-002](INV-002-consensus-order-agreed-by-all-nodes.md)                      | Consensus order is agreed by all nodes                                                    | agreement | hashgraph               | enforced |
| [INV-003](INV-003-consensus-timestamp-agreed-by-all-nodes.md)                  | Consensus timestamp is agreed by all nodes                                                | agreement | hashgraph               | enforced |
| [INV-004](INV-004-staleness-agreed-by-all-nodes.md)                            | Staleness is agreed by all nodes                                                          | agreement | hashgraph               | enforced |
| [INV-005](INV-005-honest-event-eventually-decided-or-stale.md)                 | Every honest event eventually reaches consensus or becomes stale (probability 1)          | liveness  | hashgraph               | enforced |
| [INV-006](INV-006-every-round-eventually-has-a-judge.md)                       | Every round eventually has at least one judge, for infinitely many rounds (probability 1) | liveness  | hashgraph               | enforced |
| [INV-007](INV-007-judge-set-agreed-across-deciders.md)                         | All deciders of a round agree on its judge set                                            | agreement | hashgraph               | enforced |
| [INV-008](INV-008-consensus-once-reached-is-permanent.md)                      | Consensus, once reached, is permanent                                                     | safety    | hashgraph               | enforced |
| [INV-009](INV-009-decided-election-never-flips.md)                             | A decided election never flips                                                            | safety    | hashgraph               | enforced |
| [INV-010](INV-010-consensus-parents-non-ancient-with-matching-birth-rounds.md) | Consensus parents are non-ancient with matching claimed birth rounds                      | integrity | event-intake, hashgraph | enforced |
| [INV-011](INV-011-consensus-events-have-verified-signatures.md)                | Every event used in consensus has a verified creator signature                            | integrity | event-intake, hashgraph | enforced |
| [INV-012](INV-012-birth-round-monotonic-along-ancestry.md)                     | Birth round is monotonic along ancestry                                                   | safety    | hashgraph               | enforced |
| [INV-013](INV-013-min-non-ancient-round-monotonic.md)                          | The minimum non-ancient round never decreases                                             | safety    | hashgraph               | enforced |
| [INV-014](INV-014-every-round-has-a-supermajority-of-judges.md)                | Every round has a supermajority of judges                                                 | safety    | hashgraph               | enforced |
| [INV-015](INV-015-honest-event-coin-is-unpredictable.md)                       | An honest event's coin value is unpredictable                                             | integrity | hashgraph               | enforced |
| [INV-016](INV-016-consensus-order-is-a-strict-total-order.md)                  | Consensus order is a strict total order with unique ranks                                 | ordering  | hashgraph               | enforced |
| [INV-017](INV-017-active-address-book-never-moves-backward.md)                 | The active address book never moves backward                                              | safety    | hashgraph               | enforced |

<!--
Row convention, one line per entry, kept in INV-NNN order:
| INV-NNN | Title from entry frontmatter | class | topic-slug[, topic-slug] | enforced\|proposed\|divergent |
-->
