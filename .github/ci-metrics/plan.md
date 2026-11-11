### Goal and baseline

Cut MATS + XTS runner minutes by **5%**, without making wall-clock time or failure detection worse.

The baseline is the mean of the complete weeks after the Oct 22 restructuring (weeks of Oct 26 and Nov 2): about **850 runner hours per week for MATS and 575 for XTS, 1,425 together, so 5% is about 71 hours per week**. Earlier weeks averaged 495 and 260, but the workflows changed (see below), so earlier weeks are the wrong yardstick.

### What changed recently

MATS cost per PR run rose about **26% after Oct 22**, from 138 to 174 runner minutes. HAPI went from 95 to 118 min per run (in a sample of successful shards, 122 to 141, about 92% of it from the new CLPR shard added Oct 14 in #27354), Otter from 6 to 12, and two new jobs appeared (EVM Functional Tests, 7.4 min; Benchmark Smoke, 1.6 min). PR volume also rose (about 290 runs a week vs about 200 before). XTS cost per run rose from 193 to 341 minutes, and XTS now runs through controller `226` (from Oct 22) instead of running its tests inside the `900` cron. Cost growth needs a guardrail, not just one-time savings.

### Where the time goes

| Finding | Size | Source in this report |
|---|---|---|
| HAPI tests are the bulk of MATS | 69% of MATS runner time (3,836 of 5,594 h); on the critical path in 52% of runs | MATS stages, critical path |
| 56% of XTS runner time is dry runs (`001`), 322 runs at about 5 h each | 1,612 of 2,853 XTS hours; the 72 real `226` runs cost 570 h | XTS workflows table |
| Wasted runner time | MATS 10.5% (588 h), XTS 18% (517 h, almost all failed jobs) | Waste charts |
| XTS failures concentrate in a few jobs | Cutover-config HAPI shards fail about 25% of the time (196 h wasted in that stage), bn-comms 25 to 28% | XTS jobs table |
| Same commit re-run on a PR | 90 PR runs re-ran MATS on a commit that already had a costly run: 214 h (3.8% of MATS runner time) | "PR runs repeated on a commit" table |
| Cheap-gate failures don't stop the heavy shards | 276 runs had a compile, unit or integration job fail while HAPI/Otter kept running: 319 h (5.7% of MATS) | Headlines |
| HAPI grew with each addition | A new CLPR shard costs 14 to 16 min per run (about 9% of MATS runner time); other shards are flat or creeping up (Smart Contracts & ISS 16.4 to 19.9 min since Oct 22). The test step is about 90% of each shard, so only fewer or faster tests help | Sampled shard durations, see item 9 |
| Gradle build cache already skips a lot of HAPI work | In 10 sampled successful PR runs, every HAPI shard was served entirely from the build cache in 3 (30%), and Fast Otter and Falcon in 7 (70%); those shard jobs ran about 2 minutes. Raising the hit rate is an alternative to running less | `test_shards.cached_classes` in the database; sampled, successful runs only |
| Setup overhead | 15.8% of MATS job time since Oct 22 is runner setup, checkout, upload and publish steps (XTS 9.8%) | Runner setup overhead tile |
| Many tiny jobs | 26,736 of 46,531 MATS jobs run under 3 minutes: 501 h running plus 336 h queued | Jobs under 3 min tile |
| Queue time is mostly incidents | Normal waits are 15 to 60 s. One day (Aug 11) accounts for 1,206 of 1,777 queue hours; other bad days: Sep 3, Oct 27, Nov 2–4 | Queue incidents table |

### Opportunities

Estimates are upper bounds taken from this data; each experiment states how it is measured. Percentages are of the suite's runner time.

| # | Change | Suite | Estimate | Measured by |
|---|---|---|---|---|
| 1 | Stop `labeled` (and `reopened`) from re-running MATS on a commit that already ran. Keep the re-run only for the `Run EVM Tests` label. Add `github.event.action` to the run summary first, so the real count is known. | MATS | up to 3.8%; plan on about 2% | PR runs per `head_sha`; runner h/week |
| 2 | Fail fast: when unit, integration or dependency jobs fail, cancel the HAPI and Otter shards (or have them wait for unit tests). | MATS | up to 5.7%; plan on about 3% | `heavy_min_in_gate_failed_runs`; wall p50 must not rise more than 2 min |
| 3 | `Detect Change Type` does a full-history checkout and takes 2.7 min per run. List changed files through the API instead. | MATS | about 1.5% (85 h) | stage minutes per run for `Detect Change Type` |
| 4 | Merge the small serial jobs (`Get Commit Info`, `Extract Java Version`, `Set Failure Mode`, `CI Complete`, `Report PR Check Summary`) into one or two. Each pays runner start, Prepare Runner and Post steps. | MATS | about 1.5% | jobs per run; jobs under 3 min |
| 5 | Make the default `001` XTS dry run a smaller panel set (heavy panels such as JRS, cutover configs and chaos Otter opt-in). | XTS | 25% fewer dry-run hours is about 14% of XTS | `001` runner h/week |
| 6 | Fix or quarantine the two worst XTS failure sources (cutover-config HAPI shards, bn-comms) and re-run only the failed shard. | XTS | up to 10% of XTS | wasted h per week; failure rate per job |
| 7 | Find out why Otter doubled (6 to 12 min per run) and whether the new EVM and benchmark jobs need to run on every PR. | MATS | unknown until measured | stage minutes per run |
| 8 | Right-size runner classes (`hapi-lin-xl` is used for the lighter atomic-batch shard) once per-class dollar rates are known. | both | unknown | dollars per run (needs rates) |
| 9 | **HAPI shards.** (a) Run the CLPR shard only when CLPR-relevant paths change (owners to confirm acceptable). (b) Track every shard's duration per run (done: `Publish Run Metrics` records it in the database, with cache hits split out). (c) Per-test timings now exist (Tests section of the report): split the few very long serial tests (Restart `JumpstartFileSuite` 614 s, `SteadyStateThrottlingTest` 760 s, `StakingSuite`) and rebalance shards. (d) Check whether the restored stream and log validation (#27579) and cutover defaults (#28090) added time. | MATS | (a) up to about 7%; (b), (c), (d) unknown until measured | HAPI min per run by shard; CLPR runs per PR |

Estimates are not in rank order; item 9(a) is the largest single MATS item and the one that needs the owners' agreement. Items 1 to 4 add up to about 8% of MATS as a plan, roughly 68 h per week at the current baseline (the upper bounds of 1 and 2 overlap, so they don't simply add). Item 5 alone is worth roughly 80 h per week for XTS, and item 6 adds more. Items 1 to 4 nearly reach the 71 h per week target on their own, item 5 alone exceeds it, and together they leave room for the cost growth seen since Oct 22.

