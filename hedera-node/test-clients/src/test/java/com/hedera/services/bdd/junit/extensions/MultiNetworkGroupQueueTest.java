// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MultiNetworkGroupQueue} and {@link NetworkGroup}.
 */
class MultiNetworkGroupQueueTest {
    private static final Duration LONG = Duration.ofSeconds(10);
    private static final Duration SHORT = Duration.ofMillis(200);

    private final List<CompletableFuture<NetworkGroup>> waiters = new ArrayList<>();

    private static final NetworkGroup SMALL = NetworkGroup.of(Map.of("a", 1, "b", 1));
    private static final NetworkGroup MTLS = NetworkGroup.of(Map.of("a_mtls", 1, "b_mtls", 1));
    private static final NetworkGroup MANIFEST = NetworkGroup.of(Map.of("a_manifest", 2, "b_manifest", 1));

    private final MultiNetworkGroupQueue queue = new MultiNetworkGroupQueue(4);

    @AfterEach
    void unblockWaiters() {
        // clear() wakes any test still blocked in awaitTurn, so no waiter thread outlives its test
        queue.clear();
        waiters.forEach(waiter -> waiter.orTimeout(LONG.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> null)
                .join());
    }

    /** Starts waiting for {@code testId}'s turn on another thread and asserts it is still blocked. */
    private CompletableFuture<NetworkGroup> assertBlocked(final String testId) {
        final var waiter = CompletableFuture.supplyAsync(() -> queue.awaitTurn(testId));
        waiters.add(waiter);
        assertThrows(TimeoutException.class, () -> waiter.get(SHORT.toMillis(), TimeUnit.MILLISECONDS));
        return waiter;
    }

