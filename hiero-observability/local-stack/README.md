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

## ⚙ Configuring it

Everything is driven by environment variables, and the rule is always the same:

> **The committed file is the one nobody edits. The gitignored file holds only
> your overrides.**

|   What    |   Committed    | Your override |                Mechanism                |
|-----------|----------------|---------------|-----------------------------------------|
| Variables | `defaults.env` | `local.env`   | `--env-file` twice, the later file wins |

Every config file the stack mounts into a container (VictoriaMetrics' scrape
config, Alloy's pipeline, Loki's config, Grafana's provisioning directory) has
its own override mechanism too — see "Overriding a config file" below.

### Configure metrics scraping

`local.env`:

```sh
SCRAPE_TARGETS=["host.docker.internal:9999","host.docker.internal:9998"]
SCRAPE_INTERVAL=5s
METRIC_LABELS={"environment":"localhost","node_id":"0"}
```

<details>
  <summary>Click for details</summary>

`SCRAPE_TARGETS` is a JSON list and `METRIC_LABELS` a JSON map; both are
injected into `promscrape.yml` structurally, so they must stay valid JSON.
`host.docker.internal` is how a container reaches a process on your machine, and
it resolves on macOS, Windows and Linux alike.

`METRIC_LABELS` is attached to every scraped series. It matters more than it
looks: production dashboards filter heavily on `environment`, so a dashboard
reused locally shows nothing unless the series carry a matching label.

Anything `SCRAPE_TARGETS` cannot express — a different `metrics_path`,
per-job intervals, service discovery — needs a full scrape-config override;
see "Overriding a config file" below.

</details>

### Configure logs ingestion

`local.env`:

```sh
LOGS_DIR=/path/to/your/run/output
LOG_INCLUDE=/logs/**/*.log
LOG_LABELS={"environment":"localhost","node_id":"0"}
```

<details>
  <summary>Click for details</summary>

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

</details>

### Configure dashboards

`local.env`:

```sh
GRAFANA_DASHBOARDS_DIR=/path/to/hedera-node/infrastructure/grafana/dashboards
```

<details>
  <summary>Click to expand</summary>

Dashboards under `GRAFANA_DASHBOARDS_DIR` appear in Grafana under folders
mirroring that directory's structure (`foldersFromFilesStructure` in
`services/grafana/provisioning/dashboards/dashboards.yml`). The default,
`./services/grafana/dashboards`, is an empty, gitignored directory — drop
dashboard JSON files straight into it, or point the variable anywhere else.
An unset or empty directory is a no-op: no dashboards, no error.

