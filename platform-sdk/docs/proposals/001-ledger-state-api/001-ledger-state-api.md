# Ledger State API

## Summary

This proposal aims to define a public API for the ledger state. The API should be used to interact with the
underlying Merkle tree, which will be an implementation detail in the future. Since there already exists a State
API in the services layer, another goal of this proposal is to move that API to the platform layer and use it as
the central API to access the ledger state in all code & products.

## Purpose and Context

### Why?

The Merkle tree is a rather complex data structure, and to make interaction easier, we need to hide this complexity behind an API.
Another reason to have this API is to provide additional flexibility in changing the implementation details of the Merkle tree.
As long as they are hidden, we are free to change them. This API will be critically important for the Block Node to
interact with the state without knowing the details of the Merkle tree.

Note that this API is already implemented as a part of the Service Modularization project, but these classes will be moved
to a separate module as a part of this proposal.

### Context

There are two issues that need to be addressed:

* We need to have a public API for the Merkle tree that is simple and easy to use, residing in a separate module with a minimal set of dependencies.
* The Merkle tree has to be fully expressed in terms of State API. Currently, `PlatformState` stands out and needs to be refactored.
  See more details in the [Platform State](#platform-state) section.

List of `swirlds-state-api` dependencies:

```java
requires transitive com.hedera.node.hapi;
requires transitive com.swirlds.config.api;
requires transitive com.hedera.pbj.runtime;
requires transitive com.swirlds.virtualmap;
requires transitive com.swirlds.common;
requires transitive com.swirlds.merkledb;
requires transitive com.swirlds.merkle;
```

#### Singleton

A singleton is a node with 2 children:

- key of type `String`, which is the name of the singleton
- the singleton value, which is a protobuf message

In Java code it's represented by the following classes:

```java
/**
 * Provides stateful access to a singleton type. Most state in Hedera is k/v state, represented by
 * {@link ReadableKVState}. But some state is not based on a map, but is rather a single instance,
 * such as the AddressBook information. This type can be used to access that state.
 *
 * @param <T> The type of the state, such as an AddressBook or NetworkData.
 */
public interface ReadableSingletonState<T> {
  /**
   * Gets the "state key" that uniquely identifies this {@link ReadableKVState} within the {@link
   * Schema} which are scoped to the service implementation. The key is therefore not globally
   * unique, only unique within the service implementation itself.
   *
   * <p>The call is idempotent, always returning the same value. It must never return null.
   *
   * @return The state key. This will never be null, and will always be the same value for an
   *     instance of {@link ReadableKVState}.
   */
  @NonNull
  String getStateKey();

  /**
   * Gets the singleton value.
   *
   * @return The value, or null if there is no value.
   */
  @Nullable
  T get();

  /**
   * Gets whether the value of this {@link ReadableSingletonState} has been read.
   *
   * @return true if {@link #get()} has been called on this instance
   */
  boolean isRead();
}
/**
 * Provides mutable access to singleton state.
 *
 * @param <T> The type of the state
 */
public interface WritableSingletonState<T> extends ReadableSingletonState<T> {
  /**
   * Sets the given value on this state.
   *
   * @param value The value. May be null.
   */
  void put(@Nullable T value);

  /**
   * Gets whether the {@link #put(Object)} method has been called on this instance.
   *
   * @return True if the {@link #put(Object)} method has been called
   */
  boolean isModified();
}
```

## Requirements

- `PlatformState` should be accessible via the State API
- there should be a new module - `swirlds-state-api` - to host a set of interfaces, records, and abstract classes that represent the Hashgraph state.
  This module should have a minimal set of dependencies. The Block Node should not have a compile-time dependency
  on any other modules but this one to interact with the state.

## Changes

### Public API

State API classes and interfaces will migrate to a designated module. The classes themselves will not change. `PlatformState` class will become a part of the public API.

Usage example:

```java
private PlatformState findPlatformState(LedgerState state) {
  final ReadableStates states = state.getReadableStates(PlatformState.NAME);
  final ReadableSingletonState<PlatformState> platformState = states.getSingleton(PlatformState.PLATFORM_STATE_KEY);
  return platformState.get();
}
```

## Components and Architecture

[### Platform State](#platform-state)

Currently `PlatformState` is a special case node of the merkle tree. That is, it doesn't conform to State API.
As a part of the simplification effort **it should be refactored to a singleton state**.
As a part of this change, `PlatformState` should not be a `MerkleNode` anymore.

Protobuf definition:

```
/**
 * The current state of platform consensus.<br/>
 * This message stores the current consensus data for the platform
 * in network state.
 *
 * The platform state SHALL represent the latest round's consensus.<br/>
 * This data SHALL be used to ensure consistency and provide critical data for
 * restart and reconnect.
 */
message PlatformState {

    /**
     * A version describing the current version of application software.
     * <p>
     * This SHALL be the software version that created this state.
     */
    proto.SemanticVersion creation_software_version = 1;

    /**
     * A number of non-ancient rounds.
     * <p>
     * This SHALL be the count of rounds considered non-ancient.
     */
    uint32 rounds_non_ancient = 2;

    /**
     * A snapshot of the consensus state at the end of the round.
     * <p>
     * This SHALL be used for restart/reconnect.
     */
    ConsensusSnapshot consensus_snapshot = 3;

    /**
     * A timestamp for the next scheduled time when a freeze will start.
     * <p>
     * If a freeze is not scheduled, this SHALL NOT be set.<br/>
     * If a freeze is currently scheduled, this MUST be set, and MUST
     * match the timestamp requested for that freeze.
     */
    proto.Timestamp freeze_time = 4;

    /**
     * A timestamp for the last time a freeze was performed.<br/>
     * If not set, there has never been a freeze.
     */
    proto.Timestamp last_frozen_time = 5;

    // Fields below are to be deprecated in the foreseeable future.

    /**
     * A running event hash.<br/>
     * This is computed by the consensus event stream.
     * <p>
     * This will be _removed_ and the field number reserved once the consensus
     * event stream is retired.
     */
     bytes legacy_running_event_hash = 10000 [deprecated = true];

    /**
     * A consensus generation.<br/>
     * The lowest judge generation before birth round mode was enabled.
     * <p>
     * This SHALL be `MAX_UNSIGNED` if birth round mode has not yet been enabled.
     */
    uint64 lowest_judge_generation_before_birth_round_mode = 10001 [deprecated = true];

    /**
     * A consensus round.<br/>
     * The last round before the birth round mode was enabled.
     * Will be removed after the birth round migration.
     * <p>
     * This SHALL be `MAX_UNSIGNED` if birth round mode has not yet been enabled.
     */
    uint64 last_round_before_birth_round_mode = 10002 [deprecated = true];

    /**
     * A consensus node semantic version.<br/>
     * The software version that enabled birth round mode.
     * <p>
     * This SHALL be unset if birth round migration has not yet happened.<br/>
     * If birth round migration is complete, this SHALL be the _first_ software
     * version that enabled birth round mode.
    */
    proto.SemanticVersion first_version_in_birth_round_mode = 10003 [deprecated = true];

}


/**
 * A consensus snapshot.<br/>
 * This is a snapshot of the consensus state for a particular round.
 *
 * This message SHALL record consensus data necessary for restart
 * and reconnect.
 */
message ConsensusSnapshot {
    /**
     * A consensus round.<br/>
     * The round number of this snapshot.
     */
    uint64 round = 1;
    /**
     * A list of SHA-384 hash values.<br/>
     * The hashes of all judges for this round.
     * <p>
     * This list SHALL be ordered by creator ID.<br/>
     * This list MUST be deterministically ordered.
     */
    repeated bytes judge_hashes = 2;

    /**
     * A list of minimum judge information entries.<br/>
     * These are "minimum ancient" entries for non-ancient rounds.
     */
    repeated MinimumJudgeInfo minimum_judge_info_list = 3;

    /**
     * A single consensus number.<br/>
     * The consensus order of the next event to reach consensus.
     */
    uint64 next_consensus_number = 4;

    /**
     * A "consensus" timestamp.<br/>
     * The consensus timestamp of this snapshot.
     * <p>
     * Depending on the context this timestamp may have different meanings:
     * <li> if there are transactions, the timestamp is equal to the timestamp of the last transaction
     * <li> if there are no transactions, the timestamp is equal to the timestamp of the last event
     * <li> if there are no events, the timestamp is equal to the timestamp of the previous round plus a small constant
     * <p>
     * This SHALL be a consensus value and MAY NOT correspond to an actual
     * "wall clock" timestamp.<br/>
     * Consensus Timestamps SHALL always increase.
     */
    proto.Timestamp consensus_timestamp = 5;

}

/**
 * Records the minimum ancient indicator for all judges in a particular round.
 */
message MinimumJudgeInfo {
    /**
     * A consensus round.<br/>
     * The round this judge information applies to.
     */
    uint64 round = 1;

    /**
     * This is a minimum ancient threshold for all judges for a given round.
     * The value should be interpreted as a generation if the birth
     * round migration is not yet completed, and a birth round thereafter.
     * <p>
     * This SHALL reflect the relevant minimum threshold, whether
     * generation-based or birth-round-based.
     */
    uint64 minimum_judge_ancient_threshold = 2;
}
```

## Test plan

Migration testing:

- we need to make sure that the data of the current platform state migrates properly to a platform state represented by a singleton node.
- we need to test that `PlatformState` can be properly migrated to protobuf format

## Implementation and delivery plan

- Move State API implementation classes from `swirlds-platform-core` to `swirlds-state-api`.
- Refactor `PlatformState` into a singleton. Migrate the data.
- Move `MerkleHederaState` to `swirlds-state-api`
