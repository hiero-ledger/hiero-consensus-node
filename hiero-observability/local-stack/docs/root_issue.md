# Local observability stack for app and test runs

## Problem

Observing a local app or test run today means ad-hoc setups that every team
builds differently. There is no shared, reusable way to look at metrics and
logs produced by a local run, and no way to reuse the Grafana dashboards that
already exist for production against a locally running node.

## Goal

A self-contained Docker Compose stack that a developer starts with one command
and points at their own log directory and metrics endpoints. It must be
reusable across teams and components — nothing in it may be coupled to any
particular application.

## Non-goals

- Anything running outside Docker. Docker + Compose are the only required tools.
- Production or shared-environment use. This is a local, throwaway stack.
- Historical / post-mortem metrics replay in the first version — tracked
  separately as [issue4](issue4.md), and the architecture is chosen so that it
  slots in without rework.

## Components

|    Component    |                    Role                     | Host port |
|-----------------|---------------------------------------------|-----------|
| VictoriaMetrics | Scrapes metrics targets **and** stores them | 8428      |
| Grafana Alloy   | Tails log files, pushes to Loki             | 12345     |
| Loki            | Log storage                                 | 3100      |
| Grafana         | UI, anonymous admin access                  | 3000      |

There is deliberately **no Prometheus and no OpenTelemetry Collector** in the
metrics path, and **no config templating engine** anywhere. See
[root_spec.md](root_spec.md) for why.

## Sub-issues

| # |                        Title                         |         Ticket         |         Spec         | Depends on |
|---|------------------------------------------------------|------------------------|----------------------|------------|
| 1 | Metrics: VictoriaMetrics + Grafana + config layering | [issue1.md](issue1.md) | [spec1.md](spec1.md) | —          |
| 2 | Logs: Alloy + Loki                                   | [issue2.md](issue2.md) | [spec2.md](spec2.md) | 1          |
| 3 | Dashboard provisioning from an external directory    | [issue3.md](issue3.md) | [spec3.md](spec3.md) | 1, 2       |
| 4 | Historical metrics replay from file (deferred)       | [issue4.md](issue4.md) | [spec4.md](spec4.md) | 1          |

Each of 1–3 is independently runnable and demonstrable. After 1 and 2 the stack
is fully usable via Grafana Explore; 3 only adds pre-built dashboards.

## Acceptance (epic)

- `docker compose ... up -d` starts the stack with no host tooling beyond Docker.
- A developer configures it by editing **one** gitignored file.
- No generated or rendered config files exist anywhere.
- `make selftest` passes: the stack observes purpose-built fixtures and asserts
  that metric names, log labels, and multi-line log grouping all survive the
  pipeline intact.
- The stack works on macOS, Linux, and Windows (Docker Desktop + WSL2 backend).

## Shared context

All architecture decisions, the config-layering pattern, the full variable
list, and the cross-cutting invariants live in
**[root_spec.md](root_spec.md)**. Every spec assumes it has
been read.
