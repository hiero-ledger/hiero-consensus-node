// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
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
 * Data-driven test pinning the full (status &times; trigger) transition table of the platform status state machine.
 * <p>
 * Each case asserts the status produced by {@link PlatformStatusLogic#process(StatusMachineTrigger)} for a freshly
 * constructed logic instance, or that the trigger is illegal and throws. The genuinely <i>conditional</i> cells (the
 * timed {@code TimeElapsed} transitions of OBSERVING/CHECKING/ACTIVE and the round-gated non-freeze
 * {@code StateWrittenToDisk} of RECONNECT_COMPLETE) depend on timing/round inputs and are covered by the per-status
 * {@code *StatusLogicTests}; they are intentionally omitted here.
 */
class PlatformStatusTransitionMatrixTest {

    private static final PlatformStatusConfig CONFIG =
            new TestConfigBuilder().getOrCreateConfig().getConfigData(PlatformStatusConfig.class);

    private static final Instant T = Instant.EPOCH;

    // one immutable instance of each trigger; logic instances are built fresh per case
    private static final CatastrophicFailureTrigger CAT = new CatastrophicFailureTrigger();
    private static final DoneReplayingEventsTrigger DONE = new DoneReplayingEventsTrigger(T);
    private static final FallenBehindTrigger FALLEN = new FallenBehindTrigger();
    private static final FreezePeriodEnteredTrigger FREEZE = new FreezePeriodEnteredTrigger(0);
    private static final ReconnectCompleteTrigger RECONNECT = new ReconnectCompleteTrigger(0);
    private static final SelfEventReachedConsensusTrigger SELF = new SelfEventReachedConsensusTrigger(T);
    private static final StartedReplayingEventsTrigger STARTED = new StartedReplayingEventsTrigger();
    private static final StateWrittenToDiskTrigger WRITTEN_FREEZE = new StateWrittenToDiskTrigger(0, true);
    private static final StateWrittenToDiskTrigger WRITTEN_NON_FREEZE = new StateWrittenToDiskTrigger(0, false);
    private static final TimeElapsedTrigger TIME =
            new TimeElapsedTrigger(T, new TimeElapsedTrigger.QuiescingStatus(false, T));

    static Stream<Arguments> matrix() {
        return Stream.of(
                        cases(STARTING_UP, () -> new StartingUpStatusLogic(CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(STARTED, REPLAYING_EVENTS)
                                .stays(TIME)
                                .illegal(DONE, FALLEN, FREEZE, RECONNECT, SELF, WRITTEN_FREEZE, WRITTEN_NON_FREEZE),
                        cases(REPLAYING_EVENTS, () -> new ReplayingEventsStatusLogic(CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(DONE, OBSERVING)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(FREEZE, SELF, WRITTEN_NON_FREEZE, TIME)
                                .illegal(FALLEN, RECONNECT, STARTED),
                        cases(OBSERVING, () -> new ObservingStatusLogic(T, CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(FALLEN, BEHIND)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(FREEZE, SELF, WRITTEN_NON_FREEZE)
                                .illegal(DONE, RECONNECT, STARTED),
                        cases(CHECKING, () -> new CheckingStatusLogic(CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(FALLEN, BEHIND)
                                .on(FREEZE, FREEZING)
                                .on(SELF, ACTIVE)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(WRITTEN_NON_FREEZE)
                                .illegal(DONE, RECONNECT, STARTED),
                        cases(ACTIVE, () -> new ActiveStatusLogic(T, CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(FALLEN, BEHIND)
                                .on(FREEZE, FREEZING)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(SELF, WRITTEN_NON_FREEZE)
                                .illegal(DONE, RECONNECT, STARTED),
                        cases(FREEZING, () -> new FreezingStatusLogic(0))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(FALLEN, SELF, WRITTEN_NON_FREEZE, TIME)
                                .illegal(DONE, FREEZE, RECONNECT, STARTED),
                        cases(BEHIND, () -> new BehindStatusLogic(CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(RECONNECT, RECONNECT_COMPLETE)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(FREEZE, SELF, WRITTEN_NON_FREEZE, TIME)
                                .illegal(DONE, FALLEN, STARTED),
                        cases(RECONNECT_COMPLETE, () -> new ReconnectCompleteStatusLogic(0, null, CONFIG))
                                .on(CAT, CATASTROPHIC_FAILURE)
                                .on(FALLEN, BEHIND)
                                .on(WRITTEN_FREEZE, FREEZE_COMPLETE)
                                .stays(FREEZE, SELF, TIME)
                                .illegal(DONE, RECONNECT, STARTED),
                        cases(CATASTROPHIC_FAILURE, CatastrophicFailureStatusLogic::new)
                                .stays(
                                        CAT,
                                        DONE,
                                        FALLEN,
                                        FREEZE,
                                        RECONNECT,
                                        SELF,
                                        STARTED,
                                        WRITTEN_FREEZE,
                                        WRITTEN_NON_FREEZE,
                                        TIME),
                        cases(FREEZE_COMPLETE, FreezeCompleteStatusLogic::new)
                                .stays(
                                        CAT,
                                        DONE,
                                        FALLEN,
                                        FREEZE,
                                        RECONNECT,
                                        SELF,
                                        STARTED,
                                        WRITTEN_FREEZE,
                                        WRITTEN_NON_FREEZE,
                                        TIME))
                .flatMap(StatusCases::stream);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void transitionMatrix(
            final String name,
            @NonNull final Supplier<PlatformStatusLogic> logicSupplier,
            @NonNull final StatusMachineTrigger trigger,
            @Nullable final PlatformStatus expected) {

        final PlatformStatusLogic logic = logicSupplier.get();
        if (expected == null) {
            assertThrows(IllegalPlatformStatusException.class, () -> logic.process(trigger));
        } else {
            assertEquals(expected, logic.process(trigger).getStatus());
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

        /** The trigger transitions to the given status. */
        StatusCases on(final StatusMachineTrigger trigger, final PlatformStatus expected) {
            rows.add(arguments(label(trigger, expected.name()), supplier, trigger, expected));
            return this;
        }

        /** The triggers are processed without changing the status. */
        StatusCases stays(final StatusMachineTrigger... triggers) {
            for (final StatusMachineTrigger trigger : triggers) {
                rows.add(arguments(label(trigger, "stays"), supplier, trigger, status));
            }
            return this;
        }

        /** The triggers are illegal for this status and throw. */
        StatusCases illegal(final StatusMachineTrigger... triggers) {
            for (final StatusMachineTrigger trigger : triggers) {
                rows.add(arguments(label(trigger, "illegal"), supplier, trigger, null));
            }
            return this;
        }

        private String label(final StatusMachineTrigger trigger, final String outcome) {
            return status + " + " + trigger.getClass().getSimpleName() + " -> " + outcome;
        }

        Stream<Arguments> stream() {
            return rows.stream();
        }
    }
}
