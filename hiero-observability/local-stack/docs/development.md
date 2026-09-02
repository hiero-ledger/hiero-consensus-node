# Developing and testing the local stack

This is the support/dev-facing counterpart to `README.md`: architecture
rationale, the directory layout, and how to run and extend the automated
selftest. `README.md` covers usage only (pointing the stack at your app);
read this file if you're changing the stack itself.

## 1. Architecture, in brief

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
  longer exists. Alloy also loads a **config directory**, merging every
  `*.alloy` file in it, which gives the local-override pattern for free.
- **No templating engine.** VM expands `%{ENV_VAR}`, Alloy reads
  `sys.env()`, Loki supports `-config.expand-env=true`, Grafana provisioning
  files expand `$VAR`. Every config file in this stack is a real, readable,
  un-rendered file — don't reintroduce a render step.

## 2. Directory layout

```
hiero-observability/local-stack/
├── Makefile                  # `up`/`down`/`reset`/`restart`/`logs`/`ps`, includes test/test.mk
├── README.md                 # usage
├── defaults.env / local.env  # committed defaults / gitignored overrides
├── docker-compose.yml        # the 4 real services: victoriametrics, grafana, loki, alloy
├── services/                 # per-service config, mounted read-only into the containers
│   ├── config.alloy          # log pipeline
│   ├── loki-config.yml
│   ├── promscrape.yml        # metrics scrape config
│   └── grafana/provisioning/
├── test/                     # everything selftest-only - see §4
└── docs/                     # this file, plus historical planning docs (§5)
```

`services/` also holds the gitignored override files a user creates
(`config.local.alloy`, `promscrape.local.yml`) — see the config-layering table
below for why they live there.

## 3. Config layering

Three layers, all using native Compose/Alloy/VM mechanisms, all following one
rule:

> **The committed file is the one nobody edits. The gitignored file holds
> only your overrides.**

| Layer          | Committed                 | Gitignored override             | Mechanism                         |
|----------------|---------------------------|---------------------------------|-----------------------------------|
| Variables      | `defaults.env`            | `local.env`                     | `--env-file` twice, later wins    |
| Log pipeline   | `services/config.alloy`   | `services/config.local.alloy`   | Alloy merges a config *directory* |
| Metrics scrape | `services/promscrape.yml` | `services/promscrape.local.yml` | `PROMSCRAPE_CONFIG` path variable |
| Compose itself | `docker-compose.yml`      | `docker-compose.override.yml`   | Compose auto-loads it if present  |

`.gitignore` patterns (`local.env`, `*.local.alloy`, `promscrape.local.yml`,
`docker-compose.override.yml`) are name-based, not path-anchored, so they
match these override files regardless of which directory they live in.

Alloy's config-directory mount is `./services:/etc/alloy:ro` — the whole
directory, not just `config.alloy` — because Alloy needs a *directory* to
merge `*.alloy` files from. It only reads files with that extension, so
`loki-config.yml`, `promscrape.yml` and `grafana/` being visible inside the
Alloy container costs nothing.

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

## 4. Testing (`make selftest`)

`make selftest` spins up a throwaway, fully separate copy of the stack (its
own Compose project, its own ephemeral host ports), feeds it purpose-built
fixtures, and asserts that metric names, static labels, stream labels,
`log_name` derivation and multi-line grouping all survive the pipeline
intact. It then tears itself down, including its volumes. This is the whole
point of the exercise: it queries `selftest_requests_total`,
`selftest_blockStream_round_duration_seconds` and
`selftest_platform_trans_per_sec` by their **exact** strings, so anything
that rewrites a `_total` suffix or a camelCase segment in transit fails
loudly here instead of silently blanking a dashboard panel later.

It runs its assertions **inside a container** on the Compose network (no
`bash`/`curl`/`jq` needed on the host), and deliberately does not read
`local.env` — it asserts that the *committed* defaults work, not one
developer's configuration.

### Layout

```
test/
├── test.mk                  # included by the root Makefile: the `selftest` target
├── docker-compose.test.yml  # selftest-metrics / selftest-log-writer / selftest-assert
├── promscrape.test.yml      # scrape config for the selftest run (see below)
├── selftest.env             # env-file override for the selftest Compose project
├── assert.sh                # the assertions, run inside a container
└── metrics.txt              # static fixture served to VictoriaMetrics
```

`test/docker-compose.test.yml` is merged onto the base `docker-compose.yml`
via `-f docker-compose.yml -f test/docker-compose.test.yml` rather than
`profiles:` — the selftest never reuses a running dev stack's containers, so
there's no need for the test-only services to even be defined in the file
`make up` reads.

`test/promscrape.test.yml` duplicates the `app` scrape job from
`services/promscrape.yml` (VictoriaMetrics has no config-include directive)
and adds one job of its own, for the `selftest-metrics` fixture, with its
target hardcoded rather than threaded through a shared variable. Keep the
shared job in sync if you change it.

### Running it manually

```sh
cd hiero-observability/local-stack

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  up -d --wait victoriametrics loki alloy selftest-metrics selftest-log-writer

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  run --rm selftest-assert

docker compose -p observability-stack-selftest \
  -f docker-compose.yml -f test/docker-compose.test.yml \
  --env-file defaults.env --env-file test/selftest.env \
  down -v
```

## 5. Cross-platform notes

Supported: macOS and Linux natively, Windows via Docker Desktop with the
WSL2 backend. Everything here is a Linux container, so OS differences live at
the host boundary:

- `.gitattributes` forces LF line endings in this directory — without it, Git
  on Windows checks files out with CRLF, and every env var, `.sh` script and
  YAML/HCL file in the stack silently corrupts with a trailing `\r`.
- `assert.sh` runs **inside a container** on the Compose network rather than
  as a host script, so it needs no `bash`/`curl` on the host and reaches
  services by internal name instead of published host ports.
- `make` is optional; the README documents the raw `docker compose`
  equivalent of every target for Windows users without it.
- For absolute paths in `local.env`, use forward slashes
  (`C:/Users/me/logs`) or a WSL path — backslashes and the drive-letter colon
  interact badly with Compose's volume syntax. Keep `LOGS_DIR` inside the
  WSL2 filesystem: tailing a bind mount from the Windows filesystem works but
  is slow, because inotify doesn't propagate cleanly through Docker
  Desktop's file sharing.

## 6. About `docs/root_issue.md`, `root_spec.md`, `issue*.md`, `spec*.md`

Those files are historical planning artifacts written before the stack was
built — issue tickets and their implementation specs. This file and
`README.md` supersede them as the ongoing reference; they're kept around for
now but may be out of sync with what actually got built, and are expected to
be deleted once no longer useful.
