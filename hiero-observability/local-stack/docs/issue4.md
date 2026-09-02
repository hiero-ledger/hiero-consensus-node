# 4 — Historical metrics replay from a file (deferred)

Part of [root_issue.md](root_issue.md). Depends on issue 1. **Not scheduled —
do not start without re-confirming the design.**

## Summary

After a test or app run finishes, point the stack at the metrics *file* the run
produced and browse it with the same Grafana dashboards used for live runs.

## Why it is already half-built

The architecture in issues 1–3 was chosen with this in mind, so this issue
should be additive rather than a rework:

- VictoriaMetrics is already the metrics backend, and it ingests
  OpenMetrics/Prometheus text with original timestamps via
  `/api/v1/import/prometheus`. Prometheus fundamentally cannot backfill scraped
  metrics; that is the main reason VM was chosen.
- The Grafana datasource URL is a variable, and the datasource stays
  `type: prometheus` regardless of backend, so dashboards need no rebinding.
- Static labels are already a user-supplied concept, and the import endpoint
  accepts `extra_label=name=value`, so the same `environment=localhost` story
  works on the historical side.

## Sketch

- A `historical` Compose profile, versus the implicit `live` one.
- A script that POSTs the metrics file to `/api/v1/import/prometheus`, applying
  `METRIC_LABELS` as `extra_label` query arguments.
- Logs need no equivalent: Alloy already tails files that already exist, and
  Loki is configured with `reject_old_samples: false`.

## Open decisions

- Whether live and historical share one VM instance or run as separate
  profiles. If separate, both can be given the same Docker network alias with
  VM's `-httpListenAddr` set to a common port, keeping the datasource URL
  constant and making the mode a pure profile switch. Setting both profiles at
  once would put two containers behind one alias — needs guarding or documenting.
- Whether the import is a Makefile target, a container, or a documented `curl`.
- What produces the metrics file, and in what format.

## Prior art to reconcile

`hiero-observability/docker/` is an untracked working-tree directory containing
a `prometheus/prometheus.yml`, a `metrics/metrics.txt`, and a
`scripts/import.sh` that already calls
`http://victoriametrics:8428/api/v1/import/prometheus`. It prototypes exactly
this. It was deliberately left untouched by issues 1–3. Reconciling or deleting
it belongs to this issue — two directories in `hiero-observability/` doing
adjacent things will confuse whoever arrives next.

## No spec yet

`spec4.md` records only the verified facts and the reasoning so far. Write the
real implementation spec when this is scheduled, against whatever is true then.
