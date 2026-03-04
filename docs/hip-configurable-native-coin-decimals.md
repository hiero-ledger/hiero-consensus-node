---

hip: <TBD>
title: Configurable Native Coin Decimal Precision
author: Stoyan Panayotov <@stoqnkpL>
requested-by: LimeChain
type: Standards Track
category: Core
needs-hiero-approval: Yes
needs-hedera-review: Yes
status: Draft
created: 2026-03-04
updated: 2026-03-05
discussions-to: https://github.com/hiero-ledger/hiero-improvement-proposals/discussions/<TBD>
---------------------------------------------------------------------------------------------

## Abstract

This HIP introduces a genesis-time configuration property, `nativeCoin.decimals`, that controls the number of decimal places in a Hiero network's native coin subunit. The value MUST be an integer between 0 and 18 inclusive, with a default of 8 (preserving the existing 1 HBAR = 10⁸ tinybar relationship). Once set at genesis, the value is persisted as an immutable singleton in network state and SHALL NOT change for the lifetime of the network. All denomination-sensitive paths — fees, staking rewards, EVM wei conversions, exchange rate calculations, and query responses — automatically scale to the configured subunit.

## Motivation

The Hiero consensus node currently hardcodes the native coin's smallest subunit as 10⁻⁸ of a whole coin (i.e., 1 HBAR = 100,000,000 tinybars). This assumption is embedded throughout the fee schedule, staking reward calculations, EVM gas charging, and API responses. While correct for the Hedera mainnet, this rigidity limits Hiero's utility as a general-purpose DLT framework.

Several classes of Hiero network operators require different decimal precision:

