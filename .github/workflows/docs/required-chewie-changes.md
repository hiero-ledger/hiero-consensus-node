# Required Chewie Changes

CITR in this repository is moving from cron- and tag-driven scheduling to **Chewie-driven dispatch**. Chewie decides
when an XTS candidate is tested, promotes passing candidates into builds, and dispatches the workflows below. The crons
and tags that used to do this (`302`, `900`, `901`, `903`) are deprecated stubs.

This document is the contract between the two sides: which workflows Chewie dispatches, when, with which inputs, what
each reports back, and the Chewie-side changes that must land with or before this repository's changes.

## Table of Contents

- [Dispatch Conventions](#dispatch-conventions)
- [Flow Overview](#flow-overview)
- [Workflows Chewie Dispatches](#workflows-chewie-dispatches)
  - [226: XTS Controller](#226-xts-controller)
  - [227: Daily Controllers](#227-daily-controllers)
  - [224: MDLT Controller](#224-mdlt-controller)
  - [107: Clean CITR Namespaces](#107-clean-citr-namespaces)
  - [204/205/206: MDLT Follow-Up](#204205206-mdlt-follow-up)
- [What This Repository Reports Back](#what-this-repository-reports-back)
- [Required Chewie-Side Changes](#required-chewie-side-changes)
- [Follow-Ups in This Repository](#follow-ups-in-this-repository)

## Dispatch Conventions

These apply to every dispatch below.

- **Mechanism:** `POST /repos/hiero-ledger/hiero-consensus-node/actions/workflows/<file>/dispatches` with the Chewie
  GitHub App installation token. The App needs `Actions: write` on this repository.
- **Address workflows by file name**, e.g. `226-disp-citr-xts-controller.yaml`. Deprecated workflows keep their number
  as a stub whose `name:` starts with `Deprecated:` and which exits 1 — never dispatch one.
- **Dispatch on `main`.** The dispatch `ref` decides which version of the workflow runs, and the API only accepts a
  branch or tag there, never a commit SHA. The commit or build under test is always passed as an **input**.
- **Send exactly the declared inputs.** An undeclared input makes the whole dispatch fail with `422 Unexpected inputs
  provided`. All inputs are strings on the wire, including booleans and choices.

## Flow Overview

```
push to main ── MATS (300) ── POST /api/v1/suites/results  (suite_type: mats)
        │
        ▼
[Chewie] selects an XTS candidate ──dispatch──▶ 226 (inputs.ref = <commit SHA>)
                                                 ├─ XTS (815) + optional XTS (106)
                                                 ├─ tags xts-pass-<epoch> on success (temporary)
                                                 └─ POST /api/v1/suites/results  (suite_type: xts)
        │
        ▼
[Chewie] promotion engine ── creates build-NNNNN ── Slack report
        │
        └──dispatch──▶ 227 (inputs.ref = build-NNNNN)
                        ├─ 221 SDPT  (dispatched on the tag) ── reports sdpt
                        ├─ 222 SDLT  (dispatched on the tag) ── reports sdlt
                        └─ 223 SDCT  (ref + build-tag)

[Chewie] hourly ──dispatch──▶ 107 (no inputs) — prune streams/MinIO in every CITR namespace

[Chewie] SDPT + SDLT passed for a build ──dispatch──▶ 224 (inputs.build-tag = build-NNNNN)
                        └─▶ 835 kickoff ── reports mdlt running
                        └─▶ [Chewie, later] 204 monitor ── reports mdlt running / passed / failed / cancelled
                                            205 publish / 206 tag result
```

## Workflows Chewie Dispatches

| Workflow                              | Status   | When                                           | Inputs                                                |
|---------------------------------------|----------|------------------------------------------------|-------------------------------------------------------|
| `226-disp-citr-xts-controller.yaml`   | Required | A commit is selected as the XTS candidate      | `ref` — full 40-character lowercase commit SHA        |
| `227-disp-daily-controllers.yaml`     | Required | Right after Chewie creates a `build-NNNNN` tag | `ref` — the build tag, e.g. `build-00404`             |
| `224-disp-mdlt-controller.yaml`       | Required | SDPT and SDLT have both passed for a build     | `build-tag` — the build tag, e.g. `build-00404`       |
| `107-disp-clean-citr-namespaces.yaml` | Required | Hourly                                         | none                                                  |
| `204-disp-mdlt-monitor.yaml`          | Planned  | While an MDLT allocation is running            | `build-tag`, `allocation-id`, `prune-block-node-data` |
| `205-disp-mdlt-publish-results.yaml`  | Planned  | When an MDLT run completes                     | `build-tag`, `allocation-id`, `fsts_report`           |
| `206-disp-mdlt-tag-result.yaml`       | Planned  | When an MDLT verdict is known                  | `build-tag`, `result` (`success` \| `failure`)        |

"Required" workflows have no other trigger: if Chewie does not dispatch them, they do not run. "Planned" workflows are
dispatched by hand today and already document Chewie as their intended caller.

### 226: XTS Controller

Replaces the `xts-candidate` tag set by `302` and the three-hourly `900` cron.

- **When:** Chewie selects a candidate commit. `900` used to pick the `xts-candidate` commit every three hours, so that
  is the cadence the team is used to.
- **Inputs:** `{"ref": "<40-character lowercase SHA>"}`. Anything else (a short SHA, a tag, `main`) fails the run
  immediately, because the candidate is checked out at `fetch-depth: 1` and a short SHA can't be resolved there.
- **Eligibility checked by 226:** the commit must be on `main` and must **not** already be part of a build
  (`git tag --contains <sha>` matching `build-NNNNN`). A rejected candidate does not run XTS and is reported to Chewie as
  `not_run`.
- **Concurrency:** one run per SHA. A second dispatch for the same SHA queues behind the first.
- **Results:** on success, tags `xts-pass-<epoch>` (temporary — see [Follow-Ups](#follow-ups-in-this-repository)) and
  reports the outcome to Chewie (see [What This Repository Reports Back](#what-this-repository-reports-back)). It also
  dispatches the optional XTS panels (`106`), which do not gate promotion.

### 227: Daily Controllers

Replaces the dispatch step at the end of `901`.

- **When:** immediately after Chewie creates a `build-NNNNN` tag.
- **Inputs:** `{"ref": "build-NNNNN"}`. The build tag is the single source of truth — every controller runs and checks
  out at the tag. Anything that doesn't match `^build-[0-9]{5}$` fails before any controller is dispatched.
- **Dispatches:**
  - `221` (SDPT) and `222` (SDLT) **on the tag**, with no inputs. They read the build number from `github.ref`.
  - `223` (SDCT) on the tag, with `ref` and `build-tag` both set to the tag.
- `224` (MDLT) is **not** dispatched by `227`; Chewie dispatches it separately, see [224](#224-mdlt-controller).

### 224: MDLT Controller

Replaces dispatching MDLT by hand.

- **When:** once **both** SDPT and SDLT have passed for a build, according to Chewie's own suite results
  (`report-sdpt-result` / `report-sdlt-result`). Chewie applies this gate; `224` (and the adhoc `203`) no longer check
  for `sdpt-pass-`/`sdlt-pass-` tags.
- **Inputs:** `{"build-tag": "build-NNNNN"}`.
- **What follows:** `835` kicks off a multi-day run and `224` reports `running`. Progress and the verdict then arrive
  through `204`/`205`/`206` (see [below](#204205206-mdlt-follow-up)).
- **Unresolved — manual dispatch bypasses the gate.** Because the gate now lives only in Chewie, anyone who dispatches
  `224` or `203` by hand can start a multi-day MDLT run (and its six-day allocation) against a build that has **not**
  passed SDPT and SDLT. Before this change both workflows refused to start without the pass tags. See
  [Follow-Ups](#follow-ups-in-this-repository) — this must be decided before merge.

### 107: Clean CITR Namespaces

Replaces the hourly `903` cron.

- **When:** hourly, the cadence `903` ran at.
- **Inputs:** none.
- **What it does:** on both perf clusters (Dallas, Chicago), for every namespace matching `solo-*-n<digit>` or
  containing `chewie`, deletes consensus-node stream files older than 59 minutes and removes objects older than an hour
  from the `solo-streams` and `solo-backups` MinIO buckets. It **never** deletes a namespace — allocation lifecycle stays
  with Chewie.
- **Why it matters:** without it, long-running allocations (18-hour SDLT, multi-day MDLT) can fill their disks.
- **Concurrency:** one run at a time. An overlapping dispatch waits.

### 204/205/206: MDLT Follow-Up

An MDLT run lasts about five days, outside any workflow run. `835` only kicks it off and reports the kickoff. Each of
these workflows already says, in its header, that Chewie is expected to dispatch it:

- `204` monitors the run (green/red/yellow/black) and can prune block-node data older than 59 minutes.
- `205` publishes logs and, optionally, FSTS_Insight PDF reports.
- `206` tags `mdlt-pass-<build>` or `mdlt-fail-<build>` and reports to Slack/Rootly.

## What This Repository Reports Back

### Suite results

Every suite reports to `POST /api/v1/suites/results` through one reusable workflow,
`864-call-report-suite-result.yaml`. Each caller names its suite explicitly and maps its own job results to a
disposition. The report job runs with `if: always()`, so cancelled runs are reported too.

| Workflow | Suite  | Reports against               | Disposition                                                                                                                                               |
|----------|--------|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `300`    | `mats` | commit (`github.sha`), branch | As soon as MATS finishes: success → `passed`, cancelled → `cancelled`, skipped → `not_run`, otherwise `failed`                                            |
| `226`    | `xts`  | commit (the candidate SHA)    | Rejected candidate → `not_run`; otherwise success → `passed`, failure → `failed`, cancelled → `cancelled`                                                 |
| `221`    | `sdpt` | build number                  | cancelled → `cancelled`; test result success → `passed`; skipped → `not_run`; otherwise `failed`                                                          |
| `222`    | `sdlt` | build number                  | Same as `221`                                                                                                                                             |
| `224`    | `mdlt` | build number                  | Kickoff succeeded → **`running`**; cancelled → `cancelled`; skipped (build tag not verified) → `not_run`; otherwise `failed`                              |
| `204`    | `mdlt` | build number                  | **Every status check:** in work → `running`; finished cleanly → `passed`; error in the client log → `failed`; namespace gone or run stopped → `cancelled` |
| `206`    | `mdlt` | build number                  | The verdict Chewie dispatched it with: `success` → `passed`, `failure` → `failed`. Reported once the build is verified, whether or not tagging succeeds   |

MDLT is the only suite reported more than once per run: `running` at kickoff and at each `204` check, a terminal
disposition from `204` once the run has finished, and the final verdict from `206`. `206`'s own run conclusion only
reflects tagging and notifications — a `failure` verdict still produces a successful `206` run — so its explicit report,
not the run's conclusion, is the MDLT result. MATS reports for every push to `main` and `release/**`; pull-request MATS runs
(`600`) are not reported.

Example body (XTS):

```json
{
  "suite_type": "xts",
  "disposition": "passed",
  "commit": "<40-character lowercase SHA>",
  "branch": "main",
  "start_time": "2026-10-01T03:56:13Z",
  "end_time": "2026-10-01T09:12:40Z",
  "workflow_run_id": 12345678901,
  "run_attempt": 1
}
```

- **`build_number`** is sent as an integer (`build-00404` → `404`). Empty fields are omitted.
- **`start_time`** is the reporting run's own start (`run_started_at` for this attempt). **`end_time`** is only sent
  with a terminal disposition, never with `running`.
- **`workflow_run_id`** is the run doing the reporting — for MDLT that is `224` at kickoff and a different `204` run
  for each check.
- **Authentication:** `864` exchanges the identity key for a token itself, at report time. Long suites therefore never
  report with a stale token.
- **Reporting is required.** Chewie is the source of record for suite results, so a failed report — including a Chewie
  outage — fails the run. Git result tags (`xts-pass-*`, `sdpt-*`, `sdlt-*`, `mdlt-*`) are the optional, secondary
  record and are being retired. Every tagging job (`221`/`222` `tag-*-result`, `206` `tag-mdlt-result`, `226`
  `tag-for-promotion`) runs with `continue-on-error` and is excluded from the success/failure reporting, so a tagging
  problem never fails a run or raises an alert.

### Other signals

| From                        | What                                                               | Where                                   |
|-----------------------------|--------------------------------------------------------------------|-----------------------------------------|
| `226` `tag-for-promotion`   | `xts-pass-<epoch>` tag on the candidate (temporary)                | git tags                                |
| `221`/`201`, `222`/`202`    | `sdpt-pass-`/`sdpt-fail-<build>`, `sdlt-pass-`/`sdlt-fail-<build>` | git tags (signed)                       |
| `206`                       | `mdlt-pass-`/`mdlt-fail-<build>`                                   | git tags (signed)                       |
| `103`, `221`/`222`, by hand | Early release of an allocation (`225`)                             | `DELETE /api/v1/compute/allocation/:id` |

## Required Chewie-Side Changes

Must be in place before this repository's changes are relied on in production:

1. **Dispatch permission.** Grant the Chewie App `Actions: write` on this repository. Correct
   `docs/dev/citr_build_promotion.md` §4 in the Chewie repository, which still says Chewie does not dispatch and that a
   `push: tags: ['build-*']` trigger is the handoff.
2. **XTS candidate selection → dispatch `226`.** Replaces `302` + `900`. Select commits on `main` that passed MATS and
   are not already part of a build.
3. **Accept suite results — this blocks merging.** Serve `POST /api/v1/suites/results` for `mats`, `xts`, `sdpt`,
   `sdlt` and `mdlt`. The endpoint exists only in Chewie 3.x (v3.3.0 and later). Reporting is required, so against 2.11
   every report returns 404 and **fails the run** — including MATS (`300`) on every push to `main`. Chewie 3.x must be in
   production before this repository's changes merge, which in turn requires the 3.x scheduling-label migration under
   [Follow-Ups](#follow-ups-in-this-repository).
   - **MATS gates XTS candidacy.** `300` reports MATS for every push to `main` and `release/**`, so candidate selection
     (item 2) can use those reports directly.
   - **MDLT progress arrives as repeated reports.** One MDLT run produces a `running` row at kickoff and one per `204`
     check (each from a different workflow run), then terminal rows from `204` and `206`. Results are appended, so
     Chewie must resolve an MDLT run's state from its **latest** report for the build, and must not count each row as a
     separate execution. `206`'s report carries the verdict Chewie itself dispatched it with; it is the authoritative
     MDLT result.
4. **Promotion.** Enable the promotion engine (`CHEWIE_BUILD_LIFECYCLE_ENABLED`) to replace `901`, and:
   - Continue build numbering from the highest existing `build-NNNNN` tag. The repository variable
     `XTS_BUILD_PROMOTION_INDEX` that `901` incremented is no longer used.
   - Create build tags with the **App installation token**. A tag pushed with a workflow's `GITHUB_TOKEN` does not
     trigger other workflows, and `304` (publish yahcli image) runs on `build-*` tag pushes.
   - Post the promotion and no-promotion Slack reports that `901` used to send.
5. **Dispatch `227`** with the new build tag immediately after creating it.
6. **Dispatch `224`** for a build once both SDPT and SDLT have passed for it, judged from Chewie's own suite results.
   Neither `224` nor `203` checks pass tags any more; Chewie is the gate.
7. **Dispatch `107` hourly.**
8. **MDLT follow-up (planned):** dispatch `204`/`205`/`206` for MDLT allocations.

## Follow-Ups in This Repository

Changes that depend on Chewie reaching a later state:

- **Chewie 3.x scheduling labels — needed before merge.** 3.x allocation responses carry
  `scheduling.citr.hashgraph.io/allocation-id` and `test.citr.hashgraph.io/role` instead of
  `solo.hashgraph.io/{role,owner,network-id}`. `support/chewie/parse-chewie-allocation.sh` and the remote Solo values
  files must be updated before production moves to 3.x, or they will silently read `null`/`unknown`. Because suite
  reporting requires 3.x (Required Chewie-Side Change 3), this has to land with or before this repository's changes.
- **MDLT gate on manual dispatch — needs a decision before merge.** `203` and `224` no longer verify that a build passed
  SDPT and SDLT; only Chewie's dispatch of `224` applies that gate. A human dispatching either workflow by hand can now
  start MDLT on a build that failed — or never ran — SDPT/SDLT, tying up a multi-day allocation for an ineligible build.
  Options include restoring the check in both workflows against Chewie's suite results
  (`GET /api/v1/suites/results?build_number=<n>&suite_type=sdpt&latest=true`, likewise `sdlt`), restoring it only in
  the adhoc `203` with an explicit override input, or accepting the risk for manual runs.
- **Retire the git result tags.** Chewie's reports are already the source of record; the tags are secondary. Remove
  `tag-for-promotion` from `226` once Chewie's promotion has run in production for a while, together with its
  references in `report-success` and `report-failure`, and likewise the SDPT/SDLT/MDLT result tags (`221`/`222`/`206`)
  after the same period. Each tagging job carries a comment listing what to remove with it. Nothing in this repository reads
  the SDPT/SDLT/MDLT tags any more: MDLT is gated by Chewie, not by pass tags.
