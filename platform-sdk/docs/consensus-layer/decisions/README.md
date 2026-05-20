# Decisions — Index

Architecturally significant decisions about the consensus layer, recorded as ADRs
of the form **context → decision → consequences**.

This is the catalog of choices the team has deliberately made and wants future
readers to understand without re-deriving the reasoning. Entries are
independent and self-contained: one decision per entry. ADRs are linked to
each other via the `related.decisions` field in frontmatter (e.g., a successor
named under `related.decisions` for a `superseded` entry), not nested.

- Entry format: see `FORMAT.md`.
- Allowed `topics` values: see top-level `topics.md` (when established).

Entries are born `proposed` and become `accepted` once the deciders have
signed off. `superseded` marks a decision replaced by a later ADR (name the
successor under `related.decisions`); `deprecated` marks one that no longer
applies because the underlying code or design has changed but no replacement
decision exists. Treat `status` as load-bearing — keep the row here and the
frontmatter `status:` in sync.

## Index

| ID | Title | Topics | Date | Status | Summary |
|----|-------|--------|------|--------|---------|
| [ADR-001](ADR-001-pces-state-snapshot-coordination.md) | No Coordination Between PCES Writer and Signed State Writer for Snapshot PCES Copy | signed-state, pces | 2026-05-08 | accepted | The signed state writer copies PCES files into the snapshot directory on a best-effort basis after the merkle state is written, accepting occasional copy failures rather than coordinating with the PCES writer. |
| [ADR-002](ADR-002-execution-freeze-signature-handoff.md) | Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus | freeze-and-upgrade, execution-layer-interface | 2026-05-12 | accepted | The execution layer blocks the return of `onSealConsensusRound` at freeze time until its freeze-block signature transaction is in the pool and a threshold of freeze-block signatures has been collected, bounded by a maximum timeout. Temporary; to be removed once blocks are signed with TSS signatures. |

<!--
Row convention, one line per entry, kept in ADR-NNN order:
| [ADR-NNN](ADR-NNN-short-slug.md) | Title from entry frontmatter | topic-slug[, topic-slug] | YYYY-MM-DD | proposed|accepted|superseded|deprecated | One or two sentences capturing what was decided and why. |
-->
