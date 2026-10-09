# Account Staking Configuration via the Hedera Account Service

## Purpose

An account's staking configuration — the `staked_node_id` / `staked_account_id` `oneof` and
`decline_reward` — is settable only through the HAPI `CryptoUpdate` and `ContractUpdate`
transactions. A smart contract that custodies HBAR therefore cannot direct its own balance to a
consensus node, or toggle reward-decline, without an off-chain transaction signed by an admin key it
may deliberately not hold; and no contract can read that state from the EVM at all.

HIP-1522 closes that gap the same way HIP-906 closed it for HBAR allowances: by adding the
operations to the Hedera Account Service (HAS) system contract at `0x16a`.

## References

- [HIP-1522: Account Staking Configuration via the Hedera Account Service](https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1522)
- [HIP-632: Hedera Account Service (HAS) System Contract](https://hips.hedera.com/hip/hip-632)
- [HIP-906: Proxy Redirect Contract for Hbar Allowance and Approval](https://hips.hedera.com/hip/hip-906)

## Goals

- Let an account — EOA or contract — set and clear its own staking target and reward preference
  from the EVM, gated by the existing HAS security model.
- Let any caller read an account's staking state from the EVM, agreeing field for field with what
  `CryptoGetInfo` reports over HAPI.
- Change no existing HAPI transaction, protobuf message, or system-contract selector.

## Non Goals

- A new `HederaFunctionality`, transaction body, or fee schedule entry. Every mutating call
  dispatches the existing `CryptoUpdate`.
- Any change to who may configure an account's staking. This adds an EVM entry point, not a new
  privilege.

## Architecture

### Two interfaces, two audiences

|             Interface             |               Target                |                 Reachable from                  |
|-----------------------------------|-------------------------------------|-------------------------------------------------|
| `IHRC632`                         | the account the facade is called on | an **EOA** only                                 |
| `IHederaAccountService` (`0x16a`) | the account named explicitly        | anything, including a contract acting on itself |

The `IHRC632` facade is implemented by the redirect in
`CustomMessageCallProcessor.createProxyOrCodeDelegationContext`, which rewrites a call made to an
account address into a call on `0x16a`. That redirect fires only when the target address carries no
contract bytecode. A contract has bytecode, so `IHRC632(someContract).f(...)` is an ordinary call
into that contract's own code and never reaches HAS — the same division that already applies to
HIP-906's `hbarApprove`. **A contract configures itself through the `IHederaAccountService` form,
passing `address(this)`.**

Each new facade selector must also be added to `HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES` in
`HasSystemContract`, which is the allowlist the redirect consults.

### New Solidity functions

All twelve selectors are additive. The mutating functions return an `int64` Hedera response code
rather than reverting, per `system-contract-abi-guidelines.md`.

|   Selector   |                                                         Signature                                                         |   Dispatches   | Contract |
|--------------|---------------------------------------------------------------------------------------------------------------------------|----------------|----------|
| `0x5fbd84d5` | `function stakeToNode(int64 nodeId) external returns (int64 responseCode)`                                                | CryptoUpdate   | HAS      |
| `0xa69431fe` | `function stakeToAccount(address account) external returns (int64 responseCode)`                                          | CryptoUpdate   | HAS      |
| `0x2def6620` | `function unstake() external returns (int64 responseCode)`                                                                | CryptoUpdate   | HAS      |
| `0x293d496f` | `function setDeclineReward(bool decline) external returns (int64 responseCode)`                                           | CryptoUpdate   | HAS      |
| `0xfad3a941` | `function stakeToNodeAndDeclineReward(int64 nodeId, bool decline) external returns (int64 responseCode)`                  | CryptoUpdate   | HAS      |
| `0xb40cd21d` | `function getStakingInfo() external returns (int64 responseCode, StakingInfo memory info)`                                | — (state read) | HAS      |
| `0x7a852f7c` | `function stakeToNode(address account, int64 nodeId) external returns (int64 responseCode)`                               | CryptoUpdate   | HAS      |
| `0x7563f477` | `function stakeToAccount(address account, address stakedTo) external returns (int64 responseCode)`                        | CryptoUpdate   | HAS      |
| `0xf2888dbb` | `function unstake(address account) external returns (int64 responseCode)`                                                 | CryptoUpdate   | HAS      |
| `0xf8afc6b4` | `function setDeclineReward(address account, bool decline) external returns (int64 responseCode)`                          | CryptoUpdate   | HAS      |
| `0xd52d84ea` | `function stakeToNodeAndDeclineReward(address account, int64 nodeId, bool decline) external returns (int64 responseCode)` | CryptoUpdate   | HAS      |
| `0xaa4704f3` | `function getStakingInfo(address account) external returns (int64 responseCode, StakingInfo memory info)`                 | — (state read) | HAS      |

### New Solidity structure

```solidity
struct StakingInfo {
    bool declineReward;
    int64 stakePeriodStart;   // epoch second; 0 unless staked to a node
    int64 pendingReward;      // an estimate, not a claimable balance
    int64 stakedToMe;
    int64 stakedNodeId;       // -1 if not staked to a node
    address stakedAccountId;  // zero address if not staked to an account
}
```

Because Solidity has no `oneof`, the protobuf's `staked_id` is flattened into two fields carrying
the same sentinels the mutating functions accept; at most one is ever set. `stakedAccountId` is
rendered in the account's **priority EVM address** form — its alias when it has one, long-zero
otherwise — so the value compares equal to `msg.sender` for an aliased caller and to `address(this)`
for a contract created by an Ethereum transaction.

### Clearing the staking target

Three spellings reach the same state and produce the same child record:

|             Call             |                                        Effect                                        |
|------------------------------|--------------------------------------------------------------------------------------|
| `unstake()`                  | The canonical form. Dispatches `staked_node_id = -1`.                                |
| `stakeToNode(-1)`            | `-1` is the HAPI `staked_node_id` sentinel.                                          |
| `stakeToAccount(address(0))` | The zero address resolves to account `0.0.0`, the HAPI `staked_account_id` sentinel. |

Only the **literal** zero address spells the third form. `AddressIdConverter` also yields `0.0.0` for a
non-canonical reference, and `stakeToAccount` rejects that with `INVALID_STAKING_ID` rather than
treating it as a clear — see Security Implications.

`stakeToNodeAndDeclineReward` deliberately does **not** accept the sentinel: it requires a
non-negative `nodeId` and returns `INVALID_STAKING_ID` otherwise. A function whose name says "stake
to node" should not also be the way to stop staking.

### System contract module

`.../exec/systemcontracts/has/staking/`

|        Class         |                                                        Role                                                        |
|----------------------|--------------------------------------------------------------------------------------------------------------------|
| `StakingTranslator`  | Declares all twelve methods; builds the `CryptoUpdateTransactionBody` for each mutating form.                      |
| `StakingUpdateCall`  | Dispatches the staking-only `CryptoUpdate`. Priced with `DispatchType.CRYPTO_UPDATE`.                              |
| `GetStakingInfoCall` | Reads state via `HederaNativeOperations#stakingInfoOf`. Priced with `viewGasRequirement()`; allows a static frame. |

All five mutating functions produce the same artifact and are priced identically, so they share one
`Call` class. `getStakingInfo` reuses `AccountSummariesApi#summarizeStakingInfo` — the same helper
`CryptoGetInfo` and `ContractGetInfo` use — so the EVM and HAPI views agree by construction rather
than by parallel implementation.

`HederaNativeOperations` gained `readableStakingInfoStore()`,
`readableNetworkStakingRewardsStore()` and `currentConsensusTime()`, implemented in both the handle
and query scopes because the accessor must work under `eth_call` / `STATICCALL`.

### Relaxing the contract-account restriction

`CryptoUpdateHandler` rejects any body naming an account with `smart_contract = true`. That
restriction is narrowed for — and only for — a staking update dispatched by this system contract.
**Both** conditions must hold:

1. the dispatch carries the `ACCOUNT_SERVICE_STAKING_UPDATE` marker in its `DispatchMetadata`, and
2. the body sets nothing beyond `decline_reward` and the `staked_id` `oneof`.

The marker is an in-process map with no wire representation, constructed fresh per dispatch and not
inherited by grandchildren, so a top-level HAPI `CryptoUpdate` always runs without it and continues
to fail with `INVALID_ACCOUNT_ID`. The body check is re-evaluated in the handler, so a mis-plumbed
marker still cannot mutate a contract's non-staking fields.

The "staking only" test is written as a canonical projection compared for equality with the original
body, not as a conjunction of "field X is unset" checks: `CryptoUpdateTransactionBody#equals`
compares every field including unknown ones, so a field added to the protobuf later is rejected by
default. Four of the body's fields — `proxy_fraction`, `hook_ids_to_delete`, `hook_creation_details`
and `delegation_address` — have no `hasX()` accessor at all, which is exactly where a hand-written
check goes wrong.

`MODIFYING_IMMUTABLE_CONTRACT` is never reached, because every target is updated with
`CryptoUpdate` and never `ContractUpdate`.

### Authorization

Unchanged from HIP-632/906. A contract calling on its own account is inherently authorized:
`PreHandleContextImpl#requireKey` short-circuits when the target account equals the dispatch payer,
so executing the bytecode *is* the authorization and no signature is required. A cross-account call
requires the target account's key to have signed the top-level transaction.

Note the dispatched `CryptoUpdate` fails an unauthorized cross-account call with `INVALID_SIGNATURE`
(7), but `ReturnTypes#standardized` normalizes that one code for every system contract call, so the
EVM caller observes `INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE` (326).

### Feature flags

- `contracts.systemContract.accountService.stakingEnabled` — gates all twelve selectors. Ships
  `false`; flipped in a separate activation change.
- `contracts.systemContract.accountService.enabled` — the pre-existing master switch for `0x16a`
  still gates everything.

## Security Implications

- Staking configuration moves no balances. The maximum effect of an authorized call is to redirect
  or decline the caller's own staking rewards.
- `stakeToAccount` will not accept a **non-canonical reference** — the long-zero form of an account
  whose priority address is an EVM alias, which is what `getHederaAccountNumAlias` returns.
  `AddressIdConverter` resolves such a reference to `0.0.0`, and everywhere else in the system
  contracts that id is deliberately built to fail downstream. Staking is the one place `0.0.0` is a
  *meaningful sentinel*, so accepting it would fail **open**: "stake to Alice" would silently become
  "unstake" and return `SUCCESS`. The translator therefore reserves the sentinel for the literal zero
  address and returns `INVALID_STAKING_ID` for the other route.
- Adding the six `IHRC632` selectors to `HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES` widens that allowlist
  from three Hedera-specific names to nine, and three of the new ones — `unstake()`,
  `stakeToNode(int64)`, `setDeclineReward(bool)` — are generic enough to collide with ordinary DeFi
  vocabulary. Two consequences follow from the allowlist being a static set consulted by
  `CustomMessageCallProcessor` *before* any configuration is read:
  - `createProxyOrCodeDelegationContext` gives the HAS redirect priority over EIP-7702 code
    delegation, so for an account with a delegation set, calldata carrying one of these selectors
    reaches HAS instead of the delegate.
  - With the HAS master switch `accountService.enabled` off, `HasSystemContract#computeFully` halts
    with `NOT_SUPPORTED` and consumes all remaining gas, where before the change the same calldata
    sent to a code-less account was simply a successful no-op call.

  Neither behaviour is introduced by this change — the precedence rule and the halt both predate it,
  and the facade is unimplementable without the allowlist entries — but both now apply to six more
  selectors, and neither is gated by `stakingEnabled`.

- The contract-account narrowing does not widen who may change an account's staking. The dispatched
  update is still subject to the same key requirements and the same `StakingValidator` checks, and
  still touches only `decline_reward` and `staked_id`.

- Providing a keyless, authorization-gated EVM path removes the incentive to grant a funds-custody
  contract a ledger-level admin key purely so it can sign a `ContractUpdate`.

- `getStakingInfo` is deliberately unauthorized: every field it returns is already public over HAPI
  and the mirror node. It dispatches nothing and mutates nothing.

## Acceptance Tests

### Unit tests

- `StakingTranslatorTest` — all twelve selectors match the HIP; none match with the flag off; a
  facade selector sent directly to `0x16a` does not match; each body asserted field by field,
  including that `decline_reward` is *unset* on the pure-stake forms and the two `staked_*` fields
  are never both set. Both call shapes are covered: the explicit forms via `bodyDispatchedBy` and
  each facade form via `facadeBodyDispatchedBy`, since the two are near-identical pairs and a
  copy-paste slip between them would otherwise go unnoticed. `stakeToAccount` rejects a
  non-canonical reference with `INVALID_STAKING_ID`.
- `StakingUpdateCallTest` — the dispatch carries the metadata marker; `CRYPTO_UPDATE` gas; a
  business failure is returned, never reverted.
- `GetStakingInfoCallTest` — every field and sentinel; the priority-EVM-address rendering for an
  aliased, non-aliased and missing staked-to account; a missing target returns `INVALID_ACCOUNT_ID`
  and a zeroed struct.
- `HandleHederaNativeOperationsTest` — the staking stores and consensus time are exposed from the
  handle scope, and `stakingInfoOf` is exercised **on an account staked to a node**, so the derived
  `stake_period_start` pins the `periodMins` argument. Without that case the five arguments consumed
  only inside `addNodeStakeMeta` could be transposed silently.
- `HasSystemContractTest` — every `CallVia.PROXY` staking selector is in
  `HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES` and no explicit form is, so the hand-written hex list
  cannot drift from the ABI-derived selectors.
- `CryptoUpdateHandlerTest` — a marked staking-only update may name a contract; `isStakingOnly`
  rejects every non-staking field of the body; an unmarked staking-only body on a contract still
  fails `INVALID_ACCOUNT_ID`; `StakingValidator` still runs.

### BDD tests

`suites/contract/hip1522/AccountStakingConfigurationTest`

The driver is `HRC1522Contract`, **not** the shared `HRC632Contract`. That fixture is deployed by the
HIP-632 suites under a hard-coded `creationGas`, and `IsAuthorizedTest` /`AtomicIsAuthorizedTest` pin
the exact gas at which a call to it flips between `INSUFFICIENT_GAS` and `SUCCESS`. Adding functions
to it therefore breaks suites that have nothing to do with staking, so each HIP gets its own contract.

Two properties make the assertions meaningful and are easy to lose:

- `NODE_ID` is **non-zero**. HAPI reports a cleared target as an unset `staked_id` oneof, so the proto
  getter reads `0` — which makes `stakedNodeId(0)` and `noStakingNodeId()` the same assertion and every
  before/after pair blind. Both smart-contract HAPI tasks run a 3-node network, so node 1 exists.
- Every mutating call asserts its **`int64` response code** through the record. The Solidity wrappers
  deliberately omit `require(responseCode == SUCCESS)`, so a business failure leaves the top-level
  `ContractCall` status at `SUCCESS` and would otherwise be invisible.

#### Positive

- A contract created with **no admin key** stakes its own balance to a node and declines rewards;
  `getContractInfo` confirms it.
- A contract reads its own staking state back, asserting every field of the returned struct.
- A contract stakes to another account, and `setDeclineReward` toggles the flag without disturbing
  the node target.
- An EOA configures its own staking, and declines rewards, through the `IHRC632` facade.
- `unstake()`, `stakeToNode(-1)` and `stakeToAccount(address(0))` all clear the target — each from a
  confirmed staked state, so the transition is observable in both directions.

#### Negative

- A cross-account call without the target's signature returns
  `INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE` and leaves the target's staking untouched.
- `stakeToNodeAndDeclineReward(-1, …)` returns `INVALID_STAKING_ID` and leaves a previously staked
  account exactly as it was.
- `IHRC632` calldata sent to a contract address reverts and changes nothing.
- With the flag off, the calls are unavailable.

#### Backwards compatibility

- A top-level HAPI `CryptoUpdate` naming a contract account still fails `INVALID_ACCOUNT_ID`.
- A top-level HAPI `ContractUpdate` staking an immutable contract still fails
  `MODIFYING_IMMUTABLE_CONTRACT`.
