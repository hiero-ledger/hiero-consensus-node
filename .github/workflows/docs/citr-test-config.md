# CITR Environment Configuration

## Overview

This document outlines the configuration settings for the CITR (Continuous Integration Test & Release) environment used in our workflows. The CITR environment is designed to facilitate automated testing and deployment processes.

There are several test suites that are run in the CITR environment, each with its own configuration settings. The main test suites include:

| Test Suite  | Name                          |                                      Description                                       | Automated |
|-------------|-------------------------------|----------------------------------------------------------------------------------------|-----------|
| MATS        | Minimal Acceptable Test Suite | Basic checks against main branch when changes are made                                 | X         |
| XTS         | Extended Test Suite           | More comprehensive tests run on a scheduled basis                                      | X         |
| SDCT        | Single Day Canonical Tests    | More comprehensive tests that focus on load, throughput and E2E latency                | X         |
| SDLT        | Single Day Longevity Tests    | Longevity tests to ensure stability over extended periods                              | X         |
| SDPT        | Single Day Performance Tests  | Performance-focused tests to evaluate system responsiveness                            | X         |
| MDLT        | Multi Day Longevity Tests     | Extended longevity tests over multiple days                                            | X         |
| MQPT        | Merge Queue Performance Tests | Combined performance, verification and longevity tests for use in Merge Queues         | X         |
| Shortgevity | Short Longevity Tests         | Short-term longevity tests for checking performance against a mainnet-like environment |           |

## MATS

### Environment

- MATS runs inside of self-hosted github runners on every *push* to the **default branch** (`main`).
- MATS is expected to complete within 30 minutes of the test suite starting.
- MATS has an equivalent set of tests that run in PRs against feature branches triggered on *pull_request* events.

### Workflows

- MATS is triggered by the [Node: Build Application](/.github/workflows/node-flow-build-application.yaml) workflow.
- The PR Check equivalent checks are triggered by the [Node: PR Checks](/.github/workflows/node-flow-pull-request-checks.yaml) workflow.
- MATS Dry-Run is triggered manually via the [[CITR] MATS Dry Run](/.github/workflows/flow-dry-run-mats-suite.yaml) workflow.

### Included Tests

|                Test Name                 |                                              Workflow                                               |                                                       Required Parameters                                                       |                                               Required Workflow Secrets                                               | Precursor Steps |
|------------------------------------------|-----------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|-----------------|
| MATS - Compile Application Code          | [ZXC: Compile Application Code](/.github/workflows/zxc-compile-application-code.yaml)               | `ref: <commit-sha>`                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                |                 |
| MATS - Dependency (Module Info)          | [ZXC: Dependency Module Check](/.github/workflows/zxc-dependency-module-check.yaml)                 | `ref: <commit-sha>`                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Spotless Check                    | [ZXC: Spotless Check](/.github/workflows/zxc-spotless-check.yaml)                                   | `ref: <commit-sha>`                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Unit Tests                        | [ZXC: Execute Unit Tests](/.github/workflows/zxc-execute-unit-tests.yaml)                           | `ref: <commit-sha>`                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`codacy-project-token`<br/>`codecov-token` | build           |
| MATS - Integration Tests                 | [ZXC: Execute Integration Tests](/.github/workflows/zxc-execute-integration-tests.yaml)             | `ref: <commit-sha>`<br/>`enable-network-log-capture: true`                                                                      | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Misc)                 | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-misc: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`           | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Misc Records)         | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-misc-records: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Crypto)               | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-crypto: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Simple Fees)          | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-simplefees: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (ISS)                  | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-iss: true`<br/>`enable-network-log-capture: true`                                    | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Token)                | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-token: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`          | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Smart Contract)       | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-smart-contract: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS` | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Restart)              | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-restart: true`<br/>`enable-network-log-capture: true`                                | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Node Death Reconnect) | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-nd-reconnect: true`<br/>`enable-network-log-capture: true`                           | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - HAPI Tests (Atomic Batch)         | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                           | `ref: <commit-sha>`<br/>`enable-hapi-tests-atomic-batch: true`<br/>`enable-network-log-capture: true`<br/>`mats-suffix: MATS`   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Otter Tests                       | [ZXC: Execute Otter Tests](/.github/workflows/zxc-execute-otter-tests.yaml)                         | `ref: <commit-sha>`<br/>`enable-fast-otter-tests: true`<br/>`enable-network-log-capture: true`                                  | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                | build           |
| MATS - Snyk Checks                       | [ZXC: Snyk Scan](/.github/workflows/zxc-snyk-scan.yaml)                                             | `ref: <commit-sha>`                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`snyk-token`                               | build           |
| MATS - Gradle Determinism                | [ZXC: Verify Gradle Build Determinism](/.github/workflows/zxc-verify-gradle-build-determinism.yaml) | `ref: <commit-sha>`                                                                                                             | `gradle-cache-username`<br/>`gradle-cache-password`                                                                   | build           |
| MATS - Docker Determinism                | [ZXC: Verify Docker Build Determinism](/.github/workflows/zxc-verify-docker-build-determinism.yaml) | `ref: <commit-sha>`                                                                                                             | `gradle-cache-username`<br/>`gradle-cache-password`                                                                   | build           |

