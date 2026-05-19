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

|   ID    |                 Name                  |                                                                    Description                                                                    |         Source of observation         |
|---------|---------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|
| SYM-001 | Platform status `ACTIVE` → `CHECKING` | The node's platform status transitions from `ACTIVE` to `CHECKING`. Monitored and recorded; has many possible causes, each validated differently. | Platform status monitoring in Grafana |
