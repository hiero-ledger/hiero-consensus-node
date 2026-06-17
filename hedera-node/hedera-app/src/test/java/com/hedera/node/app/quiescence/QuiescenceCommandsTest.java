// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.hiero.consensus.model.quiescence.QuiescenceCommand.BREAK_QUIESCENCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.swirlds.platform.system.Platform;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link QuiescenceCommands}. Covers the basic transition / no-op contract, the reconnect reset, and
 * the desync regression scenario.
 */
@ExtendWith(MockitoExtension.class)
class QuiescenceCommandsTest {

    @Mock
    private Platform platform;

    private QuiescenceCommands subject;

    @BeforeEach
    void setUp() {
        subject = new QuiescenceCommands(platform, new NoOpMetrics());
    }

    @Test
    void initialCommandIsDontQuiesce() {
        assertEquals(DONT_QUIESCE, subject.current());
    }

    @Test
    void updateEmitsAndRecordsTransitionToQuiesce() {
        final boolean transitioned = subject.update(QUIESCE);

        assertTrue(transitioned, "First update to QUIESCE must report a real transition");
        assertEquals(QUIESCE, subject.current());
        verify(platform).quiescenceCommand(QUIESCE);
    }

    @Test
    void updateIsNoopWhenCommandUnchanged() {
        subject.update(QUIESCE);
        final boolean repeated = subject.update(QUIESCE);

        assertFalse(repeated, "Repeated update with the same command must not report a transition");
        assertEquals(QUIESCE, subject.current());
        verify(platform, times(1)).quiescenceCommand(QUIESCE);
    }

    @Test
    void updateRoundTripQuiesceBreakQuiesceQuiesce() {
        // The exact sequence behind the #25140 desync bug: manager records QUIESCE, heartbeat transitions out
        // to BREAK_QUIESCENCE, network goes quiet again, manager polls and records QUIESCE a second time.
        // Each transition must be emitted to the platform and reflected in current().
        assertTrue(subject.update(QUIESCE));
        verify(platform).quiescenceCommand(QUIESCE);

        assertTrue(subject.update(BREAK_QUIESCENCE));
        verify(platform).quiescenceCommand(BREAK_QUIESCENCE);

        assertTrue(subject.update(QUIESCE));
        // Two QUIESCE emissions in total — the second is a real transition because the intervening
        // BREAK_QUIESCENCE moved us off QUIESCE.
        verify(platform, times(2)).quiescenceCommand(QUIESCE);

        assertEquals(QUIESCE, subject.current());
    }

    @Test
    void resetForReconnectRestoresInitialState() {
        subject.update(QUIESCE);
        verify(platform).quiescenceCommand(QUIESCE);

        subject.resetForReconnect();

        assertEquals(DONT_QUIESCE, subject.current(), "Reset must move the recorded command back to DONT_QUIESCE");
        // Reset must NOT emit anything to the platform — it only clears local state.
        verify(platform, times(1)).quiescenceCommand(QUIESCE);
        // After reset, a subsequent transition to QUIESCE must fire again (the reset was effective).
        assertTrue(subject.update(QUIESCE));
        verify(platform, times(2)).quiescenceCommand(QUIESCE);
    }

    @Test
    void resetForReconnectDoesNotEmitWithoutPriorTransition() {
        subject.resetForReconnect();
        verifyNoInteractions(platform);
        assertEquals(DONT_QUIESCE, subject.current());
    }

    /**
     * Concurrent {@link QuiescenceCommands#update} invocations must preserve the
     * <i>count invariant</i>: every successful CAS (i.e. every call that returns {@code true}) corresponds to
     * exactly one platform dispatch.
     *
     * <p>This test does NOT assert ordering of the dispatched commands. Two threads winning back-to-back CAS
     * operations can land their {@code platform.quiescenceCommand(...)} calls in the opposite order from the
     * CAS sequence — that is the documented race ({@code Platform.quiescenceCommand} is explicitly
     * "last-writer-wins under concurrent calls"). The contract this collaborator must keep is weaker:
     * <ol>
     *   <li>Every CAS-recorded transition produces exactly one platform call. No transition is silently
     *       dropped; no transition is duplicated.</li>
     *   <li>The final value of {@code current()} is always a legal {@link QuiescenceCommand}, not a torn
     *       state.</li>
     * </ol>
     * If a future refactor moves the dispatch outside the CAS-success branch, the dispatch count and
     * transition count diverge and this test fails.
     */
    @Test
    void updateIsConsistentUnderConcurrentDispatch() throws InterruptedException {
        final int threads = 4;
        final int iterationsPerThread = 1000;
        final var executor = Executors.newFixedThreadPool(threads);
        final var transitionedCount = new AtomicInteger();
        final var ready = new CountDownLatch(threads);
        final var startGun = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final long seed = t;
            executor.submit(() -> {
                final var rng = new Random(seed);
                ready.countDown();
                try {
                    startGun.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < iterationsPerThread; i++) {
                    final var cmd = rng.nextBoolean() ? QUIESCE : DONT_QUIESCE;
                    if (subject.update(cmd)) {
                        transitionedCount.incrementAndGet();
                    }
                }
            });
        }
        // Make sure every thread is parked before releasing them so they actually contend.
        ready.await();
        startGun.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "Threads did not finish in time");

        // The count invariant: every transition reported by update() == 1 platform dispatch.
        verify(platform, times(transitionedCount.get())).quiescenceCommand(any());
        // The final state must be one of the legal commands actually written.
        final QuiescenceCommand finalState = subject.current();
        assertTrue(
                finalState == QUIESCE || finalState == DONT_QUIESCE,
                "current() must be a legal command, not a torn state, was: " + finalState);
    }
}
