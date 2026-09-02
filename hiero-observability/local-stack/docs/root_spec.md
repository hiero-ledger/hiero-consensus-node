# Shared implementation context

Read this before working on any sub-issue. It holds every decision that spans
more than one issue. Specs reference it rather than repeating it.

---

## 1. Architecture and why

### VictoriaMetrics scrapes *and* stores metrics — no Prometheus, no collector

VictoriaMetrics single-node embeds vmagent and scrapes Prometheus-format
targets directly via `-promscrape.config`. This was chosen over the more
conventional "OTel Collector scrapes → Prometheus stores" for four reasons:

1. **No metric-name mangling.** Routing metrics through an OTel Collector is a
   round trip: the Prometheus receiver converts into the OTel data model (where
   `_total` and unit suffixes are semantically stripped) and the remote-write
   exporter reconstructs Prometheus names on the way out. The reconstruction
   rules have changed across collector versions. Existing dashboards query
   exact names such as `platform_trans_per_sec` and camelCase `blockStream_*`;
   if those shift, every panel silently goes blank and it looks like a scrape
   failure. VM scraping natively makes this class of bug impossible.
2. **Single config file preserved.** VM supports `%{ENV_VAR}` placeholders in
   its scrape config, so targets, interval and labels stay in the env file.
3. **`/targets` page.** Target health is the single most useful local debugging
   affordance and it comes back for free.
4. **Historical mode is the same box.** VM's `/api/v1/import/prometheus`
   ingests OpenMetrics text files with original timestamps, so live and
   replayed metrics share one backend and one Grafana datasource — dashboards
   need no rebinding between modes. See [issue4.md](issue4.md).

### Alloy handles logs — not the OTel Collector

The collector was originally chosen as a single agent for both signals. Once VM
took metrics, that premise disappeared, and Alloy is simpler for logs-only:

- Alloy writes to **Loki's native push API**, where stream labels are just
  labels. The collector writes OTLP, which would have required promoting
  attributes to stream labels via `otlp_config` in Loki's `limits_config` —
  an entire config surface that no longer exists.
- `sys.env("VAR")` keeps the single-env-file property.
- Alloy loads a **config directory**, merging every `*.alloy` file in it, which
  gives the local-override pattern for free.

### No templating engine

An earlier prototype rendered configs with gomplate in an init container. It is
not needed: VM expands `%{ENV_VAR}`, Alloy reads `sys.env()`, Loki supports
`-config.expand-env=true`, and Grafana provisioning files expand `$VAR`. Every
config file in this stack is a real, readable, un-rendered file. **Do not
reintroduce a render step.** There is no `generated/` directory.

---

## 2. Config layering

Three layers, all using native Compose/Alloy mechanisms, all following one rule:

> **The committed file is the one nobody edits. The gitignored file holds only
> your overrides.**

|     Layer      |      Committed       |      Gitignored override      |             Mechanism             |
|----------------|----------------------|-------------------------------|-----------------------------------|
| Variables      | `defaults.env`       | `local.env`                   | `--env-file` twice, later wins    |
| Log pipeline   | `config.alloy`       | `config.local.alloy`          | Alloy merges a config *directory* |
| Metrics scrape | `promscrape.yml`     | `promscrape.local.yml`        | `PROMSCRAPE_CONFIG` path variable |
| Compose itself | `docker-compose.yml` | `docker-compose.override.yml` | Compose auto-loads it if present  |

`.gitignore` (local to this directory): `local.env`, `*.local.alloy`,
`promscrape.local.yml`, `docker-compose.override.yml`, `dashboards.local/`.

### Invocation

```
docker compose --env-file defaults.env --env-file local.env up -d
```

Compose merges multiple `--env-file` flags with later files winning. A
`Makefile` wraps this as `make up` / `make down` / `make reset` / `make
selftest`; `make` is a soft dependency — the raw command always works. Ship
`local.env` as an empty gitignored file created by `make up` if missing, so the
second `--env-file` never fails.

---

## 3. `defaults.env` — the complete variable list

