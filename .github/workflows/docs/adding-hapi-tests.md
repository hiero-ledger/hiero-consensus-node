# Adding HAPI Tests to CI

Every HAPI test suite in CI runs through one callable workflow,
[`874-call-execute-hapi-suite.yaml`](/.github/workflows/874-call-execute-hapi-suite.yaml). A caller
names the suite, lists the `test-clients` Gradle tasks it runs, and picks a runner; 874 runs every
suite with the same steps. Adding a test to CI therefore means one of two things: putting the test
where an existing Gradle task picks it up, or adding a Gradle task and listing it in a caller.

## What to change

| You are adding                                         | Change in `test-clients`                         | Workflows to update                                                       |
|--------------------------------------------------------|--------------------------------------------------|---------------------------------------------------------------------------|
| A test in an area that already has a tag (`CRYPTO`, …) | Give the test that tag                           | None                                                                      |
| A test with no area tag                                | None; `hapiTestMisc` and its variants pick it up | None                                                                      |
| A new Gradle task for a new area                       | [Add the task](#adding-a-gradle-task)            | [List the task in an existing suite](#adding-a-task-to-an-existing-suite) |
| A new Gradle task that needs its own job or runner     | [Add the task](#adding-a-gradle-task)            | [Add a new suite](#adding-a-new-suite)                                    |

Tags are described in
[`TEST_TAGS.md`](/hedera-node/test-clients/docs/TEST_TAGS.md), and the existing Gradle tasks in
[`GRADLE_TASKS.md`](/hedera-node/test-clients/docs/GRADLE_TASKS.md).

## Where HAPI tests run

| Caller                                                             | Called from | Triggered by                                                                                                   |
|--------------------------------------------------------------------|-------------|----------------------------------------------------------------------------------------------------------------|
| [`810`](/.github/workflows/810-call-execute-mats-hapi.yaml) MATS   | `800` MATS  | Every pull request (`600`), every push to `main` and `release/**` (`300`), and the manual MATS dry run (`000`) |
| [`827`](/.github/workflows/827-call-execute-xts-hapi.yaml) XTS     | `815` XTS   | The XTS controller (`226`, started by the `900` cron) and the manual extended-suite dry run (`001`)            |
| [`829`](/.github/workflows/829-call-execute-xts-hapi-testnet.yaml) | `815` XTS   | Same as `827`; reruns the MATS suites with the `pre-cutover` config profile                                    |
| [`906`](/.github/workflows/906-cron-block-node-matrix.yaml)        | none        | Manual dispatch; runs BN Comms once per consensus node ref and Block Node version                              |

Choose by cost and by how soon a regression should be caught:

- **MATS (`810`)** runs on every pull request, so its runtime is paid on every PR. Put tests here
  when a regression should be caught before merge.
- **XTS (`827`)** runs on a schedule. Put long-running suites, or suites that need a dedicated
  runner pool, here.
- **Pre-cutover rerun (`829`)** repeats the MATS suites with
  `hapi-test-config-profile: "pre-cutover"`. A task added to `810` belongs in `829` too, unless the
  task ignores the profile (see `profileExemptTasks` below), in which case the rerun only repeats it.
- **`906`** runs only `hapiTestBlockNodeCommunication`. Do not add other tasks to it.

## Current suites

Each suite is one job in its caller, on its own runner. Its tasks run one after another, in the
order listed, each in its own Gradle invocation; a failing task does not stop the tasks after it.

| Job id                                       | `suite-name`                       | Callers  | `gradle-tasks`                                                                                                                          | Runner                    |
|----------------------------------------------|------------------------------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------|---------------------------|
| `hapi-tests-misc`                            | Misc                               | 810, 829 | `hapiTestMisc` `hapiTestGenesisSubProcess` `hapiTestMiscEmbedded` `hapiTestMiscRepeatable` `:yahcli:testSubprocess` (810 only)          | default                   |
| `hapi-tests-misc-records-crypto-and-serial`  | Misc Records, Crypto & Misc Serial | 810, 829 | `hapiTestMiscRecords` `hapiTestMiscRecordsSerial` `hapiTestCrypto` `hapiTestCryptoEmbedded` `hapiTestCryptoSerial` `hapiTestMiscSerial` | default                   |
| `hapi-tests-time-consuming-and-node-staking` | Time Consuming & Node Staking      | 810, 829 | `hapiTestTimeConsuming` `hapiTestTimeConsumingSerial` `hapiTestNodeStaking` `hapiTestNodeStakingSerial`                                 | default                   |
| `hapi-tests-simple-fees-and-nd-reconnect`    | Simple Fees & ND Reconnect         | 810, 829 | `hapiTestSimpleFees` `hapiTestSimpleFeesEmbedded` `hapiTestSimpleFeesSerial` `hapiTestNDReconnect`                                      | default                   |
| `hapi-tests-smart-contracts-and-iss`         | Smart Contracts & ISS              | 810, 829 | `hapiTestSmartContract` `hapiTestSmartContractSerial` `hapiTestIss` `hapiTestIssGrpc`                                                   | default                   |
| `hapi-tests-restart`                         | Restart                            | 810, 829 | `hapiTestRestart` `hapiTestPreCutover` (810 only)                                                                                       | default                   |
| `hapi-tests-atomic-batch`                    | Atomic Batch                       | 810, 829 | `hapiTestAtomicBatch` `hapiTestAtomicBatchSerial` `hapiTestAtomicBatchEmbedded`                                                         | `hl-cn-hapi-lin-xl`       |
| `hapi-tests-token-and-state-throttling`      | Token & State Throttling           | 810, 829 | `hapiTestToken` `hapiTestTokenSerial` `hapiTestStateThrottling`                                                                         | default                   |
| `hapi-tests-clpr`                            | CLPR                               | 810, 829 | `hapiTestClpr` `hapiTestClprEmbedded` `hapiTestClprMultinetwork`                                                                        | default                   |
| `hapi-tests-bn-comms`                        | BN Comms                           | 827      | `hapiTestBlockNodeCommunication`                                                                                                        | `hl-cn-hapi-bn-lin-xl`    |
| `hapi-tests-wraps`                           | Wraps                              | 827, 829 | `hapiTestWraps`                                                                                                                         | `hl-cn-hapi-wraps-lin-lg` |
| `hapi-tests-cutover`                         | Cutover                            | 827      | `hapiTestCutover`                                                                                                                       | `hl-cn-hapi-wraps-lin-lg` |
| `block-node-communication`                   | BN Comms                           | 906      | `hapiTestBlockNodeCommunication`                                                                                                        | `hl-cn-hapi-bn-lin-xl`    |

`hapiTestAdhoc` is the only `prCheckTags` task that no workflow runs; it is for local use.

## Adding a Gradle task

All of these live in
[`hedera-node/test-clients/build.gradle.kts`](/hedera-node/test-clients/build.gradle.kts).

1. **Add the tag.** Add a constant to `TestTags.java` and tag the tests with it.
2. **Register the task.** Add `"hapiTest<Area>" to "<tag expression>"` to `prCheckTags`. The task
   runs test classes concurrently when its name contains `Crypto`, `Token`, `Misc`,
   `TimeConsuming`, `SimpleFees`, `AtomicBatch` or `SmartContract` and not `Serial`; otherwise it
   runs them one at a time. Serial tests (`@LeakyHapiTest`, `@OrderedInIsolation`) need a
   `hapiTest<Area>Serial` task with `(<TAG>&SERIAL)`.
3. **Exclude the tag from Misc.** Add the tag to `miscTags`. Otherwise `hapiTestMisc` and
   `hapiTestMiscRecords` run the same tests a second time.
4. **Give it a start port.** Add an unused port to `prCheckStartPorts` so the task can run next to
   its siblings locally without port clashes.
5. **Add only the overrides it needs:**
   - `prCheckPropOverrides`: node properties for the task's network.
   - `prCheckNetSizeOverrides`: a network size other than the default 4.
   - `prCheckPlatformOverrides`: platform `settings.txt` lines.
   - `prCheckWithoutStreamAndLogValidation`: skip the stream and log validation that runs after
     the suite.
   - `profileExemptTasks`: the task sets up its own world and ignores the `pre-cutover` profile.
     Such a task does not need to be in `829`.
   - The exclusion list in `prRemoteCheckTags`: the task cannot run against a remote network.
6. **Embedded or repeatable variants.** Add `hapiTest<Area>Embedded` to `embeddedBaseTags`, and
   exclude the tag from `hapiTestMiscEmbedded`'s expression so those tests do not run twice.
7. **Document it.** Add the task to
   [`GRADLE_TASKS.md`](/hedera-node/test-clients/docs/GRADLE_TASKS.md) and the tag to
   [`TEST_TAGS.md`](/hedera-node/test-clients/docs/TEST_TAGS.md).
8. **Run it locally:** `./gradlew :test-clients:hapiTest<Area>`.

## Adding a task to an existing suite

Add the task to the suite's `gradle-tasks` list in `810`, and to the same suite in `829` unless the
task is in `profileExemptTasks`:

```yaml
  hapi-tests-token-and-state-throttling:
    uses: ./.github/workflows/874-call-execute-hapi-suite.yaml
    with:
      suite-name: "Token & State Throttling"
      gradle-tasks: >-
        hapiTestToken
        hapiTestTokenSerial
        hapiTestStateThrottling
        hapiTestNewArea
```

Check the suite's run time afterwards. Each suite's job times out after `timeout-minutes`
(360 by default), and the tasks run one after another, so a long task can push the whole suite past
it. If it does, make a new suite instead.

## Adding a new suite

A new suite is a new job in a caller.

1. **Add the job.** Name it `hapi-tests-<slug>` and call 874:

   ```yaml
     hapi-tests-new-area:
       uses: ./.github/workflows/874-call-execute-hapi-suite.yaml
       with:
         suite-name: "New Area"
         gradle-tasks: >-
           hapiTestNewArea
           hapiTestNewAreaSerial
         ref: ${{ inputs.ref }}
         java-distribution: ${{ inputs.java-distribution }}
         java-version: ${{ inputs.java-version }}
         node-version: ${{ inputs.node-version }}
         enable-network-log-capture: ${{ inputs.enable-network-log-capture }}
       secrets:
         access-token: ${{ secrets.access-token }}
         gradle-cache-username: ${{ secrets.gradle-cache-username }}
         gradle-cache-password: ${{ secrets.gradle-cache-password }}
   ```

   Set `runner`, `timeout-minutes` and `node-pool-mib` only when the defaults do not fit; put the
   reason in a comment above each one, as `827` does for Wraps.
2. **Add it to `set-failure-mode`.** In the same caller, add the job id to the `set-failure-mode`
   job's `needs`, and add a line to each of `INPUT_JOB_STATUSES`, `INPUT_JOB_FAILURE_MODES` and
   `INPUT_JOB_NAMES`. `set-failure-mode.sh` pairs the three lists by position, so the new lines
   must be at the same position in all three. A job missing from `needs` is not waited for, and
   its failure does not reach the caller's `failure-mode`.
3. **Mirror it in `829`.** For a MATS suite, add the same job to `829` with
   `hapi-test-config-profile: "pre-cutover"` and `name-suffix: " (Pre-cutover configs)"`, and add
   it to `829`'s `set-failure-mode` as well. Skip this if every task in it ignores the profile.
4. **Optional: make it switchable.** `827` runs Wraps and Cutover only when
   `enable-hapi-tests-wraps` and `enable-hapi-tests-cutover` are `"true"`. To do the same, add a
   string input to the caller, put `if: ${{ inputs.enable-hapi-tests-<slug> == 'true' }}` on the
   job, and pass `"true"` from `815`. Compare the input with `'true'`; a bare string input is
   truthy even when it is `"false"`.

Do not edit `836`–`846` or `870`. They are deprecated stubs kept for their numbers.

## `874` inputs

| Input                        | Required | Default             | Use                                                                                                                                                            |
|------------------------------|----------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `suite-name`                 | yes      |                     | Shown in the job, check and artifact names. Must be unique within the caller's workflow run.                                                                   |
| `gradle-tasks`               | yes      |                     | Whitespace-separated Gradle tasks, run in order. Use a folded block (`>-`) with one task per line.                                                             |
| `runner`                     | no       | `hl-cn-hapi-lin-lg` | Runner label.                                                                                                                                                  |
| `timeout-minutes`            | no       | `360`               | Job timeout.                                                                                                                                                   |
| `node-pool-mib`              | no       | blank               | Heap pool in MiB shared by the node JVMs (`HAPI_TEST_NODE_POOL_MIB`). Blank sizes it from the runner.                                                          |
| `bn-release-version`         | no       | blank               | Block Node version for the tests that start one. Blank uses `block-node-version` from `.citr-env`. When set, 874 fails the suite unless that version was used. |
| `ref`                        | no       | blank               | Branch, tag or SHA to check out.                                                                                                                               |
| `java-distribution`          | no       | `temurin`           | JDK distribution.                                                                                                                                              |
| `java-version`               | no       | blank               | JDK version.                                                                                                                                                   |
| `node-version`               | no       | `24`                | Node.js version.                                                                                                                                               |
| `enable-network-log-capture` | no       | `"false"`           | `"true"` uploads the network logs when a task fails.                                                                                                           |
| `hapi-test-config-profile`   | no       | blank               | Node config profile (`pre-cutover`). Blank keeps the production defaults.                                                                                      |
| `name-suffix`                | no       | blank               | Appended to the job, check and artifact names, so a rerun in the same workflow run stays distinct.                                                             |
| `artifact-suffix`            | no       | blank               | Appended to the check and artifact names only, for matrix legs (letters, digits, `.`, `_`, `-`).                                                               |

The secrets `access-token`, `gradle-cache-username` and `gradle-cache-password` are required. The
`failure-mode` output is `test` when a task failed, `workflow` when the test step ended any other
way than passing, failing or being skipped (for example, it was cancelled), and `none` otherwise.

### Runners

| Label                     | Used for                                          |
|---------------------------|---------------------------------------------------|
| `hl-cn-hapi-lin-lg`       | Default for all suites                            |
| `hl-cn-hapi-lin-xl`       | Atomic Batch                                      |
| `hl-cn-hapi-bn-lin-xl`    | BN Comms, which starts real Block Node containers |
| `hl-cn-hapi-wraps-lin-lg` | Wraps and Cutover, which construct WRAPS proofs   |

### Names that come from `suite-name`

| What            | Name                                                                        | When                                  |
|-----------------|-----------------------------------------------------------------------------|---------------------------------------|
| Job             | `HAPI Tests (<suite-name>)<name-suffix>`                                    | Always                                |
| Results check   | `Node: HAPI Test (<suite-name>)<name-suffix> Results <artifact-suffix>`     | Always                                |
| Report artifact | `HAPI Test (<suite-name>)<name-suffix> Reports <artifact-suffix>`           | Always                                |
| Network logs    | `HAPI Test (<suite-name>)<name-suffix> Network Logs <artifact-suffix>`      | A task failed and capture is `"true"` |
| Failure files   | `HAPI Test (<suite-name>)<name-suffix> Failure Artifacts <artifact-suffix>` | A task failed                         |

The space before `<artifact-suffix>` is only added when it is set. Artifact names must be unique
within a workflow run, and `815` runs `827` and `829` in the same run; that is why `829` sets
`name-suffix`. Renaming a suite renames its checks and artifacts. Nothing requires the HAPI checks
on `main`, but [`summarize-bn-matrix.sh`](/.github/workflows/support/scripts/summarize-bn-matrix.sh)
matches the job name `HAPI Tests (BN Comms)`, so keep that one.

## Before opening the pull request

- Run each new task locally: `./gradlew :test-clients:<task>`.
- Run `actionlint` on the workflows you changed.
- Run `./gradlew :spotlessCheck`.
- Check that every `hapiTest*` task except `hapiTestAdhoc` is listed in a workflow. This prints the
  tasks that no workflow runs:

  ```bash
  comm -23 \
    <(sed -n '/^val prCheckTags =/,/^    )/p;/^val embeddedBaseTags =/,/^    )/p;/^val repeatableBaseTags/p' \
        hedera-node/test-clients/build.gradle.kts | grep -oE '"hapiTest[A-Za-z]+"' | tr -d '"' | sort -u) \
    <(grep -ohE '^ +hapiTest[A-Za-z]+$' .github/workflows/8[0-9][0-9]-*.yaml .github/workflows/906-*.yaml \
        | tr -d ' ' | sort -u)
  ```

The pull request's MATS run exercises any change to `810`. Changes to `827`, `829` and `906` run on
their next scheduled or manual run; to try them sooner, dispatch the extended-suite dry run (`001`) with `enable-hapi-test-suite` set
or `906` on your branch.
