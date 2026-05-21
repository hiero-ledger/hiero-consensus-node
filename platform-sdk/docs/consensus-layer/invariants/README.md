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

| ID | Title | Class | Topics | Status |
|----|-------|-------|--------|--------|
| INV-001 | Honest nodes agree on whether a transaction is output | agreement | hashgraph | [TBD] |
| INV-002 | Honest nodes agree on the order of every consensus output | ordering | hashgraph | [TBD] |
| INV-003 | Every transaction submitted to an honest node is eventually output by every honest node | liveness | hashgraph, gossip | [TBD] |
| INV-004 | Honest hashgraphs share identical ancestor sub-DAGs for any shared event | agreement | hashgraph | [TBD] |
| INV-005 | Every event in an honest hashgraph is creator-signed and parent-resolvable | integrity | hashgraph | [TBD] |
| INV-006 | No event can strongly see both branches of a fork | safety | hashgraph | [TBD] |
| INV-007 | Deterministic conclusions on the hashgraph are monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-008 | A fame decision, once made, never changes and is the same on every honest node | safety | hashgraph | [TBD] |
| INV-009 | Fame is decided for every witness with probability 1 | liveness | hashgraph | [TBD] |

<!--
Row convention, one line per entry, kept in INV-NNN order:
| INV-NNN | Title from entry frontmatter | class | topic-slug[, topic-slug] | enforced\|proposed\|divergent |
-->
