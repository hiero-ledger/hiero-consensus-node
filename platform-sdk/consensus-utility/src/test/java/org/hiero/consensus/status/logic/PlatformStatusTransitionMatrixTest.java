// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.consensus.model.status.PlatformStatus.STARTING_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.IllegalPlatformStatusException;
import org.hiero.consensus.status.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.actions.FallenBehindAction;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;
import org.hiero.consensus.status.actions.PlatformStatusAction;
import org.hiero.consensus.status.actions.ReconnectCompleteAction;
import org.hiero.consensus.status.actions.SelfEventReachedConsensusAction;
import org.hiero.consensus.status.actions.StartedReplayingEventsAction;
import org.hiero.consensus.status.actions.StateWrittenToDiskAction;
import org.hiero.consensus.status.actions.TimeElapsedAction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Data-driven test pinning the full (status &times; action) transition table of the platform status state machine.
 * <p>
 * Each case asserts the status produced by {@link PlatformStatusLogic#process(PlatformStatusAction)} for a freshly
 * constructed logic instance, or that the action is illegal and throws. The genuinely <i>conditional</i> cells (the
 * timed {@code TimeElapsed} transitions of OBSERVING/CHECKING/ACTIVE and the round-gated non-freeze
 * {@code StateWrittenToDisk} of RECONNECT_COMPLETE) depend on timing/round inputs and are covered by the per-status
 * {@code *StatusLogicTests}; they are intentionally omitted here.
 */
class PlatformStatusTransitionMatrixTest {

    private static final PlatformStatusConfig CONFIG =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(PlatformStatusConfig.class);

    private static final Instant T = Instant.EPOCH;

    // one immutable instance of each action; logic instances are built fresh per case
    private static final CatastrophicFailureAction CATASTROPHIC = new CatastrophicFailureAction();
    private static final DoneReplayingEventsAction DONE_REPLAYING = new DoneReplayingEventsAction(T);
    private static final FallenBehindAction FALLEN_BEHIN = new FallenBehindAction();
    private static final FreezePeriodEnteredAction FREEZE_ENTERED = new FreezePeriodEnteredAction(0);
    private static final ReconnectCompleteAction RECONNECT_COMPLETE = new ReconnectCompleteAction(0);
    private static final SelfEventReachedConsensusAction SELF_EVENT_CONSENSUS = new SelfEventReachedConsensusAction(T);
    private static final StartedReplayingEventsAction STARTED_REPLAYING = new StartedReplayingEventsAction();
    private static final StateWrittenToDiskAction FREEZE_STATE_WRITTEN = new StateWrittenToDiskAction(0, true);
    private static final StateWrittenToDiskAction NON_FREEZE_STATE_WRITTEN = new StateWrittenToDiskAction(0, false);
    private static final TimeElapsedAction TIME_ELAPSED =
            new TimeElapsedAction(T, new TimeElapsedAction.QuiescingStatus(false, T));

    static Stream<Arguments> matrix() {
        return Stream.of(
                        cases(STARTING_UP, () -> new StartingUpStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(STARTED_REPLAYING, REPLAYING_EVENTS)
                                .stays(TIME_ELAPSED)
                                .illegal(
                                        DONE_REPLAYING,
                                        FALLEN_BEHIN,
                                        FREEZE_ENTERED,
                                        RECONNECT_COMPLETE,
                                        SELF_EVENT_CONSENSUS,
                                        FREEZE_STATE_WRITTEN,
                                        NON_FREEZE_STATE_WRITTEN),
                        cases(REPLAYING_EVENTS, () -> new ReplayingEventsStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(DONE_REPLAYING, OBSERVING)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN, TIME_ELAPSED)
                                .illegal(FALLEN_BEHIN, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(OBSERVING, () -> new ObservingStatusLogic(T, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIN, BEHIND)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(CHECKING, () -> new CheckingStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIN, BEHIND)
                                .on(FREEZE_ENTERED, FREEZING)
                                .on(SELF_EVENT_CONSENSUS, ACTIVE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(ACTIVE, () -> new ActiveStatusLogic(T, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIN, BEHIND)
                                .on(FREEZE_ENTERED, FREEZING)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(FREEZING, () -> new FreezingStatusLogic(0))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FALLEN_BEHIN, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, FREEZE_ENTERED, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(BEHIND, () -> new BehindStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(RECONNECT_COMPLETE, PlatformStatus.RECONNECT_COMPLETE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, FALLEN_BEHIN, STARTED_REPLAYING),
                        cases(
                                        PlatformStatus.RECONNECT_COMPLETE,
                                        () -> new ReconnectCompleteStatusLogic(0, null, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIN, BEHIND)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(CATASTROPHIC_FAILURE, CatastrophicFailureStatusLogic::new)
                                .stays(
                                        CATASTROPHIC,
                                        DONE_REPLAYING,
                                        FALLEN_BEHIN,
                                        FREEZE_ENTERED,
                                        RECONNECT_COMPLETE,
                                        SELF_EVENT_CONSENSUS,
                                        STARTED_REPLAYING,
                                        FREEZE_STATE_WRITTEN,
                                        NON_FREEZE_STATE_WRITTEN,
                                        TIME_ELAPSED),
                        cases(FREEZE_COMPLETE, FreezeCompleteStatusLogic::new)
                                .stays(
                                        CATASTROPHIC,
                                        DONE_REPLAYING,
                                        FALLEN_BEHIN,
                                        FREEZE_ENTERED,
                                        RECONNECT_COMPLETE,
                                        SELF_EVENT_CONSENSUS,
                                        STARTED_REPLAYING,
                                        FREEZE_STATE_WRITTEN,
                                        NON_FREEZE_STATE_WRITTEN,
                                        TIME_ELAPSED))
                .flatMap(StatusCases::stream);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void transitionMatrix(
            final String name,
            @NonNull final Supplier<PlatformStatusLogic> logicSupplier,
            @NonNull final PlatformStatusAction action,
            @Nullable final PlatformStatus expected) {

        final PlatformStatusLogic logic = logicSupplier.get();
        if (expected == null) {
            assertThrows(IllegalPlatformStatusException.class, () -> logic.process(action));
        } else {
            assertEquals(expected, logic.process(action).getStatus());
        }
    }

    private static StatusCases cases(final PlatformStatus status, final Supplier<PlatformStatusLogic> supplier) {
        return new StatusCases(status, supplier);
    }

    /**
     * Fluent builder collecting the expected outcomes for a single status, one row of the transition table.
     */
    private static final class StatusCases {
        private final PlatformStatus status;
        private final Supplier<PlatformStatusLogic> supplier;
        private final List<Arguments> rows = new ArrayList<>();

        private StatusCases(final PlatformStatus status, final Supplier<PlatformStatusLogic> supplier) {
            this.status = status;
            this.supplier = supplier;
        }

        /** The action transitions to the given status. */
        StatusCases on(final PlatformStatusAction action, final PlatformStatus expected) {
            rows.add(arguments(label(action, expected.name()), supplier, action, expected));
            return this;
        }

        /** The actions are processed without changing the status. */
        StatusCases stays(final PlatformStatusAction... actions) {
            for (final PlatformStatusAction action : actions) {
                rows.add(arguments(label(action, "stays"), supplier, action, status));
            }
            return this;
        }

        /** The actions are illegal for this status and throw. */
        StatusCases illegal(final PlatformStatusAction... actions) {
            for (final PlatformStatusAction action : actions) {
                rows.add(arguments(label(action, "illegal"), supplier, action, null));
            }
            return this;
        }

        private String label(final PlatformStatusAction action, final String outcome) {
            return status + " + " + action.getClass().getSimpleName() + " -> " + outcome;
        }

        Stream<Arguments> stream() {
            return rows.stream();
        }
    }
}
