// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.junit.jupiter.api.Test;

/**
 * Tests specific to the {@link LatestCompleteStateNexus}
 */
public class LatestCompleteStateNexusTests {

    /**
     * Verifies that updating the platform status to {@code FREEZING} releases a reservation on the state
     */
    @Test
    void platformStatusUpdateToFreezingTest() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(platformContext);
        final int round = 456;
        final SignedState state =
                new RandomSignedStateGenerator().setRound(round).build();
        try (final ReservedSignedState ignored = state.reserve("test")) {
            final ReservedSignedState reservationForNexus = state.reserve("nexus");
            nexus.setState(reservationForNexus);
            assertEquals(2, state.getReservationCount(), "There should be 2 reservations: test and nexus");

            nexus.updatePlatformStatus(PlatformStatus.FREEZING);
            assertEquals(
                    1,
                    state.getReservationCount(),
                    "Updating the platform status to FREEZING should reduce the reservations by 1");
            assertTrue(reservationForNexus.isClosed(), "Reservation held by nexus should be closed");
            try (final ReservedSignedState nexusState = nexus.getState("check for null")) {
                assertNull(nexusState, "Nexus should no longer have a state");
            }
        }
    }

    /**
     *
     * Verifies that updating the platform status to anything other than {@code FREEZING} does not release a reservation
     * on the state
     */
    @Test
    void platformStatusUpdateToNotFreezingTest() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(platformContext);
        final int round = 456;
        final SignedState state =
                new RandomSignedStateGenerator().setRound(round).build();
        try (final ReservedSignedState reservationForNexus = state.reserve("nexus")) {
            nexus.setState(reservationForNexus);
            assertEquals(1, state.getReservationCount(), "There should be 1 reservation: nexus");

            for (final PlatformStatus status : PlatformStatus.values()) {
                if (!PlatformStatus.FREEZING.equals(status)) {
                    nexus.updatePlatformStatus(status);
                    assertEquals(
                            1,
                            state.getReservationCount(),
                            "Updating the platform status to anything other than FREEZING should not reduce the reservations");
                    assertFalse(reservationForNexus.isClosed(), "Reservation held by nexus should remain open");
                    try (final ReservedSignedState nexusState = nexus.getState("check for null")) {
                        assertNotNull(nexusState, "Nexus should still have a state");
                    }
                }
            }
        }
    }
}
