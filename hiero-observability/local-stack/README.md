# Local observability stack

A self-contained Docker Compose stack for looking at the metrics and logs a
local app or test run produces. You start it with one command and point it at
your own log directory and metrics endpoints.

Nothing in it is coupled to any particular application: labels such as
`environment` or `node_id` are values *you* supply, not concepts the stack knows
about.

|    Component    |                    Role                     |           URL            |
|-----------------|---------------------------------------------|--------------------------|
| VictoriaMetrics | Scrapes metrics targets **and** stores them | <http://localhost:8428>  |
| Grafana Alloy   | Tails log files, pushes them to Loki        | <http://localhost:12345> |
| Loki            | Log storage                                 | <http://localhost:3100>  |
| Grafana         | UI, anonymous admin access                  | <http://localhost:3000>  |

The single most useful page while debugging a setup is VictoriaMetrics'
<http://localhost:8428/targets>, which shows every scrape target and its health.

## Prerequisites

Docker and Docker Compose. Nothing else — `make` is a convenience only, and
every target below has a raw `docker compose` equivalent.

## Quick start

```sh
cd hiero-observability/local-stack
make up
```

or, without `make`:

```sh
cd hiero-observability/local-stack
touch local.env                       # only needed once
docker compose --env-file defaults.env --env-file local.env up -d
```

Then open Grafana at <http://localhost:3000> and use **Explore**. Two
datasources are provisioned: `grafanacloud-prom` (metrics) and
`grafanacloud-logs` (logs). No login is required.

Out of the box the stack scrapes itself and tails `./logs`, and the `app` scrape
target is red — there is nothing on `host.docker.internal:9999` yet. Configuring
it is the next section.

## Configuring it

Everything is driven by environment variables, and the rule is always the same:

> **The committed file is the one nobody edits. The gitignored file holds only
> your overrides.**

|         What          |      Committed       |         Your override         |                 Mechanism                 |
|-----------------------|----------------------|-------------------------------|-------------------------------------------|
| Variables             | `defaults.env`       | `local.env`                   | `--env-file` twice, the later file wins   |
| Metrics scrape config | `promscrape.yml`     | `promscrape.local.yml`        | `PROMSCRAPE_CONFIG` points at your copy   |
| Log pipeline          | `alloy/config.alloy` | `alloy/config.local.alloy`    | Alloy merges every `*.alloy` in `alloy/`  |
| Compose itself        | `docker-compose.yml` | `docker-compose.override.yml` | Compose loads it automatically if present |

All four override files are gitignored. In the common case you only ever touch
`local.env`.

### Point it at your metrics endpoint

`local.env`:

```sh
SCRAPE_TARGETS=["host.docker.internal:9999","host.docker.internal:9998"]
SCRAPE_INTERVAL=5s
METRIC_LABELS={"environment":"localhost","node_id":"0"}
```

`SCRAPE_TARGETS` is a JSON list and `METRIC_LABELS` a JSON map; both are
injected into `promscrape.yml` structurally, so they must stay valid JSON.
`host.docker.internal` is how a container reaches a process on your machine, and
it resolves on macOS, Windows and Linux alike.

`METRIC_LABELS` is attached to every scraped series. It matters more than it
looks: production dashboards filter heavily on `environment`, so a dashboard
reused locally shows nothing unless the series carry a matching label.

Anything `SCRAPE_TARGETS` cannot express — a different `metrics_path`, per-job
intervals, service discovery — is a copy of the scrape config:

```sh
cp promscrape.yml promscrape.local.yml
# edit promscrape.local.yml, then in local.env:
PROMSCRAPE_CONFIG=./promscrape.local.yml
```

### Point it at a run's log output

`local.env`:

```sh
LOGS_DIR=/path/to/your/run/output
LOG_INCLUDE=/logs/**/*.log
LOG_LABELS={"environment":"localhost","node_id":"0"}
```

`LOGS_DIR` is a *host* path, mounted read-only at `/logs` inside the container.
`LOG_INCLUDE` is therefore a **container-side** path and always starts with
`/logs`. `**` matches any number of directories, including none.

Each file gets a `log_name` stream label holding its basename without the
extension, so `hgcaa.log` is queryable as `{log_name="hgcaa"}` and
`swirlds.log` as `{log_name="swirlds"}` — including files in subdirectories.
`LOG_LABELS` entries become stream labels too, so `{environment="localhost"}`
selects everything.

Logs from a run that finished hours or days ago are ingested normally; nothing
is rejected for being old. Remember to widen Grafana's time range, which
defaults to the last hour.

Multi-line entries are grouped by `LOG_MULTILINE_START`: a line matching that
regex starts a new entry and everything after it is appended, so a Java stack
trace arrives as **one** entry rather than dozens. The default matches a leading
`2026-09-01 12:34:56` or `2026-09-01T12:34:56`. If your logs start lines
differently, override the regex.

