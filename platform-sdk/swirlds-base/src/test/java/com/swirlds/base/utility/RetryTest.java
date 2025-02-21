// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.utility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Validates the behavior of the {@link Retry} utility class.
 */
@ExtendWith(MockitoExtension.class)
class RetryTest {

    @Test
    void nullValueShouldThrow() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> Retry.check(value -> true, null, 1, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value must not be null");

        //noinspection DataFlowIssue
        assertThatThrownBy(() -> Retry.resolve(value -> true, null, 1, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("value must not be null");
    }

    @Test
    void nullCheckFnShouldThrow() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> Retry.check(null, 1, 1, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("checkFn must not be null");

        //noinspection DataFlowIssue
        assertThatThrownBy(() -> Retry.resolve(null, 1, 1, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resolveFn must not be null");
    }

    @Test
    void checkPropagatesException() {
        assertThatThrownBy(() -> Retry.check(
                        value -> {
                            throw new RuntimeException("Test exception");
                        },
                        1,
                        1,
                        0))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception");

        assertThatThrownBy(() -> Retry.resolve(
                        value -> {
                            throw new IOException("Test exception");
                        },
                        1,
                        1,
                        0))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IOException.class)
                .hasMessage("Test exception");
    }

    @Test
    void checkResolvesWithinThreshold() throws ExecutionException, InterruptedException {
        resolvesAtAttempt(1, 5, true);
        resolvesAtAttempt(2, 5, true);
        resolvesAtAttempt(3, 5, true);
        resolvesAtAttempt(4, 5, true);
        resolvesAtAttempt(5, 5, true);
        resolvesAtAttempt(6, 5, false);
    }

    @Test
    void checkResolvesWithinThresholdWithDelay() {
        final AtomicInteger counter = new AtomicInteger(0);

        final Function<Integer, Boolean> checkFn = spyLambda(value -> {
            final int val = counter.incrementAndGet();
            return val == value;
        });

        await().atMost(125, TimeUnit.MILLISECONDS).until(() -> Retry.check(checkFn, 2, 5, 100));
        verify(checkFn, Mockito.times(2)).apply(2);

        //noinspection unchecked
        reset(checkFn);
        counter.set(0);
        Awaitility.with().untilAsserted(() -> {
            assertThat(Retry.check(checkFn, 2, 5, 100)).isTrue();
            verify(checkFn, Mockito.times(2)).apply(2);
        });

        //noinspection unchecked
        reset(checkFn);
        counter.set(0);
        await().pollDelay(10, TimeUnit.MILLISECONDS)
                .atMost(25, TimeUnit.MILLISECONDS)
                .until(() -> Retry.check(checkFn, 1, 5, 100));
        verify(checkFn, Mockito.times(1)).apply(1);
    }

    @Test
    void invalidDelayShouldThrow() {
        assertThatThrownBy(() -> Retry.check(value -> true, 1, 5, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The delay must be greater than or equal to zero (0)");

        assertThatThrownBy(() -> Retry.resolve(value -> true, 1, 5, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The delay must be greater than or equal to zero (0)");
    }

    @Test
    void invalidMaxAttemptsShouldThrow() {
        assertThatThrownBy(() -> Retry.check(value -> true, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum number of attempts must be greater than zero (0)");

        assertThatThrownBy(() -> Retry.resolve(value -> true, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The maximum number of attempts must be greater than zero (0)");
    }

    private static void resolvesAtAttempt(final int targetAttempt, final int maxAttempts, final boolean shouldResolve)
            throws ExecutionException, InterruptedException {
        final AtomicInteger counter = new AtomicInteger(0);

        final Function<Integer, Boolean> checkFn = spyLambda(value -> {
            final int val = counter.incrementAndGet();
            return val == value;
        });

        boolean status = Retry.check(checkFn, targetAttempt, maxAttempts, 0);

        assertThat(status).isEqualTo(shouldResolve);
        verify(checkFn, Mockito.times(Math.min(targetAttempt, maxAttempts))).apply(targetAttempt);
    }

    /**
     * This method overcomes the issue with the original Mockito.spy when passing a lambda which fails with an error
     * saying that the passed class is final.
     */
    @SuppressWarnings("unchecked")
    static <T, P extends T> P spyLambda(final P lambda) {
        return (P) mock((Class<T>) Function.class, delegatesTo(lambda));
    }
}
