# Throttle Design Document #

This documents the design of the Hedera throttling system. It is used on each node to limit how many transactions it will accept from users each second. It is used in `handleTransaction` to decide when to engage emergency congestion pricing. And it is used in some of the tests.

The throttling is based on a set of _buckets_. A bucket might be defined as something like this:

```
{
    "name": "ThroughputLimits",
    "burstPeriod": 1,
    "throttleGroups": [
        {
            "opsPerSec": 10000,
            "operations": [
                "CryptoCreate", "CryptoTransfer", "CryptoUpdate", "CryptoDelete", "CryptoGetInfo", "CryptoGetAccountRecords",
                "ConsensusCreateTopic", "ConsensusSubmitMessage", "ConsensusUpdateTopic", "ConsensusDeleteTopic", "ConsensusGetTopicInfo",
                "TokenGetInfo",
                "ScheduleDelete", "ScheduleGetInfo",
                "FileGetContents", "FileGetInfo",
                "ContractUpdate", "ContractDelete", "ContractGetInfo", "ContractGetBytecode", "ContractGetRecords", "ContractCallLocal",
                "TransactionGetRecord",
                "GetVersionInfo", "UtilPrng"
            ]
        },
        {
            "opsPerSec": 13,
            "operations": [ "ContractCall", "ContractCreate", "FileCreate", "FileUpdate", "FileAppend", "FileDelete" ]
        },
        {
            "opsPerSec": 3000,
            "operations": [
                "ScheduleSign",
                "TokenCreate", "TokenDelete", "TokenMint", "TokenBurn", "TokenUpdate", "TokenAssociateToAccount", "TokenAccountWipe",
                "TokenDissociateFromAccount","TokenFreezeAccount", "TokenUnfreezeAccount", "TokenGrantKycToAccount", "TokenRevokeKycFromAccount"
            ]
        }
    ]
}
```

Imagine that there is a bucket that holds one liter of water, but it has a leak that causes it to drain at a rate of one liter per second (losing a billionth of a liter every billionth of a second). When the bucket is full, it will take one second to become completely empty.  Every time a transaction is submitted, it adds some water to the bucket. And when the bucket is full, it won't accept any more transactions. Once enough of the water drains out, it will start accepting new transactions again.

In the example above, the `ContractCreate` transaction and `ContractCall` transaction are each set to 13 transactions-per-second (tps). Each time a `ContractCreate` is submitted, another 1/13 of a liter of water is added to the bucket. If 13 of them come in all at once, it will completely fill the bucket, and it will stop accepting more until at least 1/13 of a liter has leaked out. That will take 1/13 of a second.  If there are no more transactions for half a second, then it will allow another 6 to come in all at once. And if there are none for a full second or more, then it will be completely empty, so it will allow 13 to come in all at once.

Similarly, if a `ContractCall` comes in, it adds 1/13 of a liter. If a `TokenMint` comes in, it will add 1/3000 liter. And if a `CryptoTransfer` comes in, it will add 1/10000 of a liter.

Overall, this means that that in one second, it will allow 10,000 crypto transfers, or 13 contract calls. Or it can do half of 10,000 crypto transfers and half of 13 contract calls. Or any other way of dividing up the bucket between the 6 transaction types listed.

However, that means that it is possible for contract calls to completely use all available resources and starve out the  crypto transfers. But we might want to ensure that no matter how many contract calls are attempted, there will always be at least some reserved time for a few crypto transfers.  To accomplish that, we can add a second bucket.

If there are multiple buckets, then an incoming transaction adds water to _all_ of the buckets that list it. And it will be reject if _any_ of those buckets are full.  So we can accomplish the above goal by adding a second bucket like this:

```
{
    "name": "PriorityReservations",
    "burstPeriod": 1,
    "throttleGroups": [
        {
            "opsPerSec": 10,
            "operations": [ "ContractCall", "ContractCreate", "FileCreate", "FileUpdate", "FileAppend", "FileDelete" ]
        }
    ]
}
```

