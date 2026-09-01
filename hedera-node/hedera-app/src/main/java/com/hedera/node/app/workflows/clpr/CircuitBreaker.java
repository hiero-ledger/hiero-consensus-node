// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.IntSupplier;

/**
 * Per-peer circuit breaker with three states: CLOSED, OPEN, HALF_OPEN.
 *
 * <p>The failure threshold is read via an {@link IntSupplier} on every
 * {@link #recordFailure()} so that operator changes to {@code clpr.retryMaxAttempts}
 * take effect on existing breakers without restarting nodes.
 */
final class CircuitBreaker {

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final IntSupplier failureThresholdSupplier;
    private final Duration cooldownDuration;
    private final InstantSource instantSource;
    private State state = State.CLOSED;
    private int failureCount;
    private Instant openedAt = Instant.MIN;

    CircuitBreaker(final int failureThreshold, @NonNull final Duration cooldownDuration) {
        this(() -> failureThreshold, cooldownDuration, InstantSource.system());
    }

    CircuitBreaker(
            final int failureThreshold,
            @NonNull final Duration cooldownDuration,
            @NonNull final InstantSource instantSource) {
        this(() -> failureThreshold, cooldownDuration, instantSource);
    }

    CircuitBreaker(@NonNull final IntSupplier failureThresholdSupplier, @NonNull final Duration cooldownDuration) {
        this(failureThresholdSupplier, cooldownDuration, InstantSource.system());
    }

    CircuitBreaker(
            @NonNull final IntSupplier failureThresholdSupplier,
            @NonNull final Duration cooldownDuration,
            @NonNull final InstantSource instantSource) {
        this.failureThresholdSupplier = requireNonNull(failureThresholdSupplier);
        this.cooldownDuration = requireNonNull(cooldownDuration);
        this.instantSource = requireNonNull(instantSource);
    }

    /**
     * Returns true if the circuit allows a sync attempt.
     */
    synchronized boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (instantSource.instant().isAfter(openedAt.plus(cooldownDuration))) {
                    state = State.HALF_OPEN;
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> true;
        };
    }

    /**
     * Records a successful sync, resetting the breaker.
     */
    synchronized void recordSuccess() {
        failureCount = 0;
        state = State.CLOSED;
    }

    /**
     * Records a failed sync. Opens the breaker if threshold exceeded.
     */
    synchronized void recordFailure() {
        failureCount++;
        if (failureCount >= failureThresholdSupplier.getAsInt()) {
            state = State.OPEN;
            openedAt = instantSource.instant();
        }
    }

    synchronized State state() {
        return state;
    }
}
