# CITR Environment Configuration

## Overview

This document outlines the configuration settings for the CITR (Continuous Integration Test & Release) environment used
in our workflows. The CITR environment is designed to facilitate automated testing and deployment processes.

There are several test suites that are run in the CITR environment, each with its own configuration settings. The main
test suites include:

| Test Suite  |             Name              |                                             Description                                             | Automated |
|-------------|-------------------------------|-----------------------------------------------------------------------------------------------------|-----------|
| MATS        | Minimal Acceptable Test Suite | Basic functional checks against main branch when changes are made                                   | X         |
| XTS         | Extended Test Suite           | Longer functional tests run on a scheduled basis                                                    | X         |
| SDCT        | Single Day Canonical Tests    | Long established transaction loads by transaction types to measure E2E latency                      | X         |
| SDPT        | Single Day Performance Tests  | Unthrottled high TPS tests with large states to evaluate throughput capability                      | X         |
| SDLT        | Single Day Longevity Tests    | Production throttled mixed TPS load to test network stability                                       | X         |
| MDLT        | Multi Day Longevity Tests     | Production throttled mixed TPS load over many days to test long term network stability              | X         |
| Shortgevity | Short Longevity Tests         | Production throttled mixed TPS load with reconnects on a mainnet-like environment over several days |           |
| MQPT        | Merge Queue Performance Tests | Combined performance, verification and longevity tests for use in Merge Queues                      | X         |

## MATS

### Purpose

MATS (Minimal Acceptable Test Suite) runs as many functional tests as possible while staying under 30 minutes of
runtime. Test suites are broken out into multiple parallel jobs to maximize the number of tests that can be run within
the time constraint. The time limit for MATS is critical because it is also run on PRs as a required gate.

### Environment

- MATS runs inside self-hosted github runners on every *push* to the **default branch** (`main`).
- MATS is expected to complete within 30 minutes of the test suite starting.
- MATS has an equivalent set of tests that run in PRs against feature branches triggered on *pull_request* events.

### Workflows

- MATS is triggered by the [300: [FLOW] Build Application](/.github/workflows/300-flow-build-application.yaml) workflow.
- The PR Check equivalent checks are triggered by
  the [600: [FLOW] PR Checks](/.github/workflows/600-flow-pull-request-checks.yaml) workflow.
- MATS Dry-Run is triggered manually via the [000: [USER] CITR MATS Dry Run](/.github/workflows/000-user-dry-run-mats-suite.yaml)
  workflow.

### Included Tests

