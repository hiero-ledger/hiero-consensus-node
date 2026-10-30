// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.errorprone.annotations.ThreadSafe;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-peer reputation for weighted peer selection.
 * Reputation starts at 1.0 and decays toward 0.1 on failures, recovering toward 1.0 on successes.
 * Scores decay toward 0.5 overtime when idle; the next recorded event discards any pending decay
 * and resumes from the stored raw score.
 * <p>
 * This class is thread-safe (can be safely written/read by different threads).
 */
@ThreadSafe
final class PeerReputation {

    private static final double MAX_SCORE = 1.0;
    private static final double MIN_SCORE = 0.1;
    private static final double NEUTRAL_SCORE = 0.5;
    private static final double FAILURE_PENALTY = 0.3;
    private static final double SUCCESS_BOOST = 0.1;

    private record Snapshot(double rawScore, Instant lastUpdated) {}

    private final Duration decayPeriod;
    private final InstantSource instantSource;
    private final AtomicReference<Snapshot> state;

    PeerReputation(@NonNull final Duration decayPeriod) {
        this(decayPeriod, InstantSource.system());
    }

    PeerReputation(@NonNull final Duration decayPeriod, @NonNull final InstantSource instantSource) {
        this.decayPeriod = requireNonNull(decayPeriod);
        this.instantSource = requireNonNull(instantSource);
        this.state = new AtomicReference<>(new Snapshot(MAX_SCORE, instantSource.instant()));
    }

    /**
     * Returns the current reputation score after applying time-based decay toward {@link #NEUTRAL_SCORE}.
     */
    double score() {
        final var snap = state.get();
        final var elapsed = Duration.between(snap.lastUpdated, instantSource.instant());
        if (elapsed.compareTo(decayPeriod) >= 0) {
            return NEUTRAL_SCORE;
        }
        // Linear interpolation toward NEUTRAL_SCORE
        final double fraction = (double) elapsed.toMillis() / decayPeriod.toMillis();
        return snap.rawScore + (NEUTRAL_SCORE - snap.rawScore) * fraction;
    }

    void recordSuccess() {
        state.updateAndGet(s -> new Snapshot(Math.min(MAX_SCORE, s.rawScore + SUCCESS_BOOST), instantSource.instant()));
    }

    void recordFailure() {
        state.updateAndGet(
                s -> new Snapshot(Math.max(MIN_SCORE, s.rawScore - FAILURE_PENALTY), instantSource.instant()));
    }

    double rawScore() {
        return state.get().rawScore;
    }
}
