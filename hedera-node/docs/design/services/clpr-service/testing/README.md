# CLPR Testing Documentation

This directory contains the test specification and reference guides for the CLPR protocol. The documents
are organized into three categories: the master test inventory, detailed test definitions per CITR category,
and infrastructure reference guides.

## Master Test Inventory

| Document | Purpose |
|---|---|
| [clpr-test-spec.md](clpr-test-spec.md) | Top-level inventory of all CLPR tests. Defines test categories, enumerates tests with one-line descriptions, and provides a cross-reference table mapping each CLPR specification section to the test IDs that cover it. Use this document for coverage tracking and gap analysis. |

## Detailed Test Definitions

Each file below contains detailed test definitions for a specific CITR test category. Tests include
purpose, properties to test, setup procedures, step-by-step instructions, and expected outcomes.

| Document | CITR Category | Scope | Test Count |
|---|---|---|---|
| [clpr-test-mats.md](clpr-test-mats.md) | MATS (30 min, every push) | Single-ledger handler correctness for each CLPR transaction type | 11 |
| [clpr-test-xts.md](clpr-test-xts.md) | XTS (3 hours, every 3 hours) | Cross-ledger integration, adversarial scenarios, deferred single-ledger tests, recovery and fault tolerance | 65 |
| [clpr-test-mqpt.md](clpr-test-mqpt.md) | MQPT (3h 40min, merge queue) | Cross-ledger smoke test and node restart during CLPR traffic | 3 |
| [clpr-test-sdct.md](clpr-test-sdct.md) | SDCT (20 hours, scheduled) | Throughput profiling, latency measurement, queue behavior under load | 7 |
| [clpr-test-sdlt.md](clpr-test-sdlt.md) | SDLT (16 hours, daily) | Sustained cross-ledger traffic alongside normal Hiero workload, state growth, resource consumption | 3 |
| [clpr-test-sdpt.md](clpr-test-sdpt.md) | SDPT (20 hours, daily) | Sequential performance benchmarks: handler costs, endpoint scaling, proof construction latency | 6 |
| [clpr-test-cross-platform.md](clpr-test-cross-platform.md) | Outside CITR | Hiero-to-Ethereum, Ethereum-to-Ethereum, multi-ledger topologies (N >= 3), EVM-specific adversarial | 12 |
| [clpr-test-app-workflows.md](clpr-test-app-workflows.md) | Outside CITR | Application workflow patterns: token transfer, NFT, escrow, multi-ledger atomic swap, DEX | 11 |

## Infrastructure Reference Guides

| Document | Purpose |
|---|---|
| [clpr-test-solo-multi-network.md](clpr-test-solo-multi-network.md) | Step-by-step guide for deploying two or more Hiero networks using SOLO in a single Kubernetes cluster for CLPR cross-ledger testing. Covers namespace separation, port mapping, cross-namespace networking, parallel deployment, and teardown. |
| [clpr-test-smart-contracts.md](clpr-test-smart-contracts.md) | Guide for CLPR smart contract testing across three tiers: Hardhat unit tests (local EVM), network tests (two SOLO networks via JSON-RPC relay), and native E2E tests (real CLPR system contracts via HAPI). Covers the mock contract library, test patterns, and framework configuration. |

## Relationship to the CLPR Specification

The test definitions in this directory are derived from the [CLPR Design Document](../clpr-service.md)
and the [Cross-Platform Specification](../clpr-service-spec.md). The cross-reference table in
`clpr-test-spec.md` maps every specification section to the tests that cover it.

These test definitions are subject to change as the specification evolves during implementation. The
specification is the source of truth — if a test definition conflicts with the spec, the spec takes
precedence.
