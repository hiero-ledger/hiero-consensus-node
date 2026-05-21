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
| INV-007 | Every deterministic conclusion on the hashgraph is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-008 | A fame decision, once made, never changes and is the same on every honest node | safety | hashgraph | [TBD] |
| INV-009 | Fame is decided for every witness with probability 1 | liveness | hashgraph | [TBD] |
| INV-010 | Ancestry is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-011 | Seeing is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-012 | Strongly-seeing is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-013 | An event's round assignment is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-014 | An event's witness status is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-015 | An event's round received is monotone and eventually agreed | determinism | hashgraph | [TBD] |
| INV-016 | An event's round is at least as large as every ancestor's round | safety | hashgraph | [TBD] |
| INV-017 | At most one witness per round on any single self-parent chain | safety | hashgraph | [TBD] |
| INV-018 | A round-received r implies fame is decided for every witness in rounds ≤ r | ordering | hashgraph | [TBD] |
| INV-019 | Only witnesses can have a decided fame value | integrity | hashgraph | [TBD] |

INV-007 is the umbrella entry for the deterministic-conclusion meta-property
named in the paper's §IV-C end. INV-010 through INV-015 are the per-component
instantiations of that property. Two further components — fame and consensus
order — are absorbed into INV-008 and INV-002 respectively rather than
carrying their own splits, because the cross-node finality of each is already
the entry's primary claim and the intra-node monotonicity follows from the
same mechanism.

INV-016 through INV-019 are structural one-step consequences of Algorithms 2,
3, and 4 — properties the algorithms force directly without going through the
monotonicity umbrella. They complement the INV-010–INV-015 family: where
those entries say *the value of a derived field is stable over time*, the
INV-016–INV-019 entries say *the relations between fields, or between a
field and the DAG, are constrained by the algorithm's structure*. In
particular, INV-016 (round respects ancestry) and INV-013 (round stable over
time) together pin down the round function as a stable map that respects the
DAG partial order; INV-017 (witness count per chain) and INV-014 (witness
flag stable over time) together pin down the witness set on a chain;
INV-018 records the data dependency between fame finality and round-received
that Algorithm 1's sequencing makes a control-flow rule; INV-019 records
the typing constraint that fame decisions live only on witnesses.

<!--
Row convention, one line per entry, kept in INV-NNN order:
| INV-NNN | Title from entry frontmatter | class | topic-slug[, topic-slug] | enforced|proposed|divergent |
-->
