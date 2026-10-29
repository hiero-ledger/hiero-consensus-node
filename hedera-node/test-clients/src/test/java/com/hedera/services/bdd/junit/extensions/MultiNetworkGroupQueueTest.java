// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.extensions.MultiNetworkValidation.ReadyGroup;
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

    private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
    private final List<CompletableFuture<ReadyGroup>> takers = new ArrayList<>();

    private static final NetworkGroup SMALL = NetworkGroup.of(Map.of("a", 1, "b", 1));
    private static final NetworkGroup TLS = NetworkGroup.of(Map.of("a_tls", 1, "b_tls", 1));
    private static final NetworkGroup MANIFEST = NetworkGroup.of(Map.of("a_manifest", 2, "b_manifest", 1));

    private final MultiNetworkGroupQueue queue = new MultiNetworkGroupQueue(4);

    @AfterEach
    void unblockWaiters() {
        // clear() wakes any test still blocked in awaitSlot or takeGroupToValidate, so no waiter thread outlives its
        // test
        queue.clear();
        waiters.forEach(waiter -> waiter.orTimeout(LONG.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> null)
                .join());
        takers.forEach(taker -> taker.orTimeout(LONG.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> null)
                .join());
    }

    /** Starts waiting for a slot for {@code group} on another thread and asserts it is still blocked. */
    private CompletableFuture<Void> assertBlocked(final NetworkGroup group) {
        final var waiter = CompletableFuture.runAsync(() -> queue.awaitSlot(group));
        waiters.add(waiter);
        assertThrows(TimeoutException.class, () -> waiter.get(SHORT.toMillis(), TimeUnit.MILLISECONDS));
        return waiter;
    }

    /** Asserts that {@code waiter} gets its slot. */
    private static void admitted(final CompletableFuture<Void> waiter) throws Exception {
        waiter.get(LONG.toMillis(), TimeUnit.MILLISECONDS);
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
    @DisplayName("Groups that fit the budget together get slots together")
    void groupsThatFitRunTogether() {
        queue.register("small-1", SMALL);
        queue.register("tls-1", TLS);

        queue.awaitSlot(SMALL);
        queue.awaitSlot(TLS);
    }

    @Test
    @DisplayName("A group that already has a slot lets its next test through at once")
    void runningGroupAdmitsNextTest() {
        queue.register("small-1", SMALL);
        queue.register("small-2", SMALL);
        queue.awaitSlot(SMALL);

        assertTrue(queue.testsFinished("small-1").isEmpty(), "not the group's last test");
        queue.awaitSlot(SMALL);
    }

    @Test
    @DisplayName("A waiting group gets a slot once a running group's last test finishes and it is released")
    void releaseAdmitsWaitingGroup() throws Exception {
        queue.register("small-1", SMALL);
        queue.register("manifest-1", MANIFEST);
        queue.awaitSlot(SMALL);

        final var manifestTurn = assertBlocked(MANIFEST);

        assertEquals(List.of(SMALL), queue.testsFinished("small-1"), "last test of a running group");
        assertThrows(
                TimeoutException.class,
                () -> manifestTurn.get(SHORT.toMillis(), TimeUnit.MILLISECONDS),
                "nodes are held until the group is released");

        queue.release(SMALL);
        admitted(manifestTurn);
    }

    @Test
    @DisplayName("Finishing a container finishes only the tests under it")
    void containerFinishesItsDescendantsOnly() {
        queue.register("[class:Suite]/[test-factory:one()]", SMALL);
        queue.register("[class:Suite]/[test-factory:two()]", SMALL);
        queue.register("[class:SuiteTwo]/[test-factory:one()]", SMALL);
        queue.awaitSlot(SMALL);

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
        queue.awaitSlot(SMALL);

        queue.bootFailed(SMALL);

        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitSlot(SMALL));
        assertTrue(e.getMessage().contains("failed to start"), e.getMessage());
        assertTrue(queue.testsFinished("small-1").isEmpty(), "already released");
        assertTrue(queue.testsFinished("small-2").isEmpty(), "already released");
        queue.awaitSlot(MANIFEST);
    }

    @Test
    @DisplayName("A group needing more nodes than the budget fails fast without blocking others")
    void oversizedGroupFailsFast() {
        final var oversized = NetworkGroup.of(Map.of("big_a", 3, "big_b", 2));
        queue.register("big-1", oversized);
        queue.register("small-1", SMALL);

        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitSlot(oversized));
        assertTrue(e.getMessage().contains("needs 5 nodes"), e.getMessage());
        queue.awaitSlot(SMALL);
    }

    @Test
    @DisplayName("A network used by two groups fails both groups")
    void networkInTwoGroupsFailsBoth() {
        queue.register("small-1", SMALL);
        final var overlap = NetworkGroup.of(Map.of("a", 1));
        queue.register("overlap-1", overlap);

        assertThrows(IllegalStateException.class, () -> queue.awaitSlot(SMALL));
        final var e = assertThrows(IllegalStateException.class, () -> queue.awaitSlot(overlap));
        assertTrue(e.getMessage().contains("Network 'a' is used by two network groups"), e.getMessage());
    }

    @Test
    @DisplayName("A group that was not registered cannot get a slot")
    void unregisteredTestFails() {
        assertThrows(IllegalStateException.class, () -> queue.awaitSlot(NetworkGroup.of(Map.of("unknown", 1))));
    }

    @Test
    @DisplayName("Registering the same test twice counts it once")
    void duplicateRegistrationCountsOnce() {
        queue.register("small-1", SMALL);
        queue.register("small-1", SMALL);
        queue.awaitSlot(SMALL);

        assertEquals(List.of(SMALL), queue.testsFinished("small-1"));
    }

    /** Takes the next group to validate on a ForkJoinPool worker, as a parallel JUnit run does. */
    private CompletableFuture<ReadyGroup> takeAsync() {
        final var taker = CompletableFuture.supplyAsync(queue::takeGroupToValidate);
        takers.add(taker);
        return taker;
    }

    private static ReadyGroup taken(final CompletableFuture<ReadyGroup> taker) throws Exception {
        return taker.get(LONG.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("With validation enabled, a group is validated after its tests and then torn down")
    void groupIsValidatedThenTornDown() throws Exception {
        queue.register("small-1", SMALL);
        queue.setValidationEnabled(true);
        queue.awaitSlot(SMALL);

        assertTrue(queue.testsFinished("small-1").isEmpty(), "validation is still ahead");
        assertEquals(new ReadyGroup(SMALL, null), taken(takeAsync()));
        assertTrue(queue.validationFinished(SMALL), "to be torn down");
        assertNull(taken(takeAsync()), "no group left to validate");
    }

    @Test
    @DisplayName("Without validation, a group goes from its last test straight to teardown")
    void noValidationGoesStraightToTeardown() throws Exception {
        queue.register("small-1", SMALL);
        queue.awaitSlot(SMALL);

        assertEquals(List.of(SMALL), queue.testsFinished("small-1"));
        assertNull(taken(takeAsync()), "nothing to validate");
    }

    @Test
    @DisplayName("A group awaiting or in validation keeps its nodes until it is torn down and released")
    void groupInValidationHoldsItsNodes() throws Exception {
        queue.register("small-1", SMALL);
        queue.register("manifest-1", MANIFEST);
        queue.setValidationEnabled(true);
        queue.awaitSlot(SMALL);
        queue.testsFinished("small-1");

        // 2 + 3 nodes don't fit the budget of 4, even though small-1's tests are done
        final var manifestTurn = assertBlocked(MANIFEST);

        assertEquals(SMALL, taken(takeAsync()).group());
        assertTrue(queue.validationFinished(SMALL));
        assertThrows(
                TimeoutException.class,
                () -> manifestTurn.get(SHORT.toMillis(), TimeUnit.MILLISECONDS),
                "nodes are held through teardown");
        queue.release(SMALL);
        admitted(manifestTurn);
    }

    @Test
    @DisplayName("A validation test that starts first lets the groups' tests run and takes the groups as they finish")
    void validationStartedFirstDoesNotBlockTests() throws Exception {
        queue.register("small-1", SMALL);
        queue.register("tls-1", TLS);
        queue.setValidationEnabled(true);
        final var first = takeAsync();
        assertThrows(TimeoutException.class, () -> first.get(SHORT.toMillis(), TimeUnit.MILLISECONDS));

        queue.awaitSlot(TLS);
        queue.awaitSlot(SMALL);
        queue.testsFinished("tls-1");
        assertEquals(TLS, taken(first).group());
        queue.testsFinished("small-1");
        assertEquals(SMALL, taken(takeAsync()).group());
    }

    @Test
    @DisplayName("A group whose tests never ran or whose networks failed to start is still handed out, with the reason")
    void unvalidatableGroupsAreHandedOutWithReason() throws Exception {
        queue.register("small-1", SMALL);
        queue.setValidationEnabled(true);
        queue.testsFinished("small-1");

        final var neverRan = taken(takeAsync());
        assertEquals(SMALL, neverRan.group());
        assertTrue(neverRan.notValidatedReason().contains("ran"), neverRan.notValidatedReason());
        assertFalse(queue.validationFinished(SMALL), "holds no nodes, nothing to tear down");

        queue.register("tls-1", TLS);
        queue.awaitSlot(TLS);
        queue.bootFailed(TLS);
        queue.testsFinished("tls-1");
        final var failed = taken(takeAsync());
        assertEquals(TLS, failed.group());
        assertTrue(failed.notValidatedReason().contains("failed to start"), failed.notValidatedReason());
    }

    @Test
    @DisplayName("Ending validation moves the groups still awaiting it on to teardown")
    void endedValidationMovesAwaitingGroupsOn() throws Exception {
        queue.register("small-1", SMALL);
        queue.setValidationEnabled(true);
        queue.awaitSlot(SMALL);
        queue.testsFinished("small-1");

        assertEquals(List.of(SMALL), queue.validationEnded(), "no validation left: teardown");
        assertTrue(queue.validationEnded().isEmpty(), "ending it again does nothing");
        assertNull(taken(takeAsync()), "nothing left to validate");
    }

    @Test
    @DisplayName("Clearing the queue releases a validation test waiting for a group")
    void clearReleasesWaitingValidation() throws Exception {
        queue.register("small-1", SMALL);
        queue.setValidationEnabled(true);
        final var waiter = takeAsync();
        assertThrows(TimeoutException.class, () -> waiter.get(SHORT.toMillis(), TimeUnit.MILLISECONDS));

        queue.clear();

        assertNull(taken(waiter));
    }
}
