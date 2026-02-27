# Lifecycle Management for the State

## Summary

This proposal describes a possible implementation of state lifecycle management that could work equally well for both the Consensus Node and the Block Node.

|      Metadata      |                 Entities                 |
|--------------------|------------------------------------------|
| Designers          | [@imalygin](https://github.com/imalygin) |
| Functional Impacts | Consensus Node, Block Node               |

## Purpose and Context

There is an initiative to create self-contained State modules (`swirlds-state-api` and `swirlds-state-impl`) that have minimal dependencies. Specifically, these modules should not depend on `swirlds-platform-core`. As part of this initiative, a state lifecycle management mechanism will be implemented for use by both the Consensus Node and the Block Node.

### Requirements

- Introduce an interface representing state lifecycle management and its implementation.
- Update the current State API to align with the State Lifecycle API.
- Implement these APIs and migrate existing code to use them.

## Design & Architecture

### Summary

The following design describes a solution for managing the lifecycle of the state object. It covers the following aspects:

- State initialization (loading a snapshot or creating a new state)
- Maintaining references to the mutable state and the latest immutable state
- Restricting mutability to a single state object
- Loading snapshots from disk

## Current State of the Code

### Reservation Count Mechanism

This section provides important context about how state objects are managed in memory.

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

---

### Java Classes

`com.swirlds.state.State` provides the following lifecycle-related methods:

- `State copy()`: Creates a mutable copy of the state. The source state becomes immutable.
- `void computeHash()`: Computes the hash of the immutable state.
- `void createSnapshot(Path targetPath)`: Creates a snapshot of the state at the specified path.
- `State loadSnapshot(Path sourcePath)`: Loads a snapshot from the specified path.

These methods correspond to the state lifecycle as follows:

[![State lifecycle](state-lifecycle.svg)](state-lifecycle.svg)

---

### `SwirldStateManager`

The `SwirldStateManager` class handles all interactions with the state object in the context of the platform.

The current implementation of `SwirldStateManager` has two distinct responsibilities:

1. **State Management**:
   - Managing references to the latest immutable state and the latest mutable state.
   - Creating copies of the state and updating references.
     This functionality belongs in `swirlds-state-api`.
2. **Platform-Related Event Handling** (belongs to `swirlds-platform-core`):
   - Handling consensus rounds
   - Sealing consensus rounds
   - Keeping the `freeze time` parameter up to date
   - Providing the state for signing
   - Initializing the state by extracting it from the signed state

## Proposed Changes

All lifecycle-related methods from `com.swirlds.state.State` should be moved to a separate class responsible for managing the state lifecycle. This will provide a clearer separation of concerns.

- `com.swirlds.state.State` should focus solely on accessing and modifying the state.
- The state lifecycle will be managed externally.
- Its hash will be computed asynchronously and managed externally.

```java
public interface State {
   @NonNull
   ReadableStates getReadableStates(@NonNull String serviceName);

   @NonNull
   WritableStates getWritableStates(@NonNull String serviceName);

   default void registerCommitListener(@NonNull final StateChangeListener listener)l;

   @Nullable
   Future<Hash> getHashFuture();
}
```

#### Notes

- The signature of the `State#getHash()` method will change to `Future<Hash> getHash()` to accommodate asynchronous hash computation.
- The `State` interface will no longer include the `copy`, `computeHash`, `createSnapshot`, or `loadSnapshot` methods.

The `SwirldStateManager` class requires refactoring to separate its two distinct responsibilities. `swirlds-state-impl` should not include any platform-specific details but should provide the necessary mechanisms for the platform to interact with the state and its lifecycle.

### `StateLifecycleManager` Interface

To achieve this, `swirlds-state-api` will define a `StateLifecycleManager` interface, with an implementation `StateLifecycleManagerImpl` in `swirlds-state-impl`. The interface will handle:

- Creating/loading an initial state. If no snapshot is available, a new state will be created.
- Managing references to the latest immutable state and the latest mutable state.
- Resetting the mutable state to the latest immutable state.
- Restricting mutability to a single state object. Specifically, only one state can be mutable at a time.
- Loading snapshots from disk. States loaded from snapshots are immutable.

The interface will look something like this:

```java
public interface StateLifecycleManager {
    /**
     * Get the mutable state.
     */
    State getMutableState();

   /**
    * Reset the mutable state to the latest immutable state.
    */
   void resetMutableState();

   /**
    * Creates a snapshot for the latest immutable state.
    *
    * @param targetPath The path to save the snapshot.
    */
    State createSnapshot(final @NonNull Path targetPath);

    /**
     * Creates a mutable copy of the state. The previous mutable state becomes immutable,
     * replacing the latest immutable state.
     *
     * @return an instance of the state that just became immutable, that is, the previous mutable state.
     */
    State copyMutableState();
}
```

#### Notes

- Initial version of the interface included `getLatestImmutableState` method, but upon further consideration,
  it was removed as it is not necessary. After the state becomes immutable, only three things can happen to it:

  - It can be hashed
  - It can be stored to disk as a snapshot
  - It can become a source for a mutable copy in case of the `resetMutableState` operation

  All these operations are managed by the `StateLifecycleManager` implementation. Client code may get the immutable copy using `copyMutableState` method.

- The `resetMutableState` method is to effectively revert all the changes since the last copy was made. This method will require additional code changes
  as we normally do not allow creation of immutable state copies.

- The `createSnapshot` method requires the latest immutable state to be hashed. It will block on the `Future` returned by the `State#getHash()` method until the hash is ready.

---

### Implementation Details

The implementation will include the methods defined in the interface and two additional static utility methods:

```java
public class StateLifecycleManagerImpl implements StateLifecycleManager {

    /**
     * This constructor initializes the state from the snapshot directory.
     *
     * @param sourcePath the path to the snapshot directory to initialize the state from
     */
    public StateLifecycleManagerImpl(final @NonNull Path sourcePath) {
        // implementation
    }

    /**
     * Returns a list of paths to snapshots discovered in the specified directory.
     *
     * @param sourcePath the path to the directory
     * @return a list of paths to snapshots
     */
    public static List<Path> discover(Path sourcePath) {
        // implementation
    }
}
```

#### Notes

- The `StateLifecycleManager` implementation will maintain a **1-to-1 relationship** with the "family" of states.
  This means that it will maintain a reference to the mutable state and the latest immutable state, but the instances of these states will change over time - a copy of the mutable state will become immutable, and a new mutable state will be created.
- If client code needs a state loaded from an arbitrary snapshot, it can directly use `StateLifecycleManagerImpl` constructor with the path to the snapshot directory as a parameter.
- The `copyMutableState` method, in addition to creating a mutable copy, will also asynchronously initiate the hashing of the previous state, which became immutable.

---

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

Invalid state file cleanup and hash validation should be moved to the `StateLifecycleManager`. The remaining responsibilities belong to the platform code.

This refactoring will ensure that state initialization logic is centralized in the `StateLifecycleManager` implementation.
The platform code will use `StateLifecycleManager` to retrieve either a mutable state or the latest immutable copy.

The end result will look like this:

[![State lifecycle](state-lifecycle-new.svg)](state-lifecycle-new.svg)

---

### Metrics

No additional metrics are required for this functionality.

### Performance Impact

This change introduces no performance impact.

## Testing

This functionality will be covered by a combination of unit tests and integration tests. Additionally, we need to ensure that no functionality is broken after refactoring `SwirldStateManager` and replacing it with `StateLifecycleManager`.
