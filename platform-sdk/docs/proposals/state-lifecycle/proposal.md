# Lifecycle management for the State

## Summary

This proposal describes a possible implementation of state lifecycle management that could work equally well for both the Consensus Node and the Block Node.

|      Metadata      |                 Entities                 |
|--------------------|------------------------------------------|
| Designers          | [@imalygin](https://github.com/imalygin) |
| Functional Impacts | Consensus Node, Block Node               |

## Purpose and Context

There is an initiative to create self-contained State modules (`swirlds-state-api` and `swirlds-state-impl`) that would have minimal dependencies, 
and specifically, they should not depend on `swirlds-platform-core`. Part of this initiative is the implementation of a 
state lifecycle management mechanism that could be used by both the Consensus Node and the Block Node.

### Requirements

- Add an interface representing state lifecycle management and its implementation.
- Update the current State API to be coherent with this State Lifecycle API.
- Implement this set of APIs and migrate the existing code to use it.

## Design & Architecture

### Summary 

The following design details describe a solution for managing the lifecycle of the state object. It covers the following aspects:
- state initialization (loading a snapshot or creating a new state)
- maintaining references to the mutable state and the latest immutable state
- restricting mutability to a single state object
- loading snapshots from the disk 

### Reservation Count Mechanism

The reservation count mechanism ensures that an object is not garbage collected until it is no longer needed. 
This mechanism is implemented using a combination of the `Reservable` and `Releasable` interfaces. 
A state object, like any other Merkle tree node, can be reserved and released. The number of reservations and releases is tracked via a reference count.

An important concept to understand with this mechanism, and the classes implementing these interfaces, 
is the distinction between **implicit reservations** and **explicit reservations**.

#### Implicit Reservations

When an object is initially constructed, it is considered to have an implicit reservation. 
Although its reservation count is 0, it will not be garbage collected because the caller of the constructor 
is presumed to still need the object. The reservation count for an object with an implicit reservation is 0. 
If `Releasable#release()` is called on such an object, it will be destroyed.

#### Explicit Reservations

When an object with an implicit reservation has `Reservable#reserve()` called on it for the first time, the reservation 
becomes explicit, and its reservation count increases to 1. From this point forward, the reservation remains 
explicit until the object is destroyed. An object with an explicit reservation cannot return to having an implicit reservation.

If, at any time after obtaining an explicit reservation, the object has `Releasable#release()` called enough times 
to reduce its reservation count to 0, the object will be destroyed, and its reservation count will be set to -1.

---

We rely on this mechanism to manage the number of states kept in memory.

**Important**: It is the responsibility of the client code to ensure that the state is released once it is no longer needed.

Once the reservation count reaches 0, the state becomes eligible for garbage collection.

### Java classes

- `com.swirlds.state.State` has the following lifecycle-related methods:
  - `State copy()`: creates a copy of the state, which is mutable. The source of the copy becomes immutable.
  - `void computeHash()`: computes the hash of the immutable state.
  - `void createSnapshot(Path targetPath)`: creates a snapshot of the state at the specified path.
  - `State loadSnapshot(Path sourcePath)`: loads the snapshot from the specified path.

These methods correspond to the state lifecycle as follows:

[![State lifecycle](state-lifecycle.svg)](state-lifecycle.svg)

All of these methods should be moved do a separate class, which will be responsible for managing the state lifecycle. This design will provide clearer separation of concerns.
`com.swirlds.state.State` should only be responsible for accessing and modifying the state, and the state lifecycle should be managed externally.

The `SwirldStateManager` class requires refactoring. The current implementation has two sets of responsibilities:
- Keeping track of the references to the latest immutable state and the latest mutable state. This includes creating a copy of the state and updating the references.
  This functionality should reside in `swirlds-state-api`.

- Handling Platform-related events (belongs to `swirlds-platform-core`):
  - Handling consensus rounds.
  - Sealing consensus rounds.
  - Keeping the `freeze time` parameter up-to-date.
  - Providing state for signing.
  - Initializing the state by extracting it from the signed state.

These two sets of responsibilities should be separated. The `swirlds-state-impl` should not have any details related to the platform. However, it should offer all necessary mechanisms for the platform to interact with the state and its lifecycle.

Therefore, `swirlds-state-api` needs a `StateLifecycleManager` interface with an implementation `StateLifecycleManagerImpl` in `swirlds-state-impl`  that will have the following responsibilities:
- Create/load an initial state as a starting point. If no snapshot is available, it should create a new state. 
- Managing references to the latest immutable state and the latest mutable state.
- Restrict mutability to a single state. Specifically, only one state object should be mutable at a time. To achieve this, we need to use a combination of `StateLifecycleManager.copy` method and `NonCopyableState` class.
- Load snapshots from the disk. A state loaded from a snapshot must be immutable.

The interface will look something like this:

```java
public interface StateLifecycleManager {
  /**
   * Get the latest immutable state.
   */
  State getLatestImmutableState();

  /**
   * Get the mutable state.
   */
  State getMutableState();

  /**
   * Load an immutable copy of a state from the snapshot at the specified path. 
   * @param sourcePath the path to the snapshot
   * @return the immutable state loaded from the snapshot
   */
  State loadSnapshot(Path sourcePath);

  /**
   * Calling this method creates a mutable copy of the state. The previous mutable state becomes immutable, 
   * replacing the current latest immutable state, and is accessible via the {@link #getLatestImmutableState} method.
   * @return a mutable copy of the state
   */
  State copy();

  /**
   * Hashes the latest immutable state on demand if it is not already hashed. If the state is already hashed, this method is a no-op.
   */
  void computeHash();
}
```

Note that after the refactoring `State` will no longer have the `copy`, `computeHash`, `createSnapshot`, and `loadSnapshot` methods.

### State Initialization

The current state initialization logic is fragmented across multiple classes and methods, including:

- `ServicesMain#main`
- `Hedera#newMerkleStateRoot`
- `StartupStateUtils.getInitialState`
- `StartupStateUtils.loadStateFile`
- `StartupStateUtils.loadLatestState`
- `SignedStateFileReader.readStateFile`

Additionally, this logic is tightly coupled with other functionalities, such as:

- State signatures
- Platform service registration
- Invalid state file cleanup
- Hash validation
- State reservation

Invalid state file cleanup and hash validation should be moved to the `StateLifecycleManager` implementation. The remaining responsibilities belong to the platform code.
Note that the round is deemed invalid if an attempt to read it ends with `IOException` for one reason or the other.

As part of this design, the code responsible for state initialization should be refactored and centralized in the `StateLifecycleManager`. The platform code should then use the `StateLifecycleManager` to retrieve either a mutable state or the latest immutable copy.

This should be the end result of the refactoring:

[![State lifecycle](state-lifecycle-new.svg)](state-lifecycle-new.svg)

### Metrics

No additional metrics are required for this functionality.

### Performance impact

This change doesn't introduce any performance impact. 

## Testing

This functionality should be covered by a combination of unit tests and integration tests. Additionally, we need to ensure that no functionality is broken after refactoring `SwirldStateManager` and replacing it with `StateLifecycleManager`.
