# CLPR Configuration, Fees & Slashing (Hiero)

> Prereq: `clpr-service-spec.md` §4.6 (Slashing Decision), §7 (Configuration Parameters).
> This doc covers concrete Hiero values: `ClprConfig` keys (with defaults), the slashing
> math in `ClprSlashingUtils`, and the fee-calculator wiring.

## `ClprConfig` (`com.hedera.node.config.data.ClprConfig`)

`@ConfigData("clpr")` — every key below is `clpr.<name>`. Keys with `@NetworkProperty`
are network-wide (in genesis bootstrap) and need a `network update` to change at runtime;
others are node-local.

### Top-level

|        Key        |     Default      | Network? |                                                 Purpose                                                  |
|-------------------|------------------|----------|----------------------------------------------------------------------------------------------------------|
| `enabled`         | `false`          | yes      | Master flag. When `false`, CLPR APIs are inert and CLPR system contracts behave as nonexistent accounts. |
| `chainId`         | `hiero:localnet` | yes      | CAIP-2 chain ID seeded into `LEDGER_CONFIGURATION` at genesis.                                           |
| `protocolVersion` | `1`              | yes      | CLPR protocol version seeded into `LEDGER_CONFIGURATION`.                                                |

### Stake / slashing economics (consumed by `ClprSlashingUtils`)

|            Key            |            Default            | Network? |                                  Purpose                                   |
|---------------------------|-------------------------------|----------|----------------------------------------------------------------------------|
| `minLockedStake`          | `100 000 000` tinybars        | yes      | Required stake for `completeConnector`; transferred to `stakingAccount`.   |
| `stakingAccount`          | `0.0.803` (account num `803`) | yes      | Holds locked connector stake; created at genesis/upgrade if CLPR enabled.  |
| `slashBasePenalty`        | `10 000 000` tinybars         | yes      | Base slash on first offence.                                               |
| `slashMultiplier`         | `2`                           | yes      | Geometric escalation factor per repeat offence.                            |
| `slashBanThreshold`       | `5`                           | yes      | Cumulative offences after which a connector is banned.                     |
| `endpointPenaltyTinybars` | `5 000 000`                   | yes      | Penalty paid by source connector to the submitting endpoint per spec §4.6. |
| `progressRewardTinycents` | `100 000 000` (1¢)            | yes      | Reward `Y` per bundle item that makes progress, billed to connectors.      |

### Sync orchestration (CLPR-4.3, used by `ClprChannelManager` / `ClprEndpointClient`)

|               Key               | Default  | Network? |                          Purpose                           |
|---------------------------------|----------|----------|------------------------------------------------------------|
| `maxConcurrentSyncs`            | `4`      | no       | Sync slots; handed out to ready channels in FIFO order.    |
| `syncTimeoutSeconds`            | `15`     | no       | Deadline for a whole outbound sync exchange.               |
| `syncReadTimeoutSeconds`        | `5`      | no       | Max wait for any one write or read in an exchange.         |
| `reputationDecaySeconds`        | `300`    | no       | Half-life of peer reputation.                              |
| `retryInitialDelayMs`           | `1 000`  | no       | First per-channel backoff after a failed sync.             |
| `retryMaxDelayMs`               | `30 000` | no       | Cap on the per-channel backoff, which doubles per failure. |
| `retryMaxAttempts`              | `5`      | no       | Max retries before circuit-break.                          |
| `circuitBreakerCooldownSeconds` | `120`    | no       | Cooldown after circuit opens.                              |

### Inbound misbehaviour (CLPR-4.6, used by `InboundSyncThrottle`)

|           Key           | Default | Network? |                   Purpose                   |
|-------------------------|---------|----------|---------------------------------------------|
| `maxInboundSyncsPerSec` | `10`    | no       | Sliding-window rate limit per peer.         |
| `shunDurationSeconds`   | `60`    | no       | Temporary shun length on rate-limit breach. |

### Queue monopolisation (CLPR-3.5, used by `ClprServiceApiImpl`)

|           Key            | Default | Network? |                          Purpose                          |
|--------------------------|---------|----------|-----------------------------------------------------------|
| `connectorQueueQuotaPct` | `50`    | yes      | Max % of total queue depth a single connector may occupy. |

Each sync runs on its own virtual thread; one timer thread fires the per-channel ticks. Circuit breakers and
reputation are tracked per `(channel, host:port)`, so failures through one channel's manifest can't exclude another
channel's peer. Only an exchange that moves a bundle counts as a success; one that moves nothing is neutral, and a peer
that sends more bundle-less messages than the protocol allows fails the sync.

### Operator controls (node-local)