```sh
# --- logs ---
LOGS_DIR=./logs                                     # host dir, mounted read-only at /logs
LOG_INCLUDE=/logs/**/*.log                          # single glob, container-side path
LOG_MULTILINE_START=^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}
LOG_LABELS={"environment":"localhost"}              # JSON map -> Loki stream labels

# --- metrics ---
SCRAPE_TARGETS=["host.docker.internal:9999"]        # JSON list, injected structurally
SCRAPE_INTERVAL=15s
METRIC_LABELS={"environment":"localhost"}           # JSON map -> labels on every series
PROMSCRAPE_CONFIG=./promscrape.yml

# --- grafana ---
METRICS_DATASOURCE_NAME=grafanacloud-prom
METRICS_DATASOURCE_URL=http://victoriametrics:8428
LOKI_DATASOURCE_NAME=grafanacloud-logs
GRAFANA_DASHBOARDS_DIR=./dashboards

# --- host ports ---
GRAFANA_PORT=3000
VICTORIAMETRICS_PORT=8428
LOKI_PORT=3100
ALLOY_PORT=12345

# --- retention ---
METRICS_RETENTION=15d
LOGS_RETENTION=168h
```

Issue 1 introduces the metrics, Grafana, port and retention blocks. Issue 2
adds the logs block and `LOKI_DATASOURCE_NAME`. Issue 3 adds
`GRAFANA_DASHBOARDS_DIR`.

---

## 4. Invariants — violating these produces silent wrong behaviour

1. **Every variable referenced anywhere must exist in `defaults.env`.** VM
   leaves an unset `%{FOO}` in the file *literally*, and it is then parsed as a
   plain YAML scalar. There is no error. `defaults.env` is the safety net;
   `local.env` only overrides.
2. **`METRIC_LABELS` / `SCRAPE_TARGETS` are injected structurally.** VM expands
   `%{ENV_VAR}` on the raw byte stream *before* YAML parsing
   (`envtemplate.ReplaceBytes` → `yaml.UnmarshalStrict`), so a JSON list or map
   lands as a valid YAML flow collection. Keep them valid JSON.
3. **Datasource names must match what dashboards expect.** They default to
   `grafanacloud-prom` / `grafanacloud-logs` because existing production
   dashboards reference those as literal UIDs *and* as legacy name-only
   references. Both `name` and `uid` are set to the same value.
4. **The metrics datasource stays `type: prometheus`** regardless of what is
   behind it. VM speaks PromQL and is queried through Grafana's Prometheus
   plugin. This is what lets historical mode reuse the same dashboards.
5. **Named volumes, never bind-mounts, for backend data.** Bind-mounting host
   directories breaks on Linux where container uids differ from yours. Reset is
   `docker compose down -v`, identical on every OS.
6. **No consensus-node concepts in the tool.** Labels like `environment` and
   `node_id` are supplied by the user through `METRIC_LABELS` / `LOG_LABELS`.
   The tool ships `environment=localhost` as a default *value* only.

---

## 5. Verified facts about VictoriaMetrics

Confirmed against official docs and source; do not re-derive.

- Scrape flag: `-promscrape.config`, standard Prometheus `scrape_configs`
  syntax including `static_configs` with `targets` and `labels`. Global and
  per-job `scrape_interval` supported. `refresh_interval` inside SD configs is
  *not* — VM uses `-promscrape.*SDCheckInterval` flags.
- `%{ENV_VAR}` expansion is pure textual substitution before YAML parsing.
  Unset variables are left as the literal `%{FOO}`. Recursive across variables.
- Endpoints on `:8428`: `/targets`, `/service-discovery`, `/config`,
  `/api/v1/targets`, `/api/v1/write`, `/api/v1/import/prometheus`.
- Retention flag: `-retentionPeriod`, accepts `1d`…`100y`. **Always include the
  unit** — a bare number means *months*.
- Storage: `-storageDataPath=/victoria-metrics-data`, mounted as a named volume.
- The official image has no `USER` directive and runs as root, so named volumes
  need no chown.

### Grafana datasource settings this forces

