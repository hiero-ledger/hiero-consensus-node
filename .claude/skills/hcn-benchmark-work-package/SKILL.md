---
name: hcn-benchmark-work-package
description: Carries one work package of the consensus-layer benchmark backlog (fix, replace, or add a JMH benchmark in a platform-sdk module) from its GitHub issue to a tracked, calibrated benchmark that meets the definition of done. Eleven steps in order: fix, latency, profile, tracking, smoke run, calibration on the benchmark machine, pull request, first nightly result, dashboard, review, alert. Each step names its commands and the check that ends it; the rules live in the benchmark guide, platform-sdk/docs/consensus-layer/benchmarks/. Load this when asked to work on a benchmark issue, to fix, write, calibrate, or track a JMH benchmark of a consensus module, or to bring one to its definition of done.
---

# Benchmark Work Package

One work package takes one JMH benchmark of a consensus-layer module to the
[definition of done](/platform-sdk/docs/consensus-layer/benchmarks/README.md#definition-of-done). This skill holds the
procedure: the order of the steps, their commands, and the check that ends each one. The
[benchmark guide](/platform-sdk/docs/consensus-layer/benchmarks/README.md) holds the rules; each step links the
sections it applies. Read the guide's README and `writing.md` before step 1, `operations.md` before step 4, and
`calibration.md` before step 6.

## Input: the issue

The work package's GitHub issue, by number or link: `gh issue view <number>`. It says what the benchmark measures and
why, with the component's share of CPU; which existing benchmark it fixes or replaces; what other issues cover; and
things to check, found in a first read of the code and not confirmed.

Before step 1, settle with the developer what the issue leaves open. Only the team decides these:

- **The parameters that represent production,** for example the number of nodes, the event rate, or the number of
  parents and transactions per event. A signing or verification benchmark covers both Ed25519 and RSA.
- **What one operation is:** one call, or one item through a pipeline.
- **Fix or replace** the existing benchmark, if there is one.
- **The nightly time it may take,** as an orientation: about 10 minutes per benchmark on average. Calibration picks
  the cheapest configuration that gives good results; some benchmarks need longer, others less.

Note the answers; they go into the description suggested in step 7.

## How to work

- **Prove each step with its check and show the output;** don't argue that a step is done. Name a check that cannot
  run yet as open.
- **Ask before anything that leaves this machine:** a workflow run (it occupies the shared benchmark machine), a
  comment on GitHub. The developer commits, pushes, and creates the pull request; never commit, merge, or rebase their
  branches, and never create or edit a pull request.
- **Ask, don't decide,** when a choice belongs to the team: the inputs above, a change to production code, the fork
  count when calibration offers a trade, a change to shared tooling, the processor pin, or the nightly schedule.
  Present the options with their costs and a recommendation.
- **Propose before building** anything new beyond the benchmark class: a tool, a metric, a change to the plugin or a
  workflow. Small, then extend.
- **Never recommend a value nobody measured.** Say "unmeasured" and name the run that would measure it. Local runs show
  only that results are plausible ([measuring locally](/platform-sdk/docs/consensus-layer/benchmarks/writing.md#measuring-locally)).
  A time is true only under its condition: "the run ends at 20:00 UTC" holds only if it starts at once.
- **Write for a developer who knows Java but not JMH or the JIT:** plain words, each term explained where it first
  appears, in Javadoc, script headers, and the pull request alike.
- **Run long JMH runs in the background,** and only one JMH process at a time on a machine: JMH holds a lock in
  `java.io.tmpdir`, and a second run fails at once or disturbs the first. Don't wait for a queued workflow run in the
  foreground; report its id and what to check when it is done.
- **Keep to the work package.** A finding outside it, such as a production bug or a CI problem, is reported with its
  evidence and handed off, not investigated. A fix to production code, such as one for fork modes, and a change to
  shared tooling each get a pull request of their own.
- **When a session ends before the work package does,** say which step is done, which check is open, and which
  decisions were made, so that the next session can continue.

## The steps

Do steps 1–5 locally. Then the developer pushes the branch, step 6 calibrates it on the benchmark machine, and only
then does the developer create the pull request (step 7). Once merged, the module tracks the benchmark and every night
publishes its results; calibrating first keeps an uncalibrated configuration out of the nightly history. Steps 8 and 9
check the first night after the merge.

| Step | What | Ends when | Commands |
|------|------|-----------|----------|
| 1 | **Fix:** seeded inputs generated outside the measured calls; fresh copies per pass if the code changes its input; an exact output check after every invocation; things to check from the issue resolved. | A run with two forks exits 0, so every pass of both forks produced the expected output. | [steps-1-3.md](references/steps-1-3.md#step-1-fix) |
| 2 | **Latency:** `LatencyRecorder` times every call, `LatencyProfiler` reports. | The JSON has `latency.mean`, p50 to p99.99, max, `alloc.norm`, and no discarded pass; a run without the profiler exits 1. | [steps-1-3.md](references/steps-1-3.md#step-2-latency) |
| 3 | **Profile** a run with async-profiler. | `profile-regions.sh`: the code under test takes nearly all of the measured region, the timing under 1%, and the measured region shows no locks, waiting, or handoffs. | [steps-1-3.md](references/steps-1-3.md#step-3-profile) |
| 4 | **Track:** the module applies the tracking plugin and names the benchmark. | `jmhTracked` writes a manifest that names it, and the driver runs every parameter set from it. | [steps-4-6.md](references/steps-4-6.md#step-4-track) |
| 5 | **Smoke run.** | `jmhSmoke` passes, a rerun is up to date, and after deleting its output it comes from the cache. | [steps-4-6.md](references/steps-4-6.md#step-5-smoke-run) |
| 6 | **Calibrate** the pushed branch on the benchmark machine. | The annotations and the Javadoc block equal what `analyze_calibration.py` prints for the chosen configuration, and the smoke run passes again. | [steps-4-6.md](references/steps-4-6.md#step-6-calibrate) |
| 7 | **Pull request:** suggest a concise title and description; the developer creates it. | The pull request's "Benchmark Smoke" check passes. | [steps-7-9.md](references/steps-7-9.md#step-7-pull-request) |
| 8 | **First nightly result** after the merge. | Every parameter set has a quiet result, and the dry run of the converter shows its series. | [steps-7-9.md](references/steps-7-9.md#step-8-first-nightly-result) |
| 9 | **Dashboard.** | The developer sees the night in every panel of the benchmark on the dashboard. | [steps-7-9.md](references/steps-7-9.md#step-9-dashboard) |
| 10 | **Review:** general lessons into the guide, reusable code into `consensus-benchmark-tools`, open points into the issue. | `./gradlew qualityGate` passes on the changed files, every link resolves, and the issue lists what is left. | below |
| 11 | **Alert.** | Not set up yet. | [step-11.md](references/step-11.md) |

### Step 10: review

- **A lesson that holds for any benchmark goes into the guide,** in the section that covers its topic, in this pull
  request or a follow-up. A lesson about this procedure, such as a command that changed, goes into this skill.
- **Code that a second benchmark will need goes into `consensus-benchmark-tools`,** in its own pull request (its
  README states who may depend on it).
- **The issue lists what is left,** at least the alert (step 11), and links the pull requests.
