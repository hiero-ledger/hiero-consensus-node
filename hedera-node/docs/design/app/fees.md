# Fees

The current implementation of the fee engine is complex, containing a mixture of 3 different implementations that work
together. Some of this implementation exists in `hapi-fees`, some in `hapi-utils`, and the rest in
`hedera-mono-service`. A single json file, `feeSchedules.json`, contains values for fee components that are used, with
custom equations for different services.

A new design is proposed that will simplify the implementation, based on a far simpler "base price + up-sale" model.
That design is out of scope of this document.

In both the current and new design, fees are a function of:
 - the transaction itself
   - The protobuf encoded number of bytes making up the signature map
   - The number of UTF-8 characters in the memo
   - The number of signatures that were verified
   - The number of cryptographic keys on the payer account
   - Other details intrinsic to the specific transaction type
 - the current state
   - The percentage of entity space remaining (i.e. 900M accounts with a max of 1B accounts would be 90% full)
   - Determining the cost if extending expiry
   - Other state intrinsic to the specific transaction type
 - The backend usage throttles
   - These are deterministic throttles updated only during `handle` with the full network throttles (i.e. 10K TPS)
 - The current exchange rate

It can be seen that some of the fee calculation is based on generic transaction or state information, and some is based
on transaction or entity specific information. Our design must account for both common fee inputs and per-transaction
fee inputs. The current design is based on _usage_ and _fees_. The fees are computed based on the usage. The main task
is therefore to determine the usage, and from this, compute the fees.

Fees must be applied BEFORE execution, so we can detect insufficient funds on crypto transfer in case fees makes it so.

### Transactions and The Current State

Each different type of transaction has its own function for computing the fees for the transaction. For example, a
crypto transfer has a base fee of $0.001, but additional fees are added for each signature, and for each account in the
transfer list. Only the crypto transfer logic in the token service can know exactly how to compute these fees.

As another example, the smart contract system's `ContractCall` transaction is based on a base cost of 21,000 gas, plus
any additional gas used, plus an 80% minimum based on the max-gas, converted to HBAR and applied as service fees. It is
true that each individual transaction handler has to implement the logic for at least part of the fee computation.

On the `HandleContext` is a `FeeCalculator`. This can be used to record usage information. It has been preloaded with
usage information common to all transactions. The handler must update the calculator with any additional usage
information, and then use it to calculate the fees. Once the fees are obtained, the handle must use the
`FeeAccumulator` (also retrieved from the context) to charge the fees. If this is not done, no fees are charged. We will
definitely revisit this design when we update the fee model.

### Scheduled Transaction

We only compute the service fee at the time it is triggered. The inner transaction does not have a node or network fee.
The inner transaction has its own payer, or if it is left unset at the time schedule create, then the Payer of the
schedule create is the payer. Since there is no node or network fees, if the inner transaction fails, or the payer
doesn't have sufficient funds for the inner transaction, then the payer doesn't pay for it. This is consistent with
other transactions where if the payer has insufficient funds or in some other way the transaction fails, they pay only
for node + network fees, and not service fees.

NOTE: We charge service fees BEFORE we execute, and you never get a refund. Except for smart contracts, see below.

### Smart Contracts

For smart contracts, we don't know exactly how much the transactions will cost until after the transaction has been
executed. We take the max gas of the transaction and use that as a basis for computing the amount of HBAR that the
payer MUST have for us to even proceed. If they don't have enough, then we reject the transaction. If they do have
enough, then we proceed to execute the transaction, and **refund** any HBAR that was not used.

There is a difference between the fee calculation to compute the amount of HBAR that the payer must have, and the amount
that the payer will actually be charged.

Smart contract is complicated further by the algorithm used to determine whether the relay, or the payer pays for the
fees, and when we refund, we need to sometimes refund the relay up to some amount, and then the rest to the payer.
In other words, when computing fees and refunds, it is NOT true that the payer, solely, pays for fees and gets refunds.
In the case of smart contracts, the relay is ALSO involved.

There may be an analogous issue with auto-renew, where we charge the auto-renew account up to the amount we can and then
charge the payer or the account itself, etc. The key is, that multiple parties, not just the payer, may be involved
in paying fees, in some cases!

### Estimating Fees on Ingest

During ingest, we need to know the node and network fees for a transaction, so the node can know that the payer has
enough HBAR to pay for the transaction itself. We could let nodes put some kind of estimate surcharge over the normal
estimate to minimize any fluctuation in exchange rate between ingest and handle, so the node isn't failing due-diligence.

OR, if the node underestimates the fees, the users account is drained + the delta from the node. At the moment, we just
charge the node the full fee for due-diligence failures.

### Throttles

There are two different throttle values. There is the "front-end" throttle, used to reject excessive numbers of
transactions. These throttles are per-node (set to L/N, where L is the network-wide throttle limit, and N is the number
of nodes), and not in state. Given a network-wide throttle like 10,000 TPS, and a 100 node network, each node would have
throttles set to 100 TPS.

The second set of throttles are "back-end", network wide, deterministic throttles. These throttles are updated ONLY
during the handle-transaction phase, on a single thread, and use the consensus timestamps as the basis for measuring
the amount of elapsed time. These throttles are used to compute congestion pricing. Thus, even if one node is hardly
used but all other nodes are heavily used, the congestion pricing will be the same for all nodes.

There is a generic configuration for the congestion pricing, which is a utility level relative to the maximum and how
long it is sustained. We have a step function, 10x at 90%, etc. But it could be some other calculation. The key is that
this is applied uniformly. If a given transaction was in multiple buckets, then we'd take the bucket with the highest
utilization and use that for computing congestion pricing (or do an independent surcharge for each bucket exceeded?).

TODAY: We look at which throttle buckets crypto transfer is mapped to, look at its current utilization relative to the
total capacity, if that percent is above the multiplier, then congestion starts. Once above it for the congestion
period, then the pricing kicks in. Once you've been above those for a minute, the multiplier starts, once we drop
below one of those limits, then it clears the flag, and we go back to normal pricing. Use the max utilization.

### Expiry

Throughout the normal execution of the system, we perform some periodic tasks such as handling expiration of entities.
In this case, there is no user transaction for computing the fees. Instead, an alternative method is used to compute
the cost of extending expiration.

**NEXT: [gRPC](grpc.md)**
