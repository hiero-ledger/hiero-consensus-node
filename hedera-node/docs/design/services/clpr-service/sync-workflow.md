# CLPR Endpoint-to-Endpoint Sync (Hiero)

> Prereq: `clpr-service-spec.md` §1.5 (Sync Protocol), §4.2 (Bundle Verification),
> §5.2 (Endpoint Discovery). This doc covers the Hiero-specific orchestration: which
> classes do what, lifecycle management, and how inbound bundles transition from gRPC into
> consensus.

The protocol-level model is "every consensus node is an endpoint." Hiero realises this
without a separate endpoint identity — the consensus roster *is* the endpoint roster.

All classes live under
`hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/`.

## Class roles at a glance

```
                    ┌─────────────────────┐
   peer →  gRPC →   │  ClprMethod         │     (Netty server route)
                    └─────────┬───────────┘
                              │
                    ┌─────────▼───────────┐
                    │ ClprSyncWorkflow    │
                    │ (impl)              │
                    └──┬──────────┬───────┘
   outbound (read     │           │   inbound bundle ingest
   from latest        │           │
   immutable state)   │           ▼
                      │      ┌──────────────────┐
                      │      │ InboundSync      │
                      │      │ Throttle         │
                      │      └────────┬─────────┘
                      │               │ pass
                      │               ▼
                      │      ┌──────────────────┐
                      │      │ ClprBundle       │  → AppContext.Gossip.submit(
                      │      │ Submitter        │       ClprSubmitBundleTxBody)
                      │      └──────────────────┘
                      │
                      ▼
              gRPC response to peer

   ┌──────────────────────────────────┐
   │ ClprChannelManager            │   background scheduler
   │ ─ owns channel sync schedule  │ ──┐
   │ ─ honours rate limits            │   │  per channel
   │ ─ implements                     │   │  outbound sync
   │   ClprChannelLifecycle        │   ▼
   └──────────────────────────────────┘  ┌──────────────────┐
                                         │ ClprEndpoint     │  Netty + grpc-java
                                         │ Client           │  client unary call
                                         └──────────────────┘
```

## Inbound path (peer → me)

### `ClprMethod` and `ClprDiscoveryMethod`

`MethodBase` adapters (in `hedera-app/.../grpc/impl/`) wired into the Netty gRPC server by
`GrpcServiceBuilder` / `NettyGrpcServerManager` for `proto.ClprEndpointService`. They
dispatch to `ClprSyncWorkflow.handleSync` and `handleDiscovery` respectively.

### `ClprSyncWorkflow` / `ClprSyncWorkflowImpl`

`@Singleton`. Server-side handler.

`handleSync`:
1. Parse incoming `ClprSyncPayload`.
2. Do not apply local peer-exclusion heuristics on the sync RPC when
`clpr.syncPeerExclusionEnabled=false` (the default). Historical shunning logic is retained
behind the feature flag for further archeology.
3. Validate the channel referenced by the payload: must be `ACTIVE` (rejects
`PAUSED`/`CLOSING`/`CLOSED`). Lookup uses the latest **immutable** state (a frozen
snapshot, not the current handle round).
4. **For outbound messages** (we are the source): read messages and queue metadata from
the immutable state, build a `ClprSyncPayload` response with the proof (Hiero proof
construction is currently stubbed — TODO CLPR-4.3 referenced in code).
5. **For inbound messages** (we are the destination): hand the received payload to
`ClprBundleSubmitter.submitBundle(...)`.

`handleDiscovery`: replies with the local node's known peers from the seed-endpoint cache
(maintained by `ClprChannelManager`) plus filtered roster contacts. When
`clpr.syncPeerExclusionEnabled=true`, discovery requests pass through `InboundSyncThrottle`
and may return `RESOURCE_EXHAUSTED`; when false, the throttle fails open.

### `InboundSyncThrottle`

`@Singleton`. Sliding-window rate limiter keyed by peer identity. Limit is
`clpr.maxInboundSyncsPerSec`. When `clpr.syncPeerExclusionEnabled=true`, exceeding it
puts the peer on a temporary shun list for `clpr.shunDurationSeconds`. When false, all
requests are allowed and existing shun state is ignored/cleared. This is Hiero's
app-layer realisation of spec §1.6 (misbehaviour — local-only).

### `ClprBundleSubmitter`

`@Singleton`. Wraps a received `ClprSyncPayload` into a `ClprSubmitBundleTransactionBody`
and submits via `AppContext.Gossip.submit(TransactionBody)` — the same path used by
TSS and Hints submissions.

