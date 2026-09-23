// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void startsInClosedState() {
        final var cb = new CircuitBreaker(3, Duration.ofSeconds(60));
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void opensAfterThresholdFailures() {
        final var cb = new CircuitBreaker(3, Duration.ofSeconds(60));
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
    }

    @Test
    void resetsOnSuccess() {
        final var cb = new CircuitBreaker(3, Duration.ofSeconds(60));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void transitionsToHalfOpenAfterCooldown() {
        final var now = new AtomicReference<>(Instant.parse("2025-01-01T00:00:00Z"));
        final InstantSource clock = now::get;
        final var cb = new CircuitBreaker(1, Duration.ofSeconds(60), clock);
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Advance past the cooldown window.
        now.set(now.get().plusSeconds(61));

        assertThat(cb.allowRequest()).isTrue();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void remainsOpenWithinCooldown() {
        final var now = new AtomicReference<>(Instant.parse("2025-01-01T00:00:00Z"));
        final InstantSource clock = now::get;
        final var cb = new CircuitBreaker(1, Duration.ofSeconds(60), clock);
        cb.recordFailure();

        // Advance, but not past the cooldown window.
        now.set(now.get().plusSeconds(30));

        assertThat(cb.allowRequest()).isFalse();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpenResetsToClosedOnSuccess() {
        final var now = new AtomicReference<>(Instant.parse("2025-01-01T00:00:00Z"));
        final InstantSource clock = now::get;
        final var cb = new CircuitBreaker(1, Duration.ofSeconds(60), clock);
        cb.recordFailure();

        now.set(now.get().plusSeconds(61));

        cb.allowRequest(); // transitions to HALF_OPEN
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
