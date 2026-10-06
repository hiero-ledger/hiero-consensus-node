// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation;

import static org.assertj.core.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

public class TestFixtureValidationUtils {

    /**
     * Assert that base events for equality. This does not check any consensus data, only pre-consensus. If the equality
     * is not met, then cause the test to fail and print a meaningful error message.
     *
     * @param description a string that is printed if the events are unequal
     * @param l1 the first list of events
     * @param l2 the second list of events
     * @param shouldBeEqual true if we expect lists have equal events, false if we expect unequal
     */
    static void assertBaseEventLists(
            @NonNull final String description,
            @NonNull final List<PlatformEvent> l1,
            @NonNull final List<PlatformEvent> l2,
            final boolean shouldBeEqual) {

        if (l1.size() != l2.size()) {
            fail(String.format("Length of event lists are unequal: %d vs %d", l1.size(), l2.size()));
        }

        // Iterate rather than index into the lists: the arguments may be linked lists, for which
        // repeated get(index) calls would make this loop quadratic.
        final Iterator<PlatformEvent> it1 = l1.iterator();
        final Iterator<PlatformEvent> it2 = l2.iterator();
        int index = 0;
        while (it1.hasNext() && it2.hasNext()) {
            final PlatformEvent e1 = it1.next();
            final PlatformEvent e2 = it2.next();
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
                fail(sb);
            }
            if (!shouldBeEqual && !equals) {
                // events are not equal, and they are not expected to be, we can stop checking
                return;
            }
            index++;
        }
        if (!shouldBeEqual) {
            // events are not expected to be equal, but we have gone through the whole list without finding a mismatch
            fail(String.format("Events are added in exactly the same order. Number of events: %d", l1.size()));
        }
    }
}
