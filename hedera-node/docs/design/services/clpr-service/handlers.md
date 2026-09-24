# CLPR HAPI Transaction Handlers (Hiero)

> Prereq: read `clpr-service-spec.md` §6 (Pseudo-API Reference) for the protocol-level
> semantics of each operation. This document covers the *Hiero handlers* — file
> locations, validation contracts, state changes, lifecycle hooks, and admin gating.

All handlers live at:
`hedera-node/hedera-clpr-service-impl/src/main/java/com/hedera/node/app/service/clpr/impl/handlers/`

## Common pattern: `AbstractClprHandler`

Every handler extends `AbstractClprHandler` and overrides `doHandle`, **not** `handle`. The
abstract class enforces:

1. **Feature flag:** ingest logs an error and rejects CLPR transaction submissions with
   `CLPR_NOT_ENABLED` when `clpr.enabled = false`. The consensus-handler guard returns
   the same response if a transaction bypasses ingest.
2. **Channel lookup helpers:** `requireActiveChannel(...)` returns the
   `ClprChannel` or throws `HandleException` (e.g. `INVALID_CLPR_CHANNEL_ID`,
   `CLPR_CHANNEL_NOT_ACTIVE`).
3. **Admin gating** for admin-only handlers (`closeChannel`, `redactMessage`,
   `updateLedgerConfiguration`) is performed by the platform's `PrivilegesVerifier`
   (`checkClprAdmin`) — payer must be **treasury or systemAdmin**. There is no separate
   "CLPR admin key" stored in state or config. Handlers do not perform their own
   signature checks for admin gating; the framework rejects unauthorized payers with
   `AUTHORIZATION_FAILED` (or `NOT_SUPPORTED` if the throttle layer hasn't been wired
   for the new functionality).

Subclasses implement `pureChecks`, `preHandle`, and `doHandle` (per
`TransactionHandler` contract).

## Aggregator: `ClprHandlers`

`@Singleton` Dagger-injectable record (10 handlers + 1 query handler). Wired into the
HAPI dispatch table for the CLPR `HederaFunctionality` values. Add new handlers here.

## Handler-by-handler

For each: trigger functionality → spec §6 method → key validation → state writes →
side-effects.

### `ClprRegisterChannelHandler` — commit phase

- Functionality: `CLPR_REGISTER_CHANNEL`. Spec §5.1.2 / §6.2 (`registerChannel`).
- Body: `ClprRegisterChannelTransactionBody`.
- Validates: payer pays the registration fee; commitment hash well-formed.
- Writes: `PENDING_COMMITMENTS[commitment_hash] = registrant_account`.
- No channel record is created in this phase.

### `ClprCompleteChannelHandler` — reveal phase

- Functionality: `CLPR_COMPLETE_CHANNEL`. Spec §5.1.3 / §6.2 (`completeChannel`).
- Validates: preimage matches the prior `PENDING_COMMITMENTS` entry; signer key matches
  preimage; verifier contract resolves; chain ID, throttles, etc. on the candidate config
  pass `verifyConfig`. Re-derives `channelId`.
- Writes: removes pending commitment; `CHANNELS[channelId] = ClprChannel{ status: ACTIVE, … }`.
- **Lifecycle hook:** calls `ClprChannelLifecycle.onChannelActivated(channelId)`
  → `ClprChannelManager` adds the channel to its sync schedule. (In the standalone
  executor module the lifecycle is a no-op.)

### `ClprCloseChannelHandler` — admin close

- Functionality: `CLPR_CLOSE_CHANNEL`. Spec §6.2 (`closeChannel`).
- Admin-gated.
- Status transitions to `CLOSING` (then `DRAINED`/`CLOSED` per spec §2.1.1; depending on
  queue state at handle time).
- **Lifecycle hook:** `onChannelClosed(channelId)` → orchestrator removes from
  sync schedule.

### `ClprRegisterConnectorHandler` / `ClprCompleteConnectorHandler`

- Functionalities: `CLPR_REGISTER_CONNECTOR`, `CLPR_COMPLETE_CONNECTOR`. Spec §6.3.
- Same commit-reveal shape as channel registration.
- Reveal verifies signature `ECDSA(keccak256(connector_id ‖ 0x…16e))` (Hiero-specific
  binding from spec §6.3 callout).
- Stake: `complete` requires the connector's `min_locked_stake` (`ClprConfig.minLockedStake`)
  to be transferred from payer to `ClprConfig.stakingAccount` via the transaction's
  crypto-transfer list.
- Writes: `CONNECTORS[ClprConnectorKey] = ClprConnector{...}`.

### `ClprDeregisterConnectorHandler`

- Functionality: `CLPR_DEREGISTER_CONNECTOR`. Spec §6.3.
- Removes connector record; releases locked stake back to the connector owner via system
  transfer.
- **Known gap (see [drift-from-spec.md §2.5](drift-from-spec.md)):** spec §6.3 requires
  "MUST NOT deregister if the Connector has unresolved in-flight messages." Hiero does
  not currently enforce this — deregister succeeds even with messages still queued for
  the connector.

