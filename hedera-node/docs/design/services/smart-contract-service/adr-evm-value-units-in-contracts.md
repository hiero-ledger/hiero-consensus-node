# Units of `BALANCE`, `SELFBALANCE` and `CALLVALUE` inside the Hedera EVM

## Context and Problem Statement

Inside the Hedera EVM, `BALANCE`, `SELFBALANCE` (`address.balance`) and `CALLVALUE` (`msg.value`) return **tinybars** (1 HBAR = 10^8 tinybar). Ethereum defines them in **wei** (1 ETH = 10^18 wei). Hedera maps 1 tinybar to 10^10 weibar ([HIP-410](https://hips.hedera.com/#hip-410)), so a contract sees a number 10^10 times smaller than the EVM-equivalent value. For example, an account holding 0.29 HBAR reports `29_000_000` instead of `290_000_000_000_000_000` ([#27582](https://github.com/hiero-ledger/hiero-consensus-node/issues/27582)).

Where the tinybar behavior comes from (verified in https://github.com/hiero-ledger/hiero-consensus-node at `c31ff872a52`):

| Opcode                   | Code path                                                                                                                                 | Unit    |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|---------|
| `BALANCE`, `SELFBALANCE` | `DispatchingEvmFrameState.getBalance()` returns `Wei.of(validatedAccount(id).tinybarBalance())`                                           | tinybar |
| `CALLVALUE`              | `HederaEvmTransaction.weiValue()` returns `Wei.of(value)`, passed by `FrameBuilder` as value and apparent value                           | tinybar |
| `CALL`/`CREATE` value    | executed via `frame.getValue().toLong()` at (`CustomMessageCallProcessor.doTransferValueOrHalt`, `CustomContractCreationProcessor.start`) | tinybar |

Ethereum transactions arrive with `value` in weibar (`EthTxData.value`) and are divided by `WEIBARS_IN_A_TINYBAR` before the EVM runs (`EthTxData.getAmount()` / `effectiveTinybarValue()`), so only the JSON-RPC wire format is weibar.

**Problem:** should the node change what the opcodes return to match Ethereum (and the current docs), or keep the behavior and make the documentation match it?

## Decision Drivers

* **EVM equivalence:** Solidity libraries, tooling and the upstream Ethereum execution spec tests (`hiero-ethereum-execution-spec-tests`, `forks/amsterdam-hedera`) assume wei.
* **Backwards compatibility:** the behavior has been live since the 2023 services rewrite (and likely earlier). Any contract deployed with tinybar assumptions (`require(msg.value == 100_000_000)`, `.call{value: tinybarAmount}`) would break. Deployed immutable contracts cannot be patched.
* **Fund safety:** value mis-scaling can lock, burn or mint value from a contract's perspective. Sub-tinybar remainders of a weibar value cannot be represented on the ledger.
* **Blast radius:** a change touches `BALANCE`, `SELFBALANCE`, `CALLVALUE`, the value operand of `CALL`/`CALLCODE`/ `CREATE`/`CREATE2`, system contracts that read `msg.value` (`ClassicCreatesCall`), the Bonneville EVM, Mirror Node `web3`, hooks and CLPR dispatches.
* **Correctness of published guidance:** developers are being told the wrong units today, regardless of what we decide about the node.
* **Cost and time:** a docs fix is available now. A HIP, activation and ecosystem migration requires huge effort.
* **Consistency across surfaces:** relay and `eth_getBalance` already speak weibar. HAPI, SDKs, mirror REST and record/block streams speak tinybar.

## Considered Options

### ❌ Option 1: Change the EVM to report and consume values in weibar

Scale the ledger's tinybar values at the EVM boundary, as proposed in [#27582](https://github.com/hiero-ledger/hiero-consensus-node/issues/27582). The ledger, HAPI, SDKs, streams and mirror REST stay in tinybar. It is a breaking change and requires a HIP.

| Item                                                 | Current behavior           | New behavior (proposed)                                                                                                                         |
|------------------------------------------------------|----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `BALANCE`, `SELFBALANCE`, `address.balance`          | `tinybarBalance`           | `tinybarBalance * 10^10`                                                                                                                        |
| `CALLVALUE`, `msg.value`                             | value in tinybar           | `tinybarValue * 10^10` (for Ethereum txs, the amount actually transferred, scaled)                                                              |
| `CALL`/`CALLCODE`/`CREATE`/`CREATE2` value operand   | interpreted as tinybar     | interpreted as weibar, converted to tinybar exactly (`/ 10^10`) before transfer, and a non-multiple of 10^10 fails the call (recommended, open) |
| System contract `msg.value` (e.g. HTS token create)  | compared to a tinybar fee  | caller must send weibar, and the contract converts once                                                                                         |
| `1 ether` in Solidity                                | 10^18 tinybar (10^10 HBAR) | 1 HBAR                                                                                                                                          |
| `GASPRICE`, `tx.gasprice`                            | tinybar per gas            | open: if left unchanged it stays tinybar while `BALANCE` and `msg.value` become weibar, which is another inconsistency to settle                |
| `BASEFEE`, solvency checks                           | unchanged                  | unchanged (stay in tinybar internally)                                                                                                          |
| HAPI `amount`, record/block stream, mirror REST, SDK | tinybar                    | tinybar (unchanged)                                                                                                                             |

* Good: EVM-equivalent, spec-test workaround removed, matches what the docs claim today, simplifies developer mental model (`msg.value` equals the relay's `value`).
* Bad: breaking for existing contracts with tinybar constants (exact-value checks, refunds mixing `msg.value` and a tinybar fee, exchange-rate comparisons, HTS payable creates, tinybar `.call{value:}` amounts). It requires a HIP, a mainnet bytecode scan, a coordinated Mirror Node `web3` change, ecosystem migration, and carries fund-safety risk if any conversion is missed. Sub-tinybar remainder semantics need a decision, and so does whether `GASPRICE` is also scaled to weibar.
* Rejected because: it is a breaking, network-wide change to already-deployed immutable contracts whose scale of dependency on tinybar semantics is unmeasured. We have no mainnet bytecode data, and any estimate of impact would be a guess.

### ❌ Option 2: Change the JSON-RPC relay to report values in tinybar

Leave the EVM as is and make the relay speak tinybar, so `eth_getBalance`, the `value` of `eth_call` and `eth_estimateGas`, and `eth_gasPrice` agree with what Solidity sees (`address.balance`, `msg.value`).

* Good: removes the mismatch between the relay and the in-EVM view without touching consensus code.
* Bad: the `value` inside a signed Ethereum transaction is RLP-encoded and signed by the user in weibar (`EthTxData.value`, HIP-410), so the relay cannot rewrite it without invalidating the signature. Only read-side responses and call parameters could change.
* Bad: Ethereum clients (wallets, ethers, viem, Hardhat) assume 18 decimals for a chain's native currency. Balances would display 10^10 times too large and `parseEther` would produce wrong values, breaking every JSON-RPC consumer that works today.
* Bad: it reverses the purpose of HIP-410 ("maximize compatibility with third party tools that expect ether units") and still leaves `msg.value` and `BALANCE` non-equivalent to Ethereum, so the spec-test workaround and wei-based library issues remain.
* Rejected because: does not remove the divergence: `msg.value` and `BALANCE` stay non-equivalent to Ethereum. It also cannot rewrite the signed `value` of an Ethereum transaction and would break every Ethereum client that expects 18 decimals.

### ✅ Option 3: Make no behavior change and fix the documentation to describe the actual behavior

Keep tinybar semantics inside the EVM. Correct the published docs per [hedera-docs#792](https://github.com/hashgraph/hedera-docs/issues/792) and merge [hedera-docs#801](https://github.com/hashgraph/hedera-docs/pull/801):

* State plainly: **inside Solidity, `msg.value`, `address.balance`, and the value operand of `call` are tinybars (8 decimals), while weibars (18 decimals) appear only at the Ethereum-transaction and JSON-RPC boundary (the signed transaction `value`, `eth_getBalance`, `eth_gasPrice`)**. State also that the node divides an Ethereum transaction's weibar `value` by 10^10 before the EVM runs, dropping any sub-tinybar remainder, and that a native `ContractCall` `amount` is already in tinybar.
* Pages to correct: `evm/development/troubleshooting`, `evm/hedera-services/system-contracts/exchange-rate`, `evm/hedera-services/hts-solidity/create-tokens` (payable-amount rule: price plus 20%, short payment returns `INSUFFICIENT_TX_FEE` and a zero address without reverting), `evm/tokens/whbar`, `evm/differences/hbar-decimals` (split into "JSON-RPC wire: 18" and "inside Solidity: 8"), and the glossary.
* Add an explicit entry to the "Hedera EVM vs Ethereum" differences list, with a before/after Solidity example (`require(msg.value >= 1e8)` instead of `1 ether`).
* Keep the Hedera-specific `BALANCE` workaround in the spec-test fork (#27582, #20222), documented as an intended deviation, and record it in the spec-test fork's README.
* Fix the WHBAR page: the reference contract (`hashgraph/hedera-smart-contracts`, `examples/tokens/whbar/contracts/WHBAR.sol`) uses `msg.value` as tinybars directly, so the Solidity-side wrap example is `deposit{value: 10 * 10**8}()`, and only the JSON-RPC/ethers `value` is 10^18. Also correct the source link. Verify the other docs examples empirically on a Solo/testnet network before publishing (compile with solc >= 0.8.24 and run).
* Good: no risk to deployed contracts or funds, no HIP, activation or Mirror Node change. It fixes the immediate harm (developers following wrong docs), and it can ship now.
* Bad: Hedera stays non-equivalent to Ethereum for value semantics, every wei-based Ethereum library needs adaptation, the spec-test suite keeps a permanent workaround, and the question is deferred, not answered.

## Proposed Decision Outcome

**Chosen option: Option 3: Make no behavior change and fix the documentation to describe the actual behavior.**

Reasons:

* The docs, not the node, are the inconsistent party. Option 3 removes the harmful inconsistency immediately and at near-zero risk.
* The tinybar behavior is a de facto contract. It has been consistent for some time.

### Consequences

* Positive: developers get correct guidance, no consensus change or activation, no risk to funds.
* Negative: value semantics remain a documented Ethereum divergence, the `amsterdam-hedera` spec-test fork keeps the `BALANCE` workaround, and tooling that assumes wei needs documented adapters.
* Scope: no consensus-node release, HIP or Mirror Node change is required. Ticket 1 in Feature Slicing is the only change in this repository, and it is documentation and tests only.

### Dependencies

**1. Documentation team**

* [hedera-docs#801](https://github.com/hashgraph/hedera-docs/pull/801): review and merge (open, no reviews yet). Needed for all documentation corrections.
* Agreement on the wording "tinybar inside Solidity, weibar on the JSON-RPC wire". Needed for #801 and the new "Hedera EVM vs Ethereum" differences entry.

Coverage of the pages to correct by #801 (checked against `origin/fix/792-hbar-units` at `5b589d4`, 15 files changed):

| Page                                                 | In ADR "Pages to correct" | In #801 | Notes                                                                                         |
|------------------------------------------------------|---------------------------|---------|-----------------------------------------------------------------------------------------------|
| `evm/development/troubleshooting`                    | yes                       | yes     | Tinybar sample, `call{value: 1 ether}` fixed                                                  |
| `evm/hedera-services/system-contracts/exchange-rate` | yes                       | yes     | `msg.value` compared directly with `requiredTinybars`                                         |
| `evm/hedera-services/hts-solidity/create-tokens`     | yes                       | yes     | Payable rule (price plus 20%), `INSUFFICIENT_TX_FEE`, `{value: tinybars}`                     |
| `evm/tokens/whbar`                                   | yes                       | yes     | `10 * 10**8` from Solidity, `parseEther("10")` from ethers, stale `WHBAR.sol` links repointed |
| `evm/differences/hbar-decimals`                      | yes                       | yes     | Split into "inside Solidity: 8" and "JSON-RPC wire: 18", HTML table converted to Markdown     |
| `support/glossary`                                   | yes                       | yes     | New tinybar entry, weibar entry corrected                                                     |
| `evm/development/deploying`                          | no                        | yes     | `msg.value` described as tinybars, not wei                                                    |
| `evm/development/json-rpc/index`                     | no                        | yes     | No longer says relay `msg.value` is 18 decimals                                               |
| `evm/differences/native-token-transfers`             | no                        | yes     | New "amounts are in tinybars" consideration                                                   |
| `evm/integrations/cross-chain/layerzero`             | no                        | yes     | Units note corrected, 404 links repointed                                                     |
| `evm/integrations/cross-chain/usdt0`                 | no                        | yes     | "relay expects `msg.value` in weibars" changed to "transaction `value`"                       |
| `evm/integrations/oracles/pyth`                      | no                        | yes     | Tinybar no longer called "1 wei"                                                              |
| `evm/tools/hardhat/index`                            | no                        | yes     | No longer says relay `msg.value` is 18 decimals                                               |
| `evm/tutorials/intermediate/send-receive-hbar`       | no                        | yes     | Explains `2000000000` as 20 HBAR in tinybars                                                  |
| `native/fundamentals/hbars`                          | no                        | yes     | Units text and table corrected                                                                |

Pages not changed by #801 that may still need a correction:

| Page                                                                                                      | Finding                                                                                                                                                                                             | Suggested action                                                                     |
|-----------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `evm/integrations/cross-chain/chainlink-ccip:203`                                                         | "conversions between tinybars and wei-based values may be required" is vague and does not say `msg.value` is tinybars                                                                               | Reword, link to `hbar-decimals`                                                      |
| `evm/tutorials/hedera/hts-evm/part1-mint-nfts`, `part2-kyc-update`, `part3-pause-freeze-wipe`             | Contract emits `HBARReceived(msg.sender, msg.value)` (tinybars) while the client sends `parseEther(...)` (weibar)                                                                                   | Add a comment that the event amount is in tinybars                                   |
| `skill.md:162,237`                                                                                        | Says to send HBAR via `msg.value` for HTS token creation with no unit. The required amount is the TokenCreate price plus 20% minus the fixed gas fee, all in tinybars                               | Add "(tinybars)"                                                                     |
| `evm/differences/index:45`, `evm/overview/evm-on-hedera-explained:51`, `evm/overview/choose-your-path:53` | Mention only that the relay presents 18 decimals, not that `msg.value` inside contracts is tinybars                                                                                                 | Add one clause or link to `hbar-decimals`                                            |
| `native/smart-contracts/ethereum-transaction:14-16`                                                       | Correct (the Ethereum transaction `value` is weibar)                                                                                                                                                | Optional cross-reference to `hbar-decimals`                                          |
| `evm/development/gas-fees:17-20`                                                                          | Gas in weibar, correct for JSON-RPC. Inside the EVM `tx.gasprice` is tinybars per gas (`HederaOperations.gasPriceInTinybars()` feeds `HederaEvmContext.gasPrice`, and `GasPriceSuite` asserts `71`) | Add a note that inside contracts `tx.gasprice` is tinybars per gas, like `msg.value` |

**2. Smart Contract team**

* A Solo or testnet network and solc >= 0.8.24, to verify every documentation example empirically (ticket 2).

### Feature Slicing (Tickets)

Each tickets is independently shippable and ordered by value.

| # | Title                            | Scope                                                                                                                                                                                                                                                                                                                                                                                        | Estimate |
|---|----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| 1 | Pin the behavior in this repo    | Javadoc on `DispatchingEvmFrameState.getBalance()` and `HederaEvmTransaction.weiValue()` stating the unit is tinybar, fix the misleading `// weibar` comment on `HederaEvmContext.gasPrice` (the value is tinybars) and the `weibarGasPrice` Javadoc on `EthTxData.effectiveOfferedGasPriceInTinybars`, plus unit tests asserting `BALANCE`/`CALLVALUE` return tinybars, and no logic change | 1d       |
| 2 | Verify docs examples empirically | Compile and run the corrected snippets (exchange-rate, create-tokens, WHBAR) against Solo and fix any remaining wrong example (WHBAR source already checked)                                                                                                                                                                                                                                 | 1d       |

## References

* https://github.com/hiero-ledger/hiero-consensus-node/issues/27582
* https://github.com/hashgraph/hedera-docs/issues/792
* https://github.com/hashgraph/hedera-docs/pull/801
* [HIP-410](https://hips.hedera.com/#hip-410) (Value of gas price and value fields)
* https://docs.hedera.com/evm/differences/hbar-decimals
* https://docs.hedera.com/evm/development/troubleshooting
* https://docs.hedera.com/evm/hedera-services/system-contracts/exchange-rate
* https://docs.hedera.com/evm/tokens/whbar