### Everything you can set

See `defaults.env` — it is the authoritative, commented list. In outline:

- **Logs** — `LOGS_DIR`, `LOG_INCLUDE`, `LOG_MULTILINE_START`, `LOG_LABELS`
- **Metrics** — `SCRAPE_TARGETS`, `SCRAPE_INTERVAL`, `METRIC_LABELS`,
  `PROMSCRAPE_CONFIG`
- **Grafana** — `METRICS_DATASOURCE_NAME`, `METRICS_DATASOURCE_URL`,
  `LOKI_DATASOURCE_NAME`
- **Host ports** — `GRAFANA_PORT`, `VICTORIAMETRICS_PORT`, `LOKI_PORT`,
  `ALLOY_PORT`
- **Retention** — `METRICS_RETENTION`, `LOGS_RETENTION`

Two things not to change casually: the datasource names default to
`grafanacloud-prom` and `grafanacloud-logs` because existing production
dashboards reference exactly those strings, and retention values must always
carry a unit — a bare `15` means *fifteen months* to VictoriaMetrics.

Every variable used anywhere has a value in `defaults.env`, and that is
deliberate: VictoriaMetrics leaves an unset `%{VAR}` in its scrape config
literally, with no error. If you add a placeholder, give it a default there too.

## Day-to-day commands

|     `make`      |                           Raw equivalent                            |
|-----------------|---------------------------------------------------------------------|
| `make up`       | `docker compose --env-file defaults.env --env-file local.env up -d` |
| `make down`     | `... down`                                                          |
| `make reset`    | `... down -v` — also deletes all stored metrics and logs            |
| `make restart`  | `... up -d --force-recreate` — after editing a config file          |
| `make logs`     | `... logs -f`                                                       |
| `make ps`       | `... ps`                                                            |
| `make selftest` | see below                                                           |

`make up` creates an empty `local.env` if it is missing; the raw command needs
that file to exist, because Compose fails on a missing `--env-file`.

Editing `local.env`, `promscrape.local.yml` or `alloy/config.local.alloy`
requires `make restart` (or `up -d --force-recreate`) to take effect.

## Selftest

```sh
make selftest
```

This starts a throwaway copy of the stack alongside whatever you have running,
points it at purpose-built fixtures, and asserts that metric names, static
labels, stream labels, `log_name` derivation and multi-line grouping all survive
the pipeline intact. It then tears itself down, including its volumes.

It runs its assertions **inside a container** on the Compose network, so it
needs no `bash`, `curl` or `jq` on your machine, and it uses its own Compose
project and ephemeral host ports, so it cannot disturb or be disturbed by your
running stack.

The metric-name assertions are the point of the exercise: they query
`selftest_requests_total`, `selftest_blockStream_round_duration_seconds` and
`selftest_platform_trans_per_sec` by their exact strings, so anything that
rewrites a `_total` suffix or a camelCase segment in transit fails loudly here
instead of silently blanking every panel of a dashboard later.

## Windows

Supported through Docker Desktop with the WSL2 backend. Native Windows
containers are not.

- `make` is usually absent, so use the raw `docker compose` commands. Create
  `local.env` once by hand — `New-Item local.env` in PowerShell.
- Run the commands from this directory, so the relative paths in the env files
  resolve.
- For absolute paths in `local.env`, use forward slashes (`C:/Users/me/logs`) or
  a WSL path. Backslashes and the drive-letter colon interact badly with
  Compose's volume syntax.
- **Keep `LOGS_DIR` inside the WSL2 filesystem**, and ideally run the whole
  stack from a WSL2 working directory. Tailing a bind mount from the Windows
  filesystem works but is slow and can miss change notifications, because
  inotify does not propagate cleanly through Docker Desktop's file sharing.

## Troubleshooting

**A target is red on <http://localhost:8428/targets>.** The page shows the
error. `connection refused` on `host.docker.internal` means nothing is listening
on that port on your machine yet. The `app` target is red by default until you
set `SCRAPE_TARGETS`.

**Metrics exist but a panel is empty.** Widen the time range first. Then check
that the series carry the labels the query filters on — production dashboards
filter on `environment` and `node_id`, which come from your `METRIC_LABELS`.

**No logs in Explore.** Widen the time range. Then check that `LOG_INCLUDE` is a
container-side path under `/logs` and that it actually matches something:
Alloy's own UI at <http://localhost:12345> lists the discovered files under
`local.file_match.logs`. Alloy re-scans the glob every 10 seconds, so a brand
new file takes a moment to appear.

**Something in a config file was ignored.** Config files are read at container
start: `make restart`.

**Start over.** `make reset` deletes all stored data; `make up` then starts
clean.

## Not included

Dashboards. The stack ships none and provisions none yet — use Grafana Explore
for now. Pointing it at a directory of existing dashboards, and replaying the
metrics *file* a finished run produced, are tracked separately in `docs/`.
