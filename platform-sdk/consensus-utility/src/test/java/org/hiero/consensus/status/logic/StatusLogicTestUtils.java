// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;

/**
 * Utility methods for testing {@link PlatformStatusLogic} implementations.
 */
public class StatusLogicTestUtils {
    /**
     * Hidden constructor
     */
    private StatusLogicTestUtils() {}

    /**
     * Process a trigger and assert that the new status is as expected.
     *
     * @param logic          the logic to test
     * @param trigger         the trigger to process
     * @param expectedStatus the expected status after the trigger is processed
     */
    public static void assertTransition(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final StatusMachineTrigger trigger,
            @NonNull final PlatformStatus expectedStatus) {

        final PlatformStatus newStatus = logic.process(trigger).getStatus();
        assertEquals(expectedStatus, newStatus);
    }

    /**
     * Process a trigger and assert that the status does not change.
     *
     * @param logic          the logic to test
     * @param trigger         the trigger to process
     * @param originalStatus the original status before the trigger is processed
     */
    public static void assertNoTransition(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final StatusMachineTrigger trigger,
            @NonNull final PlatformStatus originalStatus) {

        final PlatformStatus newStatus = logic.process(trigger).getStatus();
        assertEquals(originalStatus, newStatus);
    }

    /**
     * Process a trigger and assert that an exception is thrown.
     *
     * @param logic          the logic to test
     * @param trigger         the trigger to process
     * @param originalStatus the original status before the trigger is processed
     */
    public static void assertException(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final StatusMachineTrigger trigger,
            @NonNull final PlatformStatus originalStatus) {

        assertThrows(
                IllegalPlatformStatusException.class,
                () -> logic.process(trigger),
                "Expected an exception to be thrown when processing trigger " + trigger + " in status "
                        + originalStatus);
    }
}
