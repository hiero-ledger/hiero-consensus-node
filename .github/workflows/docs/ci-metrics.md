# CI run metrics (data collection)

Records, for every MATS and XTS run, what the Actions API later drops: step timings (kept about 18 days) and the shard JUnit
reports (kept 7 days), plus the trigger context that is not recorded anywhere queryable. The last job of each instrumented workflow
runs `865-call-publish-run-metrics.yaml`, which runs `run_metrics.py` and uploads one small artifact, `run-metrics` (about 50 KB,
kept 90 days). Nothing here reads the artifact back.

Tracking issue: hiero-ledger/hiero-consensus-node#28275.

## Workflows covered

| Workflow | Suite | Context recorded |
|---|---|---|
| `600` PR Checks | MATS | event action, head sha, `Detect Change Type` outputs, MATS failure mode |
| `300` Build Application (push to `main` and `release/**`) | MATS | ref name, head sha, MATS failure mode |
| `000` MATS Dry Run | MATS | ref name, head sha, MATS failure mode |
| `226` XTS Controller | XTS | candidate ref, whether XTS proceeded, tag commit, branch |
| `001` XTS Dry Run | XTS | ref name, head sha |
| `106` XTS Optional Tests | XTS | ref, ref name |

Not covered: the cron shells `900`, `901` and `227` (they prime and dispatch, they run no tests). Adding a workflow means adding it to
`WORKFLOWS` in `collect.py` (by file name) and adding a `publish-run-metrics` job that calls `865`, with every other job of the
workflow in `needs` and `if: always()`.

## How a run is recorded

- `865` is called with a JSON `context` of string values. It checks out only the scripts, installs the image's `python3` with `apt`
  (`setup-python` installs a build that needs a newer glibc than the `hl-cn-*` images have), runs `run_metrics.py` and uploads the file.
- The calling job has every other job in `needs` so it starts last, and `if: always()` so runs cancelled by a newer push are recorded
  too. The job inside `865` is `continue-on-error` with a 10 minute timeout, so it never fails or holds up the calling run.
- `600` only runs it for pull requests from branches in this repository, and every caller only in the public repository.
- It uses the default `GITHUB_TOKEN` (read access to Actions) and about 30 API calls per run. The token allows about 1,000 calls an
  hour per repository, shared by every job, so the script does not wait for a rate-limit reset: it fails the job instead.

## Artifact format (schema 1)

`run-metrics.json`:

- `run`: the run (id, workflow name and file, suite, event, head sha, created time, display title, PR numbers). Its conclusion is
  derived, because the job is part of the run: the latest attempt's `CI Complete` job when there is one, otherwise the latest
  attempt's other jobs (failure or timed out first, then cancelled).
- `context`: the caller's values.
- `jobs`: every completed job of the run, all attempts, with runner labels, timestamps and steps.
- `tests`: rows of kind `shard` (totals, plus `cached_classes` and `cached_seconds`), `class` (15 slowest per shard) and `case` (30
  slowest per shard).
- `test_errors`: report artifacts that could not be fetched or parsed (`artifact`, `error`). One bad report does not lose the run.

Gradle restores test results from its build cache when nothing relevant changed, and the XML then keeps the original execution's
timestamp. Suites with a timestamp older than the run are counted as cached and get no class or case rows.

Test reports come from CI runs, so they are parsed as untrusted input: size caps, any DOCTYPE or entity declaration refused wherever
it appears in the file (checked before parsing), non-UTF-8 files refused, and nothing is extracted to disk. The API token is not sent
to the blob-storage redirect. Pull requests from forks publish nothing.

## Files

All under `.github/workflows/support/scripts/ci-metrics/` (Python 3.10, standard library only; the runner image's `python3`):

| File | Purpose |
|---|---|
| `run_metrics.py` | Entry point. Writes `run-metrics.json`. |
| `collect.py` | API helpers (a GET with bounded retries), run and job shapes, the `WORKFLOWS` map from workflow file to name and suite. |
| `test_timings.py` | Reads the run's shard JUnit artifacts and returns per-shard, per-class and per-test rows. |
| `tests/` | Unit tests. Run by `857-call-workflow-unit-tests.yaml` (`python3 -I -m unittest discover -s .github/workflows/support/scripts/ci-metrics/tests`). |

## Consumer and ownership

The artifact is read by tools in the `hashgraph/consensus-node-tools` repository (`ci-metrics/`): `ingest.py` loads it into a SQLite
database, and an HTML report and a dashboard read that database. Maintainer: Josh Marinacci. Today the database is a local file on the
maintainer's machine; there is no shared store yet. That repository's `run_metrics.py` is a reference copy only.

## Removal

This collector is temporary. Remove `865`, the `publish-run-metrics` jobs, this directory and the `857` test job once Chewie provides
per-run step and JUnit data (link the Chewie issue here when it exists). Chewie already archives run, job and step history; what it
lacks is per-test timings and the PR trigger context, which is why this exists.
