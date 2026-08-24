---
type: symptom-catalog
title: Symptoms — Catalog
description: Controlled vocabulary of observable symptoms (SYM-NNN) referenced by heuristics and scenarios; each entry pairs an ID with a name, description, and source of observation.
last_reviewed: TBD
---

# Symptoms — Catalog

Controlled vocabulary of observable symptoms referenced by the `symptoms` field of
`heuristics/` entries (and available to `scenarios/`, the Diagnostician, and a future
Fire Drill Simulator). Single file, sequential IDs, parallel to `invariants.md` and
`tunables.md`.

A symptom here is something **observable and recorded** — a monitored status change, a
metric pattern, a log signature — independent of cause. Many heuristics may share one
symptom; that is expected and is the reason this catalog exists.

Adding a value: append the next `SYM-NNN`, fill all columns, keep the table in ID order.
Never reuse or renumber IDs; retire by marking, not deleting.

|   ID    |                 Name                  |                                                                                                                                            Description                                                                                                                                             |                       Source of observation                        |
|---------|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| SYM-001 | Platform status `ACTIVE` → `CHECKING` | The node's platform status transitions from `ACTIVE` to `CHECKING`. Monitored and recorded; has many possible causes, each validated differently.                                                                                                                                                  | Platform status monitoring in Grafana                              |
| SYM-002 | Inconsistent state signature (ISS)    | A node's per-round state hash disagrees with peers' signatures. Recorded by ISS detection; has many possible causes, each a distinct determinism break upstream. Where only the running event hash diverges, the break is in consensus event membership/ordering rather than transaction handling. | ISS detection (`consensus-iss-detection`), logs                    |
| SYM-003 | Branch detected (self-fork)           | A creator is observed with two events, neither a self-ancestor of the other, within the non-ancient window. Logged at `ERROR`; an honest node branching indicates a bug in self-parent selection or recovery, not Byzantine behaviour.                                                             | Branch detection (`ERROR` log); otter test no-error-log assertions |
