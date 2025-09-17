# Quiescence implementation details

## Summary

Quiescence is a feature that stops event creation when it is unnecessary.
Please refer to
the [Quiescence HIP](https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1238.md)
for the high level information about this feature.

| Metadata           | Entities             |
|--------------------|----------------------|
| Designers          | Lazar Petrović       |
| Functional Impacts | Consensus, Execution |
| Related Proposals  | N/A                  |
| HIPS               | TODO                 |

---

## Unknowns

- How will we know which events are consensus after a reconnect or restart?
- Figure out what to do about [metric modification](#metrics). The consensus module will not be tracking what needs to
  reach consensus, so it cannot modify the metrics. Should these metrics move to execution?
- Various parts of the system assume that events are constantly being created and consensus is
  always advancing. With quiescence, this is not the case. This means that various parts of the system need to be
  modified to account for this. NOTE FOR REVIEWERS: Please try to think of any possible issues that may arise from
  this change.

## Quiescence component

A new quiescence component needs to be created to track all the quiescence conditions. It needs to receive the following
data:

- Pre-consensus events
- Consensus rounds
- TCTs
- Fully signed blocks
- Transaction pool counts

### Quiescence configuration

The following configuration record should be introduced to configure quiescence:

```java

import java.time.Duration;

/**
 * Configuration for quiescence.
 * @param enabled       indicates if quiescence is enabled
 * @param tctDuration   the amount of time before the target consensus timestamp (TCT) when quiescence should not be 
 *                      active
 */
@ConfigData("quiescence")
public record QuiescenceConfig(
        @ConfigProperty(value = "enabled", defaultValue = "true")
        boolean enabled,
        @ConfigProperty(value = "tctDuration", defaultValue = "5s")
        Duration tctDuration) {
}

```

## Cross-module changes

### Transaction pool move

- The transaction pool (along with `TransactionConfig`) needs to move to execution. When the consensus module is ready
  to create a new event, it must ask execution for transactions to include in the event.
- A new required field when creating the `PlatformBuilder` will be a transaction supplier. The consensus module will use
  this supplier to put transactions in new events.
- The existing `Platform` method `boolean createTransaction(byte[] transaction)` will be removed.
- All apps need to be updated.

### Quiescence API TODO

- Two new methods on the `Platform` interface will be created:
  `void quiesce()` and `void breakQuiescence()`. These methods will send data via a new input wire to the Event Creator
  component. The Platform Status Component will either get this data directly or via the Event Creator component.

## Consensus module changes

- The transaction resubmitter component should be deleted. Output of the stale event detector should be modified to
  notify the execution of the stale events. TODO specify the API for this.
- The platform status `ACTIVE` currently moves to `CHECKING` based on wall-clock time. We will need to add a
  `QUIESCED` status.
- The `SignedStateSentinel` uses wall-clock time to determine if a signed state is old. This will need to be modified to
  use the take quiescence into account.
- `PcesConfig.minimumRetentionPeriod` uses wall-clock time to determine how long to keep events, this may need to be
  modified.

## Execution module changes

The execution layer will need to track the number of transactions that need to reach consensus. This number should be
incremented in the following cases:

1. A transaction is submitted to this node by a user.
2. A user transaction is received in pre-handle that was submitted by another node.
3. A user transaction submitted by this node goes stale and is resubmitted.

The number should be decremented in the following cases:

1. A user transaction is handled and the result is written to a block.

Execution must also track the latest block with user transactions. When the latest block with user transactions is fully
signed, it can instruct the consensus module to quiesce. The only exception to this rule is if the wall clock time is
within the configured duration of an upcoming TCT (Target Consensus Time).

Execution will instruct the consensus module to break quiescence when any of the following conditions are met:

1. A user transaction is added to this node's execution-owned transaction pool.
2. A user transaction is received in pre-handle.

### Functionality changes

Changes needed for [Rule 1](../../HIP/hip-xxxx-quiescence.md#rule-1-transactions-that-need-to-reach-consensus):

- The new quiescence module should keep a set of all non-ancient non-consensus events. If any of these events have
  transactions that need to reach consensus, we should not quiesce. This means that the quiescence module also needs
  to receive consensus rounds.

Changes needed for [Rule 2](../../HIP/hip-xxxx-quiescence.md#rule-2-fully-signed-blocks):

- The event creator module should store the round number of the latest consensus transaction.
- The event creator module should also store the latest block number that is fully signed.
- If the latest fully signed block is less than the latest consensus transaction round, the event creator should not
  quiesce.

Changes needed for [Rule 3](../../HIP/hip-xxxx-quiescence.md#rule-3-target-consensus-timestamp-tct):

- The event creator module should store the latest TCT.
- If the latest TCT is less than the current time plus the configured `tctDuration`, the event creator should not
  quiesce.

Other changes:

- If all the above conditions are met, the event creator should stop creating events.
- The event creator should update the platform status to `QUIESCED` when appropriate.
- The event creator should create a QB if it is told to break quiescence, there is at least one transaction to include
  in a new event, and there are restrictions on creating events that advance consensus. If we are told to break
  quiescence and there are no transactions to include in a new event, and the tipset algorithm does not allow the
  creation of a new event, no event will be created until a tipset-legal event can be created.

## Metrics

Metrics can produce misleading information due to the pause in event creation. Example: `secC2C` tracks the amount of
time that passes from an event being created to it reaching consensus. Ordinarily, this is a few seconds. If this
value spikes, it is usually an indicator of a performance issue in the network. If quiescence is not taken into
account, this value will spike to the amount of time the network was quiesced, which would look like an issue, but is
expected behavior.

The following metrics should be added:

- `numTransNeedCons` the number of non-ancient transactions that need to reach consensus.
- `lastSignedBlock` the latest block number that is fully signed

The following metrics should be modified:

- `secC2C`, `secC2RC` & `secR2C` should be modified to only track events that have transactions that need to reach
  consensus. If this is not done, these metrics will have huge spikes when quiescence is broken.

The following metrics should be removed since they would need to be modified, but are not used:

- `secSC2T`
- `secOR2T`
- `secR2F`
- `secR2nR`

---

## Test Plan

### Unit Tests

New unit tests for the quiescence component should be written for the following scenarios:

- If all the quiescence rules are met, it should stop creating events.
- If the `wallClockTime` + `tctDuration` is less than the next TCT, it should create events regardless of any
  transactions that are pending or need to reach consensus.
- If it should not quiesce until all transactions are in a fully signed block.
- If it is quiesced, there is a pending transaction that needs to reach consensus, and there are no eligible parents; it
  should create a QB event with that transaction.
- If a QB is created, it should not create another QB with the same self-parent even if there are pending transactions
  that need to reach consensus.

