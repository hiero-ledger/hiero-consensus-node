# Continuous Integration Test Overview and Best Practices

The series of workflows that make up the Continuous Integration Testing and Release (CITR) process are intended
to drive rapid development and release of code within the Hedera Hashgraph services and platform codebase. This
document aims to define key terms associated with CITR and to provide best practices for developers and
maintainers as they introduce new code to the `hedera-services` repository.

## Definitions

- **CITR** - Continuous Integration Test and Release
- **MATS** - Minimal Acceptable Test Suite
- **PR** - Pull Request
- **XTS** - eXtended Test Suite

## Phase 1 - Launch CITR, Enable MATS, Enable XTS

The first phase of the CITR implementation focuses entirely on the introduction of the CITR workflow set. It brings in
two major components of CITR: MATS and XTS.

MATS is the Minimal Acceptable Test Suite; this suite of tests is run against every pull request (PR) that is opened in
the `hashgraph/hedera-services` repository.

XTS is the eXtended Test Suite; this suite of tests is run against the latest commit on the main branch every three
hours (provided there is a new commit to run against).

MATS tests are inclusive of a series of unit tests and performance tests that must be executed against a PR branch prior
to merging into main. The MATS tests are intended to complete within a 30-minute time window to provide developers
with valuable insight of the impact of new code on the default branch.

XTS tests are run against the default branch once every three hours. These cover test cases that are unable to complete
within a 30-minute window but which are still necessary to derive if a commit should be considered as a build
candidate. XTS are intended to complete within a given 3-hour window. These tests are described by the
`ZXCron: Extended Test Suite` workflow.

### XTS Test Suite

The XTS text suite consists of the following tests:

- timing-sensitive-tests
- time-consuming-tests
- hammer-tests
- hapi-tests-time-consuming

It also includes the following JRS Panels
- JRS Panel:
- Configuration file: `configs/suites/GCP-PRCheck-Abbrev-4N.json`
- Custom job name: `Platform SDK`
- Hedera Node JRS Panel:
- Configuration file: `configs/services/suites/daily/GCP-Daily-Services-Abbrev-DAB-Update-4N-2C.json`
- Custom job name: `Abbrev Update Test`

### Dry-Running XTS Tests

There is an additional workflow: `ZXF: Extended Test Suite - Dry Run` which is available for use within the
`hashgraph/hedera-services` repository.

The XTS Dry-Run workflow runs a provided commit on any branch through the same XTS tests that would be run against the
latest on main every three hours. This workflow is run with a manual trigger and will execute in parallel to any
other actions ongoing in the `hashgraph/hedera-services` repository.

A developer can manually trigger a run using the parameters in the web UI:

```text
Use Workflow From
  Branch: main # this should always be `main`
The commit sha to check out
  <your current commit hash>
The branch name, for JRS Panel output
  <your branch name>
```

Or manually using the github CLI:

```bash
cd ${REPO_ROOT}/hedera-services
gh workflow run ./.github/workflows/zxf-dry-run-extended-test-suite.yaml -f commit_sha=`git rev-parse HEAD` -f branch_name='<branch_name>'
```

**Every developer is encouraged to run the XTS Dry-Run workflow against their branch commits prior to merging a PR to default**.
