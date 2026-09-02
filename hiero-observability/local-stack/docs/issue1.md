# 1 — Metrics: VictoriaMetrics + Grafana + config layering

Part of [root_issue.md](root_issue.md).

## Summary

Stand up the foundation of the local observability stack: a Compose project
that scrapes Prometheus-format metrics endpoints and makes them queryable in
Grafana. This issue also establishes the config-layering pattern and the
directory layout that issues 2 and 3 build on.

## Scope

- New directory `hiero-observability/local-stack/`, replacing the prototype at
  `hiero-observability/_local_docker_stack/` (delete it).
- `docker-compose.yml` with two services: VictoriaMetrics and Grafana.
- VictoriaMetrics scraping targets directly via `-promscrape.config`, with
  targets, interval and static labels supplied from the env file.
- Grafana with anonymous admin access and a provisioned metrics datasource.
- `defaults.env` / `local.env` layering, a `Makefile` wrapper, `.gitignore`.
- A `selftest` profile with a fixture target and an assertion script.
- `README.md`.

## Out of scope

Logs, Loki, Alloy (issue 2). Dashboard provisioning (issue 3). Historical
replay (issue 4).

## Acceptance criteria

- `make up` starts the stack with no host tooling beyond Docker.
- A user can point the stack at a metrics endpoint by editing `local.env` only,
  and never touching a committed file.
- No rendered or generated config files are produced anywhere.
- VictoriaMetrics' `/targets` page shows configured targets and their health.
- Metrics are queryable in Grafana Explore under a datasource named
  `grafanacloud-prom`.
- Static labels from `METRIC_LABELS` appear on every scraped series.
- `make selftest` passes, and **fails loudly if metric names are altered in
  transit** — including `_total` counters and camelCase names.
- `make reset` wipes stored data; `make up` afterwards starts clean.
- Verified working on macOS, Linux, and Windows (Docker Desktop + WSL2).
  `host.docker.internal` resolves on all three; Linux additionally needs the
  `extra_hosts` mapping.
- `.gitattributes` forces LF endings, so a Windows checkout does not put
  carriage returns inside env-file values.
- The selftest runs without any host tooling beyond Docker — no bash, no curl.
