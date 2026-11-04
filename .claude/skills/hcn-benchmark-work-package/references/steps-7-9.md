# Steps 7–9: Pull Request, First Nightly Result, Dashboard

Rules: [operations.md](/platform-sdk/docs/consensus-layer/benchmarks/operations.md), sections "The nightly run" and
"Publishing and the dashboard". Variables as in [steps-1-3.md](steps-1-3.md). The scripts need Python 3.10 or newer.

## Step 7: pull request

The developer creates the pull request; never create or edit one. Suggest a title and a description to paste:

- **Title:** the type and the issue's subject, for example `perf: Fix HashgraphModuleBenchmark`.
- **Description:** `Closes #<issue>.`, then what changed in a sentence or two, and the decisions settled for the
  issue in a few words each. No check results, no session history, and no findings beyond the work package.

Once merged, the nightly job picks the benchmark up: it finds every module that applies the plugin
([the nightly run](/platform-sdk/docs/consensus-layer/benchmarks/operations.md#the-nightly-run)). No workflow changes.

**Check:** the smoke job passes on the pull request. It runs when the pull request changes code, not for documentation
only.

```bash
gh pr checks <pr> | grep "Benchmark Smoke"      # MATS / Benchmark Smoke / Benchmark Smoke Run  pass
```

## Step 8: first nightly result

After the merge, the next run of `905-cron-nightly-benchmarks.yaml` measures the benchmark and publishes it.

```bash
gh run list --workflow 905-cron-nightly-benchmarks.yaml --limit 3 --json databaseId,headSha,status,conclusion,createdAt
gh run download <run id> -n jmh-results -D "$OUT/nightly/<run id>"
jq -r '.attempts[] | "\(.benchmark) \(.params) attempt=\(.attempt) quiet=\(.quiet) others=\(.others_cpus) \((.finished - .started) / 60 | floor) min"' \
  "$OUT/nightly/<run id>/runs.json"
grep commit= "$OUT/nightly/<run id>/run-info.properties"
python3 .github/workflows/support/scripts/benchmarks/publish_results.py --results "$OUT/nightly/<run id>" --dry-run \
  | grep "$BENCH"
```

**Check:**

- the measured commit (`run-info.properties`, not the run's `headSha`: a queued run checks out the branch when it
  starts) contains the merge;
- every parameter set of the benchmark has a quiet attempt;
- the dry run shows `jmh_latency_seconds` for every statistic, `jmh_allocation_bytes_per_op`, and `jmh_run_seconds`
  for every parameter set;
- `latency.mean` is close to the calibration runs' and `jmh_run_seconds` to the block's runtime, because the machine
  and the configuration are the same. If not, report it to the developer: a change between calibration and the night
  shows up here first.

A night without a quiet result publishes no latency; that is a gap, not a failure
([publishing](/platform-sdk/docs/consensus-layer/benchmarks/operations.md#publishing-and-the-dashboard)). Then check
the next night.

## Step 9: dashboard

The dashboard "ConsensusLayer Benchmarks" (uid `consensuslayer-benchmarks`, from
`.github/workflows/support/scripts/benchmarks/grafana-dashboard.json`) takes its benchmark and parameter drop-downs
from the data, so a new benchmark needs no change to it. Claude cannot reach Grafana: ask the developer to select the
benchmark and each parameter set.

**Check:** the developer sees the night of step 8 in the panels for latency, allocation, percentiles, load, attempts,
and discarded passes, and in the runs log. A change to the dashboard goes into its own pull request and keeps the uid,
so that an import overwrites it.
