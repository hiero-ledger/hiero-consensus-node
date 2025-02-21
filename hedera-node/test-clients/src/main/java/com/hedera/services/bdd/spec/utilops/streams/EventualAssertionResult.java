// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.utilops.streams.assertions.AssertionResult;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Represents the possibly pending result of an assertion. The result will be pending for at most the given timeout
 * duration, after which the result will be completed as either a success or a failure based on the value of the
 * {@code passAfterTimeout} flag.
 */
public class EventualAssertionResult {
    /**
     * The maximum time the result can be pending before timing out.
     */
    private final Duration timeout;
    /**
     * Whether the result on timing out should be taken as success.
     */
    private final boolean passAfterTimeout;
    /**
     * The latch that will be counted down when the result is ready.
     */
    private final CountDownLatch ready = new CountDownLatch(1);

    /**
     * The result of the assertion.
     */
    private AssertionResult result;

    /**
     * Creates a new {@link EventualAssertionResult} with the given timeout and the given flag.
     * @param passAfterTimeout whether the result on timing out should be taken as success
     * @param timeout the maximum time the result can be pending before timing out
     */
    public EventualAssertionResult(final boolean passAfterTimeout, @NonNull final Duration timeout) {
        this.passAfterTimeout = passAfterTimeout;
        this.timeout = requireNonNull(timeout);
    }

    /**
     * Blocks until the result is ready, or until the timeout has elapsed. If the result is not ready by the time the
     * timeout has elapsed, the result will be completed as either a success or a timeout based on the value of the
     * {@code passAfterTimeout} flag.
     * @return the result of the assertion
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public AssertionResult get() throws InterruptedException {
        if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            if (passAfterTimeout && result == null) {
                return AssertionResult.newSuccess();
            } else {
                return AssertionResult.newTimeout(timeout);
            }
        }
        return result;
    }

    /**
     * Completes the result as a success.
     */
    public void pass() {
        this.result = AssertionResult.newSuccess();
        ready.countDown();
    }

    /**
     * Completes the result as a failure with the given reason.
     * @param reason the reason for the failure
     */
    public void fail(@NonNull final String reason) {
        requireNonNull(reason);
        this.result = AssertionResult.failure(reason);
        ready.countDown();
    }
}