### `ClprSubmitBundleHandler`

- Functionality: `CLPR_SUBMIT_BUNDLE`. Spec §4.2 (Bundle Verification Algorithm).
- Source of bundles: the `ClprBundleSubmitter` running on the same node — the platform's
  event-level signature substitutes for an explicit `SignatureMap` (which is empty);
  `CLPR_SUBMIT_BUNDLE` is therefore in `networkAdmin.nodeTransactionsAllowList`.
- Validation pipeline (each step is from spec §4.2):
  1. Lookup channel; check it is `ACTIVE`/`PAUSED`. If `DRAINED`/`CLOSED`, reject.
  2. Call `ClprVerifier.verifyBundle` (see [`verifier.md`](verifier.md)) → `ClprBundleContent`.
  3. Check monotonic & contiguous message IDs vs. channel's `acked_message_id`.
  4. Recompute received running hash with `ClprHashUtils.computeRunningHash`; compare.
  5. Apply control messages (only `ConfigUpdate` today) before data messages.
  6. Dispatch each data message to its `targetApplication` via a child contract call;
     bookkeep replies (`ClprMessageReply`).
- State writes: channel's `received_running_hash`, `last_received_message_id`; per-data-
  message replies stored back into the queue (replies on the source-side queue).
- Slashing/reimbursement: on misbehaviour, calls `ClprSlashingUtils.slashConnector(...)`
  and credits the submitting endpoint per `ClprConfig.endpointMarginPercent`. See
  [`config-and-slashing.md`](config-and-slashing.md).
- On running-hash or ordering mismatch: channel moves to `PAUSED` (spec §4.5).

> **Known gap (D-11 / C-1):** the inbound dispatch loop in `ClprSubmitBundleHandler`
> does not currently emit a `CLPR_BUNDLE_REDACTED` reply when it encounters a bundle
> slot with no one-of set (the on-wire shape of a redacted message). Per spec §4.4 /
> §4.5, a redacted slot MUST produce a `ClprMessageReply{ status = REDACTED }` so
> that the source's response-ordering invariant is not broken. Until C-1 is
> implemented and this note is removed, a bundle containing a redacted slot will
> silently advance `receivedMessageId` without enqueuing the required reply, which
> will eventually PAUSE the channel. See C-1 in
> [DRIFT-REVIEW-2026-05.md](DRIFT-REVIEW-2026-05.md) for the fix plan.

### `ClprRedactMessageHandler`

- Functionality: `CLPR_REDACT_MESSAGE`. Spec §4.4 / §6.6.
- Admin-gated.
- Replaces a queued message's payload with the `REDACTED` placeholder while preserving
  the running-hash slot. Cannot redact already-acked messages.

### `ClprUpdateLedgerConfigurationHandler`

- Functionality: `CLPR_UPDATE_LEDGER_CONFIGURATION`. Spec §6.1.
- Admin-gated.
- Writes the singleton `LEDGER_CONFIGURATION`. Bumps `timestamp`. Only path to add/edit
  `endpoints`. Throttle changes propagate to existing channels **lazily** via the
  next `sendMessage` (see `ClprServiceApiImpl` / [`evm-integration.md`](evm-integration.md)).

### `ClprGetLedgerConfigurationHandler` (query)

- Returns the current `LEDGER_CONFIGURATION` singleton. Free? See
  [`config-and-slashing.md`](config-and-slashing.md) and `ClprFeeCalculator`.

## Adding a new CLPR handler

1. Define the proto body under `services/`.
2. Add the `HederaFunctionality` enum value and a new entry to `ClprService.proto`.
3. Implement a class extending `AbstractClprHandler`.
4. Register in `ClprHandlers` (Dagger).
5. Map the functionality → `ClprService.NAME` in `ServiceScopeLookup`.
6. Add a `ClprFeeCalculator` instance in `ClprServiceImpl.serviceFeeCalculators()`.
7. If the handler is node-submitted (analogous to `submitBundle`), add the functionality
   to `networkAdmin.nodeTransactionsAllowList`.
8. Add a HAPI throttle bucket entry for the new functionality in the platform throttle
   definitions (`throttles.json` / `bootstrap.throttleDefsJsonResource`). This is the
   standard platform throttle layer — distinct from the CLPR `ClprThrottles` singleton
   and the app-layer `InboundSyncThrottle`.

## Error codes worth knowing (non-exhaustive)

`CLPR_NOT_ENABLED`, `INVALID_CLPR_CHANNEL_ID`, `CLPR_CHANNEL_NOT_ACTIVE`,
`CLPR_CHANNEL_ALREADY_EXISTS`, `CLPR_CONNECTOR_NOT_FOUND`, `CLPR_CONNECTOR_BANNED`,
`CLPR_BUNDLE_VERIFICATION_FAILED`, `CLPR_RUNNING_HASH_MISMATCH`,
`CLPR_QUEUE_FULL`, `CLPR_PAYLOAD_TOO_LARGE`. Look up exact strings in `ResponseCodeEnum`
proto.
