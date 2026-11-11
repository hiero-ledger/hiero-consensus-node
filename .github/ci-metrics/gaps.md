### What we cannot measure today

| Gap | Why it matters | Proposed fix |
|---|---|---|
| **No history beyond what the Actions API keeps.** Run records go back about 90 days, with none at all between Oct 2 and Oct 21. Step detail and logs expire much sooner: steps about 18 days, logs about 14, JUnit reports 7. | "Over time" needs more than one quarter, and step-level data is lost unless captured early. | **Done on this branch:** `600` publishes a `run-metrics` artifact (jobs, steps, per-test timings) at the end of every PR run; `ingest.py pull` keeps it in a local SQLite database. `collect.py` + `ingest.py backfill` seed the 90 days the API still has. Check Grafana and the other place CI may have run (see actions). |
| **Dollar cost.** GitHub reports no cost for self-hosted runners. | Runner minutes treat `hapi-lin-xl` and `default-lin-sm` as equal. | Get a $ per runner-hour for each runner class and enter it in the report's rate box (one rate for now; per-class later). |
| **Which PR runs were triggered by what.** The runs API has no `action` (opened, synchronize, labeled, reopened). | Needed to size opportunity 1 exactly. | **Done on this branch:** `600` sets `run-name` to `PR #N <action>: <title>` and the artifact records the action. Data starts once merged. |
| **What changed in each PR.** `Detect Change Type` computes docs-only, enable-tests and smart-contracts-changed. | Needed to see which kinds of PR pay for the full HAPI suite. | **Done on this branch:** stored per run in the artifact and in `runs` (docs_only, enable_tests, workflow_files_changed, smart_contracts_changed). |
| **Per-test timings.** HAPI shards run as one Gradle step, so a 15 to 20 min step is a single number. | Cannot rebalance shards or find slow tests. | **Done on this branch:** every run's artifact has the 15 slowest classes and 30 slowest tests per shard, with build-cache restores split out (`test_shards`, `test_classes`, `test_cases`). |
| **Build cache hit rate and compile time.** Jobs use the Gradle build cache but hit and miss counts are not exported. | Unit tests average 9 min with a p90 of 19; a cache miss tail is a likely cause. | **Partly done:** for HAPI, Otter and Falcon shards a full cache hit is now visible (`cached_classes`). Compile and unit-test cache hits still need build-scan or cache statistics. |
| **Runner pool capacity.** Queue time is visible, pool size, idle time and scale events are not. | Queue incidents (Aug 11: 1,206 queue hours) cannot be tied to a cause. | Pull pool metrics from Chewie or the runner platform. |
| **Chewie allocation and namespace setup time for XTS.** | XTS wall-clock includes time GitHub cannot see. | Have Chewie report allocation latency, or log timestamps in `226`. |
| **Flaky vs real failures.** A failed job is only counted as failed. | Items 2 and 6 trade failing early against hiding flakiness. | Classify with the existing `failure-mode` output of `810` and `800`; record per job. |
| **Time to green per PR.** Runs are counted, PRs are not. | The developer-facing cost of MATS. | Add PR-level rows to the history file (first push to last green run). |

### Action items

| Item | Owner | Notes |
|---|---|---|
| **Investigate Grafana for metrics older than 90 days.** Find what is recorded (runner counts, queue depth, job durations), the retention, and whether it can feed the same report. | CI team | You mentioned Grafana has longer history; this is the first thing to settle because it sets how far back the baseline can go. |
| Find out where CI ran between Oct 2 and Oct 21. PRs merged then have no check runs in this repo, but Codecov, TestLens and Codeowners comments appear minutes after each PR opened. | CI team | Likely a private mirror (commits #27663 and #27593 gate jobs on private vs public repo). If its runs can be read, point the collector at it. |
| Provide $ per runner-hour for each runner class. | CI team / finance | Enables dollar cost and opportunity 8. |
| Check the Aug 11 queue incident (268 jobs, 1,206 queue hours) and the Sep 1 to 2 jobs that waited 24 h for runner label `hg-cn-default-lin-sm` (a label no runner serves, now gone from the workflows). | CI team | Add an alert for any job queued over 30 minutes. |
| Review the `Publish Run Metrics` job in `600` (about 1 to 2 runner-minutes per run, no effect on PR status), then merge it. Decide who runs `ingest.py pull` and where the SQLite file lives. | CI team | Alternative to a laptop: a scheduled job that publishes the database as a release asset. |
| Build report generation from the SQLite database so the report can span more than the 90 day API window. | Josh | Phase 2; the database has the same runs, jobs and stage data. |
