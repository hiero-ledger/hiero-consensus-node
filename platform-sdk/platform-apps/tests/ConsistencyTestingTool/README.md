# Consistency Testing Tool

The purpose of the Consistency Testing Tool is to guarantee that transactions are handled exactly
once, and always in the same order.

## Motivation

When a node is shut down, there are potentially a number of transactions that have come to
consensus, but have not yet been included in a signed state. It is important that these transactions
are not lost upon restart, and that they are handled in precisely the same order as they were before
the node was shut down.

## Implementation

In order to guarantee consistency in the way that transactions are handled, the Consistency Testing
Tool utilizes a durable log file, containing information on when a transaction has come to
consensus, and in what round. This log file persists across reboots, and is read into memory at boot
time if it exists.

During operation, when a given round comes to consensus, the tool behaves in the following way:

- If the round coming to consensus doesn't already appear in the log:
  - the round is written to the log, along with all included transactions
  - each transaction is added to a hashmap, to guarantee that no transaction is handled more
    than once
- If the round coming to consensus already appears in the log:
  - the tool checks that the transactions included in the round are the same as the ones already
    in the log, in the exact same order

### config.txt

comment out the current app

```
# app,		StatsDemo.jar,		   1, 3000, 0, 100, -1, 200
```

uncomment the ConsistencyTestingTool.jar

```
app,    ConsistencyTestingTool.jar,
```

### settings.txt

Following configurations can be set in the settings.txt file

```
consistencyTestingTool.freezeAfterGenesis, 5s
consistencyTestingTool.logfileDirectory, consistency-example
```

- `freezeAfterGenesis`: The time after which the node will be frozen after genesis block is created.
- `logfileDirectory`: The directory where the consistency log file will be stored.

## Testing Consistency Behavior

### Test Scenario 1: Basic Consistency Check

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Run the app for some time

#### Validation

- Check the `swirlds.log` for any consistency errors or exceptions
- Verify that the consistency log file exists in the configured directory
- Confirm that all transactions are handled exactly once
- Verify transaction ordering remains consistent

### Test Scenario 2: Freeze After Genesis

#### Instructions

1. Delete `sdk/data/saved` directory if it exists
2. Ensure settings.txt has the following values

```
consistencyTestingTool.freezeAfterGenesis, 5s
```

3. Run the node and wait for freeze
4. Stop the node during freeze
5. Restart the node
6. Remove the freezeAfterGenesis setting from settings.txt
7. Verify consistency is maintained

#### Validation

- Check `swirlds.log` for:
  - Confirmation that freeze occurred at specified time
  - No consistency errors during or after freeze
- Verify all pre-freeze transactions are handled in same order after restart
- Confirm no transactions were lost during the freeze/restart cycle
