# Local observability stack

A self-contained Docker Compose stack for looking at the metrics and logs a
local app or test run produces. You start it with one command and point it at
your own log directory and metrics endpoints.

Nothing in it is coupled to any particular application: labels such as
`environment` or `node_id` are values *you* supply, not concepts the stack knows
about.

|    Component    |                    Role                     |           URL            |   Override port via    |
|-----------------|---------------------------------------------|--------------------------|------------------------|
| VictoriaMetrics | Scrapes metrics targets **and** stores them | <http://localhost:8428>  | `VICTORIAMETRICS_PORT` |
| Grafana Alloy   | Tails log files, pushes them to Loki        | <http://localhost:12345> | `ALLOY_PORT`           |
| Loki            | Log storage                                 | <http://localhost:3100>  | `LOKI_PORT`            |
| Grafana         | UI, anonymous admin access                  | <http://localhost:3000>  | `GRAFANA_PORT`         |

All four ports are overridable in `local.env`; the URLs above show the shipped defaults.

VictoriaMetrics' <http://localhost:8428/targets> can be used to inspect the metrics scrape targets and their health.

## Prerequisites

Docker and Docker Compose. Nothing else — `make` is a convenience only, and
every target below has a raw `docker compose` equivalent.

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
----------------------------------------------------------------------

### Configure metrics scraping

VictoriaMetrics scrapes your app's endpoints continuously, the same way
Prometheus would — there is no other mode yet (see note below).

`local.env`:

```sh
SCRAPE_TARGETS=["host.docker.internal:9999","host.docker.internal:9998"]
SCRAPE_INTERVAL=5s
METRIC_LABELS={"environment":"localhost","node_id":"0"}
```

<details>
  <summary><ins>Click to expand</ins></summary>

- **`SCRAPE_TARGETS`/`METRIC_LABELS` are JSON, not just strings.** Both are
  injected into `promscrape.yml` structurally, so they must stay valid JSON.
  `host.docker.internal` is how a container reaches a process on your
  machine, and it resolves on macOS, Windows, and Linux alike.
- **`METRIC_LABELS` matters more than it looks.** It's attached to every
  scraped series, and production dashboards filter heavily on
  `environment` — a dashboard reused locally shows nothing unless the
  series carry a matching label.
- **Beyond what `SCRAPE_TARGETS` can express** — a different `metrics_path`,
  per-job intervals, service discovery — needs a full scrape-config
  override; see "Overriding a config file" below.

</details>