Dashboards exported from Grafana with the "export for sharing externally"
option carry an `__inputs` array and reference their datasource as
`"uid": "${DS_SOMETHING}"` (or any other name — the `DS_` prefix is just a
convention Grafana's export UI happens to use, not a guarantee). Grafana's
*file* provisioner — the only path this stack uses — does not resolve those
placeholders; only the *import* path does, so dropped in unchanged, every
panel shows "Datasource ${DS_...} was not found."

A small `dashboards-init` container closes that gap: it copies
`GRAFANA_DASHBOARDS_DIR` into an internal volume, and for every `__inputs`
entry whose `pluginId` is `prometheus`, rewrites every `${<name>}` occurrence
to `METRICS_DATASOURCE_NAME`. Your copy under `GRAFANA_DASHBOARDS_DIR` is
mounted read-only and never modified. If a dashboard declares a non-`prometheus`
`__inputs` entry, `dashboards-init` fails loudly with the filename and the
offending `pluginId` — printed in `docker compose ... logs dashboards-init` —
rather than silently mis-binding it, and Grafana does not start until that's
fixed.

**Binding the datasource is necessary but not sufficient for a reused
production dashboard to show data.** Those dashboards filter heavily on
labels a plain local scrape does not produce — `environment` (via a
`label_values(environment)` template variable) and `node_id` / `node` are
the two label families that matter; production dashboards do not filter on
`namespace`, `cluster`, `pod`, `job`, or `instance`. Supply the matching
values through `METRIC_LABELS` / `LOG_LABELS`, which is why
`environment=localhost` is the shipped default.

**Refreshing after editing a source dashboard** is manual, by design — the
rewrite runs once at startup, not on a polling loop:

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env up -d --force-recreate dashboards-init
```

Grafana's file provisioner then hot-reloads the result on its own; `make
restart` also works but recreates every container, which is more than this
needs.

`allowUiUpdates: true` in `dashboards.yml` means a panel you edit in
Grafana's UI is saved rather than instantly reverted on Grafana's next
provisioning sweep — but only until `dashboards-init` next changes the
underlying file, at which point the file wins again.

</details>

### Everything you can set

See `defaults.env` — it is the authoritative, commented list. In outline:

- **Logs** — `LOGS_DIR`, `LOG_INCLUDE`, `LOG_MULTILINE_START`, `LOG_LABELS`,
  `ALLOY_CONFIG`, `LOKI_CONFIG`
- **Metrics** — `SCRAPE_TARGETS`, `SCRAPE_INTERVAL`, `METRIC_LABELS`,
  `PROMSCRAPE_CONFIG`
- **Grafana** — `METRICS_DATASOURCE_NAME`, `METRICS_DATASOURCE_URL`,
  `LOKI_DATASOURCE_NAME`, `GRAFANA_PROVISIONING_DIR`, `GRAFANA_DASHBOARDS_DIR`
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

## Overriding a config file

Every config file (or directory) the stack mounts into a container has its own
env var pointing at it, all following the same pattern: copy the committed
file (or, for Grafana, the whole directory) anywhere you like, edit your copy,
and point the variable at it in `local.env`.

|              Config              |          Env var           |              Default              |
|----------------------------------|----------------------------|-----------------------------------|
| Metrics scrape                   | `PROMSCRAPE_CONFIG`        | `./services/promscrape.yml`       |
| Log pipeline                     | `ALLOY_CONFIG`             | `./services/config.alloy`         |
| Log storage                      | `LOKI_CONFIG`              | `./services/loki-config.yml`      |
| Grafana provisioning (directory) | `GRAFANA_PROVISIONING_DIR` | `./services/grafana/provisioning` |

For example, to change something `promscrape.yml`'s environment variables
can't express:

```sh
cp services/promscrape.yml /somewhere/else/promscrape.yml
# edit /somewhere/else/promscrape.yml, then in local.env:
PROMSCRAPE_CONFIG=/somewhere/else/promscrape.yml
```

**Invariants** — violating these produces silent wrong behaviour:

1. Every variable referenced anywhere must exist in `defaults.env`. VM leaves
   an unset `%{FOO}` in the file *literally*, parsed as a plain scalar, with
   no error.
2. `METRIC_LABELS` / `SCRAPE_TARGETS` are injected structurally (VM expands
   `%{ENV_VAR}` on the raw byte stream before YAML parsing), so they must stay
   valid JSON.
3. Datasource names must stay `grafanacloud-prom` / `grafanacloud-logs` —
   existing production dashboards reference those as literal UIDs.
4. The metrics datasource stays `type: prometheus` regardless of what's behind
   it — VM speaks PromQL through Grafana's Prometheus plugin.
5. Named volumes, never bind-mounts, for backend data (works identically
   across OSes; `docker compose down -v` resets cleanly).

## Day-to-day commands

`make` is a convenience only — every target below has a raw `docker compose`
equivalent, for Windows users without `make` or anyone who'd rather not use it.

Each block below is self-contained and runnable as-is from anywhere, including
the repo root — the `make` targets use `-C` and the raw `docker compose`
equivalents start with their own `cd`.

### ▶️  Start

```sh
make -C hiero-observability/local-stack up
```

```sh
cd hiero-observability/local-stack
touch local.env                       # only needed once; Compose fails on a missing --env-file
mkdir -p logs
docker compose --env-file defaults.env --env-file local.env up -d
```

### ⏸️  Stop, keep the data

```sh
make -C hiero-observability/local-stack down
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env down
```

### ⏹️  Stop, delete all stored data

```sh
make -C hiero-observability/local-stack reset
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env down -v
```

### 🔄 Recreate, after editing a config file

```sh
make -C hiero-observability/local-stack restart
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env up -d --force-recreate
```

Editing `local.env` or any file an override env var points at requires this
(or `make restart`) to take effect — config files are only read at container
start.

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

`make selftest` spins up a throwaway, fully separate copy of the stack (its
own Compose project, its own ephemeral host ports), feeds it purpose-built
fixtures, and asserts that metric names, static labels, stream labels,
`log_name` derivation and multi-line grouping all survive the pipeline
intact, then tears itself down, including its volumes. This is the whole
point of the exercise: it queries metric names like `selftest_requests_total`
and camelCase `selftest_blockStream_round_duration_seconds` **exactly**, so
anything that rewrites a `_total` suffix or a camelCase segment in transit
fails loudly here instead of silently blanking a dashboard panel later. It
runs its assertions **inside a container** on the Compose network (no
`bash`/`curl`/`jq` needed on the host), and deliberately does not read
`local.env` — it asserts that the *committed* defaults work, not one
developer's configuration.

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
failed assertion), `test/assert.sh` for the assertions themselves, and
`test/promscrape.test.yml` for the test-only scrape config (it duplicates the
`app` job from `services/promscrape.yml` and adds one job of its own for the
`selftest-metrics` fixture — VictoriaMetrics has no config-include directive,
so keep the two in sync if you change the shared job).

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
- `.gitattributes` forces LF line endings in this directory — without it, Git
  on Windows checks files out with CRLF, and every env var, `.sh` script and
  YAML/HCL file in the stack silently corrupts with a trailing `\r`.
- `test/assert.sh` runs **inside a container** on the Compose network rather
  than as a host script, so it needs no `bash`/`curl` on the host and reaches
  services by internal name instead of published host ports — this is also
  why the selftest works the same way on Windows as anywhere else.

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

**`make up` hangs, or `grafana` never starts.** Check
`docker compose ... logs dashboards-init` — a non-`prometheus` `pluginId`
inside a dashboard's `__inputs` fails that container on purpose and blocks
Grafana from starting; the log names the offending file. Fix or remove that
dashboard, then re-run `make up`.

**Start over.** `make reset` deletes all stored data; `make up` then starts
clean.

## Design notes

- **VictoriaMetrics scrapes *and* stores metrics.** No Prometheus, no OTel
  Collector in front of it. Routing metrics through a collector is a round
  trip through the OTel data model, where `_total`/unit suffixes get
  semantically stripped and reconstructed on the way back out — and the
  reconstruction rules have changed across collector versions. Existing
  dashboards query exact names (`platform_trans_per_sec`, camelCase
  `blockStream_*`); if those shift, panels silently go blank. Scraping
  natively makes that class of bug impossible, and comes with VM's `/targets`
  page for free as the single most useful local debugging affordance.
- **Alloy handles logs, not the OTel Collector.** Alloy writes to Loki's
  native push API, where stream labels are just labels — OTLP would require
  promoting attributes to stream labels via a Loki config surface that no
  longer exists.
- **No templating engine.** VM expands `%{ENV_VAR}`, Alloy reads
  `sys.env()`, Loki supports `-config.expand-env=true`, Grafana provisioning
  files expand `$VAR`. Every config file in this stack is a real, readable,
  un-rendered file — don't reintroduce a render step.