    private static NetworkGroup admitted(final CompletableFuture<NetworkGroup> waiter) throws Exception {
        return waiter.get(LONG.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("A group's id is its sorted network names and its weight is their total node count")
    void groupIdAndWeight() {
        final var group = NetworkGroup.of(Map.of("ledgerB", 1, "ledgerA", 2));

        assertEquals("ledgerA,ledgerB", group.id());
        assertEquals(Set.of("ledgerA", "ledgerB"), group.networkNames());
        assertEquals(3, group.weight());
    }

    @Test
    @DisplayName("Groups that fit the budget together are admitted together")
    void groupsThatFitRunTogether() {
        queue.register("small-1", SMALL);
        queue.register("mtls-1", MTLS);

        assertEquals(SMALL, queue.awaitTurn("small-1"));
        assertEquals(MTLS, queue.awaitTurn("mtls-1"));
    }

    @Test
    @DisplayName("A group that is already running admits its next test at once")
    void runningGroupAdmitsNextTest() {
        queue.register("small-1", SMALL);
        queue.register("small-2", SMALL);
        queue.awaitTurn("small-1");

        assertTrue(queue.testsFinished("small-1").isEmpty(), "not the group's last test");
        assertEquals(SMALL, queue.awaitTurn("small-2"));
    }

    @Test
    @DisplayName("A heavier group waits while a lighter group is still waiting, even if its nodes fit")
    void lighterGroupGoesFirst() {
        queue.register("manifest-1", MANIFEST);
        queue.register("small-1", SMALL);

        final var manifestTurn = assertBlocked("manifest-1");

        queue.awaitTurn("small-1");
        // Now nothing lighter is waiting, but 2 + 3 nodes don't fit the budget of 4
        assertThrows(TimeoutException.class, () -> manifestTurn.get(SHORT.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("A waiting group is admitted once a running group's last test finishes and it is released")
    void releaseAdmitsWaitingGroup() throws Exception {
        queue.register("small-1", SMALL);
        queue.register("manifest-1", MANIFEST);
        queue.awaitTurn("small-1");

        final var manifestTurn = assertBlocked("manifest-1");

        assertEquals(List.of(SMALL), queue.testsFinished("small-1"), "last test of a running group");
        assertThrows(
                TimeoutException.class,
                () -> manifestTurn.get(SHORT.toMillis(), TimeUnit.MILLISECONDS),
                "nodes are held until the group is released");

        queue.release(SMALL);
        assertEquals(MANIFEST, admitted(manifestTurn));
    }

    @Test
    @DisplayName("A group whose tests all finish without running stops holding back heavier groups")
    void unstartedGroupFinishingUnblocksHeavierGroups() throws Exception {
        queue.register("small-1", SMALL);
        queue.register("manifest-1", MANIFEST);

        final var manifestTurn = assertBlocked("manifest-1");

        assertTrue(queue.testsFinished("small-1").isEmpty(), "the group never ran, so nothing to tear down");
        assertEquals(MANIFEST, admitted(manifestTurn));
    }

    @Test
    @DisplayName("Finishing a container finishes only the tests under it")
    void containerFinishesItsDescendantsOnly() {
        queue.register("[class:Suite]/[test-factory:one()]", SMALL);
        queue.register("[class:Suite]/[test-factory:two()]", SMALL);
        queue.register("[class:SuiteTwo]/[test-factory:one()]", SMALL);
        queue.awaitTurn("[class:Suite]/[test-factory:one()]");

        assertTrue(queue.testsFinished("[class:Suite]").isEmpty(), "a test of [class:SuiteTwo] is still pending");
        assertEquals(List.of(SMALL), queue.testsFinished("[class:SuiteTwo]/[test-factory:one()]"));
        assertTrue(queue.testsFinished("[class:SuiteTwo]").isEmpty(), "each test finishes only once");
    }

    @Test
    @DisplayName("After a failed boot the group's nodes are freed and its remaining tests fail fast")
    void failedBootFailsRemainingTestsAndFreesNodes() {
        queue.register("small-1", SMALL);
        queue.register("small-2", SMALL);
        queue.register("manifest-1", MANIFEST);
        queue.awaitTurn("small-1");

        queue.bootFailed("small-1");

        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitTurn("small-2"));
        assertTrue(e.getMessage().contains("failed to start"), e.getMessage());
        assertTrue(queue.testsFinished("small-1").isEmpty(), "already released");
        assertTrue(queue.testsFinished("small-2").isEmpty(), "already released");
        assertEquals(MANIFEST, queue.awaitTurn("manifest-1"));
    }

    @Test
    @DisplayName("A group needing more nodes than the budget fails fast without blocking others")
    void oversizedGroupFailsFast() {
        final var oversized = NetworkGroup.of(Map.of("big_a", 3, "big_b", 2));
        queue.register("big-1", oversized);
        queue.register("small-1", SMALL);

        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitTurn("big-1"));
        assertTrue(e.getMessage().contains("needs 5 nodes"), e.getMessage());
        assertEquals(SMALL, queue.awaitTurn("small-1"));
    }

    @Test
    @DisplayName("A network used by two groups fails both groups")
    void networkInTwoGroupsFailsBoth() {
        queue.register("small-1", SMALL);
        queue.register("overlap-1", NetworkGroup.of(Map.of("a", 1)));

        assertThrows(IllegalStateException.class, () -> queue.awaitTurn("small-1"));
        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitTurn("overlap-1"));
        assertTrue(e.getMessage().contains("Network 'a' is used by two network groups"), e.getMessage());
    }

    @Test
    @DisplayName("A test that was not registered cannot be admitted")
    void unregisteredTestFails() {
        assertThrows(IllegalStateException.class, () -> queue.awaitTurn("unknown"));
    }

    @Test
    @DisplayName("Registering the same test twice counts it once")
    void duplicateRegistrationCountsOnce() {
        queue.register("small-1", SMALL);
        queue.register("small-1", SMALL);
        queue.awaitTurn("small-1");

        assertEquals(List.of(SMALL), queue.testsFinished("small-1"));
    }
}