|          Key          | Default | Network? |                                            Purpose                                            |
|-----------------------|---------|----------|-----------------------------------------------------------------------------------------------|
| `deniedChannelIds`    | empty   | no       | Channel ids (hex) this node neither syncs nor serves (inbound syncs get `PERMISSION_DENIED`). |
| `deniedPeerEndpoints` | empty   | no       | Peer endpoints this node never dials: an IP (any port) or `ip:port` (`[ip]:port` for IPv6).   |

Metrics (category `clpr`): `channelsActivated`, `syncsQueued` (a ready channel waited for a slot), `syncFailures`,
and gauges `syncSlotsBusy` and `syncsWaiting`.

### Channel limit

|      Key      | Default  | Network? |                                                                     Purpose                                                                      |
|---------------|----------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `maxChannels` | `10 000` | yes      | Max channels (closed included). `ClprRegisterChannel` and `ClprCompleteChannel` then fail with `MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED`. |

### Verifier output limits

A Channel's verifier is chosen by whoever completes the Channel, so everything it returns is untrusted.

The first upgrade from software before 0.80 also caps existing ledger configuration at 100 messages per bundle,
1,000 queued messages, 4,096 payload bytes, and 10 peer endpoints, preserving tighter custom limits. The startup
migration advances the configuration's logical timestamp by one nanosecond so channels propagate the updated
configuration. Later restarts and upgrades preserve admin changes.

|              Key              | Default  | Network? |                                                    Purpose                                                    |
|-------------------------------|----------|----------|---------------------------------------------------------------------------------------------------------------|
| `allowNonPublicPeerEndpoints` | `false`  | yes      | Allow loopback, private, and other non-public peer endpoint addresses. On only in `configuration/dev`.        |
| `maxChannelStateBytes`        | `98 304` | yes      | Max serialized `ClprChannel` record (trust anchor + endpoint manifest + peer config), checked on every write. |

Peer endpoints must be canonical IP literals with ports in 1–65535, and (unless `allowNonPublicPeerEndpoints`)
publicly routable; see `ClprPeerEndpointPolicy`. `ClprCompleteChannel` rejects a violating manifest or oversized
record with `CLPR_VERIFIER_CONFIG_FAILED`; `ClprSubmitBundle` rejects it with `CLPR_BUNDLE_VERIFICATION_FAILED`,
charging the submitter only the transaction fee. The sync orchestrator also skips disallowed endpoints already in
state.

### Dispatch gas budgets (CLPR-4.4 / 5.3)

|        Key         |   Default   | Network? |                                  Purpose                                  |
|--------------------|-------------|----------|---------------------------------------------------------------------------|
| `verifierGasLimit` | `2 000 000` | yes      | Gas for `ClprVerifier.verifyBundle` / `verifyConfig` (`EvmClprVerifier`). |

