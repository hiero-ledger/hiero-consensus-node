// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * Utility methods for testing {@link PlatformStatusLogic} implementations.
 */
public class StatusLogicTestUtils {
    /**
     * Hidden constructor
     */
    private StatusLogicTestUtils() {}

    /**
     * Process an action and assert that the new status is as expected.
     *
     * @param logic          the logic to test
     * @param action         the action to process
     * @param expectedStatus the expected status after the action is processed
     */
    public static void assertTransition(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final PlatformStatusAction action,
            @NonNull final PlatformStatus expectedStatus) {

        final PlatformStatus newStatus = logic.process(action).getStatus();
        assertEquals(expectedStatus, newStatus);
    }

    /**
     * Process an action and assert that the status does not change.
     *
     * @param logic          the logic to test
     * @param action         the action to process
     * @param originalStatus the original status before the action is processed
     */
    public static void assertNoTransition(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final PlatformStatusAction action,
            @NonNull final PlatformStatus originalStatus) {

        final PlatformStatus newStatus = logic.process(action).getStatus();
        assertEquals(originalStatus, newStatus);
    }

    /**
     * Process an action and assert that an exception is thrown.
     *
     * @param logic          the logic to test
     * @param action         the action to process
     * @param originalStatus the original status before the action is processed
     */
    public static void assertException(
            @NonNull final PlatformStatusLogic logic,
            @NonNull final PlatformStatusAction action,
            @NonNull final PlatformStatus originalStatus) {

        assertThrows(
                IllegalPlatformStatusException.class,
                () -> logic.process(action),
                "Expected an exception to be thrown when processing action " + action + " in status " + originalStatus);
    }
}
