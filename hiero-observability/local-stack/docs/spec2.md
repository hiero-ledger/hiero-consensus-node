# Spec 2 — Logs

Prerequisite reading: [root_spec.md](root_spec.md) and the
delivered state of issue 1.

## Files to create or change

```
config.alloy                                     (new)
loki-config.yml                                  (new)
docker-compose.yml                               (add loki, alloy)
promscrape.yml                                   (add alloy job)
grafana/provisioning/datasources/datasources.yml (append Loki datasource)
defaults.env                                     (add logs block)
.gitignore                                       (add *.local.alloy)
selftest/                                        (add log fixture + assertions)
```

## Why Alloy and not the OTel Collector

Covered in root_spec §1. The short version: Alloy writes to Loki's
native push API where stream labels are just labels, which removes the need to
configure attribute promotion in Loki's `limits_config.otlp_config` entirely.
Do not "modernise" this to OTLP without re-solving that.

## docker-compose.yml additions

**loki**
- Command: `-config.file=/etc/loki/config.yml -config.expand-env=true`.
- Pass `LOGS_RETENTION` into the container's environment.
- Named volume at `/loki`. Publish `${LOKI_PORT}:3100`.

**alloy**
- Command runs Alloy against a config **directory**, not a single file, so a
gitignored `config.local.alloy` dropped alongside is merged automatically.
- Mounts: the config directory read-only; `${LOGS_DIR}:/logs:ro`; a named
volume for Alloy's positions/WAL so restarts don't re-ingest everything.
- Pass `LOG_INCLUDE`, `LOG_LABELS`, `LOG_MULTILINE_START` into the environment.
- Publish `${ALLOY_PORT}:12345`.
- `depends_on: loki`.

## config.alloy — required behaviour

1. **Discovery**: `local.file_match` over `sys.env("LOG_INCLUDE")`. One glob
   string, container-side path (`/logs/**/*.log`), not a JSON list.
2. **Tailing**: `loki.source.file` forwarding into a process stage.
3. **Multi-line grouping**: `stage.multiline` with `firstline` set from
   `sys.env("LOG_MULTILINE_START")`. Without this, stack traces arrive as
   dozens of useless single-line entries. This is the highest-value line in
   the file.
4. **`log_name` label**: `loki.source.file` attaches a `filename` label holding
   the full path. Extract the basename without its extension into `log_name`
   and promote it to a stream label. An existing production Loki query relies
   on `{environment="...", log_name="hgcaa"}`, so `hgcaa.log` must yield
   `log_name="hgcaa"`.
5. **Static labels**: `LOG_LABELS` arrives as a JSON map string. Decode it with
   Alloy's JSON decoding stdlib function and merge into the stream labels.
   **Verify the exact function name against the pinned Alloy version's stdlib
   docs** — this is the one API in this spec I am not certain of. If decoding a
   map turns out not to be supported, the fallback is to accept a comma-separated
   `key=value` string and split it, keeping the env-file surface unchanged.
6. **Write**: `loki.write` to `http://loki:3100/loki/api/v1/push`.
7. **Self-metrics**: expose Alloy's own metrics on `:12345` (default) — VM
   scrapes them, no remote-write from Alloy.

## loki-config.yml

Minimal single-binary filesystem config. Points that matter:

- `auth_enabled: false`, `replication_factor: 1`, inmemory ring.
- `limits_config.retention_period: ${LOGS_RETENTION}` (expanded via
  `-config.expand-env=true`).
- **`reject_old_samples: false`.** The prototype set
  `reject_old_samples_max_age: 1h`, which silently drops everything from a test
  run that finished more than an hour ago — precisely the case this tool
  exists for.
- Compactor with `retention_enabled: true`.
- No `otlp_config` block is needed; Alloy uses the native push API.

## promscrape.yml addition

```yaml
- job_name: alloy
  static_configs:
    - targets: ["alloy:12345"]
      labels: %{METRIC_LABELS}
```

## Loki datasource

Append to the existing datasources file, mirroring issue 1's pattern:
`name` and `uid` both `${LOKI_DATASOURCE_NAME}`, `type: loki`,
`url: http://loki:3100`. Not the default datasource.

## Selftest additions

A `busybox` container under the `selftest` profile appending to
`/logs/selftest.log`: some ordinary lines, then a Java stack trace — an
exception line followed by several `\tat com.example...` frames.

`selftest/assert.sh` runs inside a container (root_spec §8), addressing
`loki:3100` and `victoriametrics:8428` directly. It gains:

1. A LogQL query over `http://loki:3100` selecting on a label from
   `LOG_LABELS`; fail if it returns nothing. This is the check that the labels
   became *stream* labels.
2. A query selecting `{log_name="selftest"}`; fail if empty.
3. Fetch the stack-trace entry and assert it contains **more than one line** in
   a single entry — this is what proves multiline grouping works, and it is the
   assertion most likely to catch a regression.
4. Assert `up{job="alloy"} == 1` in VictoriaMetrics.

## Gotchas

- `LOGS_DIR` must exist before `up` or Docker creates it as a root-owned
  directory. Have the Makefile create it, and ship `logs/.gitkeep`.
- Alloy's positions volume must persist, or every restart re-ingests every file
  and Loki rejects the duplicates.
- On Windows, a `LOGS_DIR` on the *Windows* filesystem tails slowly and can miss
  change notifications; inotify does not propagate cleanly through Docker
  Desktop's file sharing. Alloy polls, so lines still arrive, but the README
  should tell Windows users to keep logs inside the WSL2 filesystem. See
  root_spec §8.
- Loki's own config uses `${VAR}` (expand-env), while VM's uses `%{VAR}`. They
  are different syntaxes for different tools; do not unify them.

## Definition of done

All acceptance criteria in [issue2.md](issue2.md), plus pinned image tags and a
README section on pointing `LOGS_DIR` at a run's output.
