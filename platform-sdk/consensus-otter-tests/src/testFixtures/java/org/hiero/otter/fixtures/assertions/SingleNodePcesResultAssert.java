// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.fail;

import com.swirlds.common.io.IOIterator;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.internal.result.SingleNodePcesResultImpl;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;

@SuppressWarnings("UnusedReturnValue")
public class SingleNodePcesResultAssert extends AbstractAssert<SingleNodePcesResultAssert, SingleNodePcesResult> {

    protected SingleNodePcesResultAssert(@Nullable final SingleNodePcesResult actual) {
        super(actual, SingleNodePcesResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodePcesResult}.
     *
     * @param actual the actual {@link SingleNodePcesResultImpl} to assert
     * @return a new instance of {@link SingleNodePcesResultAssert}
     */
    public static SingleNodePcesResultAssert assertThat(@Nullable SingleNodePcesResult actual) {
        return new SingleNodePcesResultAssert(actual);
    }

    /**
     * Asserts that all events stored in the PCES files have a birthround less than the given value.
     *
     * @param expected the expected maximum birth round
     * @return this assertion object for method chaining
     */
    public SingleNodePcesResultAssert hasBirthRoundsLessThan(long expected) {
        isNotNull();

        long maxBirthRound = 0L;
        try (final IOIterator<PlatformEvent> it = actual.pcesEvents()) {
            while (it.hasNext()) {
                final PlatformEvent event = it.next();
                maxBirthRound = Math.max(maxBirthRound, event.getBirthRound());
            }
            if (maxBirthRound > expected) {
                fail("Expected maximum birth round in PcesFiles to be less than <%d> but was <%d>", expected, maxBirthRound);
            }
        } catch (IOException e) {
            fail("Unexpected IOException while evaluating PcesFiles", e);
        }
        return this;
    }
}
