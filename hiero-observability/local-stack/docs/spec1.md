# Spec 1 — Metrics

Prerequisite reading: [root_spec.md](root_spec.md). This
spec adds only what is specific to issue 1.

## Files to create

```
hiero-observability/local-stack/
├── .gitattributes
├── .gitignore
├── Makefile
├── README.md
├── defaults.env
├── docker-compose.yml
├── promscrape.yml
├── grafana/provisioning/datasources/datasources.yml
├── logs/.gitkeep
└── selftest/
    ├── metrics.txt
    └── assert.sh          # runs INSIDE a container, not on the host
```

Delete `hiero-observability/_local_docker_stack/` in the same change. It is a
prototype whose approach (gomplate rendering, broken `profiles:`-based enable
flags, broken conditional volume names) is superseded.

## docker-compose.yml

Two services on one bridge network.

**victoriametrics**
- Command flags: `-storageDataPath=/victoria-metrics-data`,
`-retentionPeriod=${METRICS_RETENTION}`,
`-promscrape.config=/etc/vm/promscrape.yml`, `-httpListenAddr=:8428`.
- Mounts: `${PROMSCRAPE_CONFIG}:/etc/vm/promscrape.yml:ro`, named volume at
`/victoria-metrics-data`.
- `environment:` must pass `SCRAPE_INTERVAL`, `SCRAPE_TARGETS`, `METRIC_LABELS`
into the container — `%{ENV_VAR}` is expanded by VM at startup *inside* the
container, so Compose-level interpolation is not enough.
- Publish `${VICTORIAMETRICS_PORT}:8428`.
- `extra_hosts: ["host.docker.internal:host-gateway"]` — required on Linux,
harmless on macOS. Without it the default target is unreachable on Linux.

**grafana**
- `GF_AUTH_ANONYMOUS_ENABLED=true`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`,
`GF_AUTH_DISABLE_LOGIN_FORM=true`. No credentials anywhere.
- Pass `METRICS_DATASOURCE_NAME`, `METRICS_DATASOURCE_URL`, `SCRAPE_INTERVAL`
into the container so the provisioning file can expand them.
- Mount `./grafana/provisioning:/etc/grafana/provisioning:ro`, named volume at
`/var/lib/grafana`.
- Publish `${GRAFANA_PORT}:3000`.

## promscrape.yml

```yaml
global:
  scrape_interval: %{SCRAPE_INTERVAL}

scrape_configs:
  - job_name: app
    static_configs:
      - targets: %{SCRAPE_TARGETS}
        labels: %{METRIC_LABELS}

  - job_name: victoriametrics
    static_configs:
      - targets: ["victoriametrics:8428"]
        labels: %{METRIC_LABELS}
```

Issue 2 appends an `alloy` job here. The file is committed and never edited by
users; anyone needing more copies it to `promscrape.local.yml` and repoints
`PROMSCRAPE_CONFIG`.

## grafana/provisioning/datasources/datasources.yml

```yaml
apiVersion: 1
datasources:
  - name: ${METRICS_DATASOURCE_NAME}
    uid: ${METRICS_DATASOURCE_NAME}
    type: prometheus
    access: proxy
    url: ${METRICS_DATASOURCE_URL}
    isDefault: true
    jsonData:
      prometheusType: Prometheus
      prometheusVersion: 2.24.0
      timeInterval: ${SCRAPE_INTERVAL}
```

`prometheusVersion` is load-bearing — see root_spec §5. Issue 2
appends the Loki datasource to this same file.

## Makefile

```
up        docker compose --env-file defaults.env --env-file local.env up -d
down      ... down
reset     ... down -v
logs      ... logs -f
selftest  ... --profile selftest up -d, then selftest/assert.sh, then teardown
```

`up` must create an empty `local.env` if absent, otherwise the second
`--env-file` fails.

## Selftest

`selftest/metrics.txt` — a static OpenMetrics payload exercising exactly the
shapes that a translation layer would mangle:

```
# TYPE selftest_requests_total counter
selftest_requests_total{type="max"} 42
# TYPE selftest_blockStream_round_duration_seconds gauge
selftest_blockStream_round_duration_seconds 0.25
# TYPE selftest_platform_trans_per_sec gauge
selftest_platform_trans_per_sec 17
```

Serve it from a `busybox httpd` container under the `selftest` profile, and add
a `selftest` job to `promscrape.yml` pointing at it.

`selftest/assert.sh` runs **inside a container** on the Compose network (see
root_spec §8) — never as a host script, since Windows has no bash or curl. It
therefore addresses services by internal name, e.g.
`http://victoriametrics:8428`, not via published host ports.

After allowing one scrape interval it must:

1. Query `http://victoriametrics:8428/api/v1/query` for each metric
   name **verbatim** and fail if any returns no result. This is the check that
   catches name mangling — assert the exact strings, including the `_total`
   suffix and the camelCase `blockStream` segment.
2. Assert every returned series carries the labels from `METRIC_LABELS`.
3. Assert `up{job="selftest"} == 1`.
4. Exit non-zero with the failing query printed.

## Gotchas

- **Add `.gitattributes` with `* text=auto eol=lf` before anything else.** On
  Windows, CRLF checkout puts a carriage return inside every `defaults.env`
  value; VM then fails to parse `15s\r` and nothing explains why.
- A bare `-retentionPeriod=15` means *fifteen months*. Always include the unit.
- If a variable is missing from `defaults.env`, VM writes the literal `%{FOO}`
  into the parsed config and carries on. Cross-check that every placeholder in
  `promscrape.yml` has a default.
- `host.docker.internal` is the right default target host, but only resolves on
  Linux with the `extra_hosts` mapping above.
- Grafana provisioning expands `$VAR` from the *container's* environment, so
  every variable used there must appear in the service's `environment:` block.

## Definition of done

All acceptance criteria in [issue1.md](issue1.md), plus: pinned image tags (no
`latest`), and a README covering quick start, the layering rule, and `make reset`.
