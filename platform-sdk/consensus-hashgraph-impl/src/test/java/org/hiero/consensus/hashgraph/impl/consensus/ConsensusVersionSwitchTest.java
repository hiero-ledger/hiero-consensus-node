// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.EventEmitterFactory;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.emitter.StandardEventEmitter;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

/**
 * Tests for the in-process consensus version switch built into {@link DefaultConsensusEngine}
 * (see {@link DefaultConsensusEngine#scheduleVersionSwitch(long)}).
 *
 * <p>The central property exploited here: in this POC the "old" and "new" versions are the <b>same</b>
 * {@link org.hiero.consensus.hashgraph.impl.consensus.ConsensusImpl} code. Therefore switching versions at any
 * round must be completely <b>transparent</b> — the sequence of consensus rounds produced by an engine that
 * switches at round {@code X} must be byte-for-byte identical (same round numbers, same snapshots, same event
 * order) to the sequence produced by an engine that never switches. Any defect in the capture buffer, the
 * snapshot handoff, the round truncation, or the replay would break this equality.
 */
class ConsensusVersionSwitchTest {

    private static final int NUMBER_OF_EVENTS = 3_000;
    private static final long NO_SWITCH = Long.MIN_VALUE;

    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final Metrics metrics = new NoOpMetrics();
    private final Time time = Time.getCurrent();

    /**
     * Switching at a single mid-run round produces exactly the same consensus as never switching.
     */
    @Test
    void switchIsTransparentAtMidRound() {
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random).withSize(4).build();
        final List<PlatformEvent> events = generateEvents(random, roster);

        // Reference: never switch.
        final RunResult reference = run(roster, events, NO_SWITCH);
        assertFalse(reference.rounds.isEmpty(), "the reference run should produce consensus rounds");

        // Choose a switch round in the middle of the reference run.
        final long switchRound =
                reference.rounds.get(reference.rounds.size() / 2).getRoundNum();

        // Switching run.
        final RunResult switched = run(roster, events, switchRound);

