# Quiescence

---

## Summary

Quiescence is a feature that stops event creation when it is not needed. The purpose of this is to reduce the
amount of data produced and bandwidth used by low volume networks.

| Metadata           | Entities             |
|--------------------|----------------------|
| Designers          | Lazar Petrović       |
| Functional Impacts | Consensus, Execution |
| Related Proposals  | N/A                  |
| HIPS               | TODO                 |

---

## Purpose and Context

The purpose of this is to reduce the amount of data produced and bandwidth used by networks with intermittent usage.
When there are no transactions being submitted to the network, the network can stop creating events and thus stop
gossipping and producing blocks. This can drastically reduce the amount of data that needs to be stored long term as
well as the amount of bandwidth used by the network.

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

## HIP

Please refer to the [Quiescence HIP](TODO) for the high level information about this feature.

### Side effects of quiescence

Various parts of the system assume that events are constantly being created and consensus is always advancing. With
quiescence, this is not the case. This means that various parts of the system need to be modified to account for this.

- The `SignedStateSentinel` uses wall-clock time to determine if a signed state is old. This will need to be modified to
  use the take quiescence into account.
- `PcesConfig.minimumRetentionPeriod` uses wall-clock time to determine how long to keep events.
- The platform status `ACTIVE` currently moves to `CHECKING` based on wall-clock time. We will need to add a
  `QUIESCED` status.
- Various metrics can be misleading if the network is quiesced.
- NOTE FOR REVIEWERS: I probably haven't thought of all the side effects yet, please add any you can think of.

---

## Changes

### Architecture and/or Components

- The transaction pool needs to move to execution. When the consensus module is ready to create a new event, it must ask
  execution for transactions to include in the event.
- The transaction resubmitter component should be deleted. Output of the stale event detector should be modified to
  notify the execution of the stale transactions.
- The event creator should create a QB if we are told to break quiescence, there is at least one transaction to include
  in a new event, and there are restrictions on creating events that advance consensus. If we are told to break
  quiescence and there are no transactions to include in a new event, and the tipset algorithm does not allow the
  creation of a new event, no event will be created until a tipset-legal event can be created.

### Public API

An additional API is needed for execution to tell the consensus module to quiesce or break quiescence.

- Two new methods on the `Platform` interface will be created:
  `void quiesce()` and `void breakQuiescence()`. These methods will send data via a new input wire to the Event Creator
  component. The Platform Status Component will either get this data directly or via the Event Creator component.
- The existing `Platform` method `boolean createTransaction(byte[] transaction)` will be removed.
- A new required field when creating the `PlatformBuilder` will be a transaction supplier. The consensus module will use
  this supplier to put transactions in new events.

### Execution Conceptual Changes

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

### Configuration

A new execution level configuration option is needed to enable/disable quiescence.

### Metrics

The following metrics should be modified:

- `secC2C` & `secR2C` should be modified to only track events that have transactions that need to reach consensus. If
  this is not done, these metrics will have huge spikes when quiescence is broken. - TODO figure out what to do for this
  now that the consensus module will not be tracking what needs to reach consensus.

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

- If no non-ancient transactions need to reach consensus, there are no consensus transactions without a boundary round
  after them, and there is no upcoming freeze; it should stop creating events.
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