- **Enterprise and permissioned networks** may define native coins with fewer decimal places (e.g., 0 or 2) to align with fiat-denominated settlement systems where sub-cent precision is unnecessary or undesirable.
- **DeFi-oriented networks** may prefer 6 decimal places (matching USDC/USDT conventions) or 18 decimal places (matching Ethereum's native wei precision) to simplify integration with existing tooling and smart contracts.
- **Regulatory environments** may mandate specific precision levels for auditing, reporting, or compliance purposes.

Without this configurability, network operators must either accept the hardcoded 8-decimal assumption or maintain a permanent fork of the codebase — neither of which is sustainable for ecosystem growth.

## Rationale

### Scale at boundary, not internally

The fee schedule and internal fee computation logic are calibrated for 10⁻⁸ HBAR (the "default tinybar"). Rather than modifying every internal calculation to use a configurable base — which would be invasive, error-prone, and hard to audit — this proposal adopts a **"scale at boundary"** design:

1. Internal fee math continues to operate in default tinybars (10⁻⁸ HBAR).
2. At approximately 10 output boundaries (where fees are charged, balances are debited, or values are returned to clients), a scaling function converts default tinybars to the configured subunit.
3. When `decimals = 8` (the default), the scaling function is an identity operation — it returns the input unchanged with zero computational overhead.

This approach minimizes the blast radius of the change, preserves the correctness of the extensively tested fee computation logic, and avoids `BigInteger` allocations on the default path.

### Genesis-immutable

Allowing the decimal precision to change after genesis would require redenominating every balance, pending reward, contract storage value, and historical record in the network. This is operationally equivalent to a hard fork and introduces unacceptable risk of balance corruption. Therefore, `nativeCoin.decimals` is set exactly once at genesis and persisted as an immutable singleton. The migration logic validates that the configured value matches the persisted state on every restart.

### Range 0–18

The lower bound of 0 supports whole-unit-only coins (no fractional subunits). The upper bound of 18 matches Ethereum's wei precision (10¹⁸ wei per ether), which is the maximum precision needed for full EVM compatibility. Values above 18 would exceed the precision of a 64-bit `long` when multiplied by practical balance amounts, introducing overflow risk without practical benefit.

## User Stories

**As a network operator**, I want to configure the native coin's decimal precision at genesis so that my network's smallest unit matches my tokenomic design (e.g., 6 decimals for a stablecoin-like denomination or 18 for full EVM compatibility).

**As an SDK developer**, I want to query the network's configured decimal precision via the `NetworkGetVersionInfo` API so that my application can correctly format and parse native coin amounts without hardcoding assumptions.

**As a smart contract developer**, I want the EVM's wei-to-native-coin conversion to respect the configured decimals so that `msg.value` and gas prices in my Solidity contracts behave correctly regardless of the network's denomination.

**As a staking participant**, I want staking rewards to be calculated and distributed in the correct subunit denomination so that my rewards are accurate regardless of the network's configured decimal precision.

## Specification

### Configuration Property

A new configuration record SHALL be introduced:

|       Property        | Type  | Default |   Range   |                           Description                           |
|-----------------------|-------|---------|-----------|-----------------------------------------------------------------|
| `nativeCoin.decimals` | `int` | `8`     | `[0, 18]` | Number of decimal places for the native coin's smallest subunit |

The configuration SHALL be read at bootstrap time. Values outside the range `[0, 18]` MUST be rejected by the configuration validation framework.

### Protobuf Changes

#### New State Singleton

A new protobuf message SHALL persist the configured decimals in network state:

```protobuf
// File: services/state/token/native_coin_decimals.proto

syntax = "proto3";
package com.hedera.hapi.node.state.token;

/**
 * A singleton state object that stores the native coin decimal precision
 * as established at genesis. Once set, this value SHALL NOT change.
 */
message NativeCoinDecimals {
  /**
   * The number of decimal places for the native coin (e.g., 8 means
   * 1 whole coin = 10^8 subunits).
   * This value MUST be between 0 and 18 inclusive.
   */
  int32 decimals = 1;
}
```

#### State Identifier

A new entry SHALL be added to the `StateIdentifier` enum in `state_changes.proto`:

```protobuf
/**
 * A state identifier for the native coin decimals. Singleton state.
 */
STATE_ID_NATIVE_COIN_DECIMALS = 57;
```

The `SingletonUpdateChange` message SHALL include a new field:

```protobuf
com.hedera.hapi.node.state.token.NativeCoinDecimals native_coin_decimals_value = 20;
```

#### Version Info Response

The `NetworkGetVersionInfoResponse` message SHALL be extended with:

```protobuf
/**
 * The number of decimal places used by the native coin on this network.
 * This value is set at genesis and SHALL NOT change after that.
 * A value of 8 means 1 whole coin = 10^8 subunits (the default).
 */
uint32 native_coin_decimals = 4;
```

### Denomination Mathematics

Given a configured `decimals` value _d_:

|        Quantity         | Formula  | Example (d=8) | Example (d=6) | Example (d=18) |
|-------------------------|----------|---------------|---------------|----------------|
| Subunits per whole coin | 10ᵈ      | 100,000,000   | 1,000,000     | 10¹⁸           |
| Weibars per subunit     | 10⁽¹⁸⁻ᵈ⁾ | 10¹⁰          | 10¹²          | 1              |

#### Fee Scaling Formula

All fees are computed internally in **default tinybars** (10⁻⁸ HBAR, i.e., _d_ = 8). At output boundaries, fees MUST be scaled to the configured subunit using:

```
scaledFee = defaultTinybarFee × subunitsPerWholeUnit / DEFAULT_SUBUNITS_PER_HBAR
```

Where `DEFAULT_SUBUNITS_PER_HBAR = 100,000,000` (10⁸).

When `subunitsPerWholeUnit == DEFAULT_SUBUNITS_PER_HBAR` (i.e., _d_ = 8), the scaling SHALL be a no-op identity function returning the input unchanged.

The computation MUST use arbitrary-precision integer arithmetic (`BigInteger`) to prevent overflow when `subunitsPerWholeUnit` is large (e.g., 10¹⁸ at _d_ = 18). The final result MUST be converted back to `long` using an exact conversion that throws on overflow.

#### EVM Wei Conversion

The EVM operates in wei (10⁻¹⁸ of a whole unit). Conversion between wei and the native subunit SHALL use:

```
subunits = weiAmount / weibarsPerSubunit
weiAmount = subunits × weibarsPerSubunit
```

Where `weibarsPerSubunit = 10^(18 - d)`.

This replaces the previously hardcoded constant `WEIBARS_IN_A_TINYBAR = 10^10` (which assumed _d_ = 8).

### Genesis Behavior

At genesis, the node SHALL:

1. Read `nativeCoin.decimals` from the bootstrap configuration.
2. Create the `NATIVE_COIN_DECIMALS` singleton in state with the configured value.
3. Initialize the denomination converter used by all denomination-sensitive subsystems.

### Migration Behavior

The migration logic MUST handle two distinct non-genesis scenarios:

#### Upgrade from pre-0.71.0 (singleton does not exist)

When a network launched before version 0.71.0 upgrades to a version containing this feature, the `NATIVE_COIN_DECIMALS` singleton will not exist in previous state. This is the normal upgrade path, not an error condition.

In this case, the migration SHALL:

1. Initialize the `NATIVE_COIN_DECIMALS` singleton with the value **8** (the implicit decimal precision that all pre-0.71.0 networks operated with).
2. If the `nativeCoin.decimals` configuration property is set to a value other than 8, log a warning that the configuration is being ignored — the existing network's implicit precision of 8 takes precedence.

This ensures that existing networks upgrade seamlessly without any change in behavior.

#### Restart on 0.71.0+ (singleton exists)

When a node running version 0.71.0 or later restarts, the `NATIVE_COIN_DECIMALS` singleton MUST exist in previous state.

In this case, the migration SHALL:

1. Read the persisted `decimals` value from the singleton.
2. Compare it with the current `nativeCoin.decimals` configuration value.
3. If the values differ, log a warning. The persisted value MUST take precedence — the configuration mismatch indicates an operator error, not a redenomination request.
4. If the singleton exists but is null, the node MUST fail startup with a clear error indicating potential state corruption.

### Affected Subsystems

The following subsystems MUST respect the configured decimals:

|     Subsystem      |                        Scaling point                         |                     Mechanism                     |
|--------------------|--------------------------------------------------------------|---------------------------------------------------|
| **Fee pipeline**   | Transaction fees, query fees, ingest checks                  | `scaleToSubunits()` at output boundary            |
| **Staking**        | Reward calculations, pending reward caps, period end updates | `DenominationConverter` injected via DI           |
| **EVM**            | Wei ↔ subunit conversion, gas charging, transaction value    | `weibarsPerSubunit()` replaces hardcoded constant |
| **Exchange rates** | Tinycent ↔ subunit conversion                                | Scaled at the conversion boundary                 |
| **API responses**  | `NetworkGetVersionInfo`                                      | New `native_coin_decimals` response field         |
| **Block stream**   | State change records                                         | New singleton update change type                  |

## Backwards Compatibility

This proposal introduces **no breaking changes** for existing networks operating at the default `decimals = 8`:

- All internal fee computations remain in default tinybars.
- The scaling function is a no-op identity at `decimals = 8` — no `BigInteger` allocation, no arithmetic.
- The `NetworkGetVersionInfoResponse` gains a new field (`native_coin_decimals = 4`); existing clients that do not read this field are unaffected.
- All existing unit tests (9,984+) pass unchanged with the default configuration.
- The new `NativeCoinDecimals` state singleton is additive and does not modify any existing state structures.
- Networks upgrading from pre-0.71.0 automatically receive the singleton initialized with `decimals = 8`, preserving identical behavior.

Networks configured with a non-default value are, by definition, new genesis deployments and therefore have no pre-existing state to migrate.

## Security Implications

### Overflow Protection

When `decimals = 18`, the subunits-per-whole-unit factor is 10¹⁸ (the maximum value of a `long`). Multiplying a fee amount by this factor can overflow a 64-bit integer. The implementation MUST use `BigInteger` arithmetic for the intermediate multiplication and MUST use `longValueExact()` to convert the result back, throwing `ArithmeticException` on overflow rather than silently truncating.

Staking reward calculations MUST use `Math.multiplyExact()` for overflow-checked `long` multiplication where `BigInteger` is not used.

### Immutability Guard

Changing the decimal precision after genesis would silently reinterpret every balance in the network (e.g., changing from 8 to 6 would make 100,000,000 subunits represent 100 whole coins instead of 1). The genesis-immutability constraint and the migration validation guard prevent this class of balance corruption.

### Configuration Mismatch Detection

If an operator restarts a node with a different `nativeCoin.decimals` value than what was persisted at genesis, the migration logic detects this and logs a warning. The persisted value always takes precedence, ensuring the node operates with the correct denomination regardless of configuration drift.

## How to Teach This

### For Network Operators

Add `nativeCoin.decimals` to the genesis configuration documentation. Emphasize:
- This property is set once at genesis and cannot be changed afterward.
- The default value of 8 preserves standard HBAR behavior.
- Common values: 0 (whole units only), 6 (USDC-style), 8 (HBAR-compatible), 18 (ETH-compatible).

### For SDK Developers

Document the new `native_coin_decimals` field in the `NetworkGetVersionInfo` response. SDKs SHOULD query this value at initialization and use it to correctly format native coin amounts in user-facing displays. Example:

```
// Query the network's decimal precision
var versionInfo = client.getVersionInfo();
int decimals = versionInfo.nativeCoinDecimals; // e.g., 8
long subunitsPerCoin = (long) Math.pow(10, decimals); // e.g., 100_000_000
```

### For Smart Contract Developers

No changes to Solidity code are required. The EVM layer transparently converts between wei and the network's native subunit. At `decimals = 18`, 1 wei = 1 subunit (no conversion needed). At `decimals = 8`, 1 subunit = 10¹⁰ wei (same as today).

## Reference Implementation

[hiero-ledger/hiero-consensus-node#23955](https://github.com/hiero-ledger/hiero-consensus-node/pull/23955)

The implementation spans 97 files across protobuf definitions, configuration, fee pipeline, staking, EVM, API, and test suites. Key components:

- `NativeCoinConfig` — Configuration record with `@Min(0) @Max(18)` validation
- `DenominationConverter` — Value object providing `subunitsPerWholeUnit()`, `weibarsPerSubunit()`, and `scaleToSubunits()`
- `FeeUtils.scaleToSubunits()` — Static scaling function with identity short-circuit at decimals=8
- `V0710TokenSchema` — Genesis persistence and migration validation (handles both fresh genesis and upgrade from pre-0.71.0)
- `NativeCoinDecimalsTest` — End-to-end test suite covering decimals={0, 1, 4, 6, 8}

## Rejected Ideas

### Runtime-changeable decimals

Allowing decimals to change after genesis was rejected because it would require redenominating every balance, pending reward, and contract storage value in the network — effectively a hard fork. The operational risk and implementation complexity far outweigh any benefit.

### Scaling internally instead of at boundaries

An alternative design would modify all internal fee computations to operate in the configured subunit rather than default tinybars. This was rejected because:
1. It would require modifying the fee schedule interpretation, which is calibrated for 10⁻⁸ HBAR.
2. It would touch hundreds of internal calculation sites rather than ~10 output boundaries.
3. It would make it harder to reason about fee correctness, since the fee schedule values would mean different things on different networks.

### Removing tinybar terminology entirely

Replacing "tinybar" with a generic "subunit" throughout the codebase was considered but rejected as out of scope. The existing terminology is well-established in documentation, APIs, and community understanding. This HIP deprecates the hardcoded constants but does not rename the concept.

## Open Issues

1. **Broader native coin configurability** — This HIP addresses only decimal precision. Future work may extend `NativeCoinConfig` to include the native coin's name, symbol, total supply, and other properties, enabling fully customizable native tokens on Hiero networks.

2. **Mirror node integration** — The mirror node must be updated to recognize the `NATIVE_COIN_DECIMALS` state singleton and the new `native_coin_decimals` field in `NetworkGetVersionInfoResponse`. This is expected to be a separate, coordinated change.

3. **SDK updates** — Official Hiero SDKs should be updated to expose the `nativeCoinDecimals` field from `NetworkGetVersionInfo` queries and use it for amount formatting.

## References

- [ERC-20 Token Standard](https://eips.ethereum.org/EIPS/eip-20) — Establishes the `decimals()` pattern for fungible tokens
- [Ethereum Yellow Paper, Section 2](https://ethereum.github.io/yellowpaper/paper.pdf) — Defines wei as 10⁻¹⁸ ether
- [Hiero Consensus Node](https://github.com/hiero-ledger/hiero-consensus-node) — Reference implementation repository
- [RFC 2119](https://www.ietf.org/rfc/rfc2119) — Key words for use in RFCs to indicate requirement levels

## Copyright/License

This document is licensed under the Apache License, Version 2.0 — see [LICENSE](../LICENSE) or [https://www.apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0).
