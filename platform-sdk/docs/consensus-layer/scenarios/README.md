# Scenarios — Index

Per-file records of edge cases, near-misses, and historical incidents, each with
**setup → sequence → observable signature → mitigation**, marked for how far it has
been verified.

This is the catalog of incident and edge-case knowledge that the team otherwise
carries as undocumented war stories. Entries are independent and self-contained: one
scenario per file, with its own timeline, signature, and (where known) mitigation.
Many entries may share an observable signature — they are linked by the `symptoms`
tag, not nested. The catalog grows by **manual curation only** (war-story interviews,
Diagnostician/Workbench handoffs); nothing auto-feeds it.

- Entry format: see `FORMAT.md`.
- Allowed symptom values: see top-level `symptoms.md` (`SYM-NNN`), shared with `heuristics/`.
- Consumed by: Diagnostician (signature match), Workbench (scenario reconstruction),
  Test Scaffold Generator (verification handoff), Change Reviewer, Tutor.

Entries are born `draft` and never reach `verified` on reasoning alone — only a real
instance or a reproducing test gets them there. Treat `status` and `verification` as
load-bearing.

## Index

|                                ID                                |                                                           Title                                                            | Symptoms |  Topics   | Verification |  Status  |
|------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|----------|-----------|--------------|----------|
| [SCN-001](SCN-001-same-round-judge-ancestry-stalls-consensus.md) | A round's judge exempted from clearing has another same-round judge in its ancestry — consensus stalls after roster change | SYM-001  | hashgraph | observed     | verified |

<!--
Row convention, one line per entry, kept in SCN-NNN order:
| SCN-NNN | Title from entry frontmatter | SYM-NNN[, SYM-NNN] | topic-slug[, topic-slug] | observed\|test-reproduced\|reasoned-only | draft\|reviewed\|verified |
-->