| Test Name                                              | Workflow                                                                                         | Required Parameters                                                                                                     | Required Workflow Secrets                                                                                             | Precursor Steps |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|-----------------|
| MATS - Snyk Checks                                     | [801: [CALL] Snyk Scan](/.github/workflows/801-call-snyk-scan.yaml)                              | `ref: <commit-sha>`                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`snyk-token`                               | build           |
| MATS - Compile and Spotless Check                      | [802: [CALL] Compile And Spotless](/.github/workflows/802-call-compile-and-spotless-check.yaml)  | `ref: <commit-sha>`                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                |                 |
| MATS - Unit Tests                                      | [803: [CALL] Exec Unit Tests](/.github/workflows/803-call-execute-unit-tests.yaml)               | `ref: <commit-sha>`                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`codacy-project-token`<br/>`codecov-token` | build           |
| MATS - Integration Tests                               | [804: [CALL] Exec Integration Tests](/.github/workflows/804-call-execute-integration-tests.yaml) | `ref: <commit-sha>`<br/>`enable-network-log-capture: true`                                                              | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Misc)                               | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-misc: true`<br/>`enable-network-log-capture: true`                           | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Misc Records, Crypto & Misc Serial) | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-misc-records-crypto-and-serial: true`<br/>`enable-network-log-capture: true` | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Token & Time Consuming)             | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-token-and-time-consuming: true`<br/>`enable-network-log-capture: true`       | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Simple Fees & ND Reconnect)         | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-simple-fees-and-nd-reconnect: true`<br/>`enable-network-log-capture: true`   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Atomic Batch)                       | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-atomic-batch: true`<br/>`enable-network-log-capture: true`                   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Smart Contracts & ISS)              | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-smart-contract-and-iss: true`<br/>`enable-network-log-capture: true`         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Restart)                            | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-restart: true`<br/>`enable-network-log-capture: true`                        | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (State Throttling)                   | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `ref: <commit-sha>`<br/>`enable-hapi-tests-state-throttling: true`<br/>`enable-network-log-capture: true`               | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Otter Tests                                     | [808: [CALL] Exec Otter Tests](/.github/workflows/808-call-execute-otter-tests.yaml)             | `ref: <commit-sha>`<br/>`enable-fast-otter-tests: true`<br/>`enable-network-log-capture: true`                          | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Dependency (Module Info)                        | [809: [CALL] Dependency Module Chk](/.github/workflows/809-call-dependency-module-check.yaml)    | `ref: <commit-sha>`                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Docker Determinism                              | [822: [CALL] Verify Docker Build](/.github/workflows/822-call-verify-docker-determinism.yaml)    | `ref: <commit-sha>`                                                                                                     | `gradle-cache-username`<br/>`gradle-cache-password`                                                                   | build           |
| MATS - Gradle Determinism                              | [823: [CALL] Verify Gradle Build](/.github/workflows/823-call-verify-gradle-determinism.yaml)    | `ref: <commit-sha>`                                                                                                     | `gradle-cache-username`<br/>`gradle-cache-password`                                                                   | build           |

## XTS

### Purpose

XTS (Extended Test Suite) runs additional functional tests that go beyond the scope of MATS. It includes any functional
test that runs longer than the 30-minute limit for MATS, or simply cannot fit into the MATS time budget or
parallel runners. The balance between XTS and MATS is continuously evaluated to ensure that MATS provides as much
coverage as possible while still adhering to the time constraint, and that XTS includes tests that are valuable for
catching regressions without being unnecessarily long-running.

### Environment

- XTS runs inside self-hosted github runners every 3 hours on the **default branch** (`main`).
- XTS is expected to complete within 3 hours of the test suite starting.
- XTS has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- XTS is triggered by the [900: [CRON] CITR Ext Test Suite](/.github/workflows/900-cron-extended-test-suite.yaml)
  workflow.
- XTS Dry Run is triggered manually via
  the [001: [USER] CITR XTS Dry Run](/.github/workflows/001-user-dry-run-extended-test-suite.yaml) workflow.

### Included Tests

| Test Name                                 | Workflow                                                                                         | Required Parameters                                                                                                                                                                                                                                                                                                         | Required Workflow Secrets                                                                                                                                                                                                                                | Precursor Steps                      |
|-------------------------------------------|--------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| XTS Compile and Spotless Check            | [802: [CALL] Compile And Spotless](/.github/workflows/802-call-compile-and-spotless-check.yaml)  |                                                                                                                                                                                                                                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   |                                      |
| XTS HAPI Tests (Block Node Communication) | [805: [CALL] Exec HAPI Tests](/.github/workflows/805-call-execute-hapi-tests.yaml)               | `enable-hapi-tests-bn-communication: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS Timing Sensitive Tests                | [806: [CALL] Exec Timing Tests](/.github/workflows/806-call-execute-timing-sensitive-tests.yaml) | `ref: <commit-sha>`                                                                                                                                                                                                                                                                                                         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS Hammer Tests                          | [807: [CALL] Exec Hammer Tests](/.github/workflows/807-call-execute-hammer-tests.yaml)           | `ref: <commit-sha>`                                                                                                                                                                                                                                                                                                         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS Otter Tests                           | [808: [CALL] Exec Otter Tests](/.github/workflows/808-call-execute-otter-tests.yaml)             | `enable-full-otter-tests: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                              | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| JRS Panel                                 | [817: [CALL] JRS Regression](/.github/workflows/817-call-jrs-regression.yaml)                    | `panel-config: configs/suites/GCP-PRCheck-Abbrev-4N.json`<br/>`ref: <commit-sha>`<br/>`branch-name: <github.head_ref or github.ref_name>`<br/>`base-branch-name: github.base_ref or ''`<br/>`slack-results-channel: regression-test`<br/>`slack-summary-channel: regression-test`<br/>`use-branch-for-slack-channel: false` | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`jrs-ssh-user-name`<br/>`jrs-ssh-key-file`<br/>`gcp-project-number`<br/>`gcp-sa-key-contents`<br/>`slack-api-token`<br/>`grafana-agent-username`<br/>`grafana-agent-password` | Fetch XTS Candidate<br/>Compile Code |
| Hedera Node JRS Panel                     | [817: [CALL] JRS Regression](/.github/workflows/817-call-jrs-regression.yaml)                    | `panel-config: configs/services/suites/daily/GCP-Daily-Services-Abbrev-DAB-Update-4N-2C.json`<br/>`ref: <commit-sha>`<br/>`branch-name: <github.head_ref or github.ref_name>`<br/>`hedera-tests-enabled: true`<br/>`use-branch-for-slack-channel: false`                                                                    | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`jrs-ssh-user-name`<br/>`jrs-ssh-key-file`<br/>`gcp-project-number`<br/>`gcp-sa-key-contents`<br/>`slack-api-token`<br/>`grafana-agent-username`<br/>`grafana-agent-password` | Fetch XTS Candidate<br/>Compile Code |
| JSON-RPC Relay Regression Panel           | [818: [CALL] JSON-RPC Relay Reg](/.github/workflows/818-call-json-rpc-relay-regression.yaml)     | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |
| SDK TCK Regression Panel                  | [819: [CALL] TCK Regression](/.github/workflows/819-call-tck-regression.yaml)                    | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-tck-report-webhook`<br/>`slack-detailed-report-webhook`                                                                                                                                                                        | Fetch XTS Candidate<br/>Compile Code |
| Mirror Node Regression Panel              | [820: [CALL] Mirror Node Regress](/.github/workflows/820-call-mirror-node-regression.yaml)       | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`<br/>`helm-release-name: mirror or mirror-1`                                                                                                                                                                                                                  | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |
| Block Node Regression Panel               | [821: [CALL] Block Node Regression](/.github/workflows/821-call-block-node-regression.yaml)      | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |

## SDCT

### Purpose

SDCT (Single Day Canonical Test) is designed to run long-established transaction loads by transaction types to measure
end-to-end latency performance. It serves as a benchmark for evaluating the performance of the network under various
transaction loads compared to previous releases. This test provides the official E2E latency numbers used to determine
if the E2E SLA is met. It runs on a large, mainnet-like environment.

### Environment

- SDCT runs on the **performance** (**perf1**) network that has a Mirror node setup, hosted on GCP and various external
  server providers (**Latitude**, **OVH**, **AWS**, etc.).
- SDCT is expected to complete around 20 hours after the test suite starts.
- SDCT supports artifacts built from any PR, tag, or branch.

### Workflows

- SDCT is triggered by
  the [223: [DISP] CITR SDCT Controller](/.github/workflows/223-disp-sdct-controller.yaml)
  workflow
  - The workflow is currently being updated with a fast-fail enhancement

### Included Tests

These tests run sequentially with a Mirror node setup to measure E2E latency performance.

|                       Test                        | TPS |
|---------------------------------------------------|-----|
| Idle load                                         | 10  |
| Crypto Transfer                                   | 200 |
| Mixed Tx Types                                    | 2K  |
| Mixed Tx Types with Smartcontract                 | 10K |
| Crypto Transfer                                   | 10K |
| HCS                                               | 10K |
| HTS                                               | 10K |
| Mixed Tx Types(1/2 HCS and Crypto/HTS rest)       | 10K |
| Mixed Tx Types(equal weight among HCS/Crypto/HTS) | 10K |

## SDPT

### Purpose

SDPT (Single Day Performance Test) is designed to run unthrottled high TPS tests with large states to evaluate the
throughput capability of the network at scale. It runs on a small, heterogeneous network environment to quickly identify
potential performance regressions.

### Environment

- SDPT runs inside self-hosted github runners every 24 hours on the **default branch** (`main`).
- SDPT is expected to complete within 20 hours of the test suite starting.
- SDPT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- SDPT is triggered by
  the [221: [DISP] CITR SDPT Controller](/.github/workflows/221-disp-sdpt-controller.yaml)
  workflow.
- SDPT Dry Run is triggered manually via
  the [201: [USER] CITR SDPT Ctrl Adhoc](/.github/workflows/201-user-sdpt-controller-adhoc.yaml)
  workflow.

### Hardware

Latitude kubernetes cluster

- 7 nodes for Consensus Nodes
- 1 node for CryptoBench
- 1 node for aux services and NLG client

### Included Tests

| Test Name              | Workflow                                                                                   | Required Parameters                               | Run time  | Precursor Steps                                    |
|------------------------|--------------------------------------------------------------------------------------------|---------------------------------------------------|-----------|----------------------------------------------------|
| NftTransferLoadTest    | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 6 hours   | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| CryptoBench            | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | maxKey, numRecords, keySize, recordSize, numFiles | 4-5 hours | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| HCSLoadTest            | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | NftTransferLoadTest                                |
| CryptoTransferLoadTest | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | HCSLoadTest                                        |
| HeliSwapLoadTest       | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | nlg-accounts                                      | 6 hours   | CryptoTransferLoadTest                             |
| SmartContractLoadTest  | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | HeliSwapLoadTest                                   |
| State Validator        | [831: [CALL] CITR Exec SDPT](/.github/workflows/831-call-single-day-performance-test.yaml) |                                                   | 30 mins   | All previous tests passed                          |

### Runtime durations, practical settings

- 1 hour with arguments: nlg-time=3 (mins), nlg-accounts=100000, files=60
- 10 hours : nlg-time=180 (mins), nlg-accounts=100000000, files=600
- 20 hours: nlg-time=330, nlg-accounts=100000000, files=6000

## SDLT

### Purpose

SDLT (Single Day Longevity Test) is designed to run a production throttled mixed TPS load on small, heterogeneous
environment to quickly identify regressions in overall network stability and robustness under load.

### Environment

- SDLT runs inside of self-hosted github runners every 24 hours on the **default branch** (`main`).
- SDLT is expected to complete within 16 hours of the test suite starting.
- SDLT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- SDLT is triggered by
  the [222: [DISP] CITR SDLT Controller](/.github/workflows/222-disp-sdlt-controller.yaml)
  workflow.
- SDLT Dry Run is triggered manually via
  the [202: [USER] CITR SDLT Ctrl Adhoc](/.github/workflows/202-user-sdlt-controller-adhoc.yaml)
  workflow.

### Hardware

Latitude kubernetes cluster

- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

| Test Name         | Workflow                                                                                 | Required Parameters   | Run time | Precursor Steps                                    |
|-------------------|------------------------------------------------------------------------------------------|-----------------------|----------|----------------------------------------------------|
| LongevityLoadTest | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 16 hours | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| State Validator   | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) |                       | 30 mins  | LongevityLoadTest                                  |

### LongevityLoadTest consists of the following tests, running in parallel with pre-defined throttling:

- NftTransferLoadTest, TPS=3000
- HCSLoadTest, TPS=2000
- CryptoTransferLoadTest, TPS=5000
- HeliSwapLoadTest, TPS=300
- SmartContractLoadTest, TPS=50

### Runtime durations, practical settings

- 30 mins with arguments: nlg-time=3 (mins), nlg-accounts=100000
- 1 hour : nlg-time=21 (mins), nlg-accounts=20000000
- 3 hour: nlg-time=180, nlg-accounts=20000000
- 16 hours: nlg-time=960, nlg-accounts=100000000

## MDLT

### Purpose

MDLT (Multi Day Longevity Test) is designed to run a production throttled mixed TPS load for an extended period of
time (7 days) to identify regressions in overall network stability and robustness under load. It serves as a longer
running version of SDLT to catch potential issues that may not surface within the shorter SDLT.

### Environment

- MDLT runs inside self-hosted github runners on demand, against any PR, tag, or branch, assuming release candidate
- MDLT is expected to complete within 7 days of the test suite starting.
- MDLT has a dry-run equivalent to SDLT

### Workflows

- MDLT is triggered by
  the [222: [DISP] CITR SDLT Controller](/.github/workflows/222-disp-sdlt-controller.yaml)
  workflow.
- MDLT Dry Run is triggered manually via
  the [202: [USER] CITR SDLT Ctrl Adhoc](/.github/workflows/202-user-sdlt-controller-adhoc.yaml)
  workflow.

### Hardware

Latitude kubernetes cluster

- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

| Test Name         | Workflow                                                                                 | Required Parameters   | Run time  | Precursor Steps                                    |
|-------------------|------------------------------------------------------------------------------------------|-----------------------|-----------|----------------------------------------------------|
| LongevityLoadTest | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 7 days    | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| State Validator   | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) |                       | 1.5 hours | LongevityLoadTest                                  |

### LongevityLoadTest consists of the following tests, running in parallel with pre-defined throttling:

- NftTransferLoadTest, TPS=3000
- HCSLoadTest, TPS=2000
- CryptoTransferLoadTest, TPS=5000
- HeliSwapLoadTest, TPS=300
- SmartContractLoadTest, TPS=50

### Runtime durations, practical settings

- 30 mins with arguments: nlg-time=3 (mins), nlg-accounts=100000
- 1 hour : nlg-time=21 (mins), nlg-accounts=20000000
- 3 hour: nlg-time=180, nlg-accounts=20000000
- 7 days: nlg-time=10080, nlg-accounts=100000000

## Shortgevity

### Purpose

Shortgevity is similar to the MDLT (Multi Day Longevity Test) but runs on a mainnet-like network for a shorter period of
time. It is designed to detect stability regressions under high load in a more realistic environment.

### Environment

- Shortgevity runs on the **performance** (**perf1**) network, which is hosted on **GCP** and various external server
  hosting providers (**Latitude**, **OVH**, **AWS**, etc.).
- Shortgevity runs range from 6–8 hours to 3 days, depending on the size of the change set being tested and the
  potential impact on performance.
- Shortgevity supports artifacts built from any PR, tag, or branch.

### Workflows

- Shortgevity runs manually because it operates on an ad hoc basis and is limited by the availability of the shared
  **performance** network resource.

### Included Tests

All tests are run in parallel with adjustable total TPS. Currently runs at PROD supported 10.5K.

|                Test Type                |                     Ratio                      |
|-----------------------------------------|------------------------------------------------|
| Crypto                                  | ~30%                                           |
| HCS                                     | ~30%                                           |
| HTS                                     | ~10%                                           |
| NFT                                     | ~30%                                           |
| Smartcontract                           | ~350(including Uniswaps/ERCs)                  |
| K/V pairs                               | 200M                                           |
| Best effort coverage of Hedera Tx Types | < 100 TPS                                      |
| Re-connects                             | At most 2 nodes in re-connect at the same time |

## MQPT Merge Queue Performance Tests

### Environment

- MQPT runs inside self-hosted github runners regularly against Trunk.io Merge Queues
- MQPT is expected to complete within 3 hours 40 mins of the test suite starting.
- MQPT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- MQPT is triggered by
  the [220: [DISP] CITR MQPT Controller](/.github/workflows/220-disp-mqpt-controller.yaml)
  workflow.
- MQPT AdHoc Run is triggered manually via
  the [200: [USER] CITR MQPT Ctrl Adhoc](/.github/workflows/200-user-mqpt-controller-adhoc.yaml)
  workflow.

### Hardware

Latitude kubernetes cluster

- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

| Test Name                | Workflow                                                                                 | Required Parameters   | Run time | Precursor Steps                                    |
|--------------------------|------------------------------------------------------------------------------------------|-----------------------|----------|----------------------------------------------------|
| ScriptedLoadTest, part 1 | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 2 hours  | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| ReconnectTest            | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) | nlg-accounts,nlg-time |          | ScriptedLoadTest                                   |
| ScriptedLoadTest, part 2 | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 1 hour   | ScriptedLoadTest                                   |
| State Validator          | [833: [CALL] CITR Exec SDLT](/.github/workflows/833-call-single-day-longevity-test.yaml) |                       | 30 mins  | ReconnectTest                                      |

### ScriptedLoadTest, part 1 consists of the following tests, running sequentially to measure performance benchmarks:

- NftTransferLoadTest
- HCSLoadTest
- CryptoTransferLoadTest
- SmartContractLoadTest

### ScriptedLoadTest, part 2 consists of the following tests, running in parallel with pre-defined throttling:

- NftTransferLoadTest, TPS=3000
- HCSLoadTest, TPS=2000
- CryptoTransferLoadTest, TPS=5000
- SmartContractLoadTest, TPS=50

During this step, Reconnect test restarts Consensus Node java and verifies that Consensus Node reaches ACTIVE state.

### State Validator

This step verifies the correctness of Consensus node State by running Validator tool.

### Runtime durations, practical settings

- 30 mins with arguments: nlg-time=3 (mins), nlg-accounts=100000, -Dbenchmark.stepDuration=1m -Dbenchmark.coolDown=1m
- 3 hours 40 mins: nlg-time=60, nlg-accounts=20000000, -Dbenchmark.stepDuration=20m -Dbenchmark.coolDown=3m
