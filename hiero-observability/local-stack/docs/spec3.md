# Spec 3 — Dashboard provisioning

Prerequisite reading: [root_spec.md](root_spec.md) and the
delivered state of issues 1 and 2.

## Files to create or change

```
docker-compose.yml                               (add dashboards-init service)
grafana/provisioning/dashboards/dashboards.yml   (new)
dashboards/.gitkeep                              (new, empty default dir)
defaults.env                                     (add GRAFANA_DASHBOARDS_DIR)
.gitignore                                       (add dashboards.local/)
selftest/                                        (add a placeholder-style fixture)
```

## dashboards-init

A `busybox` init container, `depends_on` satisfied before Grafana starts.

- Mounts `${GRAFANA_DASHBOARDS_DIR}:/dashboards-src:ro` and a named volume at
  `/dashboards-out`, which Grafana also mounts read-only.
- Passes `METRICS_DATASOURCE_NAME` in the environment.

Steps:

1. **Guard.** Scan `/dashboards-src` for `"pluginId"` values inside `__inputs`
   blocks. If any value other than `prometheus` appears, print the filename and
   the offending pluginId and **exit non-zero**, blocking startup. The audit
   found only `prometheus` inputs today; this guard converts a future
   silently-mis-bound Loki panel into a startup error.
2. **Copy**, preserving directory structure, into `/dashboards-out`.
3. **Rewrite** in the copy: replace every `${DS_...}` occurrence with the value
   of `METRICS_DATASOURCE_NAME`. A single `sed` over each `.json` file is
   sufficient — the pattern is `\$\{DS_[^}]*\}` and it occurs only in datasource
   references.
4. Leave `__inputs` / `__requires` in place. They are ignored on the
   provisioning path.

The source directory is mounted read-only, so it cannot be modified even by
mistake.

### Why sed rather than jq

A `pluginId`-aware jq rewrite was considered. The audit showed every `__inputs`
entry is `pluginId: prometheus`, so the mapping logic would be dead code. The
guard in step 1 covers the case that would justify it. Prefer the simpler tool;
revisit if the guard ever fires.

## dashboards.yml

```yaml
apiVersion: 1
providers:
  - name: local
    type: file
    allowUiUpdates: true
    options:
      path: /dashboards-out
      foldersFromFilesStructure: true
```

`allowUiUpdates: true` matters — without it Grafana reverts any panel edit on
its next provisioning sweep, which is maddening locally.

## Empty-directory handling

Compose cannot bind-mount an empty path, so `GRAFANA_DASHBOARDS_DIR` defaults to
`./dashboards`, a committed directory containing only `.gitkeep`. The default
experience is "no dashboards" without any conditional logic in Compose.

## Refresh behaviour

The rewrite runs once at startup. To pick up edits in the source repo, re-run
`docker compose ... up -d --force-recreate dashboards-init`; Grafana's file
provider then hot-reloads on its own. Document this — do not build a polling
sidecar for it.

## Selftest addition

A fixture dashboard under `selftest/` in the exported format: an `__inputs`
array with a `pluginId: prometheus` entry, and one panel querying a metric the
selftest fixture emits, with `"uid": "${DS_TEST}"`.

`selftest/assert.sh` gains a check against Grafana's HTTP API at
`http://grafana:3000` (from inside the network, per root_spec §8; anonymous
admin access means no auth needed): fetch the provisioned dashboard, assert no
`${DS_` string survives anywhere in the returned JSON, and assert its panel
datasource uid equals `METRICS_DATASOURCE_NAME`.

## A caveat worth stating in the README

Binding the datasource is necessary but not sufficient for a production
dashboard to *show data*. Those dashboards filter heavily on labels that a
plain local scrape does not produce — the audit found `environment` used in 483
matchers (always via a `label_values(environment)` template variable),
plus `node_id` in platform dashboards and `node` in hedera-node dashboards. A
user reusing them must supply the matching labels through `METRIC_LABELS`,
which is why `environment=localhost` is the shipped default. Reassuringly, the
audit found **no** `namespace`/`cluster`/`pod`/`job`/`instance` matchers — those
two label families are the whole compatibility problem.

## Definition of done

All acceptance criteria in [issue3.md](issue3.md), plus a README section on
pointing at an external dashboards directory and on the label requirement above.
