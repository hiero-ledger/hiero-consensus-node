# CI run metrics (data collection)

Workflow `600` ends with a `Publish Run Metrics` job that runs `run_metrics.py` and uploads one small artifact,
`run-metrics` (about 50 KB, kept 90 days), per PR run. It captures what the Actions API later drops: step timings
(kept about 18 days) and the shard JUnit reports (kept 7 days). Loading, analysing and reporting happen outside this
repo, in a separate tools repository; nothing here reads the artifact back.

The job gates nothing: no job needs it, it is `continue-on-error`, has a 10 minute timeout, and is skipped for fork PRs
and non-public repos. It uses the default `GITHUB_TOKEN` (read access to Actions) and about 30 API calls per run.

## Files

| File | Purpose |
|---|---|
| `run_metrics.py` | Entry point for the job. Writes `run-metrics.json`. |
| `collect.py` | API helpers: authenticated GET with rate-limit handling and bounded retries, run and job shapes, the `WORKFLOWS` map. |
| `test_timings.py` | Reads the shard JUnit artifacts of the run and returns per-shard, per-class and per-test timing rows. |
| `tests/` | Unit tests: `python3 -I -m unittest discover -s .github/ci-metrics/tests` |

Python 3 standard library only.

## Artifact format (schema 1)

`run-metrics.json`:

- `run`: the run (id, workflow, suite, event, head sha, created time, display title, PR numbers). Its conclusion is
  derived, because the job is part of the run: from the `CI Complete` job, otherwise from the other jobs.
- `context`: values passed as `CTX_*` environment variables (event action, `Detect Change Type` outputs, failure mode).
- `jobs`: every completed job of the run, all attempts, with runner labels, timestamps and steps.
- `tests`: rows of kind `shard` (totals, plus `cached_classes` and `cached_seconds`), `class` (15 slowest per shard) and
  `case` (30 slowest per shard).

Gradle restores test results from its build cache when nothing relevant changed, and the XML then keeps the original
execution's timestamp. Suites with a timestamp older than the run are counted as cached and get no class or case rows.

Test reports come from CI runs, so they are parsed as untrusted input: size caps, DOCTYPE and entity declarations
rejected, never extracted, and the API token is never sent to the blob-storage redirect.

## Adding another workflow

Add its id to `WORKFLOWS` in `collect.py`, add a `Publish Run Metrics` job like the one in `600`, and pass any context it
has as `CTX_*` variables.
