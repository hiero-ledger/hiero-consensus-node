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
import org.hiero.consensus.status.triggers.CatastrophicFailureTrigger;
import org.hiero.consensus.status.triggers.DoneReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.ReconnectCompleteTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;
import org.hiero.consensus.status.triggers.StatusMachineTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Data-driven test pinning the full (status &times; action) transition table of the platform status state machine.
 * <p>
 * Each case asserts the status produced by {@link PlatformStatusLogic#process(StatusMachineTrigger)} for a freshly
 * constructed logic instance, or that the action is illegal and throws. The genuinely <i>conditional</i> cells (the
 * timed {@code TimeElapsed} transitions of OBSERVING/CHECKING/ACTIVE and the round-gated non-freeze
 * {@code StateWrittenToDisk} of RECONNECT_COMPLETE) depend on timing/round inputs and are covered by the per-status
 * {@code *StatusLogicTests}; they are intentionally omitted here.
 */
class PlatformStatusTransitionMatrixTest {

    private static final PlatformStatusConfig CONFIG =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(PlatformStatusConfig.class);

    private static final Instant DEFAULT_INSTANT = Instant.EPOCH;

    // one immutable instance of each action; logic instances are built fresh per case
    private static final CatastrophicFailureTrigger CATASTROPHIC = new CatastrophicFailureTrigger();
    private static final DoneReplayingEventsTrigger DONE_REPLAYING = new DoneReplayingEventsTrigger(DEFAULT_INSTANT);
    private static final FallenBehindTrigger FALLEN_BEHIND = new FallenBehindTrigger();
    private static final FreezePeriodEnteredTrigger FREEZE_ENTERED = new FreezePeriodEnteredTrigger(0);
    private static final ReconnectCompleteTrigger RECONNECT_COMPLETE = new ReconnectCompleteTrigger(0);
    private static final SelfEventReachedConsensusTrigger SELF_EVENT_CONSENSUS =
            new SelfEventReachedConsensusTrigger(DEFAULT_INSTANT);
    private static final StartedReplayingEventsTrigger STARTED_REPLAYING = new StartedReplayingEventsTrigger();
    private static final StateWrittenToDiskTrigger FREEZE_STATE_WRITTEN = new StateWrittenToDiskTrigger(0, true);
    private static final StateWrittenToDiskTrigger NON_FREEZE_STATE_WRITTEN = new StateWrittenToDiskTrigger(0, false);
    private static final TimeElapsedTrigger TIME_ELAPSED =
            new TimeElapsedTrigger(DEFAULT_INSTANT, new TimeElapsedTrigger.QuiescingStatus(false, DEFAULT_INSTANT));

    @NonNull
    static Stream<Arguments> matrix() {
        return Stream.of(
                        cases(STARTING_UP, () -> new StartingUpStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(STARTED_REPLAYING, REPLAYING_EVENTS)
                                .stays(TIME_ELAPSED)
                                .illegal(
                                        DONE_REPLAYING,
                                        FALLEN_BEHIND,
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
                                .illegal(FALLEN_BEHIND, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(OBSERVING, () -> new ObservingStatusLogic(DEFAULT_INSTANT, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIND, BEHIND)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(CHECKING, () -> new CheckingStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIND, BEHIND)
                                .on(FREEZE_ENTERED, FREEZING)
                                .on(SELF_EVENT_CONSENSUS, ACTIVE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(ACTIVE, () -> new ActiveStatusLogic(DEFAULT_INSTANT, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIND, BEHIND)
                                .on(FREEZE_ENTERED, FREEZING)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(FREEZING, () -> new FreezingStatusLogic(0))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FALLEN_BEHIND, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, FREEZE_ENTERED, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(BEHIND, () -> new BehindStatusLogic(CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(RECONNECT_COMPLETE, PlatformStatus.RECONNECT_COMPLETE)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, NON_FREEZE_STATE_WRITTEN, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, FALLEN_BEHIND, STARTED_REPLAYING),
                        cases(
                                        PlatformStatus.RECONNECT_COMPLETE,
                                        () -> new ReconnectCompleteStatusLogic(0, null, CONFIG))
                                .on(CATASTROPHIC, CATASTROPHIC_FAILURE)
                                .on(FALLEN_BEHIND, BEHIND)
                                .on(FREEZE_STATE_WRITTEN, FREEZE_COMPLETE)
                                .stays(FREEZE_ENTERED, SELF_EVENT_CONSENSUS, TIME_ELAPSED)
                                .illegal(DONE_REPLAYING, RECONNECT_COMPLETE, STARTED_REPLAYING),
                        cases(CATASTROPHIC_FAILURE, CatastrophicFailureStatusLogic::new)
                                .stays(
                                        CATASTROPHIC,
                                        DONE_REPLAYING,
                                        FALLEN_BEHIND,
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
                                        FALLEN_BEHIND,
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
            @NonNull final StatusMachineTrigger action,
            @Nullable final PlatformStatus expected) {

        final PlatformStatusLogic logic = logicSupplier.get();
        if (expected == null) {
            assertThrows(IllegalPlatformStatusException.class, () -> logic.process(action));
        } else {
            assertEquals(expected, logic.process(action).getStatus());
        }
    }

    @NonNull
    private static StatusCases cases(
            @NonNull final PlatformStatus status, @NonNull final Supplier<PlatformStatusLogic> supplier) {
        return new StatusCases(status, supplier);
    }

    /**
     * Fluent builder collecting the expected outcomes for a single status, one row of the transition table.
     */
    private static final class StatusCases {
        private final PlatformStatus status;
        private final Supplier<PlatformStatusLogic> supplier;
        private final List<Arguments> rows = new ArrayList<>();

        private StatusCases(
                @NonNull final PlatformStatus status, @NonNull final Supplier<PlatformStatusLogic> supplier) {
            this.status = status;
            this.supplier = supplier;
        }

        /** The action transitions to the given status. */
        @NonNull
        StatusCases on(@NonNull final StatusMachineTrigger action, @NonNull final PlatformStatus expected) {
            rows.add(arguments(label(action, expected.name()), supplier, action, expected));
            return this;
        }

        /** The actions are processed without changing the status. */
        @NonNull
        StatusCases stays(@NonNull final StatusMachineTrigger... actions) {
            for (final StatusMachineTrigger action : actions) {
                rows.add(arguments(label(action, "stays"), supplier, action, status));
            }
            return this;
        }

        /** The actions are illegal for this status and throw. */
        @NonNull
        StatusCases illegal(@NonNull final StatusMachineTrigger... actions) {
            for (final StatusMachineTrigger action : actions) {
                rows.add(arguments(label(action, "illegal"), supplier, action, null));
            }
            return this;
        }

        @NonNull
        private String label(@NonNull final StatusMachineTrigger action, @NonNull final String outcome) {
            return status + " + " + action.getClass().getSimpleName() + " -> " + outcome;
        }

        @NonNull
        Stream<Arguments> stream() {
            return rows.stream();
        }
    }
}
