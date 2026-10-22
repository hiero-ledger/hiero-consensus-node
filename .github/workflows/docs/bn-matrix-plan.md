# Block Node Version Matrix – Plan

Goal: catch Block Node (BN) regressions by running the existing BN regression panel against several BN versions,
not only the single version pinned in `.citr-env`.

## Design

- New workflow `906-cron-block-node-matrix.yaml`, `workflow_dispatch` only for now (a nightly `schedule` trigger can
  be added later, but would need automatic BN version discovery).
- The caller supplies the versions: `cn-refs` (comma-separated consensus node branches/tags/SHAs, default: the
  selected branch) and `bn-versions` (comma-separated Block Node versions, default: the BN version pinned in the
  first CN ref's `.citr-env`).
- `resolve-versions` builds the full cross product CN ref x BN version; a matrix job calls the reusable
  `821-call-block-node-regression.yaml` once per pair (`fail-fast: false`, `max-parallel: 3`).
- Each CN ref is tested with its own Solo, Java, mirror node, TCK and JS SDK versions, read from that ref's
  `.citr-env` and `gradle/toolchain-versions.properties` by `support/scripts/resolve-cn-configs.sh`.
- BN versions are validated inline (`vX.Y.Z[-suffix]`) because they reach `run:` scripts and artifact names. Tag-only
  alpha/rc builds work too, since no GitHub release is looked up.
- `resolve-versions` fails early if any CN ref pins Solo below 0.44.0 (same gate as 815's `solo-ge-0440`).
- XTS (`815`/`226`) is unchanged and keeps testing the single pinned version.

## Changes made

| File | Change |
|------|--------|
| `906-cron-block-node-matrix.yaml` | New dispatch-only workflow described above |

| `support/scripts/resolve-cn-configs.sh` | Per-CN-ref config lookup (replaces the 854/855 calls in 906); fails on Solo < 0.44.0 or missing keys |
| `support/tests/test-resolve-cn-configs.sh` | Unit tests against a throw-away git repo; wired into 857 |
| `821-call-block-node-regression.yaml` | BN version (and the optional `cn-label` input) added to uploaded artifact names and to the Slack panel label |
| `docs/workflow-manifest.md` | Row for 906 |

## Concerns

1. **Artifact name collisions (fixed).** 821 uploaded `bn-mn-log`, `cn-logs` and
   `SDK TCK Regression Test Report for BN`. `upload-artifact@v4` fails on duplicate names within a run, and all
   matrix legs share one run. Names now end in `-<bn-release-version>` (`latest` if empty) plus `-CN-<cn-label>` when set.
2. **Slack reports (fixed).** Each leg posts its own report; the panel label now includes the BN version so the
   reports are distinguishable.
3. **BN/CN/Solo compatibility.** Old BN versions may not work with the current CN or the pinned Solo. A failing
   old version is then a compatibility signal, not necessarily a regression. The caller chooses the versions.
4. **Cost.** Each leg rebuilds CN (`gradlew assemble`) and can take up to 60 minutes on
   `hl-cn-bn-regression-lin-lg`. `max-parallel: 3` bounds the runner usage; sharing one CN build across
   legs would be a larger follow-up change.
5. **Solo gate.** 815 skips the BN panel for solo < 0.44.0 (`solo-ge-0440`). 906 fails `resolve-versions` early if any
   CN ref pins solo < 0.44.0.
6. **Secrets and visibility.** 906 uses `GH_ACCESS_TOKEN` and `SLACK_CITR_DETAILED_REPORTS_WEBHOOK` like 226, and
   is limited to the public repository like 905.
7. **Not verified in CI.** `actionlint` and the unit tests pass locally, but the workflow has not run on the real
   runners. First step after merge: `workflow_dispatch` with a single `bn-versions` value.

## Alternatives considered

- Extend 815 / `.citr-env` to a list of versions: couples the matrix to the XTS pipeline and its gating.
- Matrix inside 821: duplicates the Slack/report jobs per leg and makes 821 harder to reuse.
