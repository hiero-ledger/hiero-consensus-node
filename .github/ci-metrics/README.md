# CI metrics for the MATS and XTS workflows

Measures where MATS (run on every PR through `600`) and XTS (`900` → `226`) spend runner time, queue time and wall-clock
time, and records per-job, per-step and per-test timings in a local SQLite database. Also builds a self-contained HTML report.

Python 3.10+ (standard library only) and an authenticated `gh` (or `GH_TOKEN`) are all that is needed.

## How the data flows

```
600 (each PR run) ── last job "Publish Run Metrics" ──> artifact `run-metrics` (about 50 KB, kept 90 days)
                                                              │
        ingest.py pull  (run it on your machine now and then) ┘──> ci.sqlite   (your long-term store)
collect.py + ingest.py backfill  (once)  ───────────────────────> the 90 days the API still has
```

The artifact job runs while the run's jobs, step detail and JUnit reports still exist. The API drops step detail after ~18
days and the JUnit reports after 7, so capturing at the end of the run is the only way to keep them without sampling.
Nothing is written to git.

## Daily use

```bash
# one-time seed with the ~90 days the API still has (about 5,000 API calls; waits out the rate limit)
python3 -I .github/ci-metrics/collect.py --out /tmp/ci-metrics/data --days 90
python3 -I .github/ci-metrics/ingest.py --db ci.sqlite backfill --data /tmp/ci-metrics/data

# afterwards: pull new run-metrics artifacts (needs at least one pull every 90 days; weekly is plenty)
python3 -I .github/ci-metrics/ingest.py --db ci.sqlite pull
```

Every command is safe to repeat: runs are keyed by run id and replaced whole. Artifact data replaces backfilled data for the
same run (it has steps and tests); backfill never overwrites artifact data.

### Tables and views

`runs` (one row per run, with event action, change-type flags and failure mode), `jobs`, `steps`, `test_shards`,
`test_classes`, `test_cases`; views `weekly_cost` and `stage_cost`.

```sql
-- runner hours per week
SELECT * FROM weekly_cost ORDER BY week DESC;
-- PR runs that re-ran a commit that already had a run, by event action
SELECT action, COUNT(*) FROM runs WHERE workflow LIKE '600%' GROUP BY action;
-- slowest tests across runs
SELECT shard, class, ROUND(AVG(seconds),1) AS mean_s, COUNT(*) AS runs FROM test_classes GROUP BY shard, class ORDER BY mean_s DESC LIMIT 20;
-- share of shard runs served from the Gradle build cache
SELECT shard, ROUND(AVG(cached_classes = classes), 2) AS fully_cached FROM test_shards GROUP BY shard;
-- setup steps vs real work for a stage
SELECT s.name, ROUND(SUM(s.seconds)/3600.0,1) AS hours FROM steps s JOIN jobs j USING(job_id) WHERE j.stage LIKE '%HAPI%' GROUP BY s.name ORDER BY hours DESC LIMIT 10;
```

## The HTML report

```bash
python3 -I .github/ci-metrics/collect.py --out /tmp/ci-metrics/data --days 90
python3 -I .github/ci-metrics/analyze.py --data /tmp/ci-metrics/data --out /tmp/ci-metrics/metrics.json
python3 -I .github/ci-metrics/test_timings.py --out /tmp/ci-metrics/test-timings.jsonl --per-day 1   # optional, ~100 MB of downloads
python3 -I .github/ci-metrics/report.py --metrics /tmp/ci-metrics/metrics.json --tests /tmp/ci-metrics/test-timings.jsonl --out /tmp/ci-metrics/report.html
```

`report.py` embeds `plan.md` and `gaps.md`. `out/` (git-ignored) holds generated output. Reading the report from the SQLite
database instead of the API window is not built yet.

## Scripts

| Script | Purpose |
|---|---|
| `collect.py` | Runs and jobs from the API (resumable, rate-limit aware, splits date windows past the 1,000-result cap) |
| `analyze.py` | Runner/queue/wall time, waste, critical path, overhead, gaps, eras → `metrics.json`; `build_rows` is shared with `ingest.py` |
| `run_metrics.py` | The end-of-run job: this run's jobs, steps and compact per-test timings → `run-metrics.json` |
| `ingest.py` | SQLite store: `pull`, `backfill`, `file`, `stats` |
| `test_timings.py` | Per-test timings from the shard JUnit artifacts (also used by `run_metrics.py`); sampling mode for the report |
| `report.py` | The HTML report |

Tests: `python3 -I -m unittest discover -s .github/ci-metrics/tests` (44 tests).

## Things to know

- **Build-cache restores.** Gradle restores test results from its build cache when nothing relevant changed; the XML then
  keeps the original execution's timestamp. Those suites are counted as `cached_classes`/`cached_seconds` and excluded from
  class and test timings, so a cached shard does not look like a 2-hour shard.
- **Class times overlap.** HAPI classes run concurrently on a shared network, so a shard's summed class time is several times its job
  duration. Use it as relative weight.
- **Untrusted input.** Test reports come from CI runs: size caps, DOCTYPE/entity declarations rejected, never extracted, and the
  API token is not sent to the blob-storage redirect. Fork PRs publish nothing.
- **API budget.** `GITHUB_TOKEN` allows about 1,000 calls an hour; the job uses about 30 per run.
- The run conclusion in the artifact is derived (this job is part of the run): from `CI Complete`, else from the other jobs.

## Definitions

| Term | Meaning |
|---|---|
| Runner time | `completed_at - started_at` of every job that got a runner, all attempts, in minutes. Stand-in for dollar cost. |
| Queue time | `started_at - created_at` of a job (waiting for a runner), tracked separately from runner time. |
| Wall clock | Latest executed attempt: run created → last job finished. |
| Wasted | Runner time of jobs that failed, were cancelled, or were redone by a rerun. |
| Carried-over job | "Re-run failed jobs" copies passed jobs into the new attempt with their old timestamps. Not counted again. |
| Stuck job | Cancelled or failed after 1h+ without ever getting a runner (a label nothing serves). Reported, not counted as runner time. |
| Critical path | Per run without reruns: from the last job to finish, walk back through the latest job that finished before the next one started. |
