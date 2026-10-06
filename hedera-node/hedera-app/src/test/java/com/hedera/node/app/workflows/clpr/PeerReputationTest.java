// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PeerReputationTest {

    private static final Instant T0 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    @DisplayName("starts at the maximum score")
    void startsAtMaxScore() {
        final var rep = new PeerReputation(Duration.ofSeconds(300));
        assertThat(rep.rawScore()).isEqualTo(1.0);
        assertThat(rep.score()).isCloseTo(1.0, offset(0.01));
    }

    @Test
    @DisplayName("decreases on failure")
    void decreasesOnFailure() {
        final var rep = new PeerReputation(Duration.ofSeconds(300));
        rep.recordFailure();
        assertThat(rep.rawScore()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("increases on success")
    void increasesOnSuccess() {
        final var rep = new PeerReputation(Duration.ofSeconds(300));
        rep.recordFailure();
        rep.recordSuccess();
        assertThat(rep.rawScore()).isCloseTo(0.8, offset(0.001));
    }

    @Test
    @DisplayName("is clamped at the configured minimum and maximum")
    void clampedAtMinAndMax() {
        final var rep = new PeerReputation(Duration.ofSeconds(300));
        // Drive it down
        for (int i = 0; i < 10; i++) {
            rep.recordFailure();
        }
        assertThat(rep.rawScore()).isEqualTo(0.1);

        // Drive it up
        for (int i = 0; i < 20; i++) {
            rep.recordSuccess();
        }
        assertThat(rep.rawScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("decays toward neutral over time")
    void decaysTowardNeutralOverTime() {
        final var clock = new MutableInstantSource(T0);
        final var rep = new PeerReputation(Duration.ofSeconds(100), clock);
        clock.advance(Duration.ofSeconds(200));
        assertThat(rep.score()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("partially decays toward neutral, linearly interpolated by elapsed fraction")
    void partiallyDecays() {
        final var clock = new MutableInstantSource(T0);
        final var rep = new PeerReputation(Duration.ofSeconds(100), clock);
        rep.recordFailure(); // rawScore = 0.7
        clock.advance(Duration.ofSeconds(50));
        // Halfway: 0.7 + (0.5 - 0.7) * 0.5 = 0.6
        assertThat(rep.score()).isCloseTo(0.6, offset(0.001));
    }

    @Test
    @DisplayName("a recorded event discards pending decay and resumes from the raw score")
    void recordedEventDiscardsPendingDecay() {
        final var clock = new MutableInstantSource(T0);
        final var rep = new PeerReputation(Duration.ofSeconds(100), clock);
        rep.recordFailure(); // rawScore = 0.7
        clock.advance(Duration.ofSeconds(500));
        assertThat(rep.score()).isEqualTo(0.5); // fully decayed

        rep.recordSuccess();
        // Decay was discarded — raw score resumes from 0.7 and is boosted to 0.8.
        assertThat(rep.rawScore()).isCloseTo(0.8, offset(0.001));
        assertThat(rep.score()).isCloseTo(0.8, offset(0.001));
    }

    @Test
    @DisplayName("concurrent reads and writes keep score within bounds")
    void concurrentReadsAndWrites() throws Exception {
        // Hammer a single PeerReputation from 4 threads, each interleaving recordSuccess(),
        // recordFailure() and score() reads. We assert no exception escapes and that score()
        // never returns an out-of-range value — a torn read of the (rawScore, lastUpdated)
        // pair could produce one. Future.get() re-throws any AssertionError raised inside
        // the worker on the test thread, so a failure here fails the test.
        final var rep = new PeerReputation(Duration.ofSeconds(60));
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final List<Future<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(pool.submit(() -> {
                for (int j = 0; j < 1000; j++) {
                    rep.recordSuccess();
                    rep.recordFailure();
                    assertThat(rep.score()).isBetween(0.1, 1.0);
                }
            }));
        }
        for (final var t : tasks) {
            t.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    /** Minimal mutable {@link InstantSource} for deterministic time-based tests. */
    private static final class MutableInstantSource implements InstantSource {
        private Instant now;

        MutableInstantSource(final Instant start) {
            this.now = start;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(final Duration d) {
            now = now.plus(d);
        }
    }
}
