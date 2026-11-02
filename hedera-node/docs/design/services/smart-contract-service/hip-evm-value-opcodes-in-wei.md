## Abstract

The Hedera EVM currently exposes HBAR amounts to contract code **in tinybars** (1 HBAR = 10^8 tinybar) from the
`BALANCE`, `SELFBALANCE` and `CALLVALUE` opcodes, while the Ethereum Yellow Paper (and every Solidity/Vyper compiler,
tool and test suite built on it) defines these values **in wei** (1 ETH = 10^18 wei). Hedera maps 1 tinybar to
10^10 wei ("weibar", see HIP-410), so contract code observes a number that is 10^10 times smaller than the
EVM-equivalent value.

This HIP proposes that, starting from a specified network release, the EVM reports and consumes native-currency
values **in weibar (10^-18 HBAR)** everywhere they are visible to EVM bytecode:

* `BALANCE` / `SELFBALANCE` (and `address.balance`) return `tinybarBalance * 10^10`.
* `CALLVALUE` (`msg.value`) returns `tinybarValue * 10^10`, including for system contract calls.
* The value operand of `CALL`, `CALLCODE`, `CREATE` and `CREATE2` is interpreted in weibar, and converted to
  tinybar (the only unit the ledger can move) when the transfer is executed.

HAPI, the record/block stream, Mirror Node REST, and SDKs continue to use tinybar. Only the EVM-visible view changes.

## Motivation

1. **EVM equivalence.** `msg.value`, `address(x).balance` and `.call{value: v}` are specified in wei. Code, libraries
   and tooling written against that assumption (OpenZeppelin `Address.sendValue`, wrappers, AMMs, escrow/vesting
   contracts, `1 ether` literals, fuzzers, formal verification tools) misbehave on Hedera.
2. **Inconsistency with other Hedera surfaces.** The JSON-RPC relay already presents `eth_getBalance` and
   transaction `value` in weibar, and the RLP `value` of an Ethereum transaction is signed by the user in weibar
   (`EthTxData.value`). Only the in-EVM view differs, so a contract reading its own balance disagrees with
   `eth_getBalance` for the same account.
3. **Developer confusion.** A contract funded with 0.29 HBAR reports `29_000_000` from `BALANCE` instead of
   `290_000_000_000_000_000`. `1 ether` in Solidity means 10^18 tinybar (10^10 HBAR) on Hedera today.

## Rationale

The fix is a unit conversion at the boundary between the EVM and the ledger. The ledger keeps tinybar (changing it
would be a state migration and a HAPI break for no benefit); the EVM boundary scales by
`WEIBARS_IN_A_TINYBAR = 10_000_000_000L` (already defined in
`hedera-node/hapi-utils/.../ethereum/EthTxData.java`).

