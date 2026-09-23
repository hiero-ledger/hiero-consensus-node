# CLPR on Hiero — Implementation Documentation

This directory describes how the **Cross-Ledger Permissioned Routing (CLPR)** protocol is
manifested in the Hiero (Hedera) consensus node. It is intended to be loaded by humans and AI
agents working on CLPR code in this repository.

## Required reading order

1. **Protocol-level (NOT in this directory):**
   - [`clpr-spec/clpr-service.md`](../../../../../clpr-spec/clpr-service.md) — narrative
     overview of CLPR (concepts, roles, architecture). Read first if you don't already
     know what a Channel / Connector / Bundle / Endpoint / Verifier is.
   - [`clpr-spec/clpr-service-spec.md`](../../../../../clpr-spec/clpr-service-spec.md) —
     normative protocol spec (protobuf shapes, state-machine, algorithms, pseudo-APIs).
     Read for wire formats, hash algorithms, lifecycle semantics, slashing tables, etc.
2. **This README** — Hiero-specific manifestation, file map, decision summary.
3. **Topic doc** under this directory matching your task (table below).

> **Do not duplicate the spec.** Anything defined in `clpr-service{,-spec}.md` is referenced
> by name only; this directory documents *the Hiero-specific shape* — module layout, state
> IDs, Dagger wiring, configuration keys, etc.

## How CLPR is wired on Hiero (1-page summary)

CLPR on Hiero is a **native service**, not a smart contract. It owns Merkle state, has its
own HAPI transaction types, and (uniquely) runs an additional gRPC service for
endpoint-to-endpoint sync.