Historical metrics import (e.g. re-loading an old run's metrics dump) isn't
supported yet — the plan is to mirror `import-logs.sh` below, against
VictoriaMetrics' own import API, once there's a concrete need. Out of scope
for now.
--------

### Configure logs ingestion

#### Live

Alloy tails `LOGS_DIR` continuously and pushes matching lines to Loki as
they're written. Each file's basename (without extension) becomes its
`log_name` stream label, and every `LOG_LABELS` entry is added to every
stream, so `{log_name="swirlds",environment="localhost"}` selects one file
and `{environment="localhost"}` selects everything.

`local.env`:

```sh
LOGS_DIR=/path/to/your/run/output
LOG_INCLUDE=/logs/**/*.log
LOG_LABELS={"environment":"localhost","node_id":"0"}
```

<details>
  <summary><ins>Click to expand</ins></summary>

- **Host vs. container paths.** `LOGS_DIR` is a *host* path, mounted
  read-only at `/logs` inside the container. `LOG_INCLUDE` is therefore a
  **container-side** path and always starts with `/logs`. `**` matches any
  number of directories, including none.
- **Multi-line grouping.** Entries are grouped by `LOG_TIMESTAMP_REGEX`: a
  line matching it starts a new entry, and everything after is appended, so
  a Java stack trace arrives as **one** entry rather than dozens. The
  default matches a leading `2026-09-01 12:34:56` or
  `2026-09-01T12:34:56`; override the regex if your logs start lines
  differently.
- **Timestamp extraction.** Timestamps come from the log line itself, not
  from when Alloy ingested it. `LOG_TIMESTAMP_REGEX`'s
  `(?P<timestamp>...)` named group is re-parsed with
  `LOG_TIMESTAMP_FORMAT`, a [Go reference layout](https://pkg.go.dev/time#pkg-constants)
  (not strftime) describing the exact reference instant
  `Mon Jan 2 15:04:05 MST 2006` — the shipped `2006-01-02 15:04:05.000`
  means "year-month-day, space, hour:minute:second, dot, three-digit
  milliseconds," matching `swirlds-vmap*.log`. `LOG_TIMESTAMP_LOCATION` is
  the IANA zone assumed when the format carries no zone offset of its own
  (the shipped default doesn't).
- **Fallback on parse failure.** A line that doesn't match the regex, or
  whose extracted text doesn't parse against `LOG_TIMESTAMP_FORMAT`, isn't
  dropped — Loki's "fudge" behavior (the default `action_on_failure`)
  stamps it with a synthesized timestamp that preserves stream order
  instead. Keep the `(?P<timestamp>...)` group if you override the regex,
  or every line silently falls back to fudge.
- **Timezone display.** Grafana renders Explore's Time column in
  `GRAFANA_DEFAULT_TIMEZONE` (default `UTC`, matching
  `LOG_TIMESTAMP_LOCATION`'s default); the raw line always shows whatever
  the log producer wrote. The two only visually agree when both are UTC —
  set `GRAFANA_DEFAULT_TIMEZONE=browser` for your own local time (and
  accept the offset against the raw line), or match it to whatever zone
  your logs are actually written in.
- **Startup delay.** First query after `make up` (or `make reset`) comes
  back empty for ~30-40 seconds, however small the import — this is Loki's
  own single-binary startup (ring/ingester warm-up), measured to be the
  same regardless of log volume, not your logs being slow to ingest. Give
  it under a minute before assuming nothing matched.

</details>

#### Historical

Old, already-finished logs work through plain `LOGS_DIR` too — nothing is
rejected for being old, just widen Grafana's time range (it defaults to the
last hour). That covers a single historical import, or several that don't
share a basename — the common case, and it needs nothing extra.

When two sources *would* share a basename — an older run's `swirlds.log`
re-imported next to a newer one, or several nodes' identically-named log
files in a multi-node test — reach for
`./scripts/import-logs.sh <source-dir> '<labels-json>' [glob] [wait-seconds]`
instead. It imports a directory independently of the always-on `LOGS_DIR`
tail, tagging every entry it ingests with labels you choose, so the sources
stay distinguishable instead of colliding:

```sh
./scripts/import-logs.sh /path/to/old-run/node0 '{"node_id":"1"}'
./scripts/import-logs.sh /path/to/old-run/node1 '{"node_id":"2"}'
```

<details>
  <summary><ins>Click to expand</ins></summary>

- **Why same basename collides.** `log_name` is derived from basename alone
  (above) — directory information plays no part in the stream a file ends
  up in — so dropping both nodes' `swirlds.log` straight under `LOGS_DIR`
  lands them in the *same* Loki stream.
- **Out-of-order acceptance window.** Within one stream, Loki only accepts
  an out-of-order write up to half of `ingester.max_chunk_age` behind the
  newest entry already there (raised from Loki's 1h default to a 12h
  window in `services/logs/loki-config.yml`, as a cheap safety net — see
  its comment); an entry arriving further "behind" than that is silently
  rejected.
- **Small imports can stay invisible for a while.** A stream only becomes
  queryable from an old time range once its data is flushed out of Loki's
  ingester (in-memory) into its filesystem store — Loki's default only
  does that after 30 minutes of inactivity on that stream
  (`ingester.chunk_idle_period`). An actively-tailed stream never notices,
  since it keeps getting written to; a small historical import stops the
  moment it's done, so without this it could sit unqueryable for up to
  half an hour. `services/logs/loki-config.yml` lowers `chunk_idle_period`
  to 1 minute for exactly this reason. This applies equally to plain
  `LOGS_DIR` and to `import-logs.sh`.
- **Script usage.** Run it once per source that needs distinguishing — a
  run, a node, whatever applies — instead of relying on file layout to do
  that for you. `<source-dir>` is a host path, mounted read-only exactly
  like `LOGS_DIR` (relative paths are resolved against your current
  directory, not against `local-stack/` — see the script's own usage
  comment); `<labels-json>` is a JSON map, exactly like `LOG_LABELS`
  (include `environment` yourself if you want these entries to match the
  live stack's). `[glob]` and `[wait-seconds]` are optional — they default
  to `LOG_INCLUDE`'s usual pattern and 30 seconds respectively; raise the
  wait for a large backfill. Requires `make up` already running: this
  talks to the same `loki` service the live stack does.
- **Why a second Alloy instance instead of pushing to Loki directly.**
  Loki's push API only accepts pre-parsed entries — a label set plus a
  list of `(timestamp, line)` pairs — and getting a raw, possibly
  multi-line log file into that shape is exactly what `config.alloy`'s
  multiline-grouping and timestamp-extraction stages already do (see
  `docker-compose.import.yml`'s `log-importer` service). A hand-rolled
  script would have to reimplement that in bash or Python, as a second
  copy of logic that already exists and is already tested, free to drift
  from the real one the moment `LOG_TIMESTAMP_REGEX`/`FORMAT` changes.
  Running Alloy itself avoids a second implementation entirely — same
  config file, same parsing, same correctness, just against a different
  directory and label set.

</details>

---

### Configure dashboards

`local.env`:

```sh
GRAFANA_DASHBOARDS_DIR=/path/to/hedera-node/infrastructure/grafana/dashboards
```

<details>
  <summary><ins>Click to expand</ins></summary>

- **Where dashboards appear.** Dashboards under `GRAFANA_DASHBOARDS_DIR`
  appear in Grafana under folders mirroring that directory's structure
  (`foldersFromFilesStructure` in
  `services/grafana/provisioning/dashboards/dashboards.yml`). The default,
  `./services/grafana/dashboards`, is an empty, gitignored directory — drop
  dashboard JSON files straight into it, or point the variable anywhere
  else. An unset or empty directory is a no-op: no dashboards, no error.
- **Datasource placeholder rewriting.** Dashboards exported from Grafana
  with the "export for sharing externally" option carry an `__inputs`
  array and reference their datasource as `"uid": "${DS_SOMETHING}"`.
  Grafana's *file* provisioner — the only path this stack uses — does not
  resolve those placeholders; only the *import* path does, so dropped in
  unchanged, every panel shows "Datasource ${DS_...} was not found." A
  small `dashboards-init` container closes that gap: it copies
  `GRAFANA_DASHBOARDS_DIR` into an internal volume and rewrites every
  `${<name>}` occurrence in a `prometheus`-typed `__inputs` entry to
  `METRICS_DATASOURCE_NAME`. Your copy under `GRAFANA_DASHBOARDS_DIR` is
  mounted read-only and never modified. A non-`prometheus` `__inputs`
  entry fails `dashboards-init` loudly (filename + `pluginId`, printed in
  `docker compose ... logs dashboards-init`) rather than silently
  mis-binding it, and Grafana does not start until that's fixed.
- **Binding the datasource is necessary but not sufficient.** Reused
  production dashboards filter heavily on labels a plain local scrape does
  not produce — `environment` (via a `label_values(environment)` template
  variable) and `node_id`/`node` are the two label families that matter;
  they do not filter on `namespace`, `cluster`, `pod`, `job`, or
  `instance`. Supply matching values through `METRIC_LABELS`/`LOG_LABELS`,
  which is why `environment=localhost` is the shipped default.
- **Refreshing after editing a source dashboard is manual, by design** —
  the rewrite runs once at startup, not on a polling loop:

  ```sh
  cd hiero-observability/local-stack
  docker compose --env-file defaults.env --env-file local.env up -d --force-recreate dashboards-init
  ```

  Grafana's file provisioner then hot-reloads the result on its own; `make
  restart` also works but recreates every container, which is more than
  this needs. `allowUiUpdates: true` in `dashboards.yml` means a panel you
  edit in Grafana's UI is saved rather than instantly reverted on
  Grafana's next provisioning sweep — but only until `dashboards-init`
  next changes the underlying file, at which point the file wins again.

- **Datasource names and types are pinned.** `METRICS_DATASOURCE_NAME`/
  `LOKI_DATASOURCE_NAME` must stay `grafanacloud-prom`/`grafanacloud-logs` —
  existing production dashboards reference those as literal UIDs. The
  metrics datasource also stays `type: prometheus` regardless of what's
  actually behind it — VM speaks PromQL through Grafana's Prometheus
  plugin.

</details>

---

### Everything you can set

See `defaults.env` — it is the authoritative, commented list. In outline:

- **Logs** — `LOGS_DIR`, `LOG_INCLUDE`, `LOG_TIMESTAMP_REGEX`,
  `LOG_TIMESTAMP_FORMAT`, `LOG_TIMESTAMP_LOCATION`, `LOG_LABELS`,
  `ALLOY_CONFIG`, `LOKI_CONFIG`
- **Metrics** — `SCRAPE_TARGETS`, `SCRAPE_INTERVAL`, `METRIC_LABELS`,
  `PROMSCRAPE_CONFIG`
- **Grafana** — `METRICS_DATASOURCE_NAME`, `METRICS_DATASOURCE_URL`,
  `LOKI_DATASOURCE_NAME`, `GRAFANA_DEFAULT_TIMEZONE`, `GRAFANA_PROVISIONING_DIR`,
  `GRAFANA_DASHBOARDS_DIR`
- **Host ports** — `GRAFANA_PORT`, `VICTORIAMETRICS_PORT`, `LOKI_PORT`,
  `ALLOY_PORT`
- **Retention** — `METRICS_RETENTION`, `LOGS_RETENTION`

<details>
  <summary><ins>Click to expand</ins></summary>

- **A couple of things not to change casually.** The datasource names
  default to `grafanacloud-prom` and `grafanacloud-logs` because existing
  production dashboards reference exactly those strings, and retention
  values must always carry a unit — a bare `15` means *fifteen months* to
  VictoriaMetrics.
- **Every variable used anywhere has a value in `defaults.env`, and that is
  deliberate.** VictoriaMetrics leaves an unset `%{VAR}` in its scrape
  config literally, with no error (and Compose refuses to even start if a
  variable used in a `volumes:` mount is unset). If you add a placeholder,
  give it a default there too. `IMPORT_LOGS_DIR`/`IMPORT_LOG_INCLUDE`/
  `IMPORT_LOG_LABELS`/`IMPORT_WAIT_SECONDS` are a deliberate exception,
  with none of them here at all: `docker-compose.import.yml`, the only
  file that references them, is never loaded by `make up`/`make
  selftest`'s normal path, so Compose never needs a value for them unless
  `import-logs.sh` (the only supported way to run it) is the one loading
  that file — and it always sets its own values first (see "Historical"
  under "Configure logs ingestion" above).

</details>

## Overriding a config file

Every config file (or directory) the stack mounts into a container has its own
env var pointing at it, all following the same pattern: copy the committed
file (or, for Grafana, the whole directory) anywhere you like, edit your copy,
and point the variable at it in `local.env`.

|              Config              |          Env var           |              Default              |
|----------------------------------|----------------------------|-----------------------------------|
| Metrics scrape                   | `PROMSCRAPE_CONFIG`        | `./services/promscrape.yml`       |
| Log pipeline                     | `ALLOY_CONFIG`             | `./services/logs/config.alloy`    |
| Log storage                      | `LOKI_CONFIG`              | `./services/logs/loki-config.yml` |
| Grafana provisioning (directory) | `GRAFANA_PROVISIONING_DIR` | `./services/grafana/provisioning` |

For example, to change something `promscrape.yml`'s environment variables
can't express:

```sh
cp services/promscrape.yml /somewhere/else/promscrape.yml
# edit /somewhere/else/promscrape.yml, then in local.env:
PROMSCRAPE_CONFIG=/somewhere/else/promscrape.yml
```

<details>
  <summary><ins>Click to expand</ins></summary>

- **Every variable referenced anywhere must exist in `defaults.env`.**
  VictoriaMetrics leaves an unset `%{FOO}` in the file *literally*, parsed
  as a plain scalar, with no error — and Compose refuses to even start if a
  variable used in a `volumes:` mount is unset.
- **Named volumes, never bind-mounts, for backend data.** Works
  identically across OSes, and `docker compose down -v` resets cleanly.

A couple of related invariants live closer to what they affect instead of
here: `SCRAPE_TARGETS`/`METRIC_LABELS` staying valid JSON is under
"Configure metrics scraping" above, and the Grafana datasource name/type
pinning is under "Configure dashboards" above.

</details>

## Day-to-day commands

`make` is a convenience only — every target below has a raw `docker compose`
equivalent, for Windows users without `make` or anyone who'd rather not use it.

Each block below is self-contained and runnable as-is from anywhere, including
the repo root — the `make` targets use `-C` and the raw `docker compose`
equivalents start with their own `cd`.

Run `make help` (or `make -C hiero-observability/local-stack help`) for a
one-line summary of every target below.

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

### 📜 Follow the stack logs

```sh
make -C hiero-observability/local-stack logs
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env logs -f
```

### 📋 Show container status

```sh
make -C hiero-observability/local-stack ps
```

```sh
cd hiero-observability/local-stack
docker compose --env-file defaults.env --env-file local.env ps
```

### ✅ Run the automated end-to-end assertions

<details>
  <summary>What it actually checks</summary>

`make selftest` spins up a throwaway, fully separate copy of the stack (its
own Compose project, its own ephemeral host ports), feeds it purpose-built
fixtures, and asserts that metric names, static labels, stream labels,
`log_name` derivation, multi-line grouping, real (not ingestion-time)
timestamps, an old entry's queryability, and `import-logs.sh`'s explicit
labels all survive the pipeline intact, then tears itself down, including
its volumes. This is the whole point of the exercise: it queries metric
names like `selftest_requests_total` and camelCase
`selftest_blockStream_round_duration_seconds` **exactly**, so anything that
rewrites a `_total` suffix or a camelCase segment in transit fails loudly
here instead of silently blanking a dashboard panel later. It runs its
assertions **inside a container** on the Compose network (no
`bash`/`curl`/`jq` needed on the host), and deliberately does not read
`local.env` — it asserts that the *committed* defaults work, not one
developer's configuration.

</details>

```sh
make -C hiero-observability/local-stack selftest
```

<details>
  <summary>Raw <code>docker compose</code> equivalent (no <code>make</code>)</summary>

```sh
cd hiero-observability/local-stack

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  up -d --wait --wait-timeout 120 \
  victoriametrics loki alloy grafana selftest-metrics selftest-log-writer

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  run --rm selftest-import-writer

# docker-compose.import.yml is only added to *this* invocation - see
# "Everything you can set" above for why it stays out of the others.
IMPORT_LOGS_DIR=selftest-import-src IMPORT_LOG_INCLUDE='/logs/**/*.log' \
IMPORT_LOG_LABELS='{"environment":"selftest","import_check":"1"}' IMPORT_WAIT_SECONDS=15 \
  docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f docker-compose.import.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  --profile import run --rm log-importer

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  run --rm -T selftest-assert

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  down -v --remove-orphans
```

</details>

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

<details>
  <summary>A target is red on http://localhost:8428/targets</summary>

The page shows the error. `connection refused` on `host.docker.internal`
means nothing is listening on that port on your machine yet. The `app`
target is red by default until you set `SCRAPE_TARGETS`.

</details>

<details>
  <summary>Metrics exist but a panel is empty</summary>

Widen the time range first. Then check that the series carry the labels
the query filters on — production dashboards filter on `environment` and
`node_id`, which come from your `METRIC_LABELS`.

</details>

<details>
  <summary>No logs in Explore</summary>

Widen the time range. Then check that `LOG_INCLUDE` is a container-side
path under `/logs` and that it actually matches something: Alloy's own UI
at <http://localhost:12345> lists the discovered files under
`local.file_match.logs`. Alloy re-scans the glob every 10 seconds, so a
brand new file takes a moment to appear.

</details>

<details>
  <summary><code>docker compose ... logs loki</code> shows "ingestion rate limit exceeded"</summary>

Expected only for a very large backfill — `services/logs/loki-config.yml`
already raises Loki's per-tenant defaults well above what a normal
completed run needs. If a single import still exceeds `ingestion_rate_mb`/
`ingestion_burst_size_mb`/`per_stream_rate_limit`, copy that file, raise
those further, and point `LOKI_CONFIG` at your copy (see "Overriding a
config file" above). Alloy retries rejected pushes with backoff, but that
retry budget is finite — if it's exhausted before you raise the limit and
restart Loki, those specific entries are dropped for good, and Alloy's own
file-position tracking (the `alloy-data` volume, which survives restarts)
means it will **not** automatically re-read them afterward. If you hit
this, the low-cost recovery is copying the affected file(s) to a new path
under `LOGS_DIR` so Alloy sees an unread file and tails it from byte zero;
`make reset` is the blunt alternative (clears all position tracking and
all stored data, then re-tails everything).

</details>

<details>
  <summary>Something in a config file was ignored</summary>

Config files are read at container start: `make restart`.

</details>

<details>
  <summary><code>make up</code> hangs, or <code>grafana</code> never starts</summary>

Check `docker compose ... logs dashboards-init` — a non-`prometheus`
`pluginId` inside a dashboard's `__inputs` fails that container on purpose
and blocks Grafana from starting; the log names the offending file. Fix or
remove that dashboard, then re-run `make up`.

</details>

<details>
  <summary>Start over</summary>

`make reset` deletes all stored data; `make up` then starts clean.

</details>

## Design notes

<details>
  <summary>VictoriaMetrics scrapes <em>and</em> stores metrics</summary>

No Prometheus, no OTel Collector in front of it. Routing metrics through a
collector is a round trip through the OTel data model, where `_total`/unit
suffixes get semantically stripped and reconstructed on the way back out —
and the reconstruction rules have changed across collector versions.
Existing dashboards query exact names (`platform_trans_per_sec`, camelCase
`blockStream_*`); if those shift, panels silently go blank. Scraping
natively makes that class of bug impossible, and comes with VM's `/targets`
page for free as the single most useful local debugging affordance.

</details>

<details>
  <summary>Alloy handles logs, not the OTel Collector</summary>

Alloy writes to Loki's native push API, where stream labels are just
labels — OTLP would require promoting attributes to stream labels via a
Loki config surface that no longer exists.

</details>

<details>
  <summary>No templating engine</summary>

VM expands `%{ENV_VAR}`, Alloy reads `sys.env()`, Loki supports
`-config.expand-env=true`, Grafana provisioning files expand `$VAR`. Every
config file in this stack is a real, readable, un-rendered file — don't
reintroduce a render step.

</details>
