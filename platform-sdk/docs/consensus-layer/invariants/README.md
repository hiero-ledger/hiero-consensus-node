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

|     ID     | Title | Class | Topics | Status |
|------------|-------|-------|--------|--------|
| _none yet_ |       |       |        |        |

<!--
Row convention, one line per entry, kept in INV-NNN order:
| INV-NNN | Title from entry frontmatter | class | topic-slug[, topic-slug] | enforced\|proposed\|divergent |
-->