## XTS

### Environment

- XTS runs inside of self-hosted github runners every 3 hours on the **default branch** (`main`).
- XTS is expected to complete within 3 hours of the test suite starting.
- XTS has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- XTS is triggered by the [ZXCron: [CITR] Extended Test Suite](/.github/workflows/zxcron-extended-test-suite.yaml) workflow.
- XTS Dry Run is triggered manually via the [[CITR] XTS Dry Run](/.github/workflows/flow-dry-run-extended-test-suite.yaml) workflow.

### Included Tests

|                 Test Name                 |                                             Workflow                                              |                                                                                                                                                     Required Parameters                                                                                                                                                     |                                                                                                                Required Workflow Secrets                                                                                                                 |           Precursor Steps            |
|-------------------------------------------|---------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| XTS Compile Code                          | [ZXC: Compile Application Code](/.github/workflows/zxc-compile-application-code.yaml)             |                                                                                                                                                                                                                                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   |                                      |
| XTS Timing Sensitive Tests                | [ZXC: Execute Timing Sensitive Tests](/.github/workflows/zxc-execute-timing-sensitive-tests.yaml) | `ref: <commit-sha>`                                                                                                                                                                                                                                                                                                         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS Hammer Tests                          | [ZXC: Execute Hammer Tests](/.github/workflows/zxc-execute-hammer-tests.yaml)                     | `ref: <commit-sha>`                                                                                                                                                                                                                                                                                                         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Misc)                     | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-misc: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                               | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Misc Records)             | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-misc-records: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                       | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Crypto)                   | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-crypto: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                             | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Simple Fees)              | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-simplefees: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                         | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Token)                    | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-token: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                              | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Smart Contract)           | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-smart-contract: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Time Consuming)           | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-time-consuming: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                     | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Block Node Communication) | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-bn-communication: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                   | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS HAPI Tests (Atomic Batch)             | [ZXC: Execute HAPI Tests](/.github/workflows/zxc-execute-hapi-tests.yaml)                         | `enable-hapi-tests-atomic-batch: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                       | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| XTS Otter Tests                           | [ZXC: Execute Otter Tests](/.github/workflows/zxc-execute-otter-tests.yaml)                       | `enable-full-otter-tests: true`<br/>`enable-network-log-capture: true`<br/>`ref: <commit-sha>`                                                                                                                                                                                                                              | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`                                                                                                                                                                                   | Fetch XTS Candidate<br/>Compile Code |
| JRS Panel                                 | [ZXC: Regression](/.github/workflows/zxc-jrs-regression.yaml)                                     | `panel-config: configs/suites/GCP-PRCheck-Abbrev-4N.json`<br/>`ref: <commit-sha>`<br/>`branch-name: <github.head_ref or github.ref_name>`<br/>`base-branch-name: github.base_ref or ''`<br/>`slack-results-channel: regression-test`<br/>`slack-summary-channel: regression-test`<br/>`use-branch-for-slack-channel: false` | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`jrs-ssh-user-name`<br/>`jrs-ssh-key-file`<br/>`gcp-project-number`<br/>`gcp-sa-key-contents`<br/>`slack-api-token`<br/>`grafana-agent-username`<br/>`grafana-agent-password` | Fetch XTS Candidate<br/>Compile Code |
| Hedera Node JRS Panel                     | [ZXC: Regression](/.github/workflows/zxc-jrs-regression.yaml)                                     | `panel-config: configs/services/suites/daily/GCP-Daily-Services-Abbrev-DAB-Update-4N-2C.json`<br/>`ref: <commit-sha>`<br/>`branch-name: <github.head_ref or github.ref_name>`<br/>`hedera-tests-enabled: true`<br/>`use-branch-for-slack-channel: false`                                                                    | `access-token`<br/>`gradle-cache-username`<br/>`gradle-cache-password`<br/>`jrs-ssh-user-name`<br/>`jrs-ssh-key-file`<br/>`gcp-project-number`<br/>`gcp-sa-key-contents`<br/>`slack-api-token`<br/>`grafana-agent-username`<br/>`grafana-agent-password` | Fetch XTS Candidate<br/>Compile Code |
| SDK TCK Regression Panel                  | [ZXC: TCK Regression](/.github/workflows/zxc-tck-regression.yaml)                                 | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-tck-report-webhook`<br/>`slack-detailed-report-webhook`                                                                                                                                                                        | Fetch XTS Candidate<br/>Compile Code |
| Mirror Node Regression Panel              | [ZXC: Mirror Node Regression](/.github/workflows/zxc-mirror-node-regression.yaml)                 | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`<br/>`helm-release-name: mirror or mirror-1`                                                                                                                                                                                                                  | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |
| JSON-RPC Relay Regression Panel           | [ZXC: JSON-RPC Relay Regression](/.github/workflows/zxc-json-rpc-relay-regression.yaml)           | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |
| Block Node Regression Panel               | [ZXC: Block Node Explorer Regression](/.github/workflows/zxc-block-node-regression.yaml)          | `ref: <commit-sha>`<br/>`solo-version: vars.CITR_SOLO_VERSION`                                                                                                                                                                                                                                                              | `access-token`<br/>`slack-detailed-report-webhook`                                                                                                                                                                                                       | Fetch XTS Candidate<br/>Compile Code |