Alternatives considered are listed under [Rejected Ideas](#rejected-ideas).

## User stories

* As a Solidity developer, I want `msg.value` and `address(this).balance` to be in wei so that code and libraries
  written for Ethereum behave identically on Hedera.
* As a tooling/test author, I want the upstream Ethereum execution spec tests to pass on Hedera without
  Hedera-specific patches.
* As a dApp developer, I want `eth_getBalance` and an in-contract `address.balance` to return the same number.
* As an existing Hedera contract owner, I want a clear migration path and advance notice of which of my contract
  behaviors change.

## Specification

### Terminology

| Term    | Meaning                                                                          |
|---------|----------------------------------------------------------------------------------|
| tinybar | 10^-8 HBAR. Unit of ledger balances and of HAPI `amount` fields.                 |
| weibar  | 10^-18 HBAR, i.e. 1 tinybar = 10^10 weibar. Referred to as "wei" by EVM tooling. |

### Current behavior (as of `c31ff872a52`)

| Opcode                                             | Source of value                                                                                                                         | Unit today | Code                                                                                                                                                          |
|----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BALANCE`                                          | `CustomBalanceOperation` (Besu `BalanceOperation`) -> `AbstractProxyEvmAccount.getBalance()` -> `DispatchingEvmFrameState.getBalance()` | tinybar    | `Wei.of(validatedAccount(id).tinybarBalance())` (`DispatchingEvmFrameState.java:301`)                                                                         |
| `SELFBALANCE`                                      | No custom op; Besu implementation reads the same `getBalance()`                                                                         | tinybar    | same as above                                                                                                                                                 |
| `CALLVALUE`                                        | `HederaEvmTransaction.weiValue()` -> `FrameBuilder` `.value(v)` / `.apparentValue(v)`                                                   | tinybar    | `Wei.of(value)` (`HederaEvmTransaction.java:133`)                                                                                                             |
| `CALL`/`CALLCODE`/`CREATE`/`CREATE2` value operand | Stack value compared with `getBalance()`; executed with `frame.getValue().toLong()`                                                     | tinybar    | `CustomMessageCallProcessor.java:497`, `CustomContractCreationProcessor.java:90`, `AbstractCustomCreateOperation.java:115-143`, Bonneville `CallManager.java` |

Note that the Besu type is named `Wei`, but Hedera stores tinybar in it.

### New behavior

From the activation release (see [Activation](#activation-and-configuration)):

1. **`BALANCE`, `SELFBALANCE`, `address.balance`.** Return `tinybarBalance * 10^10`.
   Overflow analysis: total HBAR supply is 5 * 10^18 tinybar, so the maximum result is 5 * 10^28 < 2^256. No overflow.
2. **`CALLVALUE` / `msg.value`.** For a top-level transaction with HAPI value `v` tinybar (or Ethereum-transaction
   value normalized to tinybar), the frame value and apparent value are `v * 10^10`. For nested frames, `CALLVALUE`
   returns the (weibar) value of the `CALL`/`CALLCODE` that created the frame; `DELEGATECALL` inherits the apparent
   value of its parent as today.
3. **Value operand of `CALL`, `CALLCODE`, `CREATE`, `CREATE2`.** Interpreted as weibar.
    * It is compared to the sender's weibar balance (consistent with item 1).
    * Before the transfer is dispatched it is converted to tinybar by exact division by 10^10.
    * **[OPEN] Sub-tinybar remainder.** Hedera cannot represent fractions of a tinybar. Options:
      (a) **Fail the call** when `value % 10^10 != 0`, with the same observable result as an insufficient-balance
      `CALL` (push `0`, no state change, no gas refund of the forwarded gas beyond the existing semantics); (b) truncate
      (floor) silently, as `EthTxData.getAmount()` does today; (c) halt the frame exceptionally. **Recommended: (a)**,
      because silently destroying value violates
      `balance_before - balance_after == value` invariants that contracts and the spec tests rely on.
4. **Top-level Ethereum transactions.** `EthTxData.value` is already in weibar and is converted to tinybar for the
   transfer (`getAmount()`, `effectiveTinybarValue()`), dropping any remainder. This HIP does not change that
   transaction-level rule, but `CALLVALUE` observed by the contract is `getAmount() * 10^10`, i.e. the amount that is
   actually transferred, so the contract's view is consistent with its balance change. **[OPEN]** Whether to
   additionally reject Ethereum transactions with a non-zero sub-tinybar remainder is out of
   scope and is deferred (it would be a separate behavior change to transaction validation).
5. **System contracts (HTS, HAS, HSS, Exchange Rate, PRNG, ...).** `msg.value` seen by a system contract call is
   the same weibar value. System contracts that today compare `msg.value` against a tinybar quantity MUST convert
   first. Known case: `ClassicCreatesCall` (`frame.getValue().lessThan(Wei.of(nonGasCost))`,
   `ClassicCreatesCall.java:115`)
   for HTS `createFungibleToken` / `createNonFungibleToken` and variants, which are payable and charge a fee in tinybar.
   Any workaround on the caller side (passing `{value: N tinybar}`) changes to `{value: N * 10^10}`.
6. **Unchanged.**
    * Gas accounting: `GASPRICE`, `tx.gasprice`, `BASEFEE`, intrinsic gas, and gas cost of value-bearing calls depend
      only on whether the value is zero (`FrameUtils.java:240`).
    * HAPI `amount`/`value` fields, `ContractFunctionResult.amount`, contract actions `value`, record/block stream
      transfer lists: remain tinybar.
    * `SELFDESTRUCT` sweeps the full tinybar balance to the beneficiary; its gas schedule only checks zero vs non-zero
      inheritance (`CustomSelfDestructOperation.java:130-136`).
    * Hook (HIP-1195) and CLPR-originated EVM executions pass tinybar values at their boundary and are scaled by the
      same frame-building path.

### Reference implementation sketch

// TODO

### Impact on existing contracts (analysis)

| Pattern in existing Hedera contracts                                                               | Before                        | After                                                                               | Severity                     |
|----------------------------------------------------------------------------------------------------|-------------------------------|-------------------------------------------------------------------------------------|------------------------------|
| `require(msg.value == 100_000_000)` meaning 1 HBAR                                                 | passes for 1 HBAR             | **fails** (msg.value = 10^18)                                                       | **Breaking**                 |
| `require(msg.value >= 1 ether)` written for Ethereum                                               | requires 10^10 HBAR; unusable | requires 1 HBAR; works as intended                                                  | Fixes                        |
| `address(this).balance` compared against tinybar constants                                         | works                         | **breaks**                                                                          | **Breaking**                 |
| `payable(x).transfer(amount)` / `.call{value: amount}` where `amount` is tinybar                   | transfers `amount` tinybar    | `amount` is now weibar: transfers 0 (floor) or **fails** (non-multiple, option (a)) | **Breaking**                 |
| Value derived from `msg.value` and passed back (`refund = msg.value - fee`)                        | consistent in tinybar         | consistent in weibar as long as `fee` is also weibar                                | Breaking if `fee` is tinybar |
| Contracts using the Exchange Rate precompile (`tinycentsToTinybars`) then comparing to `msg.value` | tinybar vs tinybar            | tinybar vs weibar: **breaks** unless multiplied by 10^10                            | **Breaking**                 |
| HTS payable create calls with `{value: X}`                                                         | X tinybar                     | X must be weibar                                                                    | **Breaking**                 |
| Pass-through contracts that never inspect or scale the value (`address.call{value: msg.value}`)    | works                         | works                                                                               | None                         |
| Contracts that only test `balance == 0` / `msg.value > 0`                                          | works                         | works                                                                               | None                         |
| Gas-sensitive code                                                                                 | unchanged                     | unchanged                                                                           | None                         |

Off-chain impact:

| Component                                      | Impact                                                                                                                                                                                                                 |
|------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Consensus node**                             | Changes above; new config gate; new unit/HAPI-suite tests (see [Testing](#testing)).                                                                                                                                   |
| **Mirror Node**                                | REST/gRPC fields stay tinybar (no change). The `web3` module's contract-call simulation (`eth_call`, `eth_estimateGas`) must use the identical new semantics at the identical activation.                              |
| **JSON-RPC relay**                             | Already converts to weibar for `eth_getBalance` and transaction `value`. Needs verification that its `eth_call`/`eth_estimateGas` `value` field is passed as tinybar to the node as before; no format change expected. |
| **SDKs**                                       | `ContractExecuteTransaction.setPayableAmount`, `ContractFunctionResult` amounts: unchanged (tinybar). Doc updates only.                                                                                                |
| **Hedera docs / tutorials / sample contracts** | Many examples multiply by `10**8` or hard-code tinybar; must be updated and flagged with the activation version.                                                                                                       |
| **Tooling (Hardhat, Foundry, Remix, ethers)**  | Becomes closer to Ethereum; workarounds for tinybar semantics can be removed.                                                                                                                                          |
| **Exchange / custody / wallets**               | No change: they use HAPI or relay.                                                                                                                                                                                     |

## Backwards Compatibility

This is a **breaking change for existing contracts** that treat `msg.value`, `address.balance` or the `CALL` value as
tinybar (see impact table). It is **not** a ledger, HAPI, SDK or mirror-node API break. Mitigations:

1. Network property gate with a published activation release and testnet/previewnet soak period before mainnet.
2. A scan of mainnet bytecode (mirror node `contract` bytecode + `contract_result` history) for use of
   `CALLVALUE`/`BALANCE`/`SELFBALANCE`/value-bearing `CALL` and of the tinybar-style constants, to quantify the
   population at risk before activation. **[OPEN]** Whoever owns this analysis must publish the numbers in this HIP
   before it moves to Last Call.
3. Communication: release notes, docs.hedera.com updates, Discord/Developer-announcements, and a migration guide
   with the patterns in the impact table.
4. **[OPEN]** Whether to offer per-contract opt-in/opt-out (e.g. keep tinybar semantics for contracts created before
   the activation consensus time, wei semantics for new ones) to avoid breaking immutable legacy contracts. Pros:
   no breakage for deployed immutable contracts. Cons: two EVM semantics permanently live in the same network,
   invalid for cross-contract calls between legacy and new contracts (a legacy contract receiving weibar from a new
   contract would misinterpret it), and it would defeat the goal of passing the upstream spec tests for
   pre-existing accounts. Not recommended, listed for discussion.

## Security Implications

// TODO

## Testing

// TODO

## How to Teach This

* Docs: update the "Hedera EVM vs Ethereum" page to state that, from release X, `msg.value`/`balance` are in wei (10^-18
  HBAR), so `1 ether` (10^18 weibar) equals 1 HBAR.
* Add a conversion cheat sheet: `1 HBAR = 10^8 tinybar = 10^18 weibar`.
* Provide before/after Solidity snippets:

```solidity
// Before (Hedera-specific, tinybar)
require(msg.value >= 100_000_000, "need 1 HBAR");

// After (EVM-equivalent, weibar) - identical to Ethereum code
require(msg.value >= 1 ether, "need 1 HBAR");
```

* A migration guide listing the patterns in the impact table and a mainnet-contract scanner script.

## Rejected Ideas

// TODO

## Open Issues

// TODO
