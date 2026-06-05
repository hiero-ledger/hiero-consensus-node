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

|                                    ID                                     |                                                   Title                                                   |    Date    |  Status  |                                                                                                                                                                                       Summary                                                                                                                                                                                       |
|---------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [ADR-001](ADR-001-pces-state-snapshot-coordination.md)                    | No Coordination Between PCES Writer and Signed State Writer for Snapshot PCES Copy                        | 2026-05-08 | Accepted | The signed state writer copies PCES files into the snapshot directory on a best-effort basis after the merkle state is written, accepting occasional copy failures rather than coordinating with the PCES writer.                                                                                                                                                                   |
| [ADR-002](ADR-002-execution-freeze-signature-handoff.md)                  | Block `onSealConsensusRound` to Hand Off Freeze Block Signatures from Execution to Consensus              | 2026-05-12 | Accepted | The execution layer blocks the return of `onSealConsensusRound` at freeze time until its freeze-block signature transaction is in the pool and a threshold of freeze-block signatures has been collected, bounded by a maximum timeout. Temporary; to be removed once blocks are signed with TSS signatures.                                                                        |
| [ADR-003](ADR-003-remove-pces-recovery-method.md)                         | Remove `SwirldsPlatform.performPcesRecovery()` and Drive ISS Recovery On the Spot                         | 2026-05-19 | Accepted | The platform no longer carries a built-in offline ISS-recovery entry point. When recovery is needed, a one-off driver is written against the platform of the day.                                                                                                                                                                                                                   |
| [ADR-004](ADR-004-retain-observing-status-for-self-event-recovery.md)     | Retain the OBSERVING Platform Status for Self-Event Recovery After Disk Loss                              | 2026-06-02 | Accepted | PCES now guarantees that all gossiped events are on disk after shutdown, making OBSERVING redundant for ordinary crashes. The status is kept anyway as the only recovery path for a node that returns with a corrupted or wiped disk, where gossip is the sole way to relearn its latest self event and avoid branching.                                                            |
| [ADR-005](ADR-005-embedded-future-event-buffers.md)                       | Embed a future-event buffer inside each consuming component instead of one standalone buffering component | 2026-06-03 | Accepted | Each of `DefaultConsensusEngine` and `DefaultEventCreationManager` owns a private `FutureEventBuffer` rather than sharing one standalone upstream component. This avoids a consensus-advance → event-window-advance → release-more-events feedback loop in the pipeline, keeping `flushIntakePipeline()` a single ordered pass and protecting the no-branching guarantee (RUL-002). |
| [ADR-007](ADR-007-save-reconnect-state-before-resuming-event-creation.md) | Save the Reconnect State to Disk Before Resuming Event Creation                                           | 2026-06-04 | Accepted | After a reconnect, the learner writes the received signed state to disk before it is allowed to resume event creation, so a node that crashes immediately after reconnecting recovers its latest self event from its own saved state and cannot branch.                                                                                                                             |

<!--
Row convention, one line per entry, kept in ADR-NNN order:
| [ADR-NNN](ADR-NNN-short-slug.md) | Title from entry frontmatter | topic-slug[, topic-slug] | YYYY-MM-DD | proposed|accepted|superseded|deprecated | One or two sentences capturing what was decided and why. |
-->