## SDCT

### Environment

- SDCT runs on the **performance** (**perf1**) network that has a Mirrornode setup, hosted on GCP and various external server providers (**Latitude**, **OVH**, **AWS**, etc.).
- SDCT is expected to complete around 20 hours after the test suite starts.
- SDCT supports artifacts built from any PR, tag, or branch.

### Workflows

- SDCT is triggered by the [ZXF: [CITR] Single Day Canonical Test (SDCT)](https://www.notion.so/.github/workflows/zxf-single-day-canonical-test.yaml) workflow
  - The workflow is currently being updated with a fast-fail enhancement

### Included Tests

These tests run sequentially with a Mirrornode setup to measure E2E latency performance.

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

## SDLT

### Environment

- SDLT runs inside of self-hosted github runners every 24 hours on the **default branch** (`main`).
- SDLT is expected to complete within 16 hours of the test suite starting.
- SDLT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- SDLT is triggered by the [ZXF: [CITR] Single Day Longevity Test Controller](/.github/workflows/zxf-single-day-longevity-test-controller.yaml) workflow.
- SDLT Dry Run is triggered manually via the [ZXF: [CITR] Adhoc - Single Day Performance Test Controller (SDPT)](/.github/workflows/zxf-single-day-performance-test-controller-adhoc.yaml) workflow.

### Hardware

Latitude kubernetes cluster
- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

|     Test Name     |                                            Workflow                                            |  Required Parameters  | Run time |                  Precursor Steps                   |
|-------------------|------------------------------------------------------------------------------------------------|-----------------------|----------|----------------------------------------------------|
| LongevityLoadTest | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 16 hours | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| State Validator   | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) |                       | 30 mins  | LongevityLoadTest                                  |

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

## SDPT

### Environment

- SDPT runs inside of self-hosted github runners every 24 hours on the **default branch** (`main`).
- SDPT is expected to complete within 20 hours of the test suite starting.
- SDPT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- SDPT is triggered by the [ZXF: [CITR] Single Day Performance Test Controller (SDPT)](/.github/workflows/zxf-single-day-performance-test-controller.yaml) workflow.
- SDPT Dry Run is triggered manually via the [ZXF: [CITR] Adhoc - Single Day Longevity Test Controller](/.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml) workflow.

### Hardware

Latitude kubernetes cluster
- 7 nodes for Consensus Nodes
- 1 node for CryptoBench
- 1 node for aux services and NLG client

### Included Tests

|       Test Name        |                                              Workflow                                              |                Required Parameters                | Run time  |                  Precursor Steps                   |
|------------------------|----------------------------------------------------------------------------------------------------|---------------------------------------------------|-----------|----------------------------------------------------|
| NftTransferLoadTest    | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 6 hours   | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| CryptoBench            | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | maxKey, numRecords, keySize, recordSize, numFiles | 4-5 hours | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| HCSLoadTest            | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | NftTransferLoadTest                                |
| CryptoTransferLoadTest | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | HCSLoadTest                                        |
| HeliSwapLoadTest       | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | nlg-accounts                                      | 6 hours   | CryptoTransferLoadTest                             |
| SmartContractLoadTest  | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) | nlg-accounts,nlg-time                             | 2 hours   | HeliSwapLoadTest                                   |
| State  Validator       | [ZXC: [CITR] Single Day Performance Test](/.github/workflows/zxc-single-day-performance-test.yaml) |                                                   | 30 mins   | All previous tests passed                          |