> `ClprCompleteChannel` and `ClprSubmitBundle` each reserve `verifierGasLimit` from the network gas throttle
> (`contracts.maxGasPerSec` / `maxGasPerSecBackend`), the same way a contract call reserves its gas limit, so verifier
> execution can't run unthrottled.
>
> The dispatched verifier call must carry at least the EIP-7623 calldata floor for its input, about 40 gas per byte
> of (mostly non-zero) proof data. A Hiero state proof carries a ~13.7 KB TSS signature, so a `verifyConfig` call
> needs ~0.6M gas, a `verifyConfig` with an endpoint manifest proof ~1.2M, and a `verifyBundle` ~0.6M plus ~60K per
> message.
>
> Application-message dispatch gas is **not** a node-config value. `ClprSubmitBundleHandler`
> caps each application callback (`onClprMessage` / `onClprResponse`) at the per-Channel
> `ClprThrottles.maxGasPerMessage` throttle read from `LEDGER_CONFIGURATION` (spec §1.1, §6.0).
> The old `appDispatchGasLimit` config field was removed (#129).
>
> `clpr.enabled` defaults to `false`, so a fresh node keeps CLPR dormant. Set
> `clpr.enabled=true` explicitly to activate it after the `LEDGER_CONFIGURATION`
> singleton exists.

## Throttles inside `ClprLedgerConfiguration`

Distinct from `ClprConfig` — these are *protocol-level* limits stored in state, set by
the genesis migration and editable only via `CLPR_UPDATE_LEDGER_CONFIGURATION`. See
[`state-and-protobufs.md`](state-and-protobufs.md) for default values. They are spec
§7 fields (`max_messages_per_bundle`, `max_message_payload_bytes`, etc.) and are enforced
in `ClprServiceApiImpl.sendMessage` and `ClprSubmitBundleHandler`.

> Two throttle layers: `ClprConfig` for node behaviour (sync rate, retries),
> `ClprLedgerConfiguration.throttles` for protocol-observable bundle/queue/payload
> limits. Don't conflate.

## Fees: `ClprFeeCalculator`

(`hedera-clpr-service-impl/.../impl/calculator/ClprFeeCalculator.java`)

A single record parameterised by `HederaFunctionality` + `TransactionBody.DataOneOfType`.
For each CLPR functionality, `ClprServiceImpl.serviceFeeCalculators()` produces one
calculator instance. Each looks up its base fee from the platform `FeeSchedule` via
`lookupServiceFee` — no CLPR-specific fee maths beyond reading the schedule.

`FacilityInitModule.provideClprServiceFeeCalculators` exposes the set into the standard
fee plumbing, so CLPR fees ride the normal fee-schedule path and are tunable through the
fee schedule update mechanism, not config.

## Slashing: `ClprSlashingUtils`

Pure function `slashConnector(connector, offences, config) → SlashResult`.

Inputs from `ClprConfig`: `slashBasePenalty`, `slashMultiplier`, `slashBanThreshold`,
`endpointPenaltyTinybars`.

Algorithm (matches spec §4.6):

```
penalty = slashBasePenalty × (slashMultiplier ^ offences)
banned  = (offences + 1) ≥ slashBanThreshold
endpoint_payout = min(penalty, stakingAccount balance)
new_locked_stake = max(0, connector.locked_stake − penalty)
```

Returned `SlashResult(updatedConnector, penaltyAmount, banned)` is consumed by
`ClprSubmitBundleHandler`, which also credits the submitting endpoint with
`endpoint_payout`. If `banned`, the connector record stays in state but
`deregisterConnector` is blocked while banned (per `ClprDeregisterConnectorHandler`).

### Which `ClprMessageReplyStatus` triggers a slash?

(Spec §4.6, Hiero implementation):
- `APPLICATION_ERROR` — slashes destination connector (target was unsuitable).
- `CONNECTOR_NOT_FOUND` — slashes source connector (lied about their own ID).
- `CONNECTOR_UNDERFUNDED` — slashes source connector.
- `SUCCESS`, `CHANNEL_CLOSED`, `REDACTED` — no slash.

The endpoint payout always goes to the *submitting* endpoint, regardless of which side
is at fault. See `ClprSubmitBundleHandler` for the exact branches.

## Success-path economics

This subsection describes the normal (non-slash) revenue flow for a successful inbound
bundle dispatch. Readers often focus on slashing because the doc is titled
"config and slashing," but steady-state revenue matters more for understanding connector
and endpoint incentives.

### Bundle compensation

Connectors pay for the bundles that deliver their messages, and the submitting endpoint
is reimbursed, per clpr-spec PR #65 §3.2 (implemented by `ClprBundleBilling`):

1. **Callback gas, per message.** Before each `onClprMessage` / `onClprResponse`
   dispatch, the worst-case gas cost (`max_gas_per_message` at the network gas price,
   including congestion) is escrowed from the connector's account to the submitter. Afterwards the unused part is
   returned and the used part (`gasUsed × gasPrice`) is charged to the network fee
   accounts, since on Hedera the submitter does not pay for callback gas.
2. **Shared cost, at settlement.** After every message is handled, each connector pays
   the submitter its share of the bundle's cost, in proportion to its message count:

   ```
   B_i = (M_i / M) × (Tm + X × Y)
   ```

   where `Tm` is the `ClprSubmitBundle` fee the submitter was charged, `X` the number of
   bundle items that made progress, and `Y` is `progressRewardTinycents`.

A bundle with no billable messages is paid for entirely by its submitter. When every
connector pays in full, the submitter nets `X × Y`. See E-10 in
[`clpr-hiero-spec.md`](clpr-hiero-spec.md) for the details.

### Slashing payouts (non-success path)

On a slash event, the endpoint payout comes from the slashed stake (not from a
separate charge). See the "Slashing" section below for the formula. The submitting
endpoint receives the payout regardless of which side was at fault.

## Tuning checklist

When you change CLPR economics:

1. Decide whether the lever is per-node (`ClprConfig` non-network) or
   network-consensus (`@NetworkProperty` or `ClprThrottles` in
   `LEDGER_CONFIGURATION`).
2. For network-consensus levers, change requires either an upgrade with revised genesis
   defaults *or* a `CLPR_UPDATE_LEDGER_CONFIGURATION` admin tx (for throttles only —
   stake/slashing live in `ClprConfig`).
3. Update unit tests in `ClprSlashingUtils` test class and any handler tests that pin
   default values.
