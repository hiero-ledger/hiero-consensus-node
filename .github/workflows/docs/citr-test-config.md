# CITR Environment Configuration

## Overview

This document outlines the configuration settings for the CITR (Continuous Integration Test & Release) environment used in our workflows. The CITR environment is designed to facilitate automated testing and deployment processes.

There are several test suites that are run in the CITR environment, each with its own configuration settings. The main test suites include:

| Test Suite  | Name                          | Description                                                                            | Automated |
|-------------|-------------------------------|----------------------------------------------------------------------------------------|-----------|
| MATS        | Minimal Acceptable Test Suite | Basic checks against main branch when changes are made                                 | X         |
| XTS         | Extended Test Suite           | More comprehensive tests run on a scheduled basis                                      | X         |
| SDCT        | Single Day Canonical Tests    | More comprehensive tests that focus on load, throughput, etc                           | X         |
| SDLT        | Single Day Longevity Tests    | Longevity tests to ensure stability over extended periods                              | X         |
| SDPT        | Single Day Performance Tests  | Performance-focused tests to evaluate system responsiveness                            | X         |
| MDLT        | Multi Day Longevity Tests     | Extended longevity tests over multiple days                                            |           |
| Shortgevity | Short Longevity Tests         | Short-term longevity tests for checking performance against a mainnet-like environment |           |

## MATS 

### Environment

- MATS runs inside of self-hosted github runners on every *push* to the **default branch** (`main`).
- MATS is expected to complete within 30 minutes of the test suite starting.
- MATS has an equivalent set of tests that run in PRs against feature branches triggered on *pull_request* events.

### Workflows

- MATS is triggered by the [node-flow-build-application.yaml](/.github/workflows/node-flow-build-application.yaml) workflow.
- The PR Check equivalent checks are triggered by the [node-flow-pull-request-checks.yaml](/.github/workflows/node-flow-pull-request-checks.yaml) workflow.

## XTS

### Environment

- XTS runs inside of self-hosted github runners every 3 hours on the **default branch** (`main`).
- XTS is expected to complete within 3 hours of the test suite starting.
- XTS has a dry-run equivalent that can be run against any PR, tag, or branch.

### Workflows

- XTS is triggered by the [zxcron-extended-test-suite.yaml](/.github/workflows/zxcron-extended-test-suite.yaml) workflow.
- XTS Dry Run is triggered manually via the [zxf-dry-run-extended-test-suite.yaml](/.github/workflows/zxf-dry-run-extended-test-suite.yaml) workflow.

## SDCT

## SDLT

## SDPT

## MDLT

## Shortgevity