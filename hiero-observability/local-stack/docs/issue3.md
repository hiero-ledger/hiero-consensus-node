# 3 — Dashboard provisioning from an external directory

Part of [root_issue.md](root_issue.md). Depends on issues 1 and 2.

## Summary

Let a user point the stack at a directory of existing Grafana dashboards —
typically the ones already used in production, living in another repository —
and have them load and render locally without editing the dashboard files.

## Background

Dashboards exported from Grafana with the "export for sharing externally"
option carry an `__inputs` array and reference datasources as
`"uid": "${DS_SOMETHING}"`. **Grafana's file provisioner does not resolve those
placeholders** — only the import path does. Dropped into a provisioning
directory unchanged, every panel renders "Datasource ${DS_...} was not found".

An audit of the 22 dashboards in `hedera-node/infrastructure/grafana/dashboards/`
found three distinct binding forms in use, only one of which needs rewriting:

- `${DS_GRAFANACLOUD-SWIRLDSLABSPREPRODUCTION-PROM}` placeholders — 12 files, **needs rewriting**
- literal `"uid": "grafanacloud-prom"` — 13 files, works if our datasource uses that uid
- legacy name-only `{"name":"grafanacloud-prom"}` / `{"name":"grafanacloud-logs"}` — works if our datasource *names* match
- `${datasource}` / `${Datasource}` — ordinary datasource-type template variables, resolve on their own

Issues 1 and 2 already name the datasources `grafanacloud-prom` and
`grafanacloud-logs` for exactly this reason, so only the first form is left.

## Scope

- `GRAFANA_DASHBOARDS_DIR` pointing anywhere on the host, mounted read-only.
- An init container that rewrites `${DS_*}` placeholders to the local datasource
  uid, writing into an internal volume — the user's directory is never modified.
- A guard that fails startup loudly if a dashboard declares a non-Prometheus
  `__inputs` entry, rather than mis-binding it silently.
- Grafana dashboard provider mirroring the source directory structure into
  Grafana folders.
- Selftest assertion that a placeholder-style dashboard binds and renders data.

## Out of scope

Shipping any dashboards of our own. The tool ships none.

## Acceptance criteria

- With `GRAFANA_DASHBOARDS_DIR` pointing at the production dashboards
  directory, dashboards appear in Grafana under folders mirroring the source
  structure.
- A `${DS_*}` dashboard renders data rather than a datasource error.
- The source directory is byte-for-byte unmodified after a run.
- An unset or empty `GRAFANA_DASHBOARDS_DIR` starts cleanly with no dashboards.
- A dashboard declaring a non-Prometheus `__inputs` entry fails startup with the
  offending filename in the error.
- Panels edited in the Grafana UI are not instantly reverted by the provisioner.
- `make selftest` covers the binding case.
