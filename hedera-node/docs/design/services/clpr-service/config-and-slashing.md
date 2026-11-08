# CLPR Configuration, Fees & Slashing (Hiero)

> Prereq: `clpr-service-spec.md` §4.6 (Slashing Decision), §7 (Configuration Parameters).
> This doc covers concrete Hiero values: `ClprConfig` keys (with defaults), the slashing
> math in `ClprSlashingUtils`, and the fee-calculator wiring.

## `ClprConfig` (`com.hedera.node.config.data.ClprConfig`)

`@ConfigData("clpr")` — every key below is `clpr.<name>`. Keys with `@NetworkProperty`
are network-wide (in genesis bootstrap) and need a `network update` to change at runtime;
others are node-local.

### Top-level

|        Key        | Default | Network? |                                                 Purpose                                                  |
|-------------------|---------|----------|----------------------------------------------------------------------------------------------------------|
| `enabled`         | `false` | yes      | Master flag. When `false`, CLPR APIs are inert and CLPR system contracts behave as nonexistent accounts. |
| `protocolVersion` | `1`     | yes      | CLPR protocol version seeded into `LEDGER_CONFIGURATION`.                                                |

There is no CLPR chain ID property. The CAIP-2 `chain_id` seeded into `LEDGER_CONFIGURATION` at genesis is
derived from the network's HIP-26 EVM chain ID, `contracts.chainId`, as `eip155:<contracts.chainId>`:

|   Network   | `contracts.chainId` | CLPR `chain_id` |
|-------------|---------------------|-----------------|
| mainnet     | `295`               | `eip155:295`    |
| testnet     | `296`               | `eip155:296`    |
| previewnet  | `297`               | `eip155:297`    |
| dev/preprod | `298`               | `eip155:298`    |

### Ledger configuration file (see [Throttles inside `ClprLedgerConfiguration`](#throttles-inside-clprledgerconfiguration))

|               Key                |                  Default                   | Network? |                                        Purpose                                         |
|----------------------------------|--------------------------------------------|----------|----------------------------------------------------------------------------------------|
| `ledgerConfigurationFile`        | `data/config/clprLedgerConfiguration.json` | no       | File whose throttles are seeded into `LEDGER_CONFIGURATION` at genesis.                |
| `upgradeLedgerConfigurationFile` | `clprLedgerConfiguration.json`             | no       | File in `networkAdmin.upgradeSysFilesLoc` whose throttles are applied at each upgrade. |

### Stake / slashing economics (consumed by `ClprSlashingUtils`)

|                 Key                  |            Default            | Network? |                                    Purpose                                    |
|--------------------------------------|-------------------------------|----------|-------------------------------------------------------------------------------|
| `minLockedStake`                     | `3 000 000 000 000` tinybars  | yes      | Required stake for `completeConnector`; transferred to `stakingAccount`.      |
| `stakingAccount`                     | `0.0.803` (account num `803`) | yes      | Holds locked connector stake; created at genesis/upgrade if CLPR enabled.     |
| `slashBasePenalty`                   | `10 000 000 000` tinybars     | yes      | Base slash on first offence; each penalty is paid to the submitting endpoint. |
| `slashMultiplier`                    | `2`                           | yes      | Geometric escalation factor per repeat offence.                               |
| `slashBanThreshold`                  | `5`                           | yes      | Cumulative offences after which a connector is banned.                        |
| `endpointMisbehaviorPenaltyTinybars` | `1 000 000 000` tinybars      | yes      | Charged to the submitting node when its bundle fails verification.            |
| `messageExecutionCost`               | `1 000 000`                   | yes      | Notional cost component used in fee/refund maths.                             |
| `endpointMarginPercent`              | `10`                          | yes      | Endpoint margin (% of recovered cost) per spec §3.3.3.                        |

### Fees (consumed by `SendMessageCall` and `ClprBundleSubmitter`)

|            Key             |          Default           | Network? |                                               Purpose                                                |
|----------------------------|----------------------------|----------|------------------------------------------------------------------------------------------------------|
| `sendMessageFeeTinycents`  | `1 000 000 000` tinycents  | yes      | Price of a `sendMessage` call ($0.10), including its gas; the rest is collected from attached value. |
| `sendMessageIncludedBytes` | `1 024`                    | yes      | Message data bytes covered by that price; each further byte is charged the `STATE_BYTES` rate.       |
| `nodeSubmitBundleMaxFee`   | `100 000 000 000` tinybars | yes      | Maximum fee a node pays to submit an inbound bundle (1,000 ℏ).                                       |

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

Distinct from `ClprConfig` — these are *protocol-level* limits stored in state, changed
only by `CLPR_UPDATE_LEDGER_CONFIGURATION`. They are spec §7 fields
(`max_messages_per_bundle`, `max_message_payload_bytes`, etc.) and are enforced in
`ClprServiceApiImpl.sendMessage` and `ClprSubmitBundleHandler`.

Like `throttles.json` and the fee schedules, they are loaded from a file on disk
(`ClprLedgerConfigurationFile`). The file is a `ClprLedgerConfiguration` in protobuf JSON
that sets **only** `throttles`; the node fixes every other field (`chain_id`,
`protocol_version`, `service_address`, `timestamp`, trust anchors), and a file that sets any
of them is rejected:

