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

### Reservation count mechanism

This mechanism is already implemented as a part of the Merkle tree implementation. However, it's importnant to mention it here
to provide important context for understanding how exactly the state lifecycle management will work.

Every Merkle tree node implements the following interface:

```java
/**
 *
 * An object that can be reserved and released. Number of reservations and releases are tracked via a reference count.
 *
 * An important paradigm to understand with this interface and with classes that implement this interface are
 * "implicit reservations" and "explicit reservations".
 
 * When an object is initially constructed, it is considered to have an implicit reservation. Even though
 * its reservation count is 0, we don't want to garbage collect it -- as presumably the caller of the constructor
 * still needs the object. The reservation count of an object with an implicit reservation is 0. Calling
 * {@link #release()} on an object with an implicit reservation will cause that object to be destroyed.
 *
 * When an object with an implicit reservation has {@link #reserve()} called on it the first time, that reservation
 * becomes explicit and has a reservation count of 1. After this point in time, the reservation remains explicit
 * until the object is eventually destroyed. It is impossible for an object with an explicit reservation to
 * return to having an implicit reservation. If at any time after the object obtains an explicit reservation it
 * has {@link #release()} called enough times to reduce its reference count to 0, then that object is destroyed
 * and has its reference count set to -1.
 */
public interface Reservable extends Releasable {

    /**
     * The reference count of an object with an implicit reference.
     */
    int IMPLICIT_REFERENCE_COUNT = 0;

    /**
     * The reference count of an object with an explicit reference.
     */
    int DESTROYED_REFERENCE_COUNT = -1;

    /**
     * Acquire a reservation on this object. Increments the reference count by 1.
     *
     * @throws com.swirlds.common.exceptions.ReferenceCountException
     * 		if this object has been fully released and destroyed
     */
    void reserve();

    /**
     * Attempts to acquire a reservation on this object. If the object is destroyed, the reservation attempt will fail.
     *
     * @return true if a reservation was acquired.
     */
    boolean tryReserve();

    /**
     * <p>
     * Release a reservation on an object. Decrements the reference count by 1. If this method releases
     * the last reservation, then this object should be destroyed.
     * </p>
     *
     * <p>
     * Should be called exactly once for each time {@link #reserve()} is called. The exception to this rule is
     * if this object only has an implicit reference (i.e. {@link #getReservationCount()} returns
     * {@link #IMPLICIT_REFERENCE_COUNT}). An object has an implicit reference immediately after it is constructed
     * but before {@link #reserve()} has been called the first time. If called with an implicit reference, this
     * object will be destroyed.
     * </p>
     *
     * @return true if this call to release() caused the object to become destroyed
     * @throws com.swirlds.common.exceptions.ReferenceCountException
     * 		this object has already been fully released and destroyed
     */
    @Override
    boolean release();

    /**
     * Get the total number of times {@link #reserve()} has been called minus the number of times {@link #release()}
     * has been called. Will return {@link #IMPLICIT_REFERENCE_COUNT} if {@link #reserve()} has never been called,
     * or {@link #DESTROYED_REFERENCE_COUNT} if this object has been fully released.
     *
     * @return
     */
    int getReservationCount();
}

```

We're going to rely on this mechanism to manage the number of states that are kept in memory. 
**Important**: It's up to the client code to make sure that the state is released once it's no longer needed.

Once the reservation count reaches 0, the state will be eligible for garbage collection.

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
- Create/load an initial state as a starting point. If there is no snapshot to load, it should create a new state. 
- Keep track of the references to the latest immutable state and the latest mutable state.
- Restrict mutability to a single state. That is, only one state object should be mutable at any given time.
- Load snapshots from the disk. A state loaded from a snapshot must be immutable and available for look up by the round number.
- Monitors the states that are in memory. If there are states that have 0 reservation count, they should be removed from a list of
states that are kept in memory and eventually garbage collected.

The interface will look something like this:

```java
public interface StateLifecycleManager {
    State getLatestImmutableState();

    State getMutableState();

    State getLoadedState(long round);

    State loadSnapshot(Path sourcePath);
}
```

The following diagram illustrates an example of the states in memory throughout their lifecycles:

[![Multistate management](multi-states.svg)](multi-states.svg)

### Metrics

We need to monitor the number of states that are kept in memory by `StateLifecycleManager` to ensure there is no memory leak.

### Performance impact

There is only one important performance consideration—the number of states that are kept in memory. However, this can be mitigated by the configuration parameter that limits the number of states kept in memory. In the absence of this parameter, it should be carefully tested and monitored to prevent memory leaks.

## Testing

This functionality should be covered by a combination of unit tests and integration tests. Additionally, we need to ensure that no functionality is broken after refactoring `SwirldStateManager` and replacing it with `StateLifecycleManager`.