### Runtime durations, practical settings

- 1 hour with arguments: nlg-time=3 (mins), nlg-accounts=100000, files=60
- 10 hours : nlg-time=180 (mins), nlg-accounts=100000000, files=600
- 20 hours: nlg-time=330, nlg-accounts=100000000, files=6000

## MDLT

### Environment

- MDLT runs inside of self-hosted github runners on demand, against any PR, tag, or branch, assuming release candidate
- MDLT is expected to complete within 7 days of the test suite starting.
- MDLT has a dry-run equivalent to SDLT

### Workflows

- MDLT is triggered by the [ZXF: [CITR] Single Day Longevity Test Controller](/.github/workflows/zxf-single-day-longevity-test-controller.yaml) workflow.
- MDLT Dry Run is triggered manually via the [ZXF: [CITR] Adhoc - Single Day Longevity Test Controller](/.github/workflows/zxf-single-day-longevity-test-controller-adhoc.yaml) workflow.

### Hardware

Latitude kubernetes cluster
- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

|     Test Name     |                                            Workflow                                            |  Required Parameters  | Run time  |                  Precursor Steps                   |
|-------------------|------------------------------------------------------------------------------------------------|-----------------------|-----------|----------------------------------------------------|
| LongevityLoadTest | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 7 days    | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| State Validator   | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) |                       | 1.5 hours | LongevityLoadTest                                  |

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

### Environment

- Shortgevity runs on the **performance** (**perf1**) network, which is hosted on **GCP** and various external server hosting providers (**Latitude**, **OVH**, **AWS**, etc.).
- Shortgevity runs range from 6–8 hours to 3 days, depending on the size of the change set being tested and the potential impact on performance.
- Shortgevity supports artifacts built from any PR, tag, or branch.

### Workflows

- Shortgevity runs manually because it operates on an ad hoc basis and is limited by the availability of the shared **performance** network resource.

### Included Tests

All tests are run in parallel with adjustable total TPS. Currently runs at PROD supported 10.5K.

|                Test Type                |                     Ratio                      |
|-----------------------------------------|------------------------------------------------|
| Crypto                                  | %30~                                           |
| HCS                                     | %30~                                           |
| HTS                                     | %10~                                           |
| NFT                                     | %30~                                           |
| Smartcontract                           | ~350(including Uniswaps/ERCs)                  |
| K/V pairs                               | 200M                                           |
| Best effort coverage of Hedera Tx Types | < 100 TPS                                      |
| Re-connects                             | At most 2 nodes in re-connect at the same time |

## MQPT Merge Queue Performance Tests

### Environment

- MQPT runs inside of self-hosted github runners regularly against Trunk.io Merge Queues
- MQPT is expected to complete within 3 hours 40 mins of the test suite starting.
- MQPT has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- MQPT is triggered by the [ZXF: [CITR] Merge Queue Performance Test Controller](/.github/workflows/zxf-merge-queue-performance-test-controller.yaml) workflow.
- MQPT AdHoc Run is triggered manually via the [ZXF: [CITR] Adhoc - Merge Queue Performance Test Controller](/.github/workflows/zxf-merge-queue-performance-test-controller-adhoc.yaml) workflow.

### Hardware

Latitude kubernetes cluster
- 7 nodes for Consensus Nodes
- 1 node for aux services and NLG client

### Included Tests

|        Test Name         |                                            Workflow                                            |  Required Parameters  | Run time |                  Precursor Steps                   |
|--------------------------|------------------------------------------------------------------------------------------------|-----------------------|----------|----------------------------------------------------|
| ScriptedLoadTest, part 1 | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 2 hours  | Code Compiles, Solo deployed CNs/NLG onto Latitude |
| ReconnectTest            | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) | nlg-accounts,nlg-time |          | ScriptedLoadTest                                   |
| ScriptedLoadTest, part 2 | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) | nlg-accounts,nlg-time | 1 hour   | ScriptedLoadTest                                   |
| State Validator          | [ZXC: [CITR] Single Day Longevity Test](/.github/workflows/zxc-single-day-longevity-test.yaml) |                       | 30 mins  | ReconnectTest                                      |

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
