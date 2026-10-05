// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.swirlds.state.merkle.VirtualMapState;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class EventCutoverCalculatorTest {

    /** The round of the initial state the node loads from. */
    private static final long INITIAL_ROUND = 32;

    /** A cutover that happened in an earlier release. */
    private static final long PREVIOUS_CUTOVER = 20;

    /** The value in state when no cutover is recorded. */
    private static final long NO_CUTOVER_IN_STATE = 0;

    /** An invalid value in state for the cutover round. */
    private static final long INVALID_CUTOVER = -1L;

    @AfterEach
    void tearDown() {
        // restore the default so tests do not leak static state
        EventHashFactory.initialize(Long.MAX_VALUE);
    }

    /** The kind of start the node is doing. */
    enum Start {
        GENESIS,
        UPGRADE,
        RESTART
    }

    /**
     * Parameters:
     * <ul>
     *  <li> Start start -> the kind of start the node is doing </li>
     *  <li> boolean eventCutoverActive -> whether the event cutover is enabled in the config </li>
     *  <li> long eventCutoverMinBirthRoundInState -> the value in state for the event cutover min birth round </li>
     *  <li> OptionalLong expectedValueToRecord -> the value that should be recorded in state, or empty if the value in state should not change </li>
     *  <li> long expectedFactoryCutover -> the value that the EventHashFactory should be initialized with </li>
     * </ul>
     */
    static Stream<Arguments> cases() {
        return Stream.of(
                // restarting the same version only reads the value in state
                Arguments.of(Start.RESTART, false, NO_CUTOVER_IN_STATE, OptionalLong.empty(), Long.MAX_VALUE),
                Arguments.of(Start.RESTART, true, NO_CUTOVER_IN_STATE, OptionalLong.empty(), Long.MAX_VALUE),
                Arguments.of(Start.RESTART, true, PREVIOUS_CUTOVER, OptionalLong.empty(), PREVIOUS_CUTOVER),
                Arguments.of(Start.RESTART, false, PREVIOUS_CUTOVER, OptionalLong.empty(), PREVIOUS_CUTOVER),
                Arguments.of(Start.RESTART, true, INVALID_CUTOVER, OptionalLong.empty(), Long.MAX_VALUE),
                Arguments.of(Start.RESTART, false, INVALID_CUTOVER, OptionalLong.empty(), Long.MAX_VALUE),
                // genesis with the cutover enabled hashes every event with SHA-256
                Arguments.of(
                        Start.GENESIS,
                        true,
                        NO_CUTOVER_IN_STATE,
                        OptionalLong.of(ConsensusConstants.ROUND_FIRST),
                        ConsensusConstants.ROUND_FIRST),
                Arguments.of(Start.GENESIS, false, NO_CUTOVER_IN_STATE, OptionalLong.empty(), Long.MAX_VALUE),
                // the cutover upgrade records the first round after the freeze round
                Arguments.of(
                        Start.UPGRADE,
                        true,
                        NO_CUTOVER_IN_STATE,
                        OptionalLong.of(INITIAL_ROUND + 1),
                        INITIAL_ROUND + 1),
                Arguments.of(
                        Start.UPGRADE, true, INVALID_CUTOVER, OptionalLong.of(INITIAL_ROUND + 1), INITIAL_ROUND + 1),
                // a later upgrade with the cutover still enabled keeps the value in state
                Arguments.of(Start.UPGRADE, true, PREVIOUS_CUTOVER, OptionalLong.empty(), PREVIOUS_CUTOVER),
                // the first upgrade with the cutover disabled resets the value, every non-ancient event is SHA-256
                Arguments.of(Start.UPGRADE, false, PREVIOUS_CUTOVER, OptionalLong.of(0), 0L),
                // an upgrade without the cutover enabled or recorded
                Arguments.of(Start.UPGRADE, false, NO_CUTOVER_IN_STATE, OptionalLong.empty(), Long.MAX_VALUE));
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @ParameterizedTest(name = "{0}, cutover enabled {1}, value in state {2} -> record {3}, factory cutover {4}")
    @MethodSource("cases")
    void calculateEventCutoverRound(
            final Start start,
            final boolean eventCutoverActive,
            final long eventCutoverMinBirthRoundInState,
            final OptionalLong expectedValueToRecord,
            final long expectedFactoryCutover) {
        final VirtualMapState state = mock(VirtualMapState.class);

        final OptionalLong valueToRecord;
        try (final MockedStatic<PlatformStateUtils> platformStateUtils = Mockito.mockStatic(PlatformStateUtils.class)) {
            platformStateUtils
                    .when(() -> PlatformStateUtils.isGenesisStateOf(state))
                    .thenReturn(start == Start.GENESIS);
            platformStateUtils.when(() -> PlatformStateUtils.roundOf(state)).thenReturn(INITIAL_ROUND);
            platformStateUtils
                    .when(() -> PlatformStateUtils.eventCutoverMinBirthRoundOf(state))
                    .thenReturn(eventCutoverMinBirthRoundInState);

            valueToRecord = EventCutoverCalculator.calculateEventCutoverRound(
                    state, start == Start.UPGRADE, eventCutoverActive);
        }

        assertThat(valueToRecord).isEqualTo(expectedValueToRecord);
        assertFactoryCutover(expectedFactoryCutover);
    }

    /**
     * Verifies that the {@link EventHashFactory} was initialized with the given cutover: the cutover birth round is
     * post cutover and the birth round before it is not.
     */
    private static void assertFactoryCutover(final long expectedCutover) {
        assertThat(EventHashFactory.isBirthRoundPostCutover(expectedCutover))
                .as("birth round %d is post cutover", expectedCutover)
                .isTrue();
        assertThat(EventHashFactory.isBirthRoundPostCutover(expectedCutover - 1))
                .as("birth round %d is pre cutover", expectedCutover - 1)
                .isFalse();
    }
}
