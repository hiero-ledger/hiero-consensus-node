// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.fail;

import com.swirlds.common.io.IOIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;

/**
 * Assertions for {@link SingleNodePcesResult}.
 */
@SuppressWarnings("UnusedReturnValue")
public class SingleNodePcesResultAssert extends AbstractAssert<SingleNodePcesResultAssert, SingleNodePcesResult> {

    /**
     * Constructor of {@link SingleNodePcesResultAssert}.
     *
     * @param actual the actual {@link SingleNodePcesResult} to assert
     */
    protected SingleNodePcesResultAssert(@Nullable final SingleNodePcesResult actual) {
        super(actual, SingleNodePcesResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodePcesResult}.
     *
     * @param actual the actual {@link SingleNodePcesResultImpl} to assert
     * @return a new instance of {@link SingleNodePcesResultAssert}
     */
    @NonNull
    public static SingleNodePcesResultAssert assertThat(@Nullable final SingleNodePcesResult actual) {
        return new SingleNodePcesResultAssert(actual);
    }

    /**
     * Asserts that all events stored in the PCES files have a birth round equal to the given value.
     *
     * @param expected the expected birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasAllBirthRoundsEqualTo(final long expected) {
        // assert that actual is not null
        isNotNull();

        try (final IOIterator<PlatformEvent> it = actual.pcesEvents()) {
            while (it.hasNext()) {
                final PlatformEvent event = it.next();
                if (event.getBirthRound() != expected) {
                    fail(
                            "Expected all birth rounds in PCES files of node %s to be equal to <%d> but was <%d> in event %s",
                            actual.nodeId(), expected, event.getBirthRound(), event);
                }
            }
        } catch (final IOException e) {
            fail("Unexpected IOException while evaluating PcesFiles", e);
        }

        return this;
    }

    /**
     * Asserts that the maximum birth round of all events stored in the PCES files is equal to the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasMaxBirthRoundEqualTo(final long expected) {
        // assert that actual is not null
        isNotNull();

        final long maxBirthRound = calculateMaxBirthRound();
        if (maxBirthRound >= expected) {
            fail(
                    "Expected the maximum birth round in PCES files of node %s to be equal to <%d> but was <%d>",
                    actual.nodeId(), expected, maxBirthRound);
        }
        return this;
    }

    /**
     * Asserts that the maximum birth round of all events stored in the PCES files is less than the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasMaxBirthRoundLessThan(final long expected) {
        // assert that actual is not null
        isNotNull();

        final long maxBirthRound = calculateMaxBirthRound();
        if (maxBirthRound >= expected) {
            fail(
                    "Expected the maximum birth round in PCES files of node %s to be less than <%d> but was <%d>",
                    actual.nodeId(), expected, maxBirthRound);
        }
        return this;
    }

    /**
     * Asserts that the maximum birth round of all events stored in the PCES files is less than or equal to the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasMaxBirthRoundLessThanOrEqualTo(final long expected) {
        // assert that actual is not null
        isNotNull();

        final long maxBirthRound = calculateMaxBirthRound();
        if (maxBirthRound > expected) {
            fail(
                    "Expected the maximum birth round in PCES files of node %s to be less than or equal to <%d> but was <%d>",
                    actual.nodeId(), expected, maxBirthRound);
        }
        return this;
    }

    /**
     * Asserts that the maximum birth round of all events stored in the PCES files is greater than the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasMaxBirthRoundGreaterThan(final long expected) {
        // assert that actual is not null
        isNotNull();

        final long maxBirthRound = calculateMaxBirthRound();
        if (maxBirthRound <= expected) {
            fail(
                    "Expected the maximum birth round in PCES files of node %s to be greater than <%d> but was <%d>",
                    actual.nodeId(), expected, maxBirthRound);
        }
        return this;
    }

    /**
     * Asserts that the maximum birth round of all events stored in the PCES files is greater than or equal to the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodePcesResultAssert hasMaxBirthRoundGreaterThanOrEqualTo(final long expected) {
        // assert that actual is not null
        isNotNull();

        final long maxBirthRound = calculateMaxBirthRound();
        if (maxBirthRound < expected) {
            fail(
                    "Expected the maximum birth round in PCES files of node %s to be greater than or equal to <%d> but was <%d>",
                    actual.nodeId(), expected, maxBirthRound);
        }
        return this;
    }

    private long calculateMaxBirthRound() {
        long maxBirthRound = 0L;
        try (final IOIterator<PlatformEvent> it = actual.pcesEvents()) {
            while (it.hasNext()) {
                final PlatformEvent event = it.next();
                maxBirthRound = Math.max(maxBirthRound, event.getBirthRound());
            }
        } catch (final IOException e) {
            fail("Unexpected IOException while evaluating PcesFiles", e);
        }
        return maxBirthRound;
    }
}
