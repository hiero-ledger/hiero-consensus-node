# Quiescence

---

## Summary

Quiescence is a feature that stops event creation when there are no transactions. The purpose of this is to reduce the
amount of data produced and bandwidth used by low volume networks.

| Metadata           | Entities             |
|--------------------|----------------------|
| Designers          | Lazar Petrović       |
| Functional Impacts | Consensus, Execution |
| Related Proposals  | N/A                  |
| HIPS               | N/A                  |

---

## Purpose and Context

The purpose of this is to reduce the amount of data produced and bandwidth used by low volume networks. When there are
no transactions being submitted to the network, the network can stop creating events and thus stop gossipping and
producing blocks. This can drastically reduce the amount of data that needs to be stored long term as well as the amount
of bandwidth used by the network.

### Requirements

- When transactions stop being submitted to the network, the network should stop producing events in a timely manner.
  This will happen after the submitted transactions reach consensus or become stale.
- The amount of time it takes the network to stop should be close to the C2C time of the network.
- When a transaction is submitted to a quiesced network, the network should start producing events and reach consensus
  on this newly submitted transaction.
- No existing functionality should be affected.
- Metrics should not produce misleading information due to the pause in event creation. Example: `secC2C` tracks the
  amount of time that passes from an event being created to it reaching consensus. Ordinarily, this is a few seconds. If
  this value spikes, it is usually an indicator of a performance issue in the network. If quiescence is not taken into
  account, this value will spike to the amount of time the network was quiesced, which would look like an issue, but is
  expected behavior.

---

## Quiescence mechanisms

The quiescence feature can be broken up as follows:

- Detecting when to quiesce (quiescence conditions)
- Quiescing
- Breaking quiescence
- Side effects of quiescence

### Quiescence conditions

In its simplest form, detecting when to quiesce is done by counting non-ancient non-consensus transactions. If there are
no non-ancient non-consensus transactions, there is nothing to reach consensus, so we can stop creating events.
Additionally, we should also check if there are any pending transactions, if there are, we should not quiesce.

One complication comes from state/block signature transactions. These transactions do not need to reach consensus.
However, they do need to be gossiped (for block signatures it has not yet been decided if they need to reach consensus
as of this writing, this document will proceed assuming they don't need to). If we want a fully signed state/block,
we need to create an event with this transaction and then stop creating events immediately after. If we create too many
events, we can reach consensus on another round. If we reach consensus again, we will have another state/block to sign.
The consensus module will need to be able to distinguish between signature transactions and normal transactions. An
additional API is needed for this.

Another exception is due to the freeze mechanism. Because the freeze mechanism relies on consensus time advancing, it
cannot occur if the network is quiescing. To circumvent this problem, quiescence cannot occur 1 minute before the freeze
time based on the wall-clock.

### Quiescing

Once all the conditions are met, we can quiesce. This is done by simply stopping event creation. The consensus module
will stay in this state until:

- A transaction is submitted to the node that needs to be put into an event
- OR a node sends us an event with transactions that need to reach consensus

If either of these conditions is met, we will break quiescence.

### Breaking quiescence

Breaking quiescence is simply starting event creation again. The complication comes from the tipset algorithm and other
mechanisms that prevent uncontrolled event creation. Creating an event that does not advance consensus is generally
considered a bad thing. But if only one node receives a transaction from an end user, it might not be possible to create
an event that advances consensus. This means that for quiescence, we need to introduce an exception.

The proposal is that we can have events that are do not follow the same rules in the tipset and other algorithms. These
events will be used for breaking quiescence in situations like the one described above. An event used to break
quiescence will be called a QB (Quiescence Breaker). A QB will not have other-parents, only a self-parent. By having
only a self-parent, the QB can be easily identified and special rules can be applied to it. To prevent malicious nodes
from flooding the network with QBs, a QB should not be allowed to have another QB as a self-parent.

Another condition for breaking quiescence is that the wall-clock time is 1 minute before the freeze time. If this
occurs, while the network is quiescing, the network should resume creating events regulaly. There is no need to create a
QB in this case, since the whole network will be resuming event creation.

### Side effects of quiescence

Various parts of the system assume that events are constantly being created and consensus is always advancing. With
quiescence, this is not the case. This means that various parts of the system need to be modified to account for this.

- The `SignedStateSentinel` uses wall-clock time to determine if a signed state is old. This will need to be modified to
  use the take quiescence into account.
- The platform status `ACTIVE` currently moves to `CHECKING` based on wall-clock time. We will need to add a
  `QUIESCED` status.
- Various metrics can be misleading if the network is quiescing.
- NOTE FOR REVIEWERS: I probably haven't thought of all the side effects yet, please add any you can think of.

---

## Changes

### Architecture and/or Components

- Each transaction we store in an event or the transaction pool needs to have an additional boolean that indicates if it
  needs to reach consensus or not.
- We will need functionality to detect non-ancient transactions that need to reach consensus. This should be part of the
  event creation module.
- The event creator should stop creating events if there are no transactions that need to reach consensus, unless there
  are pending transactions, or there is less than 1 minute before the freeze time according to the wall-clock.
- The event creator should update the platform status to `QUIESCED` when appropriate.
- The event creator should create a QB if there are transactions that need to reach consensus, and there are
  restrictions on creating events that advance consensus.

### Public API

An additional API is needed for the consensus module to determine if a transaction needs to reach consensus or not.

- When we receive transactions as part of events through gossip, we need to check if they need to reach consensus. This
  should be done with a new method in the `SwirldMain` interface with a definition like:
  `boolean needsToReachConsensus(Bytes transaction)`. This method should become part of `ApplicationCallbacks`.
- When transactions are being submitted to the platform before being put into an event, we also need this same
  information. So the method in `Platform` should be changed to:
  `boolean createTransaction(byte[] transaction, boolean needsToReachConsensus)`.

### Configuration

A new configuration option is needed to enable/disable quiescence.

### Metrics

The following metrics should be added:

- `numTransNeedCons` the number of non-ancient transactions that need to reach consensus.

The following metrics should be modified:

- `secC2C` & `secR2C` should be modified to only track events that have transactions that need to reach consensus. If
  this is not done, these metrics will have huge spikes when quiescence is broken.

The following metrics should be removed since they would need to be modified, but are not used:

- `secC2RC`
- `secSC2T`
- `secOR2T`
- `secR2F`
- `secR2nR`

---

## Test Plan

### Unit Tests

New unit tests for the event creator should be written for the following scenarios:

- If no non-ancient transactions need to reach consensus, and there is no upcoming freeze, it should stop creating
  events.
- If the wall-clock time is less than 1 minute before the freeze time, it should create events regardless of any
  transactions that are pending or need to reach consensus.
- If it is quiesced and there is a pending transaction that does not need to reach consensus (a state/block signature),
  it should create only a single event with that transaction.
- If it is quiesced and there is a pending transaction that needs to reach consensus, it should create a QB event with
  that transaction.
- If a QB is created, it should not create another QB with the same self-parent even if there are pending transactions
  that need to reach consensus.

### Integration Tests

An Otter test should be created that submits transactions periodically and validates the following:

- event creation is stopped when there are no transactions and no freeze is upcoming
- event creation is started again when a transaction is submitted
- platform status is updated to `QUIESCED` when event creation is stopped
- a freeze should be set at the end of this test, and the freeze should occur even if no transactions are submitted
