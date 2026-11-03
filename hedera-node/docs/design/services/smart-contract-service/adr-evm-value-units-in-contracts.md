# Units of `BALANCE`, `SELFBALANCE` and `CALLVALUE` inside the Hedera EVM

## Context and Problem Statement

Inside the Hedera EVM, `BALANCE`, `SELFBALANCE` (`address.balance`) and `CALLVALUE` (`msg.value`) return **tinybars**
(1 HBAR = 10^8 tinybar). Ethereum defines them in **wei** (1 ETH = 10^18 wei). Hedera maps 1 tinybar to 10^10 weibar
([HIP-410]([HIP-410](https://hips.hedera.com/#hip-410))), so a contract sees a number 10^10 times smaller than the
EVM-equivalent value. For example, an account holding 0.29 HBAR reports `29_000_000` instead of
`290_000_000_000_000_000` ([#27582](https://github.com/hiero-ledger/hiero-consensus-node/issues/27582)).

Where the tinybar behavior comes from (verified in this repo at `c31ff872a52`):

| Opcode                   | Code path                                                                                                                                 | Unit    |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|---------|
| `BALANCE`, `SELFBALANCE` | `DispatchingEvmFrameState.getBalance()` returns `Wei.of(validatedAccount(id).tinybarBalance())`                                           | tinybar |
| `CALLVALUE`              | `HederaEvmTransaction.weiValue()` returns `Wei.of(value)`, passed by `FrameBuilder` as value and apparent value                           | tinybar |
| `CALL`/`CREATE` value    | executed via `frame.getValue().toLong()` at (`CustomMessageCallProcessor.doTransferValueOrHalt`, `CustomContractCreationProcessor.start`) | tinybar |

Ethereum transactions arrive with `value` in weibar (`EthTxData.value`) and are divided by `WEIBARS_IN_A_TINYBAR`
before the EVM runs (`EthTxData.getAmount()` / `effectiveTinybarValue()`), so only the JSON-RPC wire format is weibar.

There seems no document recording a deliberate decision to expose tinybars to Solidity: no design doc in this repo, no
comment at the original code and [HIP-410]([HIP-410](https://hips.hedera.com/#hip-410)) defines weibar only for the
transaction envelope and never mentions
`msg.value`.

Worse, the **published Hedera documentation describes the opposite**. These pages say `msg.value` is 18-decimal
inside contracts:

* [troubleshooting](https://docs.hedera.com/evm/development/troubleshooting): "Inside an EVM contract, `msg.value`,
  `balance`, and `gasPrice` all use 18 decimals".
* [exchange-rate system contract](https://docs.hedera.com/evm/hedera-services/system-contracts/exchange-rate): example
  multiplies `tinycentsToTinybars` by `10**10` before comparing with `msg.value`.
* [WHBAR](https://docs.hedera.com/evm/tokens/whbar): `deposit{value: 10 * 10**18}()`.
* [decimal handling](https://docs.hedera.com/evm/differences/hbar-decimals): contradicts itself (one row says the EVM
  keeps 8 decimals, another says `msg.value` is 18).

[hedera-docs#792](https://github.com/hashgraph/hedera-docs/issues/792) (open, 2026-10-06) reports exactly this: the
docs are wrong and `msg.value`/`address.balance` are tinybars inside the EVM. Solidity examples built on the docs are
off by 10^10. [hedera-docs#801](https://github.com/hashgraph/hedera-docs/pull/801) fixes the pages. A third-party
testnet probe reports the same observation (2 HBAR sent gives `msg.value == 200000000`,
[dev.to write-up](https://dev.to/edycutjong/hederas-evm-speaks-tinybar-its-rpc-speaks-weibar-and-both-mistakes-return-success-132e)),
though that is a single unverified run.

The reference WHBAR contract independently confirms the tinybar behavior. In
[examples/tokens/whbar/contracts/WHBAR.sol](https://github.com/hashgraph/hedera-smart-contracts/blob/main/examples/tokens/whbar/contracts/WHBAR.sol)
(`hashgraph/hedera-smart-contracts@main`, checked 2026-11-02) `decimals = 8`, `deposit()` does
`balanceOf[msg.sender] += msg.value` with no scaling, and `withdraw(wad)` does `call{value: wad}`. That is only
coherent if `msg.value` and the `call` value are tinybars. Its
[Solo test](https://github.com/hashgraph/hedera-smart-contracts/blob/main/examples/tokens/whbar/test/solo/WHBAR.js)
sends the transaction `value` as `ONE_HBAR_AS_WEIBAR` (10^18, the JSON-RPC wire value), asserts that the `Deposit`
event `wad` (which is `msg.value`) equals `'100000000'` (10^8), and calls `withdraw(ONE_HBAR)` with
`ONE_HBAR = 100_000_000n` to get 1 HBAR back. The Hedera WHBAR docs page, which tells developers to call
`deposit{value: 10 * 10**18}()`, contradicts this source. The docs link
`contracts/wrapped-tokens/WHBAR.sol` no longer exists. The repo was reorganized on 2026-10-29
([#1737](https://github.com/hashgraph/hedera-smart-contracts/pull/1737)).

**Problem:** should the node change what the opcodes return to match Ethereum (and the current docs), or keep the
behavior and make the documentation match it?

## Decision Drivers

* **EVM equivalence:** Solidity libraries, tooling and the upstream Ethereum execution spec tests
  (`hiero-ethereum-execution-spec-tests`, `forks/amsterdam-hedera`) assume wei.
* **Backwards compatibility:** the behavior has been live since the 2023 services rewrite (and likely earlier). Any
  contract deployed with tinybar assumptions (`require(msg.value == 100_000_000)`, `.call{value: tinybarAmount}`)
  would break. Deployed immutable contracts cannot be patched.
* **Fund safety:** value mis-scaling can lock, burn or mint value from a contract's perspective. Sub-tinybar
  remainders of a weibar value cannot be represented on the ledger.
* **Blast radius:** a change touches `BALANCE`, `SELFBALANCE`, `CALLVALUE`, the value operand of
  `CALL`/`CALLCODE`/`CREATE`/`CREATE2`, system contracts that read `msg.value` (`ClassicCreatesCall`), the Bonneville
  EVM, Mirror Node `web3`, hooks and CLPR dispatches.
* **Correctness of published guidance:** developers are being told the wrong units today, regardless of what we
  decide about the node.
* **Cost and time:** a docs fix is available now. A HIP, activation and ecosystem migration requires huge effort.
* **Consistency across surfaces:** relay and `eth_getBalance` already speak weibar. HAPI, SDKs, mirror REST and
  record/block streams speak tinybar.

## Considered Options

### Option 1: Change the EVM to report and consume values in weibar

Scale the ledger's tinybar values at the EVM boundary, as proposed
in [#27582](https://github.com/hiero-ledger/hiero-consensus-node/issues/27582). The ledger, HAPI, SDKs, streams and
mirror REST stay in tinybar. It is a breaking change and requires a HIP.

| Item                                                 | Current behavior           | New behavior (proposed)                                                                                                                         |
|------------------------------------------------------|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `BALANCE`, `SELFBALANCE`, `address.balance`          | `tinybarBalance`           | `tinybarBalance * 10^10`                                                                                                                        |
| `CALLVALUE`, `msg.value`                             | value in tinybar           | `tinybarValue * 10^10` (for Ethereum txs, the amount actually transferred, scaled)                                                              |
| `CALL`/`CALLCODE`/`CREATE`/`CREATE2` value operand   | interpreted as tinybar     | interpreted as weibar, converted to tinybar exactly (`/ 10^10`) before transfer, and a non-multiple of 10^10 fails the call (recommended, open) |
| System contract `msg.value` (e.g. HTS token create)  | compared to a tinybar fee  | caller must send weibar, and the contract converts once                                                                                         |
| `1 ether` in Solidity                                | 10^18 tinybar (10^10 HBAR) | 1 HBAR                                                                                                                                          |
| Gas, `GASPRICE`, `BASEFEE`, solvency checks          | unchanged                  | unchanged (stay in tinybar internally)                                                                                                          |
| HAPI `amount`, record/block stream, mirror REST, SDK | tinybar                    | tinybar (unchanged)                                                                                                                             |

* Good: EVM-equivalent, spec-test workaround removed, matches what the docs claim today, simplifies developer
  mental model (`msg.value` equals the relay's `value`).
* Bad: breaking for existing contracts with tinybar constants (exact-value checks, refunds mixing `msg.value` and a
  tinybar fee, exchange-rate comparisons, HTS payable creates, tinybar `.call{value:}` amounts). It requires a HIP, a
  mainnet bytecode scan, a coordinated Mirror Node `web3` change, ecosystem migration, and carries fund-safety risk if
  any conversion is missed. Sub-tinybar remainder semantics need a
  decision.

### Option 2: Make no behavior change and fix the documentation to describe the actual behavior

Keep tinybar semantics inside the EVM. Correct the published docs per
[hedera-docs#792](https://github.com/hashgraph/hedera-docs/issues/792) and merge
[hedera-docs#801](https://github.com/hashgraph/hedera-docs/pull/801):

* State plainly: **inside Solidity, `msg.value`, `address.balance`, and the value operand of `call` are tinybars (8
  decimals), while weibars (18 decimals) appear only at the Ethereum-transaction and JSON-RPC boundary (the signed
  transaction `value`, `eth_getBalance`, `eth_gasPrice`)**. State also that the node divides an Ethereum
  transaction's weibar `value` by 10^10 before the EVM runs, dropping any sub-tinybar remainder, and that a native
  `ContractCall` `amount` is already in tinybar.
* Pages to correct: `evm/development/troubleshooting`, `evm/hedera-services/system-contracts/exchange-rate`,
  `evm/hedera-services/hts-solidity/create-tokens` (payable-amount rule: price plus 20%, short payment returns
  `INSUFFICIENT_TX_FEE` and a zero address without reverting), `evm/tokens/whbar`, `evm/differences/hbar-decimals`
  (split into "JSON-RPC wire: 18" and "inside Solidity: 8"), and the glossary.
* Add an explicit entry to the "Hedera EVM vs Ethereum" differences list, with a before/after Solidity example
  (`require(msg.value >= 1e8)` instead of `1 ether`).
* Keep the Hedera-specific `BALANCE` workaround in the spec-test fork (#27582, #20222), documented as an intended
  deviation, and record it in the spec-test fork's README.
* Fix the WHBAR page: the reference contract (`hashgraph/hedera-smart-contracts`,
  `examples/tokens/whbar/contracts/WHBAR.sol`) uses `msg.value` as tinybars directly, so the Solidity-side wrap
  example is `deposit{value: 10 * 10**8}()`, and only the JSON-RPC/ethers `value` is 10^18. Also correct the source
  link. Verify the other docs examples empirically on a Solo/testnet network before publishing (compile with solc >=
  0.8.24 and run).
* Good: no risk to deployed contracts or funds, no HIP, activation or Mirror Node change. It fixes the immediate harm
  (developers following wrong docs), and it can ship now.
* Bad: Hedera stays non-equivalent to Ethereum for value semantics, every wei-based Ethereum library needs
  adaptation, the spec-test suite keeps a permanent workaround, and the question is deferred, not answered.

## Proposed Decision Outcome

**Chosen option: Option 2: Make no behavior change and fix the documentation to describe the actual behavior.**

Reasons:

* The docs, not the node, are the inconsistent party. Option 2 removes the harmful inconsistency immediately and at
  near-zero risk.
* Option 1 is a breaking, network-wide change to already-deployed immutable contracts whose scale of dependency on
  tinybar semantics is unmeasured. We have no mainnet bytecode data, and any estimate of impact would be a guess.
* No evidence yet that the tinybar choice was an oversight rather than a long-standing de facto contract that
  developers have been coding against.
    * Option 1 stays available. Revisit it if the team decides whether EVM equivalence for value semantics is a product
      goal
      that outweighs the migration cost

### Consequences

* Positive: developers get correct guidance, no consensus change or activation, no risk to funds.
* Negative: value semantics remain a documented Ethereum divergence, the `amsterdam-hedera` spec-test fork keeps the
  `BALANCE` workaround, and tooling that assumes wei needs documented adapters.
* Scope: no consensus-node release, HIP or Mirror Node change is required. Ticket 1 in Feature Slicing is the only
  change in this repository, and it is documentation and tests only.

### Dependencies

**1. Documentation team**

* [hedera-docs#801](https://github.com/hashgraph/hedera-docs/pull/801): review and merge (open, no reviews yet). Needed
  for all documentation corrections.
* Agreement on the wording "tinybar inside Solidity, weibar on the JSON-RPC wire". Needed for #801 and the new
  "Hedera EVM vs Ethereum" differences entry.

**2. Smart Contract team**

* A Solo or testnet network and solc >= 0.8.24, to verify every documentation example empirically (ticket 2).

### Feature Slicing (Tickets)

Each tickets is independently shippable and ordered by value.

| # | Title                            | Scope                                                                                                                                                                                                      | Estimate |
|---|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| 1 | Pin the behavior in this repo    | Javadoc on `DispatchingEvmFrameState.getBalance()` and `HederaEvmTransaction.weiValue()` stating the unit is tinybar, plus unit tests asserting `BALANCE`/`CALLVALUE` return tinybars, and no logic change | 1d       |
| 2 | Verify docs examples empirically | Compile and run the corrected snippets (exchange-rate, create-tokens, WHBAR) against Solo and fix any remaining wrong example (WHBAR source already checked)                                               | 1d       |

## References

* https://github.com/hiero-ledger/hiero-consensus-node/issues/27582
* https://github.com/hashgraph/hedera-docs/issues/792
* https://github.com/hashgraph/hedera-docs/pull/801
* [HIP-410](https://hips.hedera.com/#hip-410) (Value of gas price and value fields)
* https://docs.hedera.com/evm/differences/hbar-decimals
* https://docs.hedera.com/evm/development/troubleshooting
* https://docs.hedera.com/evm/hedera-services/system-contracts/exchange-rate
* https://docs.hedera.com/evm/tokens/whbar