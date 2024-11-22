# Lifecycle management for the State

## Summary

This proposal describes a possible implementation of state lifecycle management that could work equally well for both the Consensus Node and the Block Node.

|      Metadata      |                 Entities                 |
|--------------------|------------------------------------------|
| Designers          | [@imalygin](https://github.com/imalygin) |
| Functional Impacts | Consensus Node, Block Node               |

## Purpose and Context

There is an initiative to create self-contained State modules (`swirlds-state-api` and `swirlds-state-impl`) that would have minimal dependencies, and specifically, they should not depend on `swirlds-platform-core`. Part of this initiative is the implementation of a state lifecycle management mechanism that could be used by both the Consensus Node and the Block Node.

### Requirements

- Add an interface representing state lifecycle management and its implementation.
- Update the current State API to be coherent with this State Lifecycle API.
- Implement this set of APIs and migrate the existing code to use it.

## Design & Architecture

### Java classes

- `com.swirlds.state.State` has the following lifecycle-related methods:
  - `State copy()`: creates a copy of the state, which is mutable. The source of the copy becomes immutable.
  - `void computeHash()`: computes the hash of the immutable state.
  - `void createSnapshot(Path targetPath)`: creates a snapshot of the state at the specified path.
  - `State loadSnapshot(Path sourcePath)`: loads the snapshot from the specified path.

These methods correspond to the state lifecycle as follows:

[![State lifecycle](state-lifecycle.svg)](state-lifecycle.svg)

Note that the `loadSnapshot` method doesn't really belong to `com.swirlds.state.State`, as it's a method of the class, and in some cases, we may need to load the snapshot before we have an instance of the state.
This method should be moved to a separate class that will be responsible for managing the state lifecycle.

`SwirldStateManager` needs refactoring. The current implementation has two sets of responsibilities:
- Keeping track of the references to the latest immutable state and the latest mutable state. This includes creating a copy of the state and updating the references.
This functionality belongs to `swirlds-state-api`.

- Handling Platform-related events (belongs to `swirlds-platform-core`):
  - Handling consensus rounds.
  - Sealing consensus rounds.
  - Keeping the `freeze time` parameter up-to-date.
  - Providing state for signing.
  - Initializing the state by extracting it from the signed state.

These two sets of responsibilities should be separated. The `swirlds-state-impl` should not have any details related to the platform. However, it should provide all necessary levers for the platform to interact with the state and its lifecycle.

Therefore, `swirlds-state-api` needs a `StateLifecycleManager` interface with an implementation `StateLifecycleManagerImpl` in `swirlds-state-impl`  that will have the following responsibilities:
- Take initial state as a starting point. It can be a genesis state or a state loaded from a snapshot.
- Keep track of the references to the latest immutable state and the latest mutable state.
- Restrict mutability to a single state. That is, only one state object should be mutable at any given time.

The interface will look something like this:

```java
public interface StateLifecycleManager {
    void setInitialState(State initialState);

    State getLatestImmutableState();

    State getMutableState();
}
```

**Imporatant**: It's up to the client code to make sure that the state is released once it's no longer needed.
Without this, the state will not be evicted from memory and eventually the application will run out of memory.

The following diagram illustrates an example of the states in memory throughout their lifecycles:

[![Multistate management](multi-states.svg)](multi-states.svg)

Also, `swirlds-state-api` needs a `SnapshotManager` interface with an implementation `SnapshotManagerImpl` in `swirlds-state-impl` that will have the following responsibilities:
- Load snapshots from the disk. A state loaded from a snapshot must be immutable and available for look up by the round number.
- Evict the loaded state by the round number when the state is no longer needed.

This functionality is separated from the `StateLifecycleManager` for the following reasons:
- a state loaded from a snapshot is _always_ immutable, and therefore there is no notion of mutability here.
- the mechanism of maintaining the loaded states in memory is different. This functionality doesn't rely on the reference counting.
Instead, it has explicit methods to load a state from a snapshot, and evict the state by the round number when it's no longer needed.

The interface will look something like this:

```java
public interface SnapshotManager {

    State getLoadedState(long round);

    State loadSnapshot(Path sourcePath);

    void evictLoadedState(long round);
}
```

### Metrics

We need to monitor the number of states that are kept in memory by `SnapshotManager` to ensure there is no memory leak.

### Performance impact

There is only one important performance consideration—the number of states that are kept in memory. However, this can be mitigated by the configuration parameter that limits the number of states kept in memory. In the absence of this parameter, it should be carefully tested and monitored to prevent memory leaks.

## Testing

This functionality should be covered by a combination of unit tests and integration tests. Additionally, we need to ensure that no functionality is broken after refactoring `SwirldStateManager` and replacing it with `StateLifecycleManager`.
