# Block-stream amplification proofs of concept

Working, runnable subprocess proofs that a user can cheaply generate enormous transaction output under mainnet throttles
and fill the block execution budget, so that honest submissions (and, with #28153, due scheduled transactions) are
rejected with `THROTTLED_AT_CONSENSUS`. There is one test class per attack vector from the amplification catalog.

## Branch

`petreze/block-amplification-pocs` = `main` + **#27718** (Block Stream State Changes diffing) + **#28153** (Update
Execution Budget to reject Scheduled Transactions), i.e. both PRs as if already merged. The #27718 delta encoding is on
in every proof (`enableStateChangeDeltas=true`, `stateChangeDeltaMinSaving=16`).

## How each proof works

Every test is a one-node `@GenesisSubprocessTest` (a real subprocess network, not embedded) started in
`blockStream.streamMode=BLOCKS` with a deliberately small `maxBlockExecutionSizeBytes`, the real mainnet throttles
loaded from `testSystemFiles/mainnet-throttles-hv.json` (including the HIP-1313 high-volume lanes), and
`networkAdmin.highVolumeThrottlesEnabled=true`. Setup is paid by the throttle-exempt `GENESIS` account; the attacker is a
normal civilian account. Each proof floods one cheap transaction type until the budget trips, then asserts at least one
attacker transaction is rejected `THROTTLED_AT_CONSENSUS` (see `BlockAmplificationSupport.floodAssertingBudgetRejection`).
Classes carry `@OrderedInIsolation` so their methods each get a fresh network.

## Vectors (all classes pass; 23 methods total)

| #  |                  Class                  |               Methods               |           Measured single-transaction crossing (from a run)           |
|----|-----------------------------------------|-------------------------------------|-----------------------------------------------------------------------|
| 1  | `PrngAmplificationTest`                 | prng (0x169 child-record copy)      | one call copied **60,416 B** into child records, over a 64 KiB budget |
| 2  | `EvmOutputAmplificationTest`            | return, revert, logs                | one call added **262,177 B** of result data over 64 KiB               |
| 3  | `FileAppendAmplificationTest`           | normal, high-volume                 | one append re-emitted **65,653 B** (whole file) over 48 KiB           |
| 4  | `HtsChildCallDataAmplificationTest`     | hts (0x167 child-record copy)       | crossed a 32 KiB budget via copied `call_data`                        |
| 5  | `ApprovedSpendAmplificationTest`        | approvedSpend (owner never signs)   | one debit re-emitted **2,874 B** (owner's whole list) over 8 KiB      |
| 6  | `AtomicBatchAmplificationTest`          | batch (packs output past the gate)  | one batch added **524,321 B** over 64 KiB                             |
| 7  | `AllowanceAmplificationTest`            | normal, high-volume                 | one approval re-emitted **2,874 B** (whole allowance list) over 8 KiB |
| 8  | `MaxSizeInputAmplificationTest`         | maxSizeInput (input bytes)          | ~5 KiB signed-txn bytes each over a 32 KiB budget                     |
| 9  | `NftAmplificationTest`                  | transfer, serial-approval, mint(HV) | NFT relinks/mints crossed an 8 KiB budget                             |
| 10 | `EthereumCallDataEchoAmplificationTest` | ethEcho (bug-bounty-95044)          | one reverting eth tx echoed **100,417 B** of call data over 64 KiB    |
| 11 | `EvmOutputAmplificationTest` (logs)     | logs                                | LOG data in the result, past the 256 KiB trace limiter                |
| 12 | `BudgetRejectedContractCallTest`        | free-rejection deterrent gap        | rejected calls charged ≥10× less than the executed call               |
| 13 | `ScheduleCreateAmplificationTest`       | normal, high-volume                 | stored inner body floods a 16 KiB budget                              |
| 14 | `TopicTokenListAmplificationTest`       | topic, token                        | whole-entity re-emit floods a 4 KiB budget                            |
| 15 | `ScheduleSignAmplificationTest`         | scheduleSign (signatories)          | growing signatories re-emit floods a 4 KiB budget                     |

**Not civilian-reachable (no exploit PoC):** Node certificate and CLPR-singleton re-emits are node- or
governance-gated — a normal user cannot trigger them, so there is no amplification to demonstrate; the existing
authorization is the mitigation.

## Running

Run the whole suite, a class, or a single method:

```
./gradlew :test-clients:hapiTestGenesisSubProcess \
  --tests 'com.hedera.services.bdd.suites.blockamplification.*'

./gradlew :test-clients:hapiTestGenesisSubProcess \
  --tests 'com.hedera.services.bdd.suites.blockamplification.FileAppendAmplificationTest'
```

Each method boots its own genesis subprocess network (~1 minute). Watch the node log for the proof:

```
BlockStreamManagerImpl - Block #N reached a serialized size of ... bytes after adding ... bytes,
exceeding the ...-byte max-block-size-throttle limit; rejecting further dispatches in this block with THROTTLED_AT_CONSENSUS
```

## Note on budget sizes

The budgets here (4–64 KiB) are shrunk from the mainnet 50 MiB so a single one-node network crosses them in a handful
of transactions and the proof stays fast. The amplification ratios are the point, not the absolute budget: a FileAppend
re-emits the whole file, an EVM call emits its whole return/revert payload, an approval re-emits the whole allowance
list — each for the price of one small transaction — so the same ratios fill the real 50 MiB budget at mainnet scale.
Full worst-case sizing, high-volume multipliers and mitigations are in the `.context` reports on the analysis branch.
