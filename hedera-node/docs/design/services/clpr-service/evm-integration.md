# CLPR ↔ EVM Integration (Hiero)

> Prereq: `clpr-service-spec.md` §3.2 (Connector Authorization Interface), §6.4
> (`sendMessage`). This doc describes how the Hiero EVM exposes outbound CLPR sending and
> channel reads via the system contract at `0x16e`.

The EVM only originates **outbound** CLPR traffic. Inbound bundles arrive via the gRPC
sync RPC (see [`sync-workflow.md`](sync-workflow.md)) and are dispatched to user contracts
*through* the EVM, but those calls are made by `ClprSubmitBundleHandler`, not the system
contract.

## System contract `0x16e`

|        Item        |                                         Value                                         |
|--------------------|---------------------------------------------------------------------------------------|
| Class              | `ClprSystemContract` (`hedera-smart-contract-service-impl/.../exec/systemcontracts/`) |
| Address constant   | `CLPR_EVM_ADDRESS = 0x16e`                                                            |
| Name               | `CLPR`                                                                                |
| Disabled behaviour | When `clpr.enabled = false`, halts with `CLPR_NOT_ENABLED`.                           |

### Method registry

Translators are bound in `ClprTranslatorsModule` (Dagger `@Named("ClprTranslators")`):

|                    Translator                    |                                                                                                                           EVM Solidity-style signature                                                                                                                           |          Backed by           |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------|
| `SendMessageTranslator` (in `clpr/sendmessage/`) | `sendMessage(bytes32 channelId, bytes connectorId, bytes targetApplication, bytes messageData) → uint64 messageId` (note: `connectorId` is the 32-byte derived ID per spec §2.2; the ABI uses `bytes` rather than `bytes32` — see [drift-from-spec.md §2.6](drift-from-spec.md)) | `ClprServiceApi.sendMessage` |
| `GetChannelTranslator` (in `clpr/getchannel/`)   | `getChannel(bytes32 channelId) → (...)`                                                                                                                                                                                                                                          | `ReadableChannelStore`       |

Read-only `getChannel` is a static call. `sendMessage` mutates state — see flow below.

### `ClprCallAttempt` and `ClprCallFactory`

Same pattern as the HTS / HAS system contracts. `AbstractCallAttempt<ClprCallAttempt>`,
no redirect support, `systemContractKind() = CLPR`. `ClprCallFactory` decodes calldata and
selects the translator.

## `sendMessage` flow

`SendMessageCall` (in `…clpr/sendmessage/`):

1. **Connector authorization — KNOWN GAP.** The spec (§3.2 / §4.3 step 3) requires a
   per-message sub-call to `IClprConnectorAuth.authorizeMessage` on the connector's
   contract. **Hiero does not currently make this call** — `SendMessageCall.execute`
   (lines 64-88) only verifies that the connector contract exists, deferring per-message
   auth to a future ticket. See [drift-from-spec.md §2.3](drift-from-spec.md) (interop
   blocker).
2. **Dispatch into native code:** invokes `ClprServiceApi.sendMessage(channelId,
   connectorId, targetApplication, sender, messageData)`. The `sender` parameter is the
   originating EVM address — it is **stamped server-side** from the EVM frame's
   immediate caller (`msg.sender` of the precompile call), not part of the user-supplied
   calldata. The wire-level `ClprMessageSender` SPI in `hedera-clpr-service` reflects this.
3. Returns the assigned `uint64 messageId`.

### What `ClprServiceApiImpl.sendMessage` does

(`hedera-clpr-service-impl/.../impl/ClprServiceApiImpl.java`)

1. Lookup channel; require status `ACTIVE`. Throws if missing/wrong status.
2. **Lazy config propagation** (spec §1.3, §4.2 step 5c, §4.3 step 1a): if the
   channel's `lastConfigTimestamp` is older than the singleton
   `ClprLedgerConfiguration.timestamp`, prepend a `ClprControlMessage.configUpdate` to the
   queue and bump the channel's `lastConfigTimestamp`.
3. Lookup connector by `(channelId, connectorId)`; require not banned. (The Java
   parameter is currently named `connectorContract` — historical leftover from before
   the spec's connector-derivation model; carries the 32-byte derived `connector_id`.)
4. Validate `messageData.size ≤ ClprThrottles.max_message_payload_bytes`.
5. Validate queue depth ≤ `ClprThrottles.max_queue_depth`.
6. Per-connector quota: count messages already enqueued for this connector across the
   queue; reject if > `ClprConfig.connectorQueueQuotaPct` of total queue depth.
7. Build `ClprMessage` payload, compute new `sent_running_hash` via
   `ClprHashUtils.computeRunningHash`, append to `MESSAGE_QUEUE`.
8. Increment channel's `next_message_id`, update `sent_running_hash`. Return assigned id.

`ClprServiceApiImpl` is constructed per-transaction by `ClprServiceApiProvider`
(`CLPR_SERVICE_API_PROVIDER`) from the `WritableStates` view of CLPR state.

## SPI types in `hedera-clpr-service`

- `ClprServiceApi` — the only public API surface for native callers (today: just the
  EVM).
- `ClprMessageSender` — Java mirror of the `sendMessage` Solidity selector; consumed by
  the translator to decode/encode calldata.
- `ClprChannelLifecycle` — implemented by `ClprChannelManager` (or no-op in
  standalone). Called from `ClprCompleteChannelHandler` /
  `ClprCloseChannelHandler`.

## Wiring summary

- `FacilityInitModule.provideClprServiceApi()` registers `CLPR_SERVICE_API_PROVIDER`.
- `HederaNativeOperations` exposes `ReadableChannelStore` to the EVM scope so
  `getChannel` can be served from a static call without mutating state.
- The translator set is named `ClprTranslators` in the smart-contract module, joined into
  the global translator list at the standard system-contract dispatch point.

## What the EVM cannot do

- Inbound dispatch into user contracts (CLPR data messages reaching applications) is
  driven by `ClprSubmitBundleHandler` — *not* via the `0x16e` system contract.
- There is no EVM API to register a channel, register a connector, or close a
  channel. Those are HAPI-only ([`handlers.md`](handlers.md)). User contracts must
  already-registered channels/connectors to call `sendMessage`.
