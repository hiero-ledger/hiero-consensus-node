# Spec 4 — Historical replay (design notes only)

**This is not an implementation spec.** It preserves the facts and reasoning
that would otherwise be lost, so that whoever picks up
[issue4.md](issue4.md) does not re-derive them. Prerequisite reading:
[root_spec.md](root_spec.md).

## Verified facts

- Import endpoint: `POST http://<vm>:8428/api/v1/import/prometheus`, accepting
  Prometheus/OpenMetrics text. Example from the docs:
  `curl -d 'metric_name{foo="bar"} 123' -X POST 'http://<vm>:8428/api/v1/import/prometheus'`
- `extra_label=name=value` query arguments add labels to all imported lines.
  This is documented for the `/api/v1/import/*` family; I could not retrieve a
  sentence naming this exact path, so **confirm it against the pinned VM
  version before relying on it**.
- The endpoint also accepts Pushgateway-style path labels:
  `/api/v1/import/prometheus/metrics/job/my_app/instance/host123`.
- Remote-write ingestion is `/api/v1/write` on the same port.
- VM accepts arbitrary historical timestamps; this is the capability the whole
  issue rests on and the reason Prometheus was rejected as the backend.

## Decisions already made that this must not break

1. The Grafana datasource stays `type: prometheus` with `name`/`uid` =
   `METRICS_DATASOURCE_NAME` (default `grafanacloud-prom`). Historical mode must
   present itself through that same datasource, or dashboards need rebinding —
   which defeats the point.
2. `METRICS_DATASOURCE_URL` is already a variable, so switching what sits behind
   the datasource is an env change, not a file edit.
3. Labels are a user concept supplied via `METRIC_LABELS`. Imported series must
   carry the same labels as scraped ones, or dashboards filtering on
   `environment` will show live data and not historical data, or vice versa.
4. `reject_old_samples: false` in Loki means the log side of a post-mortem
   already works without changes.

## The network-alias trick

If live and historical end up as separate VM containers, give both the same
Docker network alias (e.g. `metrics-backend`) and start the historical one with
`-httpListenAddr=:8428` so the port matches. The datasource URL then becomes a
constant and "mode" reduces to which Compose profile is active, with no
substitution logic anywhere. The failure mode to guard: activating both
profiles puts two containers behind one alias and DNS round-robins between
them, which is confusing to debug.

## Open questions to settle before writing a real spec

- Does the app write one metrics file per run, or append continuously? Does it
  contain timestamps, and in what format?
- One VM instance for both modes, or two behind an alias?
- Import as a Makefile target, an init container, or documented `curl`?
- What happens to `hiero-observability/docker/` — absorb its `import.sh`, or
  delete it once this lands?