```json
{
  "throttles": {
    "maxMessagesPerBundle": 1000,
    "maxMessagePayloadBytes": 65536,
    "maxGasPerMessage": 15000000,
    "maxQueueDepth": 10000,
    "maxSyncBytes": 4194304
  }
}
```

|              |              Throttles              |                    CLPR ledger configuration                     |
|--------------|-------------------------------------|------------------------------------------------------------------|
| Genesis file | `bootstrap.throttleDefsJson.file`   | `clpr.ledgerConfigurationFile`                                   |
| Fallback     | classpath `genesis/throttles.json`  | classpath `clpr/clprLedgerConfiguration.json` (the values above) |
| Upgrade file | `networkAdmin.upgradeThrottlesFile` | `clpr.upgradeLedgerConfigurationFile`                            |
| Applied by   | synthetic `FileUpdate`              | synthetic `ClprUpdateLedgerConfiguration` (payer `0.0.50`)       |

- **Genesis.** The throttles come from `clpr.ledgerConfigurationFile` if it exists and is valid,
  otherwise from the bundled resource. An invalid file (malformed JSON, unknown fields,
  non-throttle fields, or throttles that fail validation) is logged as an error and ignored.
- **Upgrade.** A file in `networkAdmin.upgradeSysFilesLoc` is applied by
  `SystemTransactions.doPostUpgradeSetup`, but only if its throttles differ from the current
  ones: every update sets a new `timestamp`, which queues a `ConfigUpdate` on every Channel.
  It is skipped while CLPR is disabled (and applied at the next upgrade with CLPR enabled),
  and when `LEDGER_CONFIGURATION` does not exist yet (it is then seeded from the same file).
- `max_gas_per_message` is capped at `contracts.maxGasPerTransaction` in both cases, and the
  throttles are validated like `CLPR_UPDATE_LEDGER_CONFIGURATION` validates them.
- The file is node-local. As with `throttles.json`, every node must have an identical file, or
  nodes seed different state and ISS.

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

Besides its base fee, `ClprCompleteChannel` is charged the `STATE_BYTES` extra for its config and
endpoint-manifest proof bytes (first 8,192 free), and `ClprSubmitBundle` the `PROCESSING_BYTES` extra for its
bundle payload. Neither lists the `GAS` extra: their verifier call is billed at the gas it actually uses, through
a payer reservation made before the verifier runs, so a fixed schedule allowance would charge it twice. The
`ClprGetLedgerConfiguration` and `ClprGetEndpointManifest` queries are paid, priced by `ClprQueryFeeCalculator`
(exposed by `FacilityInitModule.provideClprQueryFeeCalculators`) at their base fee plus the `GAS` extra at a fixed
`ClprFeeCalculator.VERIFIER_FEE_GAS` (300,000).

`ClprSubmitBundle` is also exempt from node and network fees (`nodeNetworkFeeExempt`), like the node-submitted
`ClprEndpointPublication`. Its total fee is then the fee proposal's $0.01 + `PROCESSING_BYTES` × bundle bytes, plus
the verifier gas actually used. Without the exemption, the node fee's own `PROCESSING_BYTES` extra and the 9× network fee would charge the
bundle's bytes another 10 times over, so a 4 MiB bundle would cost about $46 instead of about $4.25.

CLPR transactions use the standard fee-charging path, including its existing treatment of failures. There is no
separate failure price in the schedule; handler failures retain the configured base fee and extras. The admin-only
transactions have a $0 service fee, while a privileged payer's fees are waived entirely.

Defaults for connector bonds and penalties (`minLockedStake`, `slashBasePenalty`,
`endpointMisbehaviorPenaltyTinybars`) are in tinybars, sized from the fee proposal's USD figures at $0.10/ℏ:
$3,000 stake, $10 first slash (doubling, ban on the 5th), and a $1 endpoint misbehavior penalty.

## Slashing: `ClprSlashingUtils`

`applySlash(connector, config, connectorStore) → SlashResult(updatedConnector, penaltyAmount, banned)` uses
`slashBasePenalty`, `slashMultiplier` and `slashBanThreshold` from `ClprConfig` (matches spec §4.6):

```
new_slash_count = connector.slash_count + 1
if new_slash_count ≥ slashBanThreshold:
    penalty = connector.locked_stake      // ban: all remaining stake is forfeited
    remove the connector from state
else:
    penalty = min(slashBasePenalty × slashMultiplier ^ connector.slash_count, connector.locked_stake)
    connector.locked_stake -= penalty
```

`ClprSubmitBundleHandler` then calls `reimburseEndpoint`, which transfers the whole `penalty` from `stakingAccount` to
the submitting endpoint, capped at the staking account's balance so that hbar is conserved. As in the fee proposal,
the submitter keeps the full penalty; routing it elsewhere (a protocol treasury, a per-bundle cap) is undecided.

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
2. For network-consensus levers, change requires either an upgrade with a revised
   `clprLedgerConfiguration.json` *or* a `CLPR_UPDATE_LEDGER_CONFIGURATION` admin tx (for
   throttles only — stake/slashing live in `ClprConfig`).
3. Update unit tests in `ClprSlashingUtils` test class and any handler tests that pin
   default values.