### How we know it worked

1. **Freeze the baseline.** Keep this report's numbers as the "before" (weeks of Oct 26 and Nov 2). Re-run the collector weekly.
2. **One change at a time, behind a short observation window** (two complete weeks), compared on runner minutes per run, not per week, so PR volume doesn't hide the result.
3. **Guardrails** that must not regress: wall-clock p90 (MATS 53 min, XTS 87 min), run success rate, and the number of defects found only by a later run (escape rate, once tracked).
4. **Report the result** in the same HTML report: before and after tile for each change, with the stage or job it targeted.

### Sequence

1. **Instrument first (week 1).** Merge the `Publish Run Metrics` job in `600` (it records the event action, the `Detect Change Type` outputs, steps and per-test timings for every run), seed the local SQLite database with `ingest.py backfill`, and settle the open items in "Gaps and action items".
2. **Cheap, low-risk changes (weeks 2 to 3).** Opportunities 1, 3 and 4.
3. **Behaviour changes (weeks 3 to 5).** Opportunity 2 (needs agreement that failing early is acceptable), opportunity 9(a) with the CLPR owners, and opportunities 5 and 6 with the XTS owners. 9(b) is covered by the instrumentation. Also look at why only about 30% of runs get a full HAPI cache hit: which inputs change between PRs.
4. **Revisit** opportunities 7 and 8 when step-level history and dollar rates exist.
