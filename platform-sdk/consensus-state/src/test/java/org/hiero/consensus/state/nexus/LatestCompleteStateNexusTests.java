// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.config.StateConfig_;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
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
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(configuration, new NoOpMetrics());
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
     * Verifies that an async freeze state is never retained, releases the previous state, and prevents a later state
     * from being retained.
     */
    @Test
    void asyncFreezeStateIsTerminalTest() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(configuration, new NoOpMetrics());
        final SignedState previousState =
                new RandomSignedStateGenerator().setRound(455).build();
        final SignedState freezeState = new RandomSignedStateGenerator()
                .setRound(456)
                .setFreezeState(true)
                .build();
        final SignedState lateState =
                new RandomSignedStateGenerator().setRound(457).build();

        try (final ReservedSignedState previousTestReservation = previousState.reserve("test");
                final ReservedSignedState freezeTestReservation = freezeState.reserve("test");
                final ReservedSignedState lateTestReservation = lateState.reserve("test")) {
            final ReservedSignedState previousNexusReservation = previousState.reserve("previous nexus state");
            nexus.setState(previousNexusReservation);

            final ReservedSignedState freezeObserverReservation = freezeState.reserve("freeze observer state");
            nexus.observeStateForAsyncFreeze(freezeObserverReservation);

            assertTrue(previousNexusReservation.isClosed(), "The previous nexus state should be released");
            assertTrue(freezeObserverReservation.isClosed(), "The observer reservation should be released");

            final ReservedSignedState freezeNexusReservation = freezeState.reserve("complete freeze nexus state");
            nexus.setStateIfNewer(freezeNexusReservation);

            assertTrue(freezeNexusReservation.isClosed(), "The freeze state should not be retained");
            assertEquals(1, previousState.getReservationCount(), "Only the previous test reservation should remain");
            assertEquals(1, freezeState.getReservationCount(), "Only the freeze test reservation should remain");

            final ReservedSignedState lateNexusReservation = lateState.reserve("late nexus state");
            nexus.setStateIfNewer(lateNexusReservation);

            assertTrue(lateNexusReservation.isClosed(), "A state arriving after the freeze state should be released");
            assertEquals(1, lateState.getReservationCount(), "Only the late test reservation should remain");
            try (final ReservedSignedState nexusState = nexus.getState("check for null")) {
                assertNull(nexusState, "Nexus should remain empty after the freeze state");
            }
        }
    }

    /**
     * Verifies that disabling async snapshots preserves the existing nexus behavior.
     */
    @Test
    void synchronousFreezeStateCanBeRetainedTest() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateConfig_.SAVE_STATE_ASYNC, false)
                .getOrCreateConfig();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(configuration, new NoOpMetrics());
        final SignedState freezeState = new RandomSignedStateGenerator()
                .setRound(456)
                .setFreezeState(true)
                .build();

        try (final ReservedSignedState testReservation = freezeState.reserve("test")) {
            nexus.updatePlatformStatus(PlatformStatus.FREEZING);
            final ReservedSignedState nexusReservation = freezeState.reserve("nexus state");
            nexus.setStateIfNewer(nexusReservation);

            assertFalse(nexusReservation.isClosed(), "A synchronous freeze state may be retained");
            try (final ReservedSignedState nexusState = nexus.getState("check retained state")) {
                assertNotNull(nexusState, "The synchronous freeze state should be retained");
            }
            nexus.clear();
            assertTrue(nexusReservation.isClosed(), "Clearing the nexus should release the synchronous freeze state");
        }
    }

    /**
     *
     * Verifies that updating the platform status to anything other than {@code FREEZING} does not release a reservation
     * on the state
     */
    @Test
    void platformStatusUpdateToNotFreezingTest() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final LatestCompleteStateNexus nexus = new DefaultLatestCompleteStateNexus(configuration, new NoOpMetrics());
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