Key Hiero choices:
- **No `SignatureMap`.** The platform event-level signature is the endpoint's signature
for the bundle (spec §1.5 endpoint signature). The transaction body therefore must be
in `networkAdmin.nodeTransactionsAllowList`, which it is by default.
- The submission turns into a `CLPR_SUBMIT_BUNDLE` HAPI tx that
`ClprSubmitBundleHandler` then handles in normal consensus.

## Outbound path (me → peer)

### `ClprChannelManager`

`@Singleton`. Implements `ClprChannelLifecycle`. The orchestrator.

Lifecycle (called from `Hedera.java`):
- `start()` — at network up: create scheduler, hydrate `endpoints` cache from
`ClprLedgerConfiguration`, populate the active-channel set from
`ReadableChannelStore`.
- `stop()` — at shutdown: cancel scheduler, drain in-flight syncs.
- `onChannelActivated(channelId)` — add to schedule.
- `onChannelClosed(channelId)` — remove from schedule.

Per-tick logic (background thread):
- For each scheduled channel, pick a peer using a reciprocity-biased heuristic
(spec §5.2). Bounded by `clpr.maxConcurrentSyncs` (semaphore).
- Acquire a per-channel lock (one in-flight sync per channel at a time).
- Build the request payload (queue metadata + outbound bundle) from the latest immutable
state.
- Call `ClprEndpointClient.sync(...)`. On timeout (`clpr.syncTimeoutSeconds`) or error,
apply circuit-breaker / retry policy (`clpr.retryInitialDelayMs`,
`clpr.retryMaxDelayMs`, `clpr.retryMaxAttempts`,
`clpr.circuitBreakerCooldownSeconds`); decay peer reputation
(`clpr.reputationDecaySeconds`). Open circuit breakers remove peers from the candidate
set only when `clpr.syncPeerExclusionEnabled=true`; the default false setting keeps those
signals observational and never declines to initiate a sync on that basis.
- On success, hand the response payload to `ClprBundleSubmitter` to ingest the peer's
outbound (i.e. our inbound) bundles.

### `ClprEndpointClient`

Outbound gRPC client. Netty + grpc-java `ClientCalls` for the
`proto.ClprEndpointService/sync` unary RPC. Marshallers are byte-array based — payloads
are pre-serialised `ClprSyncPayload` bytes — so the client does not need the protobuf
service stub generated. One-shot unary call with `clpr.syncTimeoutSeconds` deadline.

## Wiring (Dagger)

- `ClprSyncWorkflowInjectionModule.java` binds `ClprSyncWorkflowImpl → ClprSyncWorkflow`
  and `ClprChannelManager → ClprChannelLifecycle`. Included from
  `WorkflowsInjectionModule`.
- `Hedera.java` calls `daggerApp.clprChannelManager().start()` / `.stop()`.
- `HederaInjectionComponent` exposes `clprChannelManager()` and `clprSyncWorkflow()`.
- `StandaloneModule` provides a no-op `ClprChannelLifecycle` for the standalone
  executor (no live sync orchestration in that context).

## Important invariants / gotchas

- The sync workflow reads **immutable state**, not handle-thread state. Inbound bundle
  ingestion happens via the regular consensus path (`ClprSubmitBundleHandler`), so
  causality with other transactions is preserved by consensus order.
- `ClprBundleSubmitter` is the only place in production code that submits a
  `CLPR_SUBMIT_BUNDLE` body. Never submit one from a user transaction or a test fixture
  expecting normal signing — the empty `SignatureMap` will fail signature verification
  unless the payer is a node and the body is in `nodeTransactionsAllowList`.
- A genesis network has empty `endpoints` and no channels, so
  `ClprChannelManager` does nothing until an admin populates the config and the first
  channel is completed.
- gRPC service for sync is **separate** from the HAPI gRPC service — different proto
  service, different Netty service registrations.

## Spec-step → code mapping cheat sheet

|           Spec            |                                    Code                                    |
|---------------------------|----------------------------------------------------------------------------|
| §1.5 sync RPC             | `ClprMethod` → `ClprSyncWorkflowImpl`                                      |
| §1.5 endpoint signature   | platform event-level signature; `ClprBundleSubmitter` empty `SignatureMap` |
| §1.6 misbehaviour (local) | `InboundSyncThrottle` shun list, gated by `clpr.syncPeerExclusionEnabled`  |
| §4.2 bundle verification  | `ClprSubmitBundleHandler` (consensus path)                                 |
| §5.2 endpoint discovery   | `ClprSyncWorkflowImpl.handleDiscovery` + `ClprChannelManager` seed cache   |
| §6.2 lifecycle hooks      | `ClprChannelLifecycle` ↔ `ClprChannelManager`                              |
