# Top Level Workflow Description

## Overview

This document outlines the top-level flow of a commit from the point of PR creation to the point it is included in a
release candidate tag (`build-XXXXX`) and the subsequent workflows that are triggered by the release candidate tag.

## MATS-pr PATH

1. PR opened against `main` or `release/major.minor` branch
2. `Node: PR Checks` workflow runs all `MATS` checks
3. `MATS` checks pass
4. Other required checks pass on PRs (`DCO`, `Step security`, `title check`, etc)
5. PR is approved to merge
6. PR merged to main

## MATS-forked-pr PATH

1. PR opened against `main` by a non-contributor* or dependabot**
2. `Node: PR Checks` workflow runs all `MATS` checks available***
3. MATS checks pass
4. Other required checks pass on PRs (`DCO`, `Step security`, `title check`, etc)
5. PR content is approved by the required `CODEOWNER(s)`
6. Mirror PR ([github](https://github.com/PandasWhoCode/pr-mirror) |
   [npm](https://www.npmjs.com/package/@pandaswhocode/pr-mirror)) is opened by a user with at least `write` permissions
   on the repo
7. Mirror PR passes all checks
8. Request to @hiero-ledger/github-maintainers is made to merge the original PR by a maintainer
9. Original PR is merged to main

---

\* only can run checks that don't use github secrets management<br/>
\*\* only can run checks that dependabot secrets are configured for<br/>
\*\*\* Some checks are explicitly excluded for dependabot/forked PRs for repo security/safety

## MATS-main PATH

1. Commit pushed to `main` or `release/<major.minor>` branch
2. `Node: Build Application` is triggered
3. `Node: Build Application` runs `MATS`
4. MATS completes
5. `Node: Build Application` TRIGGERS a new workflow, `ZXF: Deploy Production Release`
6. `ZXF: Deploy Production Release` runs
7. `ZXF: Deploy Production Release` TRIGGERS a new workflow, `ZXF: [CITR] Prepare Extended Test Suite`
8. `ZXF: Deploy Production Release` TRIGGERS a new workflow, `ZXF: [Node] Deploy Integration Network Release`
9. `ZXF: [CITR] Prepare Extended Test Suite` tags the commit that was pushed to main in (`1`) with the tag `xts-candidate`

## XTS PATH

1. `ZXCron: [CITR] Extended Test Suite` schedules itself every 3 hours
2. `ZXCron: [CITR] Extended Test Suite` sees a tag xts-candidate
3. `ZXCron: [CITR] Extended Test Suite` runs XTS (required an optional)
4. XTS Required jobs jobs complete
5. XTS tags the commit that was pushed to main in (`MATS-main PATH 1`) with `xts-pass-<epochtime>`

## `ZXCron: [CITR] Promote Build Candidate` PATH

1. `ZXCron: [CITR] Promote Build Candidate` is scheduled once per day (roughly 8pm US Central)
2. `ZXCron: [CITR] Promote Build Candidate` sees all commits tagged as `xts-pass-<epochtime>`
3. `ZXCron: [CITR] Promote Build Candidate` tags the most recent `xts-pass-<epochtime>` tag as `build-XXXXX` (in this scenario `MATS-main PATH 1`)
4. `ZXCron: [CITR] Promote Build Candidate` TRIGGERS a new workflow, `ZXF: [CITR] Single Day Performance Test Controller (SDPT)`
5. `ZXCron: [CITR] Promote Build Candidate` TRIGGERS a new workflow, `ZXF: [CITR] Single Day Longevity Test Controller`
6. `ZXCron: [CITR] Promote Build Candidate` TRIGGERS a new workflow, `ZXF: [CITR] Single Day Canonical Test (SDCT)`

## Release Tag Push PATH

\*When a release-ish tag is pushed it is against a commit that has already passed `MATS` and `XTS`. A different flow is required

1. `ZXF: Deploy Production Release` is triggered on tag applied to `main` or `release/major.minor` branch
   1. Tag format is required to match `vX.Y.Z*` (allows for alpha, release candidate tags, etc)
2. `ZXF: Deploy Production Release` runs checks for `tag` path
3. `ZXF: Deploy Production Release` TRIGGERS a new workflow, `ZXF: [Node] Deploy Integration Network Release`
