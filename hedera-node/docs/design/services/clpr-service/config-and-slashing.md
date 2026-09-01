# CLPR Configuration, Fees & Slashing (Hiero)

> Prereq: `clpr-service-spec.md` §4.6 (Slashing Decision), §7 (Configuration Parameters).
> This doc covers concrete Hiero values: `ClprConfig` keys (with defaults), the slashing
> math in `ClprSlashingUtils`, and the fee-calculator wiring.

## `ClprConfig` (`com.hedera.node.config.data.ClprConfig`)

`@ConfigData("clpr")` — every key below is `clpr.<name>`. Keys with `@NetworkProperty`
are network-wide (in genesis bootstrap) and need a `network update` to change at runtime;
others are node-local.

### Top-level

|        Key        |     Default      | Network? |                                                  Purpose                                                  |
|-------------------|------------------|----------|-----------------------------------------------------------------------------------------------------------|
| `enabled`         | `true`           | yes      | Master flag. When `false`, CLPR APIs are inert and CLPR-related precompiles halt with `CLPR_NOT_ENABLED`. |
| `chainId`         | `hiero:localnet` | yes      | CAIP-2 chain ID seeded into `LEDGER_CONFIGURATION` at genesis.                                            |
| `protocolVersion` | `1`              | yes      | CLPR protocol version seeded into `LEDGER_CONFIGURATION`.                                                 |

### Stake / slashing economics (consumed by `ClprSlashingUtils`)

|            Key            |            Default            | Network? |                                  Purpose                                   |
|---------------------------|-------------------------------|----------|----------------------------------------------------------------------------|
| `minLockedStake`          | `100 000 000` tinybars        | yes      | Required stake for `completeConnector`; transferred to `stakingAccount`.   |
| `stakingAccount`          | `0.0.803` (account num `803`) | yes      | Custodian for locked connector stake.                                      |
| `slashBasePenalty`        | `10 000 000` tinybars         | yes      | Base slash on first offence.                                               |
| `slashMultiplier`         | `2`                           | yes      | Geometric escalation factor per repeat offence.                            |
| `slashBanThreshold`       | `5`                           | yes      | Cumulative offences after which a connector is banned.                     |
| `endpointPenaltyTinybars` | `5 000 000`                   | yes      | Penalty paid by source connector to the submitting endpoint per spec §4.6. |
| `messageExecutionCost`    | `1 000 000`                   | yes      | Notional cost component used in fee/refund maths.                          |
| `endpointMarginPercent`   | `10`                          | yes      | Endpoint margin (% of recovered cost) per spec §3.3.3.                     |

### Sync orchestration (CLPR-4.3, used by `ClprChannelManager` / `ClprEndpointClient`)

|               Key               | Default  | Network? |                  Purpose                   |
|---------------------------------|----------|----------|--------------------------------------------|
| `maxConcurrentSyncs`            | `4`      | no       | Semaphore cap on in-flight outbound syncs. |
| `syncTimeoutSeconds`            | `30`     | no       | gRPC deadline for outbound sync.           |
| `reputationDecaySeconds`        | `300`    | no       | Half-life of peer reputation.              |
| `retryInitialDelayMs`           | `1 000`  | no       | Backoff base.                              |
| `retryMaxDelayMs`               | `30 000` | no       | Backoff cap.                               |
| `retryMaxAttempts`              | `5`      | no       | Max retries before circuit-break.          |
| `circuitBreakerCooldownSeconds` | `120`    | no       | Cooldown after circuit opens.              |

### Inbound misbehaviour (CLPR-4.6, used by `InboundSyncThrottle`)

|           Key           | Default | Network? |                   Purpose                   |
|-------------------------|---------|----------|---------------------------------------------|
| `maxInboundSyncsPerSec` | `10`    | no       | Sliding-window rate limit per peer.         |
| `shunDurationSeconds`   | `60`    | no       | Temporary shun length on rate-limit breach. |

### Queue monopolisation (CLPR-3.5, used by `ClprServiceApiImpl`)

|           Key            | Default | Network? |                          Purpose                          |
|--------------------------|---------|----------|-----------------------------------------------------------|
| `connectorQueueQuotaPct` | `50`    | yes      | Max % of total queue depth a single connector may occupy. |

### Dispatch gas budgets (CLPR-4.4 / 5.3)

|        Key         |  Default  | Network? |                                  Purpose                                  |
|--------------------|-----------|----------|---------------------------------------------------------------------------|
| `verifierGasLimit` | `300 000` | yes      | Gas for `ClprVerifier.verifyBundle` / `verifyConfig` (`EvmClprVerifier`). |

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
`endpointPenaltyTinybars`, `endpointMarginPercent`.

Algorithm (matches spec §4.6):

```
penalty = slashBasePenalty × (slashMultiplier ^ offences)
banned  = (offences + 1) ≥ slashBanThreshold
endpoint_payout = min(endpointPenaltyTinybars,
                       penalty × endpointMarginPercent / 100)
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

### Per-message charge on successful dispatch

For every inbound data message that dispatches successfully (i.e., the application
contract returns without reverting and the reply status is `SUCCESS`), the following
transfer occurs:

1. **Connector is charged** — the receiving connector's contract account is debited:

   ```
   charge = messageExecutionCost + (messageExecutionCost * endpointMarginPercent / 100)
   ```
2. **Submitting endpoint is paid** — the entire `charge` is credited to the Hiero
   consensus node that submitted the `ClprSubmitBundle` transaction. There is no
   central treasury; the submitter receives the full amount.

The submitting endpoint is identified by `endpoint_node_id` on the transaction body,
resolved through `ReadableNodeStore`. The node's account receives the payment.

### Why the submitter receives the full charge

The economics are designed so that submitting nodes have a direct financial incentive
to relay bundles promptly and honestly. A node that delays or censors bundles gives up
the execution payment to whichever node eventually submits the same bundle. There is no
"protocol fee" extracted to a treasury — the design assumes that enough competition
among nodes will keep margins honest.

### Planned gas-based model (E-10)

The current flat `messageExecutionCost` is a placeholder. The planned model charges
the connector the *actual gas consumed* by the application dispatch plus margin:

```
charge = gasUsed * gasPriceTinybars * (1 + endpointMarginPercent / 100)
```

The same "submitter receives all" rule applies. Under the gas-based model, high-gas
messages cost connectors more and compensate submitters more, aligning costs with
actual compute. See E-10 in [`clpr-hiero-spec.md`](clpr-hiero-spec.md) for the full
rationale and implementation notes.

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
