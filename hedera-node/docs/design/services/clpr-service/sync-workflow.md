# CLPR Endpoint-to-Endpoint Sync (Hiero)

> Prereq: `clpr-service-spec.md` §1.5 (Sync Protocol), §4.2 (Bundle Verification),
> §5.2 (Endpoint Manifests). This doc covers the Hiero-specific orchestration: which
> classes do what, lifecycle management, and how inbound bundles transition from gRPC into
> consensus.

The protocol-level model is "every consensus node is an endpoint." Hiero realises this
without a separate endpoint identity — the consensus roster *is* the endpoint roster.

All classes live under
`hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/clpr/`.

## Class roles at a glance

```
                    ┌─────────────────────┐
   peer →  gRPC →   │ ClprStreamingSync   │     (Netty server route,
                    │ Method              │      bidi stream)
                    └─────────┬───────────┘
                              │
                    ┌─────────▼───────────┐
                    │ ClprStreamingSync   │
                    │ Session (per stream)│
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
              bundles streamed back to peer

   ┌──────────────────────────────────┐
   │ ClprChannelManager            │   background scheduler
   │ ─ owns channel sync schedule  │ ──┐
   │ ─ honours rate limits            │   │  per channel
   │ ─ implements                     │   │  outbound sync
   │   ClprChannelLifecycle        │   ▼
   └──────────────────────────────────┘  ┌──────────────────┐
                                         │ ClprEndpoint     │  Netty + grpc-java
                                         │ Client           │  client bidi stream
                                         └──────────────────┘
```

## Inbound path (peer → me)

### `ClprStreamingSyncMethod`

Server routes (in `hedera-app/.../grpc/impl/`) for `proto.ClprEndpointService`:

- `sync` is a bidirectional stream of `ClprStreamingSyncPayload`. `NettyGrpcServerManager` registers it by hand
  (`MethodType.BIDI_STREAMING`), because `GrpcServiceBuilder` only builds unary methods. `ClprStreamingSyncMethod`
  asks `ClprSyncWorkflow.openStreamingSync` for a fresh `ClprStreamingSyncSession` per stream. There is no unary
  `sync`: a peer that only speaks the old unary RPC cannot sync with this node.

There is no endpoint-discovery RPC: peers learn each other's endpoints from the on-ledger
`ClprEndpointManifest` (see `getEndpointManifest`), not by gossiping with each other.

### `ClprSyncWorkflow` / `ClprSyncWorkflowImpl`

`@Singleton`. Server-side handler.

`openStreamingSync`: rejects the stream with `UNAVAILABLE` when CLPR is disabled; otherwise returns a new
`ClprStreamingSyncSession`. The session drives the server side of the two-phase exchange: it answers the peer's
`ClprBundleRequest` with its own request and a bundle built from the latest **immutable** state, hands every bundle
the peer sends to `ClprBundleSubmitter.submitBundle(...)`, and replies to each non-terminal message until either
side has nothing left to send.

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
- Call `ClprSynchronizer.synchronize(...)` (bound to `ClprStreamingSynchronizer`), which opens one
streaming `sync` call via `ClprEndpointClient.sync(...)`. On timeout (`clpr.syncTimeoutSeconds`) or error,
apply circuit-breaker / retry policy (`clpr.retryInitialDelayMs`,
`clpr.retryMaxDelayMs`, `clpr.retryMaxAttempts`,
`clpr.circuitBreakerCooldownSeconds`); decay peer reputation
(`clpr.reputationDecaySeconds`). Open circuit breakers remove peers from the candidate
set only when `clpr.syncPeerExclusionEnabled=true`; the default false setting keeps those
signals observational. Failed syncs still defer the next attempt using capped exponential delay between
`clpr.retryInitialDelayMs` and `clpr.retryMaxDelayMs`, independently of the exclusion flag. Successful syncs
reset that delay, so an unavailable endpoint is retried periodically rather than on every scheduler tick.
- Each bundle the peer streams back is handed to `ClprBundleSubmitter` to ingest the peer's
outbound (i.e. our inbound) messages.

### `ClprEndpointClient`

Outbound gRPC client. Netty + grpc-java `ClientCalls` for the bidirectional-streaming
`proto.ClprEndpointService/sync` RPC. Marshallers are byte-array based — payloads
are pre-serialised `ClprStreamingSyncPayload` bytes — so the client does not need the protobuf service stub
generated. `sync(timeout)` returns a `ClprStreamingSyncCall`; the deadline covers the whole multi-bundle exchange,
not a single message.

Peer destinations must be numeric IPv4 or IPv6 literals with a port in `1..65535`; DNS names and scoped IPv6
addresses are not accepted. The client validates the address before creating a channel and dials the resulting
socket address directly. By default, outbound sync uses ordinary public unicast addresses. The default IPv6 range
is `2000::/3`, excluding IETF special-purpose `2001::/23`, documentation `2001:db8::/32` and `3fff::/20`, and
6to4 `2002::/16`. A node that intentionally syncs over other unicast ranges, including local or private networks,
lists those ranges in the node-local `clpr.additionalPeerEndpointCidrs` setting. For example:

```properties
clpr.additionalPeerEndpointCidrs=127.0.0.1/32,10.0.0.0/24
```

The setting is empty by default and does not alter the peer's stored manifest. Choose ranges that match the
deployment; unspecified and multicast destinations remain invalid. Endpoint validation applies to both TLS and
plaintext clients, independently of `clpr.syncPeerExclusionEnabled`.
On the next client lookup after this setting changes, the cache retires all existing transports before validating
the requested destination and creating a client under the new setting.

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
- **Transport.** `sync` is served on the same listeners as HAPI (`grpc.port` plaintext,
  `grpc.tlsPort` TLS); there is no dedicated CLPR port and no mutual TLS. A node whose HAPI TLS
  certificate and key (`netty.tlsCrt.path` / `netty.tlsKey.path`) exist advertises `grpc.tlsPort`
  and that certificate (DER) in its `ClprEndpoint.tls_certificate` (`ClprHapiTlsEndpoint`);
  otherwise it advertises its address-book HAPI port with an empty `tls_certificate`. Peers dial
  over TLS iff `tls_certificate` is non-empty, and **do not verify** the certificate or hostname —
  TLS gives confidentiality only; authenticity comes from the state proofs in the payload.

## Spec-step → code mapping cheat sheet

|           Spec            |                                    Code                                    |
|---------------------------|----------------------------------------------------------------------------|
| §1.5 sync RPC             | `ClprStreamingSyncMethod` → `ClprStreamingSyncSession`                     |
| §1.5 endpoint signature   | platform event-level signature; `ClprBundleSubmitter` empty `SignatureMap` |
| §1.6 misbehaviour (local) | `InboundSyncThrottle` shun list, gated by `clpr.syncPeerExclusionEnabled`  |
| §4.2 bundle verification  | `ClprSubmitBundleHandler` (consensus path)                                 |
| §5.2 endpoint manifests   | `ClprChannelManager` dial targets from `Channel.endpoint_manifest`         |
| §6.2 lifecycle hooks      | `ClprChannelLifecycle` ↔ `ClprChannelManager`                              |
