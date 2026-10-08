// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Admits {@link NetworkGroup}s to run under a total node budget, so lazily-started shared networks don't all
 * boot together and starve each other.
 *
 * <p>Every enabled test is {@link #register registered} with its group during the test-plan walk, before
 * anything runs. A group then waits until it is <b>admitted</b>, which happens only when (1) its nodes fit the
 * free budget and (2) no lighter group is still waiting. So the smaller groups go first, while groups that
 * fit together still run together. An admitted group keeps its nodes until its last registered test
 * {@link #testsFinished finishes}, and its owner tears its networks down and calls {@link #release}.
 *
 * <p>All state is guarded by a single lock, so every admission decision is atomic.
 */
public final class MultiNetworkGroupQueue {
    private static final Logger log = LogManager.getLogger(MultiNetworkGroupQueue.class);

    /** Max subprocess nodes alive at once across all network groups. Tuned to the HAPI runner (8 cores). */
    public static final int DEFAULT_CAPACITY = 4;

    /** How long a test waits for its group to be admitted before failing. */
    public static final Duration DEFAULT_ADMISSION_TIMEOUT = Duration.ofMinutes(60);

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<String, GroupState> groupsById = new HashMap<>();
    private final Map<String, GroupState> groupsByNetwork = new HashMap<>();
    /** Registered tests that have not finished yet, by JUnit unique id. */
    private final Map<String, GroupState> pendingTests = new HashMap<>();

    private final PriorityQueue<GroupState> waiting = new PriorityQueue<>(
            Comparator.<GroupState>comparingInt(s -> s.group.weight()).thenComparing(s -> s.group.id()));
    private int nodesInUse;

    public MultiNetworkGroupQueue(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
    }

    private static final class GroupState {
        private final NetworkGroup group;
        private int testsToRun;
        private boolean running;
        private boolean failed;

        @Nullable
        private String error;

        private GroupState(@NonNull final NetworkGroup group) {
            this.group = group;
        }
    }

    /**
     * Registers one enabled test of {@code group}. Called once per test method during the test-plan walk,
     * before any test runs. Registering the same test twice has no effect.
     *
     * <p>A group that can never run (it needs more nodes than the budget, or shares a network with another
     * group) is not queued: its tests fail fast in {@link #awaitTurn}.
     *
     * @param testId the test's JUnit unique id
     * @param group the group the test declares
     */
    public void register(@NonNull final String testId, @NonNull final NetworkGroup group) {
        requireNonNull(testId);
        requireNonNull(group);
        lock.lock();
        try {
            if (pendingTests.containsKey(testId)) {
                return;
            }
            final var state = groupsById.computeIfAbsent(group.id(), id -> newGroup(group));
            state.testsToRun++;
            pendingTests.put(testId, state);
        } finally {
            lock.unlock();
        }
    }

    private GroupState newGroup(@NonNull final NetworkGroup group) {
        final var state = new GroupState(group);
        if (group.weight() > capacity) {
            state.error = "Network group [" + group.id() + "] needs " + group.weight() + " nodes but the budget is "
                    + capacity;
        }
        for (final var name : group.networkNames()) {
            final var other = groupsByNetwork.putIfAbsent(name, state);
            if (other != null) {
                final var error = "Network '" + name + "' is used by two network groups: [" + other.group.id()
                        + "] and [" + group.id() + "]";
                state.error = error;
                other.error = error;
                waiting.remove(other);
            }
        }
        if (state.error == null) {
            waiting.add(state);
        } else {
            log.error("[MultiNetworkGroupQueue] {}", state.error);
        }
        return state;
    }

    /**
     * Blocks until the group of {@code testId} is admitted, or returns at once if it already is. The wait
     * goes through {@link ForkJoinPool#managedBlock}, so a blocked JUnit worker is compensated for.
     *
     * @param testId the test's JUnit unique id, as passed to {@link #register}
     * @param timeout how long to wait for admission
     * @return the test's group
     * @throws IllegalStateException if the test was not registered, its group can never run or failed to
     *     boot, or the group was not admitted within {@code timeout}
     */
    @NonNull
    public NetworkGroup awaitTurn(@NonNull final String testId, @NonNull final Duration timeout) {
        final var blocker = new AdmissionBlocker(testId, System.nanoTime() + timeout.toNanos(), timeout);
        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to admit the network group of " + testId, e);
        }
        return requireNonNull(blocker.admitted);
    }

    private final class AdmissionBlocker implements ForkJoinPool.ManagedBlocker {
        private final String testId;
        private final long deadlineNanos;
        private final Duration timeout;

        @Nullable
        private NetworkGroup admitted;

        private AdmissionBlocker(@NonNull final String testId, final long deadlineNanos, final Duration timeout) {
            this.testId = testId;
            this.deadlineNanos = deadlineNanos;
            this.timeout = timeout;
        }

        @Override
        public boolean block() throws InterruptedException {
            lock.lock();
            try {
                final var state = pendingTests.get(testId);
                if (state == null) {
                    throw new IllegalStateException("Test " + testId + " was not registered with a network group");
                }
                while (!tryAdmit(state)) {
                    final long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("Network group [" + state.group.id()
                                + "] was not admitted within " + timeout + " (" + nodesInUse + "/" + capacity
                                + " nodes in use)");
                    }
                    changed.awaitNanos(remaining);
                }
                admitted = state.group;
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean isReleasable() {
            return admitted != null;
        }
    }

    /** Admits {@code state} if it is allowed to run; true if it is running. Caller holds the lock. */
    private boolean tryAdmit(@NonNull final GroupState state) {
        if (state.error != null) {
            throw new IllegalStateException(state.error);
        }
        if (state.failed) {
            throw new IllegalStateException(
                    "Networks of network group [" + state.group.id() + "] failed to start; not retrying");
        }
        if (state.running) {
            return true;
        }
        final var lightest = waiting.peek();
        final boolean noLighterWaiting = lightest == null || lightest.group.weight() >= state.group.weight();
        if (!noLighterWaiting || nodesInUse + state.group.weight() > capacity) {
            return false;
        }
        waiting.remove(state);
        state.running = true;
        nodesInUse += state.group.weight();
        log.info(
                "[MultiNetworkGroupQueue] Admitted network group [{}] ({} nodes; {}/{} nodes in use)",
                state.group.id(),
                state.group.weight(),
                nodesInUse,
                capacity);
        return true;
    }

    /**
     * Marks the group of {@code testId} as failed to boot and frees its nodes. Its remaining tests then fail
     * fast in {@link #awaitTurn} instead of retrying the boot. The caller must have torn down whatever of the
     * group's networks started.
     */
    public void bootFailed(@NonNull final String testId) {
        lock.lock();
        try {
            final var state = pendingTests.get(testId);
            if (state != null) {
                state.failed = true;
                releaseLocked(state);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks every registered, unfinished test whose unique id is {@code uniqueId} or a descendant of it as
     * finished. Called in {@code afterEach} for a single test, and by the launcher listener when a container
     * finishes or is skipped, so tests that never ran still count down.
     *
     * @param uniqueId a test's or a container's JUnit unique id
     * @return the running groups whose last test this was; the caller must tear down their networks and then
     *     {@link #release} each of them
     */
    @NonNull
    public List<NetworkGroup> testsFinished(@NonNull final String uniqueId) {
        final List<NetworkGroup> toTearDown = new ArrayList<>();
        lock.lock();
        try {
            final var descendantPrefix = uniqueId + "/";
            final var finishedIds = pendingTests.keySet().stream()
                    .filter(id -> id.equals(uniqueId) || id.startsWith(descendantPrefix))
                    .toList();
            for (final var id : finishedIds) {
                final var state = pendingTests.remove(id);
                if (--state.testsToRun > 0) {
                    continue;
                }
                if (state.running) {
                    log.info(
                            "[MultiNetworkGroupQueue] Network group [{}] finished its last test; tearing down",
                            state.group.id());
                    toTearDown.add(state.group);
                } else if (waiting.remove(state)) {
                    // None of its tests ran (e.g. all skipped), so it no longer holds back heavier groups
                    changed.signalAll();
                }
            }
        } finally {
            lock.unlock();
        }
        return toTearDown;
    }

    /** Frees the nodes of {@code group}. Call after its networks are torn down. */
    public void release(@NonNull final NetworkGroup group) {
        lock.lock();
        try {
            final var state = groupsById.get(group.id());
            if (state != null) {
                releaseLocked(state);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Forgets every registered group and test, for the next test plan. */
    public void clear() {
        lock.lock();
        try {
            groupsById.clear();
            groupsByNetwork.clear();
            pendingTests.clear();
            waiting.clear();
            nodesInUse = 0;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void releaseLocked(@NonNull final GroupState state) {
        if (state.running) {
            state.running = false;
            nodesInUse -= state.group.weight();
            changed.signalAll();
        }
    }
}