        assertSwitchWasExercised(switched.rounds, switchRound);
        assertConsensusIdentical(reference.rounds, switched.rounds);
    }

    /**
     * Sweeping the switch round across the whole run (every decided round) must be transparent for every choice.
     * This exhaustively covers, among other things, every batch-straddle boundary.
     */
    @Test
    void switchIsTransparentAtEveryRound() {
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random).withSize(4).build();
        final List<PlatformEvent> events = generateEvents(random, roster);

        final RunResult reference = run(roster, events, NO_SWITCH);
        assertFalse(reference.rounds.isEmpty(), "the reference run should produce consensus rounds");

        // Skip the first couple and last couple of rounds so the switch round is always a valid future boundary
        // with room on both sides.
        final List<Long> roundNumbers =
                reference.rounds.stream().map(ConsensusRound::getRoundNum).collect(Collectors.toList());
        for (int i = 2; i < roundNumbers.size() - 1; i++) {
            final long switchRound = roundNumbers.get(i);
            final RunResult switched = run(roster, events, switchRound);
            assertSwitchWasExercised(switched.rounds, switchRound);
            assertConsensusIdentical(reference.rounds, switched.rounds, "switchRound=" + switchRound);
        }
    }

    /**
     * Targeted straddle test: find a round {@code X} such that, in the reference run, a single
     * {@code addEvent} call decides both {@code X-1} and {@code X} in one batch. Switching there exercises the
     * exact path where the boundary snapshot and the discarded new-regime rounds appear in the same call.
     */
    @Test
    void switchIsTransparentAcrossABatchStraddle() {
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random).withSize(4).build();
        final List<PlatformEvent> events = generateEvents(random, roster);

        final RunResult reference = run(roster, events, NO_SWITCH);

        final long straddleRound = findBatchStraddleRound(reference.batches);
        assertTrue(
                straddleRound != NO_SWITCH,
                "expected at least one addEvent call to decide multiple rounds so a straddle can be tested");

        final RunResult switched = run(roster, events, straddleRound);
        assertSwitchWasExercised(switched.rounds, straddleRound);
        assertConsensusIdentical(reference.rounds, switched.rounds, "straddleRound=" + straddleRound);
    }

    // =================================================================================================
    //  Harness
    // =================================================================================================

    /** The flat list of consensus rounds, plus the per-addEvent-call batch of round numbers. */
    private record RunResult(
            @NonNull List<ConsensusRound> rounds, @NonNull List<List<Long>> batches) {}

    /**
     * Drive a fresh {@link DefaultConsensusEngine} over the given events. Events are hashed and passed through an
     * orphan buffer (so the engine receives them in topological order regardless of input order), exactly as the
     * production intake pipeline does. If {@code switchRound != NO_SWITCH}, a version switch is armed up front so
     * capture spans the whole run.
     */
    @NonNull
    private RunResult run(
            @NonNull final Roster roster, @NonNull final List<PlatformEvent> events, final long switchRound) {

        final NodeId selfId = NodeId.of(0);
        final FreezePeriodChecker noFreeze = (@NonNull final Instant t) -> false;
        final DefaultConsensusEngine engine =
                new DefaultConsensusEngine(configuration, metrics, time, roster, selfId, noFreeze, 0L);

        if (switchRound != NO_SWITCH) {
            // Arm before feeding anything: capture begins at genesis, so the capture buffer is guaranteed to
            // cover the entire non-ancient set at the switch boundary (pruning trims the ancient tail as we go).
            engine.scheduleVersionSwitch(switchRound);
        }

        final EventHasher hasher = new DefaultEventHasher();
        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());

        final List<ConsensusRound> allRounds = new ArrayList<>();
        final List<List<Long>> batches = new ArrayList<>();

        for (final PlatformEvent event : events) {
            // Fresh, unhashed copy so each run hashes independently and deterministically.
            final PlatformEvent hashed = hasher.hashEvent(event.copyGossipedData());
            for (final PlatformEvent released : orphanBuffer.handleEvent(hashed)) {
                final ConsensusEngineOutput out = engine.addEvent(released);
                if (!out.consensusRounds().isEmpty()) {
                    allRounds.addAll(out.consensusRounds());
                    batches.add(out.consensusRounds().stream()
                            .map(ConsensusRound::getRoundNum)
                            .collect(Collectors.toList()));
                }
            }
        }
        return new RunResult(allRounds, batches);
    }

    @NonNull
    private List<PlatformEvent> generateEvents(@NonNull final Randotron random, @NonNull final Roster roster) {
        final StandardEventEmitter emitter =
                new EventEmitterFactory(configuration, metrics, time, random, roster).newStandardEmitter();
        return emitter.emitEvents(NUMBER_OF_EVENTS);
    }

    /**
     * @return a round number {@code X} such that some addEvent call decided both {@code X-1} and {@code X}, or
     *         {@link #NO_SWITCH} if no such straddle exists in the run.
     */
    private static long findBatchStraddleRound(@NonNull final List<List<Long>> batches) {
        for (final List<Long> batch : batches) {
            for (int i = 1; i < batch.size(); i++) {
                if (batch.get(i) == batch.get(i - 1) + 1) {
                    return batch.get(i); // X such that X-1 and X are in the same batch
                }
            }
        }
        return NO_SWITCH;
    }

    // =================================================================================================
    //  Assertions
    // =================================================================================================

    private static void assertSwitchWasExercised(@NonNull final List<ConsensusRound> rounds, final long switchRound) {
        final boolean hasBelow = rounds.stream().anyMatch(r -> r.getRoundNum() < switchRound);
        final boolean hasAtOrAbove = rounds.stream().anyMatch(r -> r.getRoundNum() >= switchRound);
        assertTrue(
                hasBelow && hasAtOrAbove,
                "the run must span the switch boundary so the swap is actually exercised (switchRound=" + switchRound
                        + ")");
    }

    private static void assertConsensusIdentical(
            @NonNull final List<ConsensusRound> expected, @NonNull final List<ConsensusRound> actual) {
        assertConsensusIdentical(expected, actual, "");
    }

    /**
     * Consensus rounds must match exactly: same count, same round numbers, same snapshots, same consensus event
     * order. Local-only fields (wall-clock reachedConsTimestamp, pcesRound) are intentionally not compared.
     */
    private static void assertConsensusIdentical(
            @NonNull final List<ConsensusRound> expected,
            @NonNull final List<ConsensusRound> actual,
            @NonNull final String context) {

        assertEquals(expected.size(), actual.size(), "different number of consensus rounds [" + context + "]");

        for (int i = 0; i < expected.size(); i++) {
            final ConsensusRound e = expected.get(i);
            final ConsensusRound a = actual.get(i);

            assertEquals(
                    e.getRoundNum(), a.getRoundNum(), "round number mismatch at index " + i + " [" + context + "]");

            // The snapshot is the version-invariant consensus fingerprint of the round; equality here is the
            // strongest single check that the switch preserved consensus exactly.
            assertEquals(
                    e.getSnapshot(),
                    a.getSnapshot(),
                    "snapshot mismatch for round " + e.getRoundNum() + " [" + context + "]");

            final List<org.hiero.base.crypto.Hash> expectedHashes =
                    e.getConsensusEvents().stream().map(PlatformEvent::getHash).collect(Collectors.toList());
            final List<org.hiero.base.crypto.Hash> actualHashes =
                    a.getConsensusEvents().stream().map(PlatformEvent::getHash).collect(Collectors.toList());
            assertEquals(
                    expectedHashes,
                    actualHashes,
                    "consensus event order mismatch for round " + e.getRoundNum() + " [" + context + "]");
        }
    }
}
