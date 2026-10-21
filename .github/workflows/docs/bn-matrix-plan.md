# Block Node Version Matrix – Plan

Goal: catch Block Node (BN) regressions by running the existing BN regression panel against several BN versions,
not only the single version pinned in `.citr-env`.

## Design

- New scheduled workflow `906-cron-block-node-matrix.yaml` (nightly 04:00 UTC, plus `workflow_dispatch`).
- `resolve-versions` job picks the BN versions; a matrix job calls the unchanged reusable
  `821-call-block-node-regression.yaml` once per version (`fail-fast: false`, `max-parallel: 3`).
- Version set (`support/scripts/resolve-bn-versions.sh`): the version pinned in `.citr-env` + the newest 2 stable
  releases + the newest release if it is a prerelease (alpha/rc), de-duplicated.
- `workflow_dispatch` input `bn-versions` (comma-separated) overrides the set, e.g. for tag-only builds that have
  no GitHub release.
- Everything else (CN `ref`, solo, mirror node, TCK, JS SDK versions) comes from `.citr-env` / the selected ref.
- XTS (`815`/`226`) is unchanged and keeps testing the single pinned version.

## Changes made

| File | Change |
|------|--------|
| `906-cron-block-node-matrix.yaml` | New nightly/dispatch workflow described above |
| `support/scripts/resolve-bn-versions.sh` | Version selection logic, kept out of YAML so it is unit-testable |
| `support/tests/test-resolve-bn-versions.sh` | Unit tests for the script; wired into `857-call-workflow-unit-tests.yaml` |
| `821-call-block-node-regression.yaml` | BN version added to uploaded artifact names and to the Slack panel label |
| `docs/workflow-manifest.md` | Row for 906 |

## Concerns

1. **Artifact name collisions (fixed).** 821 uploaded `bn-mn-log`, `cn-logs` and
   `SDK TCK Regression Test Report for BN`. `upload-artifact@v4` fails on duplicate names within a run, and all
   matrix legs share one run. Names now end in `-<bn-release-version>` (`latest` if empty).
2. **Slack reports (fixed).** Each leg posts its own report; the panel label now includes the BN version so the
   reports are distinguishable.
3. **BN/CN/Solo compatibility.** Old BN versions may not work with the current CN or the pinned Solo. A failing
   old version is then a compatibility signal, not necessarily a regression. Decide the supported range;
   `BN_STABLE_COUNT` (default 2) in 906 controls how far back to go.
4. **Cost.** Each leg rebuilds CN (`gradlew assemble`) and can take up to 60 minutes on
   `hl-cn-bn-regression-lin-lg`. Nightly with `max-parallel: 3` bounds the runner usage; sharing one CN build across
   legs would be a larger follow-up change.
5. **Prerelease discovery.** The resolver uses the GitHub releases API. Tag-only alpha/rc builds are not listed
   there and can only be tested through the `bn-versions` override.
6. **Solo gate.** 815 skips the BN panel for solo < 0.44.0 (`solo-ge-0440`). 906 does not gate; `.citr-env` pins a
   solo version well above that. Revisit if the pinned solo version is ever lowered.
7. **Secrets and visibility.** 906 uses `GH_ACCESS_TOKEN` and `SLACK_CITR_DETAILED_REPORTS_WEBHOOK` like 226, and
   is limited to the public repository like 905.
8. **Not verified in CI.** `actionlint` and the unit tests pass locally, but the workflow has not run on the real
   runners. First step after merge: `workflow_dispatch` with a single `bn-versions` value.

## Alternatives considered

- Extend 815 / `.citr-env` to a list of versions: couples the matrix to the XTS pipeline and its gating.
- Matrix inside 821: duplicates the Slack/report jobs per leg and makes 821 harder to reuse.
