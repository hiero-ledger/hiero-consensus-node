# Changes

## 2026-11-09 10:28

Added `.github/ci-metrics/`: measurement of the MATS and XTS workflows.

- `collect.py`: pulls 90 days of runs and jobs (all attempts) for `600`, `000`, `226`, `001`, `106`, `900`, `901`, `227`. Resumable, rate-limit aware, splits date windows to get past the API's 1,000-result cap, retries transient 5xx and truncated responses.
- `analyze.py`: runner time, queue time, wall clock, waste buckets, critical path, stage and job rollups, setup overhead, queue incidents, data gaps, before/after comparison, same-commit re-runs, and an append-only `--history` file.
- `report.py` and `report_template.html`: one self-contained HTML report (light and dark, tooltips, table view per chart, optional $ per runner-hour).
- `plan.md`, `gaps.md`: improvement plan and metrics gaps / action items shown in the report.
- `tests/test_analyze.py`: 18 unit tests.
- `907-cron-ci-workflow-metrics.yaml`: not-yet-active daily collection workflow (outside `.github/workflows` on purpose).
- No existing workflow was changed. Nothing committed.

## 2026-11-09 11:05

- `plan.md`: added opportunity 9 (HAPI shards: CLPR path filtering, per-shard tracking, per-test timings) and the HAPI growth finding.
- Corrected the report and `gaps.md`: CI most likely ran elsewhere during Oct 2-21 (bot comments on merged PRs), and step detail is a rolling ~18 day retention, not an Oct 22 event.
- `analyze.py`: history rows now keep per-stage setup overhead (`stepped_min`, `setup_min`).

## 2026-11-09 11:30

- Added `.github/workflows/907-cron-ci-workflow-metrics.yaml` (daily history collection, public repo only) and its row in `.github/workflows/docs/workflow-manifest.md`. Removed the `proposed/` copy.
- `analyze.py`: new `--history-only` mode (a 3-day window can lack a suite, so the aggregates are skipped); history rows now include one entry per HAPI/Otter shard job (opportunity 9b).
- Tests: 21 (history-only, row replacement, shard rows).
- Tested the collect, history update and branch publish steps against a local bare remote with one day of real data. Not run in GitHub Actions yet; `actionlint` only complains that the repo's custom runner label is unknown, as it does for every workflow here.

## 2026-11-09 12:00

- `600-flow-pull-request-checks.yaml`: added `run-name` (`PR #N <action>: <title>`) and a "Run context" block in the PR check summary (event action, head commit, docs-only, tests enabled, workflow files changed, smart contracts changed). No job or condition changed. Note: runs now show the action in their title in the Actions UI.
- `collect.py` stores `display_title`; `analyze.py` parses the action into history rows and counts PR runs by action (`retrigger.by_action`). Tests: 24.
- Not done: the `Detect Change Type` outputs are only in the step summary, not queryable through the API.

## 2026-11-09 14:50

- Added `test_timings.py`: samples successful PR runs from this repo (not forks), downloads the HAPI/Otter/Falcon JUnit report artifacts and records per-shard totals, classes over 2 s and the 30 slowest tests per shard to `test-timings.jsonl` (merged by run and shard). Untrusted-input guards: size caps, DOCTYPE/entity rejection, never extracted, token not sent to the blob redirect.
- `907` now also runs it (last 6 days, 2 runs per day) and publishes `test-timings.jsonl` to the history branch.
- `report.py --tests` adds a "HAPI and Otter test timings" section. Tests: 32.
- Seeded `out/test-timings.jsonl` locally from 5 runs (Nov 4 to 9).

## 2026-11-09 16:15

- Replaced git-branch storage with an artifact plus local SQLite design. Removed `.github/workflows/907-cron-ci-workflow-metrics.yaml` and its manifest row.
- `600-flow-pull-request-checks.yaml`: new last job `Publish Run Metrics` (not required by anything, `continue-on-error`, 10 min timeout, skipped for fork PRs and non-public repos). It runs `run_metrics.py` and uploads artifact `run-metrics` (90 days).
- New `run_metrics.py` (end-of-run document: run, jobs with steps, context, compact per-test timings) and `ingest.py` (SQLite schema, `pull`, `backfill`, `file`, `stats`, views `weekly_cost` and `stage_cost`).
- `analyze.py`: `build_rows` extracted so ingest and the report share definitions.
- `test_timings.py`: results restored from the Gradle build cache (suite timestamp older than the run) are counted separately and excluded from class and test timings; `rows_for_artifact` takes `top_classes`. In 10 sampled successful runs, all HAPI shards were fully cached in 3 and Fast Otter and Falcon in 7.
- Tests: 44. Checked end to end on real run 42709980010 (`run_metrics.py` about 8 s, 52 KB artifact file; loaded into SQLite).
- Not done: the artifact job has not run in GitHub Actions; `pull` was only tested with a mocked API; the local database is not seeded yet (needs `collect.py --days 90` then `ingest.py backfill`).
