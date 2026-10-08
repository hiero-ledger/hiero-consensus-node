// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Caps how many subprocess <b>nodes</b> are alive at once across all shared network groups, so
 * lazy-started networks don't all boot together and starve each other. A network group is the set of
 * networks a test's {@code @Network} annotation names, e.g. {@code {ledgerA, ledgerB}},
 * {@code {ledgerA_mtls, ledgerB_mtls}}, or {@code {ledgerA_manifest, ledgerB_manifest}}.
 *
 * <p>The budget is a total <b>node count</b> ({@link #MAX_NODES}), not a group count: a network group
 * costs {@link #nodeWeight the sum of its networks' node counts}. On its first test a network group
 * acquires that many permits and boots its networks; it holds them across all its tests and releases
 * them only when its <b>last</b> test finishes. A network group whose nodes don't fit the remaining budget
 * blocks in {@code beforeEach} until enough nodes free up. With {@code MAX_NODES = 4} this runs the
 * 3-node manifest group alone while two 2-node groups (e.g. general + mtls) still pair up.
 *
 * <p>"Last test" is known from a per-network group count of the ENABLED {@code @MultiNetworkHapiTest}
 * factory methods, tallied up front by {@code SharedMultiNetworkLauncherSessionListener} while it walks
 * the test plan (see {@link #registerPendingTest}). {@code @Disabled} tests are excluded from the tally:
 * they never run, so they must not be counted (or the network group would never reach zero and its
 * permits would leak) — which also means a fully-{@code @Disabled} suite is never admitted and its
 * networks never boot.
 *
 * <p>Admission is size-priority: a group starts only when (1) enough nodes are free AND (2) no
 * strictly-smaller group is still <b>waiting to start</b> (pending but not yet admitted). So a bigger group
 * (e.g. the 3-node manifest) can't grab the budget at the start ahead of the smaller groups and runs last —
 * yet a smaller group only gates a bigger one while it is queued, not once it has started, so differently
 * sized groups that fit together still co-run. Both the wait and the permit acquire are wrapped in
 * {@link ForkJoinPool#managedBlock} so a blocked {@code beforeEach} yields its JUnit worker to the pool.
 */
public final class MultiNetworkGroupBudget {
    private static final Logger log = LogManager.getLogger(MultiNetworkGroupBudget.class);

    /** Max subprocess nodes alive at once across all network groups. Tuned to the HAPI runner (8 cores). */
    private static final int MAX_NODES = 4;

    /** Safety cap on how long a group yields to smaller groups before proceeding anyway (guards a stuck count). */
    private static final long YIELD_MAX_WAIT_MILLIS = 45L * 60L * 1000L;

    private static final Semaphore SLOTS = new Semaphore(MAX_NODES, /* fair= */ true);
    private static final Set<String> ACTIVE_NETWORK_GROUPS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<String, AtomicInteger> PENDING_BY_NETWORK_GROUP = new ConcurrentHashMap<>();
    /** Node weight (capped at {@link #MAX_NODES}) of every group in the plan, so a group can size up its peers. */
    private static final ConcurrentMap<String, Integer> WEIGHT_BY_NETWORK_GROUP = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Object> GROUP_GATES = new ConcurrentHashMap<>();
    /** Permits actually acquired per admitted network group, so release frees exactly what was taken. */
    private static final ConcurrentMap<String, Integer> ADMITTED_NODE_PERMITS = new ConcurrentHashMap<>();

    private MultiNetworkGroupBudget() {}

    /** Stable identity for the network group a test occupies: its {@code @Network} names, sorted and joined. */
    @NonNull
    public static String networkGroupKey(@NonNull final Network[] configs) {
        return Arrays.stream(configs)
                .map(MultiNetworkExtension::resolveName)
                .sorted()
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    /**
     * Total node count a network group boots: the sum of its networks' sizes over DISTINCT network names
     * (the same dedup {@link #networkGroupKey} uses), so the budget cost equals the nodes actually started.
     */
    public static int nodeWeight(@NonNull final Network[] configs) {
        final Map<String, Integer> sizeByName = new LinkedHashMap<>();
        for (final var cfg : configs) {
            sizeByName.putIfAbsent(MultiNetworkExtension.resolveName(cfg), cfg.size());
        }
        return sizeByName.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Records one ENABLED factory method for the group {@code configs} names, and (once) that group's node
     * weight so admission can compare group sizes. Called once per method during the plan walk.
     */
    public static void registerPendingTest(@NonNull final Network[] configs) {
        final String key = networkGroupKey(configs);
        WEIGHT_BY_NETWORK_GROUP.putIfAbsent(key, cappedWeight(nodeWeight(configs)));
        PENDING_BY_NETWORK_GROUP.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    /** The effective node cost of a group: its weight, floored at 1 and capped at {@link #MAX_NODES}. */
    private static int cappedWeight(final int nodeWeight) {
        return Math.min(Math.max(nodeWeight, 1), MAX_NODES);
    }

    /**
     * Ensures {@code networkGroupKey} holds its node permits, blocking (via {@link ForkJoinPool#managedBlock})
     * until enough nodes are free if the network group isn't already admitted. Called in {@code beforeEach}
     * after the caller has taken the network group's network locks (so only one test per network group
     * reaches here at a time).
     *
     * @param networkGroupKey the network group's stable key
     * @param nodeWeight the total nodes this group boots (see {@link #nodeWeight}); capped at {@link #MAX_NODES}
     *     so an over-large group runs alone rather than deadlocking
     * @return {@code true} if this call newly admitted the network group (caller should lazily start its
     *     networks), {@code false} if the network group was already alive (caller reuses the warm networks)
     */
    public static boolean enterNetworkGroup(@NonNull final String networkGroupKey, final int nodeWeight) {
        // Concurrent (READ) tests of the same group can call this at once, so a per-group monitor makes
        // the "already alive? if not, take permits" step atomic and a group takes exactly one set of
        // permits. The monitor is per key, so a group blocked on the semaphore never blocks a different
        // group's tests.
        synchronized (GROUP_GATES.computeIfAbsent(networkGroupKey, k -> new Object())) {
            if (ACTIVE_NETWORK_GROUPS.contains(networkGroupKey)) {
                return false;
            }
            final int permits = cappedWeight(nodeWeight);
            if (permits < nodeWeight) {
                log.warn(
                        "[MultiNetworkGroupBudget] Network group [{}] needs {} nodes but the budget is {}; "
                                + "admitting it alone (over-subscribed)",
                        networkGroupKey,
                        nodeWeight,
                        MAX_NODES);
            }
            WEIGHT_BY_NETWORK_GROUP.putIfAbsent(networkGroupKey, permits);
            // Size-priority admission: yield while any strictly-smaller group is still waiting to start, so a
            // bigger group (e.g. the 3-node manifest) can't grab the budget at the start ahead of the smaller
            // groups and must run last. This is robust regardless of JUnit's class-submission race (under
            // fixed parallelism it submits all suites at once, so @Order can't order them). A smaller group
            // stops gating us once it has STARTED — then the node budget alone gates — so differently-sized
            // groups that fit together still co-run. Yielding happens BEFORE acquiring, so we hold no nodes
            // while we wait and the smaller groups use the full budget.
            awaitNoSmallerGroupWaiting(networkGroupKey, permits);
            acquireNodesManaged(permits);
            ADMITTED_NODE_PERMITS.put(networkGroupKey, permits);
            ACTIVE_NETWORK_GROUPS.add(networkGroupKey);
            log.info(
                    "[MultiNetworkGroupBudget] Admitted network group [{}] ({} nodes; {} of {} node slots now in use)",
                    networkGroupKey,
                    permits,
                    MAX_NODES - SLOTS.availablePermits(),
                    MAX_NODES);
            return true;
        }
    }

    /**
     * Decrements {@code networkGroupKey}'s remaining-test count. Returns {@code true} when this was the
     * network group's LAST test, meaning the caller must tear down the network group's networks and then
     * call {@link #releaseSlot} to free its node permits for a waiting network group. Removing the network
     * group from the active set here (before teardown) prevents a late same-network group test from
     * treating it as still warm.
     */
    public static boolean leaveNetworkGroupIsLast(@NonNull final String networkGroupKey) {
        final var counter = PENDING_BY_NETWORK_GROUP.get(networkGroupKey);
        final int remaining = counter == null ? 0 : counter.decrementAndGet();
        if (remaining <= 0) {
            ACTIVE_NETWORK_GROUPS.remove(networkGroupKey);
            log.info(
                    "[MultiNetworkGroupBudget] Network group [{}] finished its last test; tearing down",
                    networkGroupKey);
            return true;
        }
        return false;
    }

    /** Frees the node permits held by a network group; call AFTER the network group's networks are torn down. */
    public static void releaseSlot(@NonNull final String networkGroupKey) {
        final Integer permits = ADMITTED_NODE_PERMITS.remove(networkGroupKey);
        if (permits != null && permits > 0) {
            SLOTS.release(permits);
        }
    }

    /**
     * Blocks while any strictly-smaller group is still <b>waiting to start</b> — pending (has un-run tests)
     * and not yet admitted — so this group yields the budget to smaller groups queued ahead of it and runs
     * after them. A smaller group stops counting the moment it is admitted (it then holds its own nodes and
     * the semaphore gates us), so groups that fit together co-run rather than serialize. The waiting set only
     * shrinks (a group goes waiting → admitted → done, never back), so this terminates; a
     * {@link #YIELD_MAX_WAIT_MILLIS} cap guards a stuck pending count. Wrapped in
     * {@link ForkJoinPool#managedBlock} so the blocked JUnit worker compensates.
     */
    private static void awaitNoSmallerGroupWaiting(final String networkGroupKey, final int myWeight) {
        final long deadline = System.nanoTime() + YIELD_MAX_WAIT_MILLIS * 1_000_000L;
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override
                public boolean block() throws InterruptedException {
                    while (!isReleasable()) {
                        Thread.sleep(200);
                    }
                    return true;
                }

                @Override
                public boolean isReleasable() {
                    if (System.nanoTime() - deadline >= 0) {
                        log.warn(
                                "[MultiNetworkGroupBudget] Group [{}] yielded for the max wait; proceeding "
                                        + "despite a smaller group still waiting to start",
                                networkGroupKey);
                        return true;
                    }
                    return !anySmallerGroupWaiting(networkGroupKey, myWeight);
                }
            });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while group [" + networkGroupKey + "] yielded to smaller groups", e);
        }
    }

    /** True if some strictly-smaller group is pending but not yet admitted (i.e. still queued to start). */
    private static boolean anySmallerGroupWaiting(final String myKey, final int myWeight) {
        return PENDING_BY_NETWORK_GROUP.entrySet().stream().anyMatch(e -> {
            final String key = e.getKey();
            if (key.equals(myKey) || e.getValue().get() <= 0 || ACTIVE_NETWORK_GROUPS.contains(key)) {
                return false; // ourselves, a finished group, or one that already started — not waiting
            }
            final Integer weight = WEIGHT_BY_NETWORK_GROUP.get(key);
            return weight != null && weight < myWeight;
        });
    }

    private static void acquireNodesManaged(final int permits) {
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                private boolean acquired = false;

                @Override
                public boolean block() throws InterruptedException {
                    if (!acquired) {
                        SLOTS.acquire(permits);
                        acquired = true;
                    }
                    return true;
                }

                @Override
                public boolean isReleasable() {
                    return acquired || (acquired = SLOTS.tryAcquire(permits));
                }
            });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring multi-network node permits", e);
        }
    }
}