This bucket gains 1/10 of a liter of water every time there is a contract call, and never gains water for any other transaction.

Suppose now, that there are no transactions for a long time (at least one second), and then there is a fast stream of contract calls. The first bucket gains 1/13 of a liter for each one, and the second bucket gains 1/10 of a liter for each one. So if 10 contract calls come in at once, the first bucket will have 10/13 of a liter of water, and the second bucket will have a full liter (10/10) of water. If another contract call comes in immediately, it will be rejected, because the second bucket is full.  Then, if a crypto transfer comes in, it can execute fine, because the first bucket still has some room left (3/13 liters), and the second bucket doesn't list the crypto transfer as being in it, so the fact that the second bucket is full doesn't block the crypto transfer from being submitted.

In this way, we can ensure that there will never be more than 10 contract calls per second, and that even when that full 10 tps comes in, there will still be leftover resources to handle quite a few crypto transfers (3/13 times 10,000 each second).

It might even be useful to add a third bucket:

```
{
    "name": "CreationLimits",
    "burstPeriod": 10,
    "throttleGroups": [
        {
            "opsPerSec": 2,
            "operations": [ "CryptoCreate" ]
        },
        {
            "opsPerSec": 5,
            "operations": [ "ConsensusCreateTopic" ]
        },
        {
            "opsPerSec": 100,
            "operations": [ "TokenCreate", "TokenAssociateToAccount", "ScheduleCreate" ]
        }
    ]
}
```

This bucket throttles how many new entities can be created each second. The goal here isn't to throttle because entity creation is slow. It's because entity creation causes long-term use of memory, and so should not be allowed to happen too many times.  These transactions are in the first bucket with much higher tps, because they are fast operations, and don't compete much with other fast transactions. But they are also in this bucket to ensure that there aren't too many of them happening per second, so that the number of entities in memory will not grow too fast.

A final bucket is valuable to put some ceiling on the queries-per-second (qps) accepted for the free account balance and transaction receipt queries.

```
{
    "name": "FreeQueryLimits",
    "burstPeriod": 1,
    "throttleGroups": [
        {
            "opsPerSec": 1000000,
            "operations": [ "CryptoGetAccountBalance", "TransactionGetReceipt" ]
        }
    ]
}
```

An additional mechanism for throttling contract-related transactions (`ContractCall`, `ContractCallLocal`, `ContractCreate`) was added soon after the original release. It involves a `frontend` (gRpc incoming transactions to the node) maximum gas per second throttle and a `consensus` (backend upon start of the actual execution of the transaction) maximum gas per second allowed property. These are configured by the `contracts.maxGasPerSec` (frontend) and `contracts.maxGasPerSecBackend` (backend) network properties, with `contracts.throttle.throttleByGas` toggling enforcement.
Transactions throttled by the frontend receive a status `BUSY` and transactions throttled on the consensus level receive a status `CONSENSUS_GAS_EXHAUSTED`. Transaction may still be throttled by the `opsPerSec` buckets where contract-related ops are listed.

So this is how Hedera does its throttling. It uses four buckets: one for speed, one for reserving some speed, one for limiting entity creation, and one for putting some mild limitations on free queries. Each incoming transaction adds water to only the buckets that list it. And it is blocked only if one of those buckets is full.

> The throttling subsystem has grown beyond TPS buckets and the contract-gas throttle described above. In addition to those, the runtime now applies a jumbo-transaction bytes-per-second throttle on the frontend, an EVM ops-duration throttle on the contract path, congestion-pricing multipliers driven by both entity and gas utilization, a separate schedule-service throttle for transactions scheduled for future execution, and HIP-1313 high-volume pricing tiers. See [`design/app/throttles.md`](design/app/throttles.md) for the up-to-date code organization, transaction flow, and state-persistence model, and [`design/app/state-throttling-tests.md`](design/app/state-throttling-tests.md) for the steady-state HAPI test suite that validates this behavior.
