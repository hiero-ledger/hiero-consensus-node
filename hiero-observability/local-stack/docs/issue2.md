# 2 — Logs: Alloy + Loki

Part of [root_issue.md](root_issue.md). Depends on issue 1.

## Summary

Add log collection to the stack. Grafana Alloy tails files from a
user-specified host directory and pushes them to Loki, which Grafana queries
through a provisioned datasource.

## Scope

- Loki and Alloy services added to the existing Compose project.
- A host log directory mounted read-only, discovered by a configurable glob.
- Multi-line grouping so JVM stack traces arrive as one log entry.
- Static stream labels from `LOG_LABELS`, plus an automatic `log_name` label
  derived from each file's basename.
- Loki datasource provisioned as `grafanacloud-logs`.
- Alloy's own metrics scraped by VictoriaMetrics for pipeline health.
- Selftest fixture and assertions for logs.

## Out of scope

Dashboards (issue 3). Log parsing beyond timestamp/multiline — level
extraction and structured parsing are deliberately left out.

## Acceptance criteria

- Pointing `LOGS_DIR` at a directory of log files makes them queryable in
  Grafana Explore within one refresh.
- A LogQL stream selector using a label from `LOG_LABELS` returns lines —
  i.e. the labels are real stream labels, not metadata.
- `{log_name="<basename>"}` selects lines from a specific file.
- A Java stack trace spanning many lines appears as **one** log entry.
- Logs written before the stack started are ingested, not rejected for being
  too old.
- Alloy's health is visible as a target in VictoriaMetrics' `/targets`.
- `make selftest` passes, covering all of the above.