|        Spec concept         |                                                                    Hiero realisation                                                                    |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| CLPR Service                | `ClprService` (SPI) + `ClprServiceImpl` registering `V0650ClprSchema`.                                                                                  |
| Endpoint                    | Each consensus node. No separate `registerEndpoint`/bond flow — endpoints derive from the consensus roster (spec §6.5 platform-managed model).          |
| Endpoint signature          | Platform event-level signature; `ClprBundleSubmitter` submits bundle txs with empty `SignatureMap` (mirrors TSS/Hints submitters).                      |
| Connector signing-key bind  | ECDSA secp256k1 over `keccak256(connector_id ‖ 0x000…0000016e)` per spec §6.3 callout.                                                                  |
| System-contract address     | `0x16e` (`CLPR_EVM_ADDRESS` in `ClprSystemContract`).                                                                                                   |
| `sendMessage` pseudo-API    | EVM precompile method on `0x16e` → `ClprServiceApi.sendMessage`.                                                                                        |
| `submitBundle` pseudo-API   | HAPI tx `ClprSubmitBundle` (allowed in `networkAdmin.nodeTransactionsAllowList`).                                                                       |
| Verifier contract           | EVM contract on this ledger; called via `ContractCallTransactionBody` step-dispatch in `EvmClprVerifier`.                                               |
| Sync RPC (gRPC)             | `proto.ClprEndpointService` exposed by Netty server, routed via `ClprMethod`/`ClprDiscoveryMethod` to `ClprSyncWorkflowImpl`.                           |
| Outbound sync orchestration | `ClprChannelManager` (background scanner, `start()`/`stop()` from `Hedera.java`).                                                                       |
| Storage                     | Merkle state via Schema; one schema `V0650ClprSchema` (genesis at v0.65.0).                                                                             |
| Admin                       | CLPR admin key (per-handler check); update of `ClprLedgerConfiguration` is a HAPI tx.                                                                   |
| Lazy config propagation     | Performed in `ClprServiceApiImpl.sendMessage` (prepends a `ClprControlMessage.configUpdate` when channel's `lastConfigTimestamp` is stale).             |
| Throttles                   | `ClprThrottles` (in singleton `ClprLedgerConfiguration`) enforced by handler/api code; `InboundSyncThrottle` enforces peer sync rate at the gRPC layer. |
| Fees                        | One `ClprFeeCalculator` per CLPR `HederaFunctionality` reading `FeeSchedule.baseFee`.                                                                   |

## Module map

|                       Gradle module                        |                         Role                          |                Package root                |
|------------------------------------------------------------|-------------------------------------------------------|--------------------------------------------|
| `hedera-node/hedera-clpr-service/`                         | Service interface / SPI                               | `com.hedera.node.app.service.clpr`         |
| `hedera-node/hedera-clpr-service-impl/`                    | Handlers, schema, stores, verifier dispatch, fee calc | `com.hedera.node.app.service.clpr.impl`    |
| `hedera-node/hedera-app/` (subset)                         | Sync orchestration, gRPC plumbing                     | `com.hedera.node.app.workflows.clpr`       |
| `hedera-node/hedera-smart-contract-service-impl/` (subset) | EVM system contract `0x16e`                           | `…contract.impl.exec.systemcontracts.clpr` |
| `hapi/hedera-protobuf-java-api/.../proto/services/`        | Wire definitions                                      | `clpr_*.proto`, `state/clpr/*.proto`       |
| `hedera-node/hedera-config/.../data/ClprConfig.java`       | Node-local config                                     | —                                          |
| `hedera-node/test-clients/` (subset)                       | BDD suites + multi-network test framework             | `com.hedera.services.bdd.suites.clpr`      |

## Topic deep-dives (load on demand)

Each file is self-contained; load only what you need.

|                            When you are working on…                             |                         Read                         |
|---------------------------------------------------------------------------------|------------------------------------------------------|
| The wire format, state schema, or proto layout                                  | [`state-and-protobufs.md`](state-and-protobufs.md)   |
| A HAPI transaction handler (any `Clpr…Handler`)                                 | [`handlers.md`](handlers.md)                         |
| The EVM `sendMessage` precompile / `ClprServiceApi`                             | [`evm-integration.md`](evm-integration.md)           |
| Endpoint-to-endpoint sync (gRPC, sync orchestration, ingest of inbound bundles) | [`sync-workflow.md`](sync-workflow.md)               |
| Inbound bundle / config verification (`ClprVerifier`)                           | [`verifier.md`](verifier.md)                         |
| Tuning configuration, slashing math, or fees                                    | [`config-and-slashing.md`](config-and-slashing.md)   |
| Writing or reading CLPR e2e/BDD tests                                           | [`testing.md`](testing.md)                           |
| Understanding where the impl diverges from spec (interop blockers, known gaps)  | [`DRIFT-REVIEW-2026-05.md`](DRIFT-REVIEW-2026-05.md) |

## Common cross-cutting facts

- **Feature flag:** every handler and the system contract gate on `clpr.enabled`
  (default `false`). Enable it explicitly for a CLPR deployment with `clpr.enabled=true`.
- **Genesis:** `V0650ClprSchema` (v0.65.0) is the only schema; it seeds
  `LEDGER_CONFIGURATION` from `ClprConfig` (`chainId`, `protocolVersion`) plus default
  `ClprThrottles`. No migration path yet.
- **`HederaFunctionality` values:** `CLPR_REGISTER_CHANNEL`, `CLPR_COMPLETE_CHANNEL`,
  `CLPR_CLOSE_CHANNEL`, `CLPR_REGISTER_CONNECTOR`, `CLPR_COMPLETE_CONNECTOR`,
  `CLPR_DEREGISTER_CONNECTOR`, `CLPR_SUBMIT_BUNDLE`, `CLPR_REDACT_MESSAGE`,
  `CLPR_UPDATE_LEDGER_CONFIGURATION`, plus the `CLPR_GET_LEDGER_CONFIGURATION` query.
  All map to `ClprService.NAME` in `ServiceScopeLookup`.
- **Two gRPC services, distinct purposes:**
  `proto.ClprService` (HAPI transactions) vs. `proto.ClprEndpointService` (peer sync,
  payloads are *not* `Transaction` envelopes).
- **`AbstractClprHandler`:** all handlers extend it; it enforces `clpr.enabled` and
  provides ACTIVE-channel lookup helpers. Override `doHandle`, not `handle`.

## Pointers to running notes

- Original design plan: [`docs/superpowers/specs/2026-04-29-clpr-hiero-to-hiero-e2e-test-design.md`](../../../../docs/superpowers/specs/2026-04-29-clpr-hiero-to-hiero-e2e-test-design.md)
- Implementation plan: [`docs/superpowers/plans/2026-04-29-clpr-hiero-to-hiero-e2e-test.md`](../../../../docs/superpowers/plans/2026-04-29-clpr-hiero-to-hiero-e2e-test.md)
- In-progress review notes: `hedera-node/hedera-clpr-service-impl/REVIEW_NOTES.md`
