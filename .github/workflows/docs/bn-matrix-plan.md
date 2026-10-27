# Block Node Version Matrix – Plan

Goal: catch Block Node (BN) regressions by running the existing BN regression panel against several BN versions,
not only the single version pinned in `.citr-env`.

## Design

- New workflow `906-cron-block-node-matrix.yaml`, `workflow_dispatch` only for now (a nightly `schedule` trigger can
  be added later, but would need automatic BN version discovery).
- Both inputs are optional. `cn-refs` (comma-separated consensus node branches/tags/SHAs) defaults to `main` plus the
  newest `release/X.Y` branch (`support/scripts/discover-cn-refs.sh`, newest by version). `bn-versions` (comma-separated
  Block Node versions) defaults, per CN ref, to the version that ref pins in `.citr-env` (`block-node-version`) plus
  the newest stable Block Node release if different (`support/scripts/resolve-latest-bn-release.sh`, sorted by
  version since the releases API lists by creation date). Given `bn-versions`, every ref is tested with every version.
  `support/scripts/build-bn-matrix.sh` builds the legs for both cases.
- Only the Block Node container version varies. The BN protobuf sources CN compiles against stay pinned
  (`blockNodeProtobufSources` in `hiero-dependency-versions/build.gradle.kts`), so each leg tests the current CN
  protobufs against that BN version.
- `resolve-versions` builds the full cross product CN ref x BN version; a matrix job calls the reusable
  `821-call-block-node-regression.yaml` once per pair (`fail-fast: false`, `max-parallel: 2`).
- Each CN ref is tested with its own Solo, Java, mirror node, TCK and JS SDK versions, read from that ref's
  `.citr-env` and `gradle/toolchain-versions.properties` by `support/scripts/resolve-cn-configs.sh`.
- BN versions are validated by `support/scripts/resolve-bn-versions.sh` (`vX.Y.Z[-suffix]`, a missing leading `v` is added) because they reach `run:` scripts and artifact names. Tag-only
  alpha/rc builds work too, since no GitHub release is looked up.
- `resolve-versions` fails early if any CN ref pins Solo below 0.44.0 (same gate as 815's `solo-ge-0440`).
- The HAPI BN Comms suite (`844-call-execute-hapi-bn-communication.yaml`) also runs per CN ref x BN version
  (`max-parallel: 1`). It selects the BN image through the `BLOCK_NODE_VERSION` env var read by `BlockNodeContainer`.
  Refs whose `BlockNodeContainer` lacks that override would silently run the pinned image, so
  `resolve-cn-configs.sh` flags each ref (`bn-version-override`) and 906 runs BN Comms only for flagged refs, with a
  warning for the rest (the 821 legs still run for every ref). As a second guard, 844 fails the leg unless
  `block-node/<requested version>/` data was created during the run.
- BN Comms downloads the plugin jars for the requested version from Maven Central, so `resolve-versions` checks that
  the `health` and `roster-bootstrap-tss` jars exist for every BN version and fails early otherwise. The plugin list
  and extra jars in `BlockNodeContainer` stay fixed to what the default version needs, so much older BN versions can
  still fail in that suite.
- XTS (`815`/`226`) is unchanged and keeps testing the single pinned version.

## Changes made

| File | Change |
|------|--------|
| `906-cron-block-node-matrix.yaml` | New dispatch-only workflow described above |
| `support/scripts/resolve-cn-configs.sh` | Per-CN-ref config lookup (replaces the 854/855 calls in 906); fails on Solo < 0.44.0 or missing keys; emits `bn-version-override` |
| `support/tests/test-resolve-cn-configs.sh` | Unit tests against a throw-away git repo; wired into 857 |
| `844-call-execute-hapi-bn-communication.yaml` | `bn-release-version` (exported as `BLOCK_NODE_VERSION`) and `artifact-suffix` inputs; step verifying the requested BN version was used |
| `BlockNodeContainer.java` | Image/plugin version overridable via `BLOCK_NODE_VERSION` (default stays the pinned version) |
| `821-call-block-node-regression.yaml` | BN version (and the optional `cn-label` input) added to uploaded artifact names and to the Slack panel label |
| `support/scripts/resolve-bn-versions.sh`, `support/tests/test-resolve-bn-versions.sh` | BN version parsing and validation for 906; tests wired into 857 |
| `support/scripts/discover-cn-refs.sh`, `resolve-latest-bn-release.sh`, `build-bn-matrix.sh` and their tests | Detection of CN refs and BN versions when the inputs are empty; tests wired into 857 |
| `support/scripts/summarize-bn-matrix.sh`, `support/tests/test-summarize-bn-matrix.sh` | Per-leg result lines for the 906 summary; tests wired into 857 |
| `docs/workflow-manifest.md` | Row for 906 |

## Concerns

1. **Artifact name collisions (fixed).** 821 uploaded `bn-mn-log`, `cn-logs` and
   `SDK TCK Regression Test Report for BN`. `upload-artifact@v4` fails on duplicate names within a run, and all
   matrix legs share one run. Names now end in `-<bn-release-version>` (`latest` if empty) plus `-CN-<cn-label>` when set.
2. **Slack reports (fixed).** The 821 legs no longer post their own report (`enable-slack-report: false`). A `summary`
   job in 906 collects every leg's conclusion from the run's jobs (`support/scripts/summarize-bn-matrix.sh`), writes
   it to the job summary and posts one Slack message. The `dry-run` input skips the Slack post.
3. **BN/CN/Solo compatibility.** Old BN versions may not work with the current CN or the pinned Solo. A failing
   old version is then a compatibility signal, not necessarily a regression. The caller chooses the versions.
4. **Cost.** Each CN ref is built once (`build-consensus-node`, `gradlew assemble`) and its `hedera-node/data` is
   handed to the 821 legs as the artifact `cn-build-<slug>` (1 day retention), so a leg no longer rebuilds CN. A leg
   can still take up to 60 minutes on `hl-cn-bn-regression-lin-lg`. `max-parallel` is 2 on the 821 matrix
   (`hl-cn-bn-regression-lin-lg`) and 1 on the 844 matrix (`hl-cn-hapi-bn-lin-xl`), so at most 3 legs run at once in
   total. 844 still builds on its own.
5. **Solo gate.** 815 skips the BN panel for solo < 0.44.0 (`solo-ge-0440`). 906 fails `resolve-versions` early if any
   CN ref pins solo < 0.44.0.
6. **Secrets and visibility.** 906 uses `GH_ACCESS_TOKEN` and `SLACK_CITR_DETAILED_REPORTS_WEBHOOK` like 226, and
   is limited to the public repository like 905.
7. **Not verified in CI.** `actionlint` and the unit tests pass locally, but the workflow has not run on the real
   runners. First step after merge: `workflow_dispatch` with a single `bn-versions` value.

## Alternatives considered

- Extend 815 / `.citr-env` to a list of versions: couples the matrix to the XTS pipeline and its gating.
- Matrix inside 821: duplicates the Slack/report jobs per leg and makes 821 harder to reuse.
