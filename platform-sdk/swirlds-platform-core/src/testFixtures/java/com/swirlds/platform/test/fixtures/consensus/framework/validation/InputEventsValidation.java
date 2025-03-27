// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Assertions;

public class InputEventsValidation {
    /**
     * Validate that the events are added in a different order
     */
    public static void validateEventsAreInDifferentOrder(
            @NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying input events are not equal", output1.getAddedEvents(), output2.getAddedEvents(), false);
    }

    /**
     * Verify that ALL base events fed into consensus are exactly identical this will check only pre-consensus data, for
     * non-consensus events, the consensus data does not have to match
     */
    public static void validateInputsAreTheSame(
            @NonNull final ConsensusOutput output1, @NonNull final ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying sorted input events are equal",
                output1.sortedAddedEvents(),
                output2.sortedAddedEvents(),
                true);
    }

    /**
     * Assert that base events for equality. This does not check any consensus data, only pre-consensus. If the equality
     * is not met, then cause the test to fail and print a meaningful error message.
     *
     * @param description a string that is printed if the events are unequal
     * @param l1 the first list of events
     * @param l2 the second list of events
     * @param shouldBeEqual true if we expect lists have equal events, false if we expect unequal
     */
    private static void assertBaseEventLists(
            @NonNull final String description,
            @NonNull final List<PlatformEvent> l1,
            @NonNull final List<PlatformEvent> l2,
            final boolean shouldBeEqual) {

        if (l1.size() != l2.size()) {
            Assertions.fail(String.format("Length of event lists are unequal: %d vs %d", l1.size(), l2.size()));
        }

        for (int index = 0; index < l1.size(); index++) {
            final PlatformEvent e1 = l1.get(index);
            final PlatformEvent e2 = l2.get(index);
            final boolean equals = e1.equalsGossipedData(e2);
            if (shouldBeEqual && !equals) {
                final String sb = description
                        + "\n"
                        + "Events are not equal:\n"
                        + "Event 1: "
                        + e1
                        + "\n"
                        + "Event 2: "
                        + e2
                        + "\n"
                        + "at index: "
                        + index;
                Assertions.fail(sb);
            }
            if (!shouldBeEqual && !equals) {
                // events are not equal, and they are not expected to be, we can stop checking
                return;
            }
        }
        if (!shouldBeEqual) {
            // events are not expected to be equal, but we have gone through the whole list without finding a mismatch
            Assertions.fail(
                    String.format("Events are added in exactly the same order. Number of events: %d", l1.size()));
        }
    }
}
