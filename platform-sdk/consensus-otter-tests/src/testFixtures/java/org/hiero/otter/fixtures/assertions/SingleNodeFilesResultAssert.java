// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.assertj.core.api.Assertions.fail;

import com.swirlds.common.io.IOIterator;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.assertj.core.api.AbstractAssert;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.otter.fixtures.result.SingleNodeFilesResult;

/**
 * Assertion class for {@link SingleNodeFilesResult}.
 *
 * <p>Provides custom assertions for validating log results of a single node.
 */
@SuppressWarnings("UnusedReturnValue")
public class SingleNodeFilesResultAssert extends AbstractAssert<SingleNodeFilesResultAssert, SingleNodeFilesResult> {

    protected SingleNodeFilesResultAssert(@Nullable final SingleNodeFilesResult actual) {
        super(actual, SingleNodeFilesResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeFilesResult}.
     *
     * @param actual the actual {@link SingleNodeFilesResult} to assert
     * @return a new instance of {@link SingleNodeFilesResultAssert}
     */
    public static SingleNodeFilesResultAssert assertThat(@Nullable SingleNodeFilesResult actual) {
        return new SingleNodeFilesResultAssert(actual);
    }

    public SingleNodeFilesResultAssert hasBirthRoundsInPcesFilesLessThan(long expected) {
        isNotNull();

        long maxBirthRound = 0L;
        try {
            for (final IOIterator<PlatformEvent> it = actual.pcesEvents(); it.hasNext(); ) {
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