- `jsonData.prometheusVersion` must be **≥ 2.24.0**. VM's
  `/api/v1/label/<name>/values` defaults to *the last day starting 00:00 UTC*
  rather than all time; below 2.24 Grafana omits `start`/`end`, so
  `label_values(...)` template queries silently resolve against that fixed
  window instead of the dashboard's time range.
- `jsonData.timeInterval` should be `${SCRAPE_INTERVAL}` so `$__rate_interval`
  is computed correctly.

---

## 6. Image versions

Pin exact tags in `docker-compose.yml` — never use `latest`. Look up the
current stable tag for each at implementation time rather than trusting a
version written here:

`victoriametrics/victoria-metrics`, `grafana/grafana`, `grafana/loki`,
`grafana/alloy`, `busybox` (init containers and selftest fixtures).

---

## 7. Directory layout

```
hiero-observability/local-stack/
├── .gitattributes
├── .gitignore
├── Makefile
├── README.md
├── defaults.env
├── docker-compose.yml
├── promscrape.yml
├── config.alloy
├── loki-config.yml
├── grafana/provisioning/datasources/datasources.yml
├── grafana/provisioning/dashboards/dashboards.yml
├── dashboards/.gitkeep
├── logs/.gitkeep
├── selftest/
└── docs/            <- these planning files; delete once issues 1-3 close
```

The existing prototype at `hiero-observability/_local_docker_stack/` is
superseded and removed in issue 1. `hiero-observability/docker/` is unrelated
prior art for historical mode — leave it alone, see [issue4.md](issue4.md).

---

## 8. Cross-platform: macOS, Linux, Windows

Supported: macOS and Linux natively, and Windows via **Docker Desktop with the
WSL2 backend**. Native Windows containers are not supported and not a goal.
Every service is a Linux container, so all OS differences live at the host
boundary.

### `.gitattributes` is mandatory

Ship a `.gitattributes` in this directory forcing LF endings:

```
* text=auto eol=lf
```

Without it, Git on Windows checks files out with CRLF and:

- Compose reads `SCRAPE_INTERVAL=15s\r` from `defaults.env` — the carriage
  return becomes part of the value and VM cannot parse the duration. The same
  corruption hits every variable, including the JSON ones.
- `.sh` files fail with `bad interpreter: /bin/sh^M`.
- `promscrape.yml` and `config.alloy` gain a trailing `\r` on every line.

None of these produce a useful error message. This is the highest-value
portability item in the stack and it is one file.

### Selftest assertions run *inside a container*

Do not write the selftest assertions as a host script invoked with bash and
curl — neither exists in PowerShell or cmd. Run them in a small container joined
to the Compose network under the `selftest` profile.

This is better regardless of Windows: assertions then reach services by internal
name (`victoriametrics:8428`, `loki:3100`) rather than published host ports, so
they stop depending on port mapping or on which ports a user remapped.

### `make` is optional, never required

`make` is absent from a default Windows install (available via Git Bash, WSL, or
chocolatey). The `Makefile` is convenience only. The README must document the
raw equivalent of every target, and the canonical invocation

```
docker compose --env-file defaults.env --env-file local.env up -d
```

must work verbatim in PowerShell. The Makefile's "create `local.env` if missing"
step has no PowerShell equivalent, so the README should tell Windows users to
create that file once by hand.

### Host paths

Relative paths (`./logs`) work everywhere. For absolute paths in `local.env`,
Windows users should use forward slashes (`C:/Users/me/logs`) or a WSL path —
backslashes and the drive-letter colon interact badly with Compose's volume
syntax.

### Log tailing across the Windows filesystem boundary

Alloy tailing a bind mount from the *Windows* filesystem works but is slow and
can miss change notifications, because inotify does not propagate cleanly
through Docker Desktop's filesystem sharing. Alloy polls rather than relying
purely on inotify, so lines do arrive, but with latency. **Document that Windows
users should keep `LOGS_DIR` inside the WSL2 filesystem**, and ideally run the
whole stack from a WSL2 working directory — the difference is large.
