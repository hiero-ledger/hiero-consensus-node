// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.GroupPhase.AWAITING_VALIDATION;
import static com.hedera.services.bdd.junit.extensions.GroupPhase.DONE;
import static com.hedera.services.bdd.junit.extensions.GroupPhase.RUNNING_TESTS;
import static com.hedera.services.bdd.junit.extensions.GroupPhase.TEARDOWN;
import static com.hedera.services.bdd.junit.extensions.GroupPhase.VALIDATING;
import static com.hedera.services.bdd.junit.extensions.GroupPhase.WAITING_FOR_SLOT;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.extensions.MultiNetworkValidation.ReadyGroup;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Moves {@link NetworkGroup}s through their {@link GroupPhase}s under a total node budget, so lazily-started shared
 * networks don't all boot together and starve each other.
 *
 * <p>Every enabled test is {@link #register registered} with its group during the test-plan walk, before anything
 * runs. A group waits for a slot until its nodes fit the free budget, then runs its tests. After its last test it is
 * validated, if validation is enabled (see {@link MultiNetworkValidation}), and is then torn down and
 * {@link #release released}. A group holds its nodes from the moment it gets a slot until it is released, so no
 * more than the budget's nodes ever run at once.
 *
 * <p>All state is guarded by a single lock, so every decision is atomic.
 */
public final class MultiNetworkGroupQueue {
    private static final Logger log = LogManager.getLogger(MultiNetworkGroupQueue.class);

    /** Max subprocess nodes alive at once across all network groups. Tuned to the HAPI runner (8 cores). */
    public static final int DEFAULT_CAPACITY = 4;

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<String, GroupState> groupsById = new HashMap<>();
    private final Map<String, GroupState> groupsByNetwork = new HashMap<>();
    /** Whether groups are validated after their tests, i.e. the validation test runs alongside them. */
    private boolean validationEnabled;

    public MultiNetworkGroupQueue(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
    }

    private static final class GroupState {
        private final NetworkGroup group;
        /** Where the group is in its lifecycle. */
        private GroupPhase phase = WAITING_FOR_SLOT;
        /** JUnit unique ids of the group's registered tests that have not finished yet. */
        private final Set<String> unfinishedTestIds = new HashSet<>();
        /**
         * Why the group cannot run or be validated (it cannot fit the budget, its networks failed to start, none of
         * its tests ran), or null.
         */
        @Nullable
        private String failureReason;

        private GroupState(@NonNull final NetworkGroup group) {
            this.group = group;
        }

        /** Whether the group's nodes count against the budget: it got a slot and has not been released. */
        private boolean holdsNodes() {
            return phase.compareTo(RUNNING_TESTS) >= 0 && phase != DONE && failureReason == null;
        }
    }

    /**
     * Registers one enabled test of {@code group}. Called once per test method during the test-plan walk, before
     * any test runs. Registering the same test twice has no effect.
     *
     * <p>A group that can never run (it needs more nodes than the budget, or shares a network with another group)
     * gets a failure reason: its tests fail fast in {@link #awaitSlot}.
     *
     * @param testId the test's JUnit unique id
     * @param group the group the test declares
     */
    public void register(@NonNull final String testId, @NonNull final NetworkGroup group) {
        requireNonNull(testId);
        requireNonNull(group);
        lock.lock();
        try {
            groupsById
                    .computeIfAbsent(group.id(), id -> newGroupState(group))
                    .unfinishedTestIds
                    .add(testId);
        } finally {
            lock.unlock();
        }
    }

    private GroupState newGroupState(@NonNull final NetworkGroup group) {
        final var state = new GroupState(group);
        if (group.weight() > capacity) {
            state.failureReason = "Network group [" + group.id() + "] needs " + group.weight()
                    + " nodes but the budget is " + capacity;
        }
        for (final var name : group.networkNames()) {
            final var other = groupsByNetwork.putIfAbsent(name, state);
            if (other != null) {
                final var reason = "Network '" + name + "' is used by two network groups: [" + other.group.id()
                        + "] and [" + group.id() + "]";
                state.failureReason = reason;
                other.failureReason = reason;
            }
        }
        if (state.failureReason != null) {
            log.error("[MultiNetworkGroupQueue] {}", state.failureReason);
        }
        return state;
    }

    /**
     * Sets whether groups are validated after their tests. Called once, before any test runs, with true only if the
     * validation test is in the plan and runs alongside the groups.
     */
    public void setValidationEnabled(final boolean enabled) {
        lock.lock();
        try {
            validationEnabled = enabled;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until {@code group} has a slot, or returns at once if it already has one. The wait goes through
     * {@link ForkJoinPool#managedBlock}, so a blocked JUnit worker is compensated for.
     *
     * @param group the group of the test about to run
     * @throws IllegalStateException if the group was not registered, or cannot run
     */
    public void awaitSlot(@NonNull final NetworkGroup group) {
        requireNonNull(group);
        final var blocker = new ForkJoinPool.ManagedBlocker() {
            private boolean admitted;

            @Override
            public boolean block() throws InterruptedException {
                lock.lock();
                try {
                    final var state = groupsById.get(group.id());
                    if (state == null) {
                        throw new IllegalStateException("Network group [" + group.id() + "] was not registered");
                    }
                    while (!tryAdmit(state)) {
                        changed.await();
                    }
                    admitted = true;
                    return true;
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public boolean isReleasable() {
                return admitted;
            }
        };
        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for a slot for network group [" + group.id() + "]", e);
        }
    }

    /** Gives {@code state} a slot if its nodes fit; true if it has one. Caller holds the lock. */
    private boolean tryAdmit(@NonNull final GroupState state) {
        if (state.failureReason != null) {
            throw new IllegalStateException(state.failureReason);
        }
        if (state.phase != WAITING_FOR_SLOT) {
            return true;
        }
        if (nodesInUse() + state.group.weight() > capacity) {
            return false;
        }
        state.phase = RUNNING_TESTS;
        log.info(
                "[MultiNetworkGroupQueue] Network group [{}] got a slot ({} nodes; {}/{} nodes in use)",
                state.group.id(),
                state.group.weight(),
                nodesInUse(),
                capacity);
        return true;
    }

    /**
     * Marks {@code group} as failed to start, which frees its nodes. Its remaining tests then fail
     * fast in {@link #awaitSlot} instead of retrying the boot. The caller must have torn down whatever of the group's
     * networks started.
     */
    public void bootFailed(@NonNull final NetworkGroup group) {
        lock.lock();
        try {
            final var state = groupsById.get(group.id());
            if (state != null) {
                state.failureReason = "Networks of network group [" + state.group.id() + "] failed to start";
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks every registered, unfinished test whose unique id is {@code uniqueId} or a descendant of it as finished.
     * Called in {@code afterEach} for a single test, and by the launcher listener when a container finishes or is
     * skipped, so tests that never ran still count down. A group whose last test this was moves on to validation,
     * if enabled, or to teardown.
     *
     * @param uniqueId a test's or a container's JUnit unique id
     * @return the groups to tear down now; the caller must tear down their networks and then {@link #release} each
     */
    @NonNull
    public List<NetworkGroup> testsFinished(@NonNull final String uniqueId) {
        final List<NetworkGroup> toTearDown = new ArrayList<>();
        lock.lock();
        try {
            final var descendantPrefix = uniqueId + "/";
            for (final var state : groupsById.values()) {
                final boolean removed =
                        state.unfinishedTestIds.removeIf(id -> id.equals(uniqueId) || id.startsWith(descendantPrefix));
                if (!removed || !state.unfinishedTestIds.isEmpty()) {
                    continue;
                }
                if (state.phase == WAITING_FOR_SLOT && state.failureReason == null) {
                    state.failureReason = "None of the tests of network group [" + state.group.id() + "] ran";
                }
                log.info("[MultiNetworkGroupQueue] Network group [{}] finished its last test", state.group.id());
                if (validationEnabled) {
                    state.phase = AWAITING_VALIDATION;
                    changed.signalAll();
                } else if (endLifecycle(state)) {
                    toTearDown.add(state.group);
                }
            }
        } finally {
            lock.unlock();
        }
        return toTearDown;
    }

    /**
     * Blocks until a group awaits validation, moves it to {@link GroupPhase#VALIDATING} and returns it. The wait goes
     * through {@link ForkJoinPool#managedBlock}, so a blocked JUnit worker is compensated for.
     *
     * @return the group, or null once no group can await validation any more (every group is past it, validation
     *     ended, or the queue was cleared)
     */
    @Nullable
    public ReadyGroup takeGroupToValidate() {
        final var blocker = new ForkJoinPool.ManagedBlocker() {
            private boolean done;

            @Nullable
            private ReadyGroup taken;

            @Override
            public boolean block() throws InterruptedException {
                lock.lock();
                try {
                    while (true) {
                        final var awaiting = groupsById.values().stream()
                                .filter(state -> state.phase == AWAITING_VALIDATION)
                                .findFirst();
                        if (awaiting.isPresent()) {
                            final var state = awaiting.get();
                            state.phase = VALIDATING;
                            taken = new ReadyGroup(state.group, state.failureReason);
                            break;
                        }
                        final boolean moreToCome = validationEnabled
                                && groupsById.values().stream()
                                        .anyMatch(state -> state.phase.compareTo(AWAITING_VALIDATION) < 0);
                        if (!moreToCome) {
                            break;
                        }
                        changed.await();
                    }
                    done = true;
                    return true;
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public boolean isReleasable() {
                return done;
            }
        };
        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a network group to validate", e);
        }
        return blocker.taken;
    }

    /**
     * Marks {@code group} as validated; it moves on to teardown.
     *
     * @return true if the group is to be torn down now; the caller must then tear down its networks and
     *     {@link #release} it
     */
    public boolean validationFinished(@NonNull final NetworkGroup group) {
        lock.lock();
        try {
            final var state = requireNonNull(groupsById.get(group.id()), () -> "Unknown group " + group.id());
            if (state.phase != VALIDATING) {
                throw new IllegalStateException(
                        "Network group [" + group.id() + "] is in " + state.phase + ", not " + VALIDATING);
            }
            return endLifecycle(state);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Disables validation once the validation test has finished or was skipped. A group still awaiting validation
     * would otherwise hold its nodes forever, so every such group moves on to teardown.
     *
     * @return the groups to tear down now; the caller must tear down their networks and then {@link #release} each
     */
    @NonNull
    public List<NetworkGroup> validationEnded() {
        final List<NetworkGroup> toTearDown = new ArrayList<>();
        lock.lock();
        try {
            validationEnabled = false;
            for (final var state : groupsById.values()) {
                if (state.phase == AWAITING_VALIDATION && endLifecycle(state)) {
                    toTearDown.add(state.group);
                }
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        return toTearDown;
    }

    /**
     * Moves {@code state} to teardown if it holds nodes, else straight to done. Caller holds the lock.
     *
     * @return true if the group entered teardown
     */
    private boolean endLifecycle(@NonNull final GroupState state) {
        changed.signalAll();
        if (state.holdsNodes()) {
            state.phase = TEARDOWN;
            return true;
        }
        state.phase = DONE;
        return false;
    }

    /** Frees the nodes of {@code group}. Call after its networks are torn down. */
    public void release(@NonNull final NetworkGroup group) {
        lock.lock();
        try {
            final var state = groupsById.get(group.id());
            if (state != null) {
                state.phase = DONE;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fails every registered group that uses any of {@code networkNames}: its tests fail fast in {@link #awaitSlot}
     * with {@code reason}.
     *
     * @param networkNames the networks whose groups to fail
     * @param reason the reason the groups fail with
     */
    public void invalidate(@NonNull final Collection<String> networkNames, @NonNull final String reason) {
        lock.lock();
        try {
            for (final var name : networkNames) {
                final var state = groupsByNetwork.get(name);
                if (state != null) {
                    state.failureReason = reason;
                }
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Forgets every registered group and test, for the next test plan, and wakes every waiter. */
    public void clear() {
        lock.lock();
        try {
            groupsById.clear();
            groupsByNetwork.clear();
            validationEnabled = false;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** The nodes of every group that holds a slot. Caller holds the lock. */
    private int nodesInUse() {
        return groupsById.values().stream()
                .filter(GroupState::holdsNodes)
                .mapToInt(state -> state.group.weight())
                .sum();
    }
}
