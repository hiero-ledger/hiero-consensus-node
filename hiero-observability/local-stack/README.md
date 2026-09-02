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

Out of the box the stack tails `./logs`, and the `app` scrape target is red —
there is nothing on `host.docker.internal:9999` yet. Configuring it is the
next section.

## Configuring it

Everything is driven by environment variables, and the rule is always the same:

> **The committed file is the one nobody edits. The gitignored file holds only
> your overrides.**

| What                  | Committed                 | Your override                   | Mechanism                                   |
|-----------------------|---------------------------|---------------------------------|---------------------------------------------|
| Variables             | `defaults.env`            | `local.env`                     | `--env-file` twice, the later file wins     |
| Metrics scrape config | `services/promscrape.yml` | `services/promscrape.local.yml` | `PROMSCRAPE_CONFIG` points at your copy     |
| Log pipeline          | `services/config.alloy`   | `services/config.local.alloy`   | Alloy merges every `*.alloy` in `services/` |
| Compose itself        | `docker-compose.yml`      | `docker-compose.override.yml`   | Compose loads it automatically if present   |

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
cp services/promscrape.yml services/promscrape.local.yml
# edit services/promscrape.local.yml, then in local.env:
PROMSCRAPE_CONFIG=./services/promscrape.local.yml
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

`make` is a convenience only — every target below has a raw `docker compose`
equivalent, for Windows users without `make` or anyone who'd rather not use it.

Each block below is self-contained and runnable as-is from anywhere, including
the repo root — the `make` targets use `-C` and the raw `docker compose`
equivalents start with their own `cd`.

### Start the stack

```sh
make -C hiero-observability/local-stack up
```

```sh
cd hiero-observability/local-stack
touch local.env                       # only needed once; Compose fails on a missing --env-file
mkdir -p logs
docker compose --env-file defaults.env --env-file local.env up -d
```

### Stop the stack, keep the data

```sh
make -C hiero-observability/local-stack down
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env down
```

### Stop the stack and delete all stored data

```sh
make -C hiero-observability/local-stack reset
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env down -v
```

### Recreate the containers, after editing a config file

```sh
make -C hiero-observability/local-stack restart
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env up -d --force-recreate
```

Editing `local.env`, `services/promscrape.local.yml` or
`services/config.local.alloy` requires this (or `make restart`) to take
effect — config files are only read at container start.

### Follow the stack logs

```sh
make -C hiero-observability/local-stack logs
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env logs -f
```

### Show container status

```sh
make -C hiero-observability/local-stack ps
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env ps
```

### Run the automated end-to-end assertions

```sh
make -C hiero-observability/local-stack selftest
```

```sh
cd hiero-observability/local-stack

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  up -d --wait --wait-timeout 120 \
  victoriametrics loki alloy selftest-metrics selftest-log-writer

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  run --rm -T selftest-assert

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  down -v --remove-orphans
```

Runs as its own Compose project against ephemeral host ports, so it cannot
disturb or be disturbed by a stack already running from `make up`. See
`test/test.mk` for the exact version (teardown-on-failure, log dump on a
failed assertion) and `docs/development.md` for what it asserts and why.

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

## Development

For architecture rationale, the directory layout, and how the automated
selftest works, see [`docs/development.md`](docs/development.md).